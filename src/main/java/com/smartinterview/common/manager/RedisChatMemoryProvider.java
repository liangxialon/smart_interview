package com.smartinterview.common.manager;

import com.smartinterview.service.ai.MemoryChatService;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/**
 * 基于 Redis 的 ChatMemoryProvider，支持跨重启的会话记忆持久化 + 长期记忆压缩
 */
@Component
public class RedisChatMemoryProvider implements ChatMemoryProvider {

    // 自定义线程池，固定20条压缩线程，可控不爆线程
    private static final Executor COMPRESS_EXECUTOR = new ThreadPoolExecutor(
            5,
            20,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(50),
            r -> {
                Thread t = new Thread(r, "memory-compress");
                t.setDaemon(true);
                return t;
            }, //县城工厂 R：任务  ，name任务名前缀
            new ThreadPoolExecutor.CallerRunsPolicy() // 队列满了交给调用线程同步执行，不丢任务。 拒绝策略
    );

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private MemoryChatService memoryChatService;

    @Autowired
    private PromptManager promptManager;

    @Override
    public ChatMemory get(Object memoryId) {
        Long sessionId;
        if (memoryId instanceof Long) {
            sessionId = (Long) memoryId;
        } else if (memoryId instanceof Number) {
            sessionId = ((Number) memoryId).longValue();
        } else {
            sessionId = Long.parseLong(memoryId.toString());
        }
        return new RedisChatMemory(sessionId, stringRedisTemplate, memoryChatService, COMPRESS_EXECUTOR, promptManager);
    }
}
