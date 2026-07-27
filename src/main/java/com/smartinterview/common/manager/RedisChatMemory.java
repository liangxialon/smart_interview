package com.smartinterview.common.manager;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.smartinterview.common.constants.RedisConstants;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import com.smartinterview.service.ai.MemoryChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Redis 持久化的会话记忆：短期滑动窗口（最近10轮问答）+ 长期压缩摘要
 * <p>
 * 修复清单：
 * 1. 压缩异步化，不阻塞 SSE 流式响应
 * 2. 短期窗口常量统一为 10 条
 * 3. 加载/压缩时过滤 SystemMessage
 * 4. 压缩入口加 Redis 分布式锁防并发
 * 5. 压缩失败回滚待压缩消息，连续3次失败停止压缩
 */
@Slf4j
public class RedisChatMemory implements ChatMemory {

    /** 短期窗口：最多保留 6 条对话消息（不含 SystemMessage） */
    private static final int WINDOW_SIZE = 6;
    /** 溢出达到此阈值才触发压缩（保证至少2对完整问答） */
    private static final int COMPRESS_THRESHOLD = 4;
    /** 分布式锁过期时间（秒） */
    private static final long LOCK_TIMEOUT = 30;
    /** 连续压缩失败次数上限，达到后停止压缩 */
    private static final int MAX_FAIL_COUNT = 3;

    private final Long sessionId;
    private final StringRedisTemplate redisTemplate;
    private final MemoryChatService memoryChatService;
    private final Executor asyncExecutor;
    private final PromptManager promptManager;

    public RedisChatMemory(Long sessionId, StringRedisTemplate redisTemplate,
                           MemoryChatService memoryChatService, Executor asyncExecutor,
                           PromptManager promptManager) {
        this.sessionId = sessionId;
        this.redisTemplate = redisTemplate;
        this.memoryChatService = memoryChatService;
        this.asyncExecutor = asyncExecutor;
        this.promptManager = promptManager;
    }

    @Override
    public Object id() {
        return sessionId;
    }

    // ──────────── 核心接口 ────────────

