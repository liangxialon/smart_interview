package com.smartinterview.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartinterview.common.constants.RabbitConstants;
import com.smartinterview.common.constants.RedisConstants;
import com.smartinterview.common.exception.InterviewSessionException;
import com.smartinterview.common.exception.ResumeAnalysisException;
import com.smartinterview.common.exception.ResumeNotFindException;
import com.smartinterview.common.manager.ChatContextManager;
import com.smartinterview.common.manager.RedisChatMemory;
import com.smartinterview.common.manager.RedisChatMemoryProvider;
import com.smartinterview.common.util.UserHolder;
import com.smartinterview.dto.ChatDTO;
import com.smartinterview.dto.StartInterviewDTO;
import com.smartinterview.entity.*;
import com.smartinterview.mapper.ChatMessageMapper;
import com.smartinterview.service.*;
import com.smartinterview.mapper.InterviewSessionMapper;
import com.smartinterview.service.ai.InterviewChatService;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.smartinterview.vo.InterviewSessionVO;
import com.smartinterview.vo.InterviewStartVO;
import com.smartinterview.vo.InterviewStatsVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
* @author 32341
* @description 针对表【interview_session(面试会话主表)】的数据库操作Service实现
* @createDate 2026-02-26 16:36:05
*/
@Service
@Slf4j
public class InterviewSessionServiceImpl extends ServiceImpl<InterviewSessionMapper, InterviewSession>
    implements InterviewSessionService{
    @Autowired
    private ChatMessageMapper chatMessageMapper;
    @Autowired
    private InterviewChatService interviewChatService;
    @Autowired
    private RedisChatMemoryProvider redisChatMemoryProvider;
    @Autowired
    private SysQuestionService sysQuestionService;
    @Autowired
    private ResumeAnalysisService resumeAnalysisService;

    @Autowired
    private InterviewReportService interviewReportService;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Lazy
    @Autowired
    private ChatContextManager chatContextManager;
    @Autowired
    private ResumeChunkService resumeChunkService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    public InterviewStartVO startInterview(StartInterviewDTO dto){
        Long userId=UserHolder.getUser().getId();
        ResumeAnalysis resume=resumeAnalysisService.getById(dto.getResumeId());
        if(resume==null){
            throw new ResumeNotFindException("简历未找到，请重新上传简历");
        }
        if(resume.getStatus()==null||resume.getStatus()<1){
            throw new ResumeAnalysisException("简历尚未分析，请稍后重试");
        }
        LambdaQueryWrapper<InterviewSession> existWrapper=new LambdaQueryWrapper<>();
        existWrapper.eq(InterviewSession::getUserId,userId)
                .eq(InterviewSession::getResumeId,resume.getId())
                .eq(InterviewSession::getIsDeleted,0)
                .eq(InterviewSession::getStatus,1)
                .last("limit 1");
        InterviewSession s = getOne(existWrapper);
        if(s!=null){
            return new InterviewStartVO(s.getId());
        }
        InterviewSession session=InterviewSession.builder()
                .userId(userId)
                .difficulty(dto.getDifficulty())
                .jobIntention(dto.getJobIntention())
                .resumeId(dto.getResumeId())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .title(dto.getTitle())
                .status(1)
                .isDeleted(0)
                .build();
        save(session);
        log.info("面试会话已创建，会话ID:{},用户ID:{}",session.getId(),userId);

        return new InterviewStartVO(session.getId());
    }
    /**
     *  面试对话：SSE 流式返回 AI 回复
     * 由系统提示词+历史会话+当前用户会话共同组成消息集合传到AI生成流式文本
     *
     * @return
     */


    @Override
    public SseEmitter chat(ChatDTO dto) {

        // 1. 获取 session、校验状态
        Long sessionId = dto.getSessionId();
        InterviewSession session = getById(sessionId);
        if (session == null) {
            throw new InterviewSessionException("面试会话不存在");
        }
        if (Integer.valueOf(2).equals(session.getStatus())) {
            throw new InterviewSessionException("面试已结束，请重新上传简历");
        }

        // 2. FSM：读取/推进面试阶段
        Long userId = session.getUserId();
        //面试阶段key
        String phaseKey = RedisConstants.INTERVIEW_PHASE + sessionId;
        String countKey = RedisConstants.INTERVIEW_QUESTION_COUNT + sessionId;
        //获取面试题的数量
        String countStr = stringRedisTemplate.opsForValue().get(countKey);
        int questionCount = countStr != null ? Integer.parseInt(countStr) : 0;
        questionCount++;
        stringRedisTemplate.opsForValue().set(countKey, String.valueOf(questionCount));
        //创建阶段对象  ，根据当前题目数量跟难度判断处于哪个阶段
        InterviewPhase phase = InterviewPhase.of(questionCount, session.getDifficulty());
        stringRedisTemplate.opsForValue().set(phaseKey, phase.name());
        // 判断是否到达最大题数（面试收尾）
        int maxQuestions = InterviewPhase.getMaxQuestions(session.getDifficulty());
        boolean isLastQuestion = questionCount >= maxQuestions;
        log.debug("FSM: sessionId={}, questionCount={}, phase={}, isLast={}", sessionId, questionCount, phase.name(), isLastQuestion);

        // 3. 提取上下文信息
        // 3a. 从 Redis 历史中提取上一轮 AI 问题，检索标准答案（用于评分）
        List<Message> history = chatContextManager.getHistoryFromRedis(sessionId);
      final  String lastAiQuestion;
      final  String standardAnswer;
        if (!history.isEmpty()) {
            Message lastMsg = history.get(history.size() - 1);
            if (Role.ASSISTANT.getValue().equals(lastMsg.getRole())) {
                lastAiQuestion = lastMsg.getContent();
                standardAnswer = sysQuestionService.searchStanderAnswer(lastAiQuestion);
            } else {
                standardAnswer = null;
                lastAiQuestion = "";
            }
        } else {
            standardAnswer = null;
            lastAiQuestion = "";
        }

        // 3b. 简历 RAG：根据面试阶段构造不同检索 Query
        String userMessage = dto.getUserMessage();
        String resumeChunks = searchResumeChunksByPhase(userId, phase, userMessage, session.getJobIntention());

        // 4. 开始 SSE
        SseEmitter emitter = new SseEmitter(60000L);
        // 将用户的发言先存入 MySQL
        com.smartinterview.entity.ChatMessage chatMessage = saveMessage(sessionId, Role.USER.getValue(), userMessage);
        // 👇================ 核心修改点：把占位记录和发MQ，提前到这里 ================👇
        // 只要不是第一句打招呼（即存在上一轮的AI问题），就立刻在数据库占位并发送MQ评分。
        // 用户的回答已经固定，不需要等大模型慢吞吞地生成完回复再去做这件事！
        if (StrUtil.isNotBlank(lastAiQuestion)) {
            InterviewReport pendingReport = InterviewReport.builder()
                    .sessionId(sessionId)
                    .messageId(chatMessage.getId())
                    .questionText(lastAiQuestion) // 直接用上一轮的AI问题
                    .userAnswer(userMessage)
                    .standardAnswer(standardAnswer != null ? standardAnswer : "")
                    .createTime(LocalDateTime.now())
                    // score 默认为 null
                    .build();
            interviewReportService.save(pendingReport);

            // 封装评分消息 DTO 发给 MQ
            QuestionScoreMessage scoreMsg = QuestionScoreMessage.builder()
                    .reportId(pendingReport.getId())
                    .sessionId(sessionId)
                    .messageId(chatMessage.getId())
                    .aiQuestion(lastAiQuestion)
                    .userAnswer(userMessage)
                    .standardAnswer(standardAnswer != null ? standardAnswer : "")
                    .build();
            rabbitTemplate.convertAndSend(
                    RabbitConstants.INTERVIEW_SCORE_EXCHANGE,
                    RabbitConstants.INTERVIEW_SCORE_ROUTING_KEY,
                    scoreMsg
            );
        }
        // 5. 准备 AIService 参数
        // 获取长期记忆（压缩摘要）
        RedisChatMemory chatMemory = (RedisChatMemory) redisChatMemoryProvider.get(sessionId);
        String longTermMemory = chatMemory.getLongTermMemory();
        if (longTermMemory.isEmpty()) {
            longTermMemory = "暂无历史记忆，这是面试的第一轮对话";
        }

        AtomicBoolean completed = new AtomicBoolean(false);
        // 6. 调用 AIService 流式聊天（短期记忆由 RedisChatMemory 自动管理，长期记忆注入系统提示词）
        // 收尾题：覆盖阶段提示词，指示 AI 输出结束语
        String finalPhasePrompt = isLastQuestion
                ? "【面试即将结束】请对候选人说：本次面试到此结束，感谢你的参与。后续我们会综合本次面试作答情况进行评估，祝你一切顺利！注意：在回复的最后，请在新的一行输出标记 [INTERVIEW_END]"
                : phase.getPhasePrompt();
        StringBuilder aiResponseBuffer = new StringBuilder();
        //todo
        interviewChatService.chat(
                sessionId,
                userMessage,        //用户消息
                finalPhasePrompt,  //阶段系统提示此
                StrUtil.isNotBlank(resumeChunks) ? resumeChunks : "暂无相关简历片段", //简历切片
                session.getJobIntention() != null ? session.getJobIntention() : "", //求职意向
                session.getDifficulty() != null ? session.getDifficulty() : "",  //面试难度
                longTermMemory  //长期记忆   ,短期记忆redis列表自动发送
        ).onPartialResponse(partialResponse -> {
            if (completed.get()) {
                return;
            }
            if (StrUtil.isNotBlank(partialResponse)) {
                aiResponseBuffer.append(partialResponse);
                try {
                    emitter.send(partialResponse);
                } catch (IOException e) {
                    completed.set(true);
                    emitter.completeWithError(e);
                }
            }
        }).onCompleteResponse(response -> {
            //看看是否是false
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            String aiFullResponse = aiResponseBuffer.toString();
            // AI 回复存入 MySQL
            saveMessage(sessionId, Role.ASSISTANT.getValue(), aiFullResponse);
            // 发送 [DONE] 标记，告知前端流结束
            try {
                emitter.send("[DONE]");
            } catch (Exception ignore) {
            }
            emitter.complete();
        }).onError(error -> {  //onPartialResponse捕获了异常，就不再执行onError
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            log.error("模拟面试发生异常:{}", error);
            try {
                emitter.completeWithError(error);
            } catch (Exception e) {
                // ignore
            }
        }).start();

        return emitter;
    }

   public void finishInterview(Long sessionId) {

       if (sessionId == null) {
           throw new InterviewSessionException("sessionId 不能为空");

       }
       InterviewSession session = getById(sessionId);
       if (session == null) {
           throw new InterviewSessionException("会话不存在");

       }
       if (Integer.valueOf(2).equals(session.getStatus())) {
           throw new InterviewSessionException("面试已结束，请勿重复操作");

       }

       session.setStatus(2);
       session.setUpdateTime(LocalDateTime.now());
       updateById(session);
       log.info("面试结束，sessionId={}", sessionId);


   }
   public List<InterviewSessionVO> queryInterview(){
        Long userId=UserHolder.getUser().getId();
        LambdaQueryWrapper<InterviewSession> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(InterviewSession::getUserId,userId)
                .orderByDesc(InterviewSession::getCreateTime);
        List<InterviewSessionVO> vo=list(wrapper).stream()
                .map(r-> BeanUtil.copyProperties(r,InterviewSessionVO.class))
                .collect(Collectors.toList());
        return vo;
   }
   //私有工具方法
    //添加消息
    public ChatMessage saveMessage(Long sessionId,String role,String content){
        ChatMessage chatMessage=ChatMessage.builder()
                .sessionId(sessionId)
                .content(content)
                .role(role)
                .createTime(LocalDateTime.now())
                .build();
        chatMessageMapper.insert(chatMessage);
        return chatMessage;
    }

    /**
     * 按面试阶段构造简历 RAG 检索 Query
     * - WARM_UP：检索技能栈/技术清单，不依赖项目细节
     * - PROJECT_DEEP_DIVE：检索项目描述/技术选型/问题与方案，强制绑定项目
     * - SYSTEM_DEEP：检索架构/分布式经验，无则用通用深度关键词
     */
    private String searchResumeChunksByPhase(Long userId, InterviewPhase phase,
                                              String userMessage, String jobIntention) {
        String ragQuery;
        int topK;

        switch (phase) {
            case WARM_UP:
                // 暖场：检索技能清单，不看项目细节
                ragQuery = "技术栈掌握技能" + (StrUtil.isNotBlank(jobIntention) ? " " + jobIntention : "");
                topK = 2;
                break;

            case PROJECT_DEEP_DIVE:
                // 项目深挖：强制检索项目段落、技术选型、痛点优化
                ragQuery = "项目 技术方案 架构 遇到问题 解决方案 优化 " + userMessage;
                topK = 2;
                break;

            case SYSTEM_DEEP:
                // 系统深潜：优先检索架构经验，结合用户话题
                ragQuery = "分布式高可用架构 分库分表 中间件 性能优化 " + userMessage;
                topK = 2;
                break;

            default:
                ragQuery = userMessage;
                topK = 2;
        }

        String chunks = resumeChunkService.searchRelevantChunks(userId, ragQuery, topK);

        // 降级：无结果时用求职意向兜底
        if (StrUtil.isBlank(chunks)) {
            String fallback = StrUtil.isNotBlank(jobIntention) ? jobIntention : "技术面试";
            chunks = resumeChunkService.searchRelevantChunks(userId, fallback, 1);
        }

        return chunks;
    }
    public void logicalDelete(Long sessionId){
        InterviewSession interviewSession=getById(sessionId);
        if(interviewSession==null){
            throw new InterviewSessionException("找不到该面试记录");
        }
        removeById(sessionId);
        //删除相关的面试报告
        interviewReportService.lambdaUpdate()
                .eq(InterviewReport::getSessionId, sessionId)
                .remove(); // 如果 InterviewReport 实体类配了逻辑删除注解，这里就是逻辑删除
        //删除聊天记录
        LambdaUpdateWrapper<ChatMessage> wrapper=new LambdaUpdateWrapper<>();
        wrapper.eq(ChatMessage::getSessionId,sessionId);
        chatMessageMapper.delete(wrapper);
    }
    public InterviewStatsVO getInterviewStats(){
        Long userId=UserHolder.getUser().getId();
        List<InterviewSession> list = lambdaQuery().eq(InterviewSession::getUserId, userId)
                .orderByAsc(InterviewSession::getCreateTime)
                .eq(InterviewSession::getStatus, 2)
                .list();
        //统计折线图图数据，每次面试的日期加得分
        List<InterviewStatsVO.ScoreTrend> collect = list.stream().map(r -> {
            InterviewStatsVO.ScoreTrend scoreTrend = new InterviewStatsVO.ScoreTrend();
            scoreTrend.setDate(r.getCreateTime().format(DateTimeFormatter.ofPattern("MM-dd")));
            scoreTrend.setTitle(r.getTitle());
            scoreTrend.setScore(r.getTotalScore() == null ? 0 : r.getTotalScore());
            return scoreTrend;
        }).collect(Collectors.toList());
        //计算平均分,返回值类型是double需要强转int
        int avgScore=Math.round((int) list.stream().mapToInt(r->r.getTotalScore()==null?0:r.getTotalScore()).average().orElse(0));
        InterviewStatsVO vo =new InterviewStatsVO();
        vo.setAvgScore(avgScore);
        vo.setTotalCount(list.size());
        vo.setScoreTrends(collect);
        return vo;
    }

}




