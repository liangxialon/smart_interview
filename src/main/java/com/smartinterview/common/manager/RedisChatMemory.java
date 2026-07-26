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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis 持久化的会话记忆，兼容 DashScope Message 的 Redis 存储格式
 */
@Slf4j
public class RedisChatMemory implements ChatMemory {

    private final Long sessionId;
    private final int maxMessages;
    private final StringRedisTemplate redisTemplate;

    public RedisChatMemory(Long sessionId, int maxMessages, StringRedisTemplate redisTemplate) {
        this.sessionId = sessionId;
        this.maxMessages = maxMessages;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Object id() {
        return sessionId;
    }

    @Override
    public void add(ChatMessage message) {
        List<ChatMessage> messages = loadFromRedis();
        messages.add(message);
        if (messages.size() > maxMessages) {
            messages = new ArrayList<>(messages.subList(messages.size() - maxMessages, messages.size()));
        }
        saveToRedis(messages);
    }

    @Override
    public List<ChatMessage> messages() {
        return loadFromRedis();
    }

    @Override
    public void clear() {
        String redisKey = RedisConstants.INTERVIEW_CHAT_HISTORY + sessionId;
        redisTemplate.delete(redisKey);
    }

    private String redisKey() {
        return RedisConstants.INTERVIEW_CHAT_HISTORY + sessionId;
    }

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
