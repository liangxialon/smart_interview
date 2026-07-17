package com.smartinterview.common.manager;

import cn.hutool.json.JSONUtil;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.smartinterview.common.constants.RedisConstants;
import com.smartinterview.common.exception.InterviewSessionException;
import com.smartinterview.dto.ChatDTO;
import com.smartinterview.entity.ChatContext;
import com.smartinterview.entity.InterviewSession;
import com.smartinterview.service.InterviewSessionService;
import com.smartinterview.service.SysQuestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class ChatContextManager {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private SysQuestionService sysQuestionService;
    @Autowired
    private PromptManager promptManager;
    // 假设 getSummary 和 getById 的逻辑也在相关 Service 中
    @Autowired
    private InterviewSessionService sessionService;
    private static final int MAX_HISTORY_MSG=40;
    /**
     * 构建大模型所需的完整上下文消息列表
     */
    public ChatContext buildChatContext(ChatDTO dto)  {
        Long sessionId = dto.getSessionId();

        // 1. 获取基础信息（Session、摘要等）
        InterviewSession session = sessionService.getById(sessionId);
        if (session == null) {
            throw new InterviewSessionException("面试会话不存在");
        }
        if(session.getStatus().equals(Integer.valueOf(2))){
            throw new InterviewSessionException("面试已结束，请重新上传简历");
        }

        String summaryText = sessionService.getSummary(sessionId);

        // 2. 获取 Redis 历史消息
        List<Message> historyMessages = getHistoryFromRedis(sessionId);

        String lastAiQuestion="";
        // 3. 提取上一轮 AI 问题并检索 RAG 标准答案
        String standerAnswer = null;
        if (!historyMessages.isEmpty()) {
            //*****redis最后一条数据就是AI本次问题******
            Message lastMsg = historyMessages.get(historyMessages.size() - 1);

            if (Role.ASSISTANT.getValue().equals(lastMsg.getRole())) {
                lastAiQuestion=lastMsg.getContent();
                standerAnswer = sysQuestionService.searchStanderAnswer(lastMsg.getContent());
            }
        }

        // 4. 构建 System Prompt
        String systemPrompt = promptManager.buildInterviewChatSystemPrompt(
                summaryText, //建立摘要
                standerAnswer, //标准答案
                session.getDifficulty(),  //难度
                session.getJobIntention()  //求职意向
        );

        // 5. 组装最终的消息列表
        List<Message> fullContext = new ArrayList<>();
        // 系统层
        fullContext.add(Message.builder().role(Role.SYSTEM.getValue()).content(systemPrompt).build());
        // 历史层
        fullContext.addAll(historyMessages);
        // 用户当前层
        fullContext.add(Message.builder().role(Role.USER.getValue()).content(dto.getUserMessage()).build());

        // 返回包装后的结果
        return ChatContext.builder()
                .messages(fullContext)
                .aiQuestion(lastAiQuestion)
                .standerAnswer(standerAnswer)
                .build();
    }
    public List<Message>  getHistoryFromRedis(Long sessionId){
        String redisKey= RedisConstants.INTERVIEW_CHAT_HISTORY+sessionId;
        List<String> range = stringRedisTemplate.opsForList().range(redisKey, 0, -1);
        if (range == null || range.isEmpty()) {
            return new ArrayList<>();
        }
        //字符串转成java对象
        return range.stream().map(s-> JSONUtil.toBean(s,Message.class)).collect(Collectors.toList());
    }
    public void updateContext(Long sessionId,Message userMessage,Message aiMessage){
        String redisKey=RedisConstants.INTERVIEW_CHAT_HISTORY+sessionId;
        // 1. 将一问一答压入 Redis (使用 rightPushAll 减少网络交互)
        stringRedisTemplate.opsForList().rightPushAll(
                redisKey,
                JSONUtil.toJsonStr(userMessage),
                JSONUtil.toJsonStr(aiMessage)
        );
        //滑动窗口裁剪，只保留最近N条数据，防止大模型token爆炸
        stringRedisTemplate.opsForList().trim(redisKey,-MAX_HISTORY_MSG,-1);
        stringRedisTemplate.expire(redisKey,RedisConstants.INTERVIEW_CHAT_TTL, TimeUnit.MINUTES);
    }
}
