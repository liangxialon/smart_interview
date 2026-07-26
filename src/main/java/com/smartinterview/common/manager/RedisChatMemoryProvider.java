package com.smartinterview.common.manager;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的 ChatMemoryProvider，支持跨重启的会话记忆持久化
 */
@Component
public class RedisChatMemoryProvider implements ChatMemoryProvider {

    private static final int MAX_HISTORY_MSG = 20;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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
        return new RedisChatMemory(sessionId, MAX_HISTORY_MSG, stringRedisTemplate);
    }
}
