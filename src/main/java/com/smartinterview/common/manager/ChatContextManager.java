package com.smartinterview.common.manager;

import cn.hutool.json.JSONUtil;
import com.alibaba.dashscope.common.Message;
import com.smartinterview.common.constants.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 管理 Redis 中的面试聊天历史（DashScope Message 格式）
 */
@Component
public class ChatContextManager {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 从 Redis 获取历史消息列表
     */
    public List<Message> getHistoryFromRedis(Long sessionId) {
        String redisKey = RedisConstants.INTERVIEW_CHAT_HISTORY + sessionId;
        List<String> range = stringRedisTemplate.opsForList().range(redisKey, 0, -1);
        if (range == null || range.isEmpty()) {
            return new ArrayList<>();
        }
        return range.stream().map(s -> JSONUtil.toBean(s, Message.class)).collect(Collectors.toList());
    }
}