    /**
     * 添加新消息。短期窗口满时，先保存再异步压缩，不阻塞调用方。
     */
    @Override
    public void add(ChatMessage message) {
        List<ChatMessage> messages = loadFromRedis();

        //添加每次问答时的系统消息
        if (message instanceof SystemMessage) {
            // SystemMessage 替换模式：移除旧的，插入新的到列表头部
            messages.removeIf(m -> m instanceof SystemMessage);
            messages.add(0, message);
            saveToRedis(messages);
            return;
        }

        // 过滤第一条用户消息（如"请开始面试"），保证对话从 AI 问题开始，Q&A 始终成对
        if (message instanceof UserMessage) {
            boolean hasAiMessage = false;
            for (ChatMessage m : messages) {
                if (m instanceof AiMessage) {
                    hasAiMessage = true;
                    break;
                }
            }
            if (!hasAiMessage) {
                // 还没有任何 AI 回复，说明这是第一条用户消息，跳过不存 Redis
                log.debug("跳过首条用户消息，不存入聊天记忆, sessionId={}", sessionId);
                return;
            }
        }

        // 分离 SystemMessage 和对话消息
        ChatMessage systemMsg = null;
        List<ChatMessage> conversation = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage) {
                systemMsg = m;
            } else {
                conversation.add(m);
            }
        }
        conversation.add(message);

        int overflow = conversation.size() - WINDOW_SIZE;
        //再六条消息的基础上，溢出四条后在压缩。
        if (overflow >= COMPRESS_THRESHOLD) {
            // 溢出  >= 4条，裁剪窗口 + 异步压缩
            // overflow 必为偶数（每轮+1，从3→4、7→8、11→12...），保证完整问答对
            List<ChatMessage> toKeep = new ArrayList<>();
            if (systemMsg != null) toKeep.add(systemMsg);
            toKeep.addAll(conversation.subList(overflow, conversation.size()));
            saveToRedis(toKeep);

            // 压缩前 overflow 条对话消息（不含 SystemMessage）
            List<ChatMessage> toCompress = new ArrayList<>(
                    conversation.subList(0, overflow));
            CompletableFuture.runAsync(() -> compressWithLock(toCompress), asyncExecutor);
        } else {
            // 未达阈值，直接保存（窗口可能临时超过6条）
            List<ChatMessage> toSave = new ArrayList<>();
            if (systemMsg != null) toSave.add(systemMsg);
            toSave.addAll(conversation);
            saveToRedis(toSave);
        }
    }

    @Override
    public List<ChatMessage> messages() {
        return loadFromRedis();
    }

    @Override
    public void clear() {
        redisTemplate.delete(redisKey());
        redisTemplate.delete(memoryKey());
        redisTemplate.delete(failCountKey());
    }

    // ──────────── 长期记忆读写 ────────────

    public String getLongTermMemory() {
        String val = redisTemplate.opsForValue().get(memoryKey());
        return val != null ? val : "";
    }

    private void saveLongTermMemory(String memory) {
        redisTemplate.opsForValue().set(memoryKey(), memory,
                RedisConstants.INTERVIEW_CHAT_TTL, TimeUnit.MINUTES);
    }

    // ──────────── 压缩核心逻辑（带分布式锁 + 失败回滚 + 重试计数）────────────

    private void compressWithLock(List<ChatMessage> messages) {
        //建立分布式锁，防止重复压缩。
        String lockKey = RedisConstants.INTERVIEW_COMPRESS_LOCK + sessionId;
        // 尝试获取分布式锁（SETNX + 过期时间）
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", LOCK_TIMEOUT, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("压缩锁获取失败，跳过本次压缩, sessionId={}", sessionId);
            return;
        }
        try {
            // 检查连续失败次数，超过阈值停止压缩
            String failStr = redisTemplate.opsForValue().get(failCountKey());
            int failCount = failStr != null ? Integer.parseInt(failStr) : 0;
            if (failCount >= MAX_FAIL_COUNT) {
                log.warn("连续压缩失败{}次，停止压缩, sessionId={}", failCount, sessionId);
                return;
            }

            // 备份待压缩消息（用于失败回滚）
            backupMessages(messages);

            try {
                doCompress(messages);
                // 成功，重置失败计数
                redisTemplate.delete(failCountKey());
            } catch (Exception e) {
                // 压缩失败：回滚 + 累加失败计数
                log.error("记忆压缩失败，回滚待压缩消息, sessionId={}", sessionId, e);
                rollbackMessages();
                redisTemplate.opsForValue().increment(failCountKey());
                redisTemplate.expire(failCountKey(), RedisConstants.INTERVIEW_CHAT_TTL, TimeUnit.MINUTES);
            }
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * 真正的压缩逻辑：通过 MemoryChatService（@SystemMessage + @UserMessage）调用 qwen3.7-plus
     */
    private void doCompress(List<ChatMessage> messages) {
        String existingMemory = getLongTermMemory();

        StringBuilder dialogBuilder = new StringBuilder();
        for (ChatMessage msg : messages) {
            dialogBuilder.append(formatMessage(msg)).append("\n");
        }

        // 构建 @UserMessage 中的 existingMemoryPart 部分
        String existingMemoryPart = promptManager.buildExistingMemoryPart(existingMemory);
        // 调用 AIService：@SystemMessage 加载模板，@UserMessage 传入动态内容
        String summary = memoryChatService.compress(existingMemoryPart, dialogBuilder.toString());
        saveLongTermMemory(summary);
        log.info("记忆压缩完成, sessionId={}, 摘要长度={}", sessionId, summary.length());
    }

    private String formatMessage(ChatMessage msg) {
        if (msg instanceof UserMessage) {
            return "候选人：" + ((UserMessage) msg).singleText();
        } else if (msg instanceof AiMessage) {
            return "面试官：" + ((AiMessage) msg).text();
        }
        return msg.toString();
    }

    // ──────────── 备份与回滚 ────────────

    private void backupMessages(List<ChatMessage> messages) {
        JSONArray arr = new JSONArray();
        for (ChatMessage msg : messages) {
            arr.add(serialize(msg));
        }
        //备份压缩的消息
        redisTemplate.opsForValue().set(backupKey(), arr.toString(),
                LOCK_TIMEOUT, TimeUnit.SECONDS);
    }

    //回滚
    private void rollbackMessages() {
        String backupJson = redisTemplate.opsForValue().get(backupKey());
        if (backupJson == null || backupJson.isEmpty()) {
            return;
        }
        // 将备份消息重新插入 Redis 列表头部
        JSONArray arr = JSONUtil.parseArray(backupJson);
        List<String> jsonList = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            jsonList.add(arr.getStr(i));
        }
        if (!jsonList.isEmpty()) {
            // 读取当前列表
            List<String> current = redisTemplate.opsForList().range(redisKey(), 0, -1);
            // 清空后重建：备份消息 + 当前消息
            redisTemplate.delete(redisKey());
            redisTemplate.opsForList().rightPushAll(redisKey(), jsonList);
            if (current != null && !current.isEmpty()) {
                redisTemplate.opsForList().rightPushAll(redisKey(), current);
            }
            redisTemplate.expire(redisKey(), RedisConstants.INTERVIEW_CHAT_TTL, TimeUnit.MINUTES);
        }
        redisTemplate.delete(backupKey());
        log.info("压缩回滚完成, sessionId={}, 恢复{}条消息", sessionId, jsonList.size());
    }

    // ──────────── Redis Key ────────────

    private String redisKey() {
        return RedisConstants.INTERVIEW_CHAT_HISTORY + sessionId;
    }

    private String memoryKey() {
        return RedisConstants.INTERVIEW_LONG_TERM_MEMORY + sessionId;
    }

    private String failCountKey() {
        return RedisConstants.INTERVIEW_COMPRESS_LOCK + "fail:" + sessionId;
    }

    private String backupKey() {
        return RedisConstants.INTERVIEW_COMPRESS_BACKUP + sessionId;
    }

    // ──────────── 序列化/反序列化 ────────────

    private List<ChatMessage> loadFromRedis() {
        List<String> range = redisTemplate.opsForList().range(redisKey(), 0, -1);
        if (range == null || range.isEmpty()) {
            return new ArrayList<>();
        }
        List<ChatMessage> messages = new ArrayList<>();
        for (String json : range) {
            JSONObject obj = JSONUtil.parseObj(json);
            String role = obj.getStr("role");
            String content = obj.getStr("content");
            messages.add(deserialize(role, content));
        }
        return messages;
    }

    private void saveToRedis(List<ChatMessage> messages) {
        String redisKey = redisKey();
        List<String> jsonList = new ArrayList<>();
        for (ChatMessage msg : messages) {
            jsonList.add(serialize(msg));
        }
        redisTemplate.delete(redisKey);
        if (!jsonList.isEmpty()) {
            redisTemplate.opsForList().rightPushAll(redisKey, jsonList);
        }
        redisTemplate.expire(redisKey, RedisConstants.INTERVIEW_CHAT_TTL, TimeUnit.MINUTES);
    }

    private String serialize(ChatMessage message) {
        JSONObject obj = new JSONObject();
        if (message instanceof SystemMessage) {
            obj.set("role", "system");
            obj.set("content", ((SystemMessage) message).text());
        } else if (message instanceof UserMessage) {
            obj.set("role", "user");
            obj.set("content", ((UserMessage) message).singleText());
        } else if (message instanceof AiMessage) {
            obj.set("role", "assistant");
            obj.set("content", ((AiMessage) message).text());
        }
        return obj.toString();
    }

    private ChatMessage deserialize(String role, String content) {
        if (content == null) content = "";
        return switch (role) {
            case "system" -> SystemMessage.from(content);
            case "user" -> UserMessage.from(content);
            case "assistant" -> AiMessage.from(content);
            default -> UserMessage.from(content);
        };
    }
}
