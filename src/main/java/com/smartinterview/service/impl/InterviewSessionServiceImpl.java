package com.smartinterview.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
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
import com.smartinterview.common.manager.PromptManager;
import com.smartinterview.common.result.Result;
import com.smartinterview.common.util.UserHolder;
import com.smartinterview.dto.ChatDTO;
import com.smartinterview.dto.StartInterviewDTO;
import com.smartinterview.entity.*;
import com.smartinterview.mapper.ChatMessageMapper;
import com.smartinterview.service.*;
import com.smartinterview.mapper.InterviewSessionMapper;
import com.smartinterview.vo.InterviewSessionVO;
import com.smartinterview.vo.InterviewStartVO;
import com.smartinterview.vo.InterviewStatsVO;
import io.reactivex.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
    private AiAnalysisService aianalysisService;
    @Autowired
    private ResumeAnalysisService resumeAnalysisService;

    @Autowired
    private InterviewReportService interviewReportService;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Lazy
    @Autowired
    private ChatContextManager chatContextManager;


    public InterviewStartVO startInterview(StartInterviewDTO dto){
        Long userId=UserHolder.getUser().getId();
        ResumeAnalysis resume=resumeAnalysisService.getById(dto.getResumeId());
        if(resume==null){
            throw new ResumeNotFindException("简历未找到，请重新上传简历");
        }
        if(resume.getStatus()==null||resume.getStatus()<3){
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
        //获取上下文消息  系统评分提示词++历史会话+当前用户消息
        //todo
        //先获取redis最后一条数据的AI问题ES匹配标准答案构建提示词，在添加的用户本次回答，跟AI下一次提问
        ChatContext chatContext = chatContextManager.buildChatContext(dto);
        List<Message> messages=chatContext.getMessages();
        //开始SSE
        SseEmitter emitter = new SseEmitter(60000L);
        Long sessionId=dto.getSessionId();
        String userMessage=dto.getUserMessage();
        //将用户的发言先异步存入mysql
        ChatMessage chatMessage = saveMessage(sessionId, Role.USER.getValue(), userMessage);
        //调用AI开启流式聊天
        Flowable<GenerationResult> flowable=aianalysisService.streamChat(messages);
        //拼接字符串
        StringBuilder aiResponseBuffer=new StringBuilder();

        flowable.subscribe(
                result -> {
                    String chunk=result.getOutput().getChoices().get(0).getMessage().getContent();
                    if(StrUtil.isNotBlank(chunk)){
                        aiResponseBuffer.append(chunk);
                        try {
                            emitter.send(chunk);
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    }
                },
                error -> {
                    log.error("模拟面试发生异常:{}",error);
                    try {
                        emitter.completeWithError(error);
                    } catch (Exception e) {

                    }
                },
                ()->{
                    String aiFullResponse=aiResponseBuffer.toString();
                    //AI本次的问题添加到数据库
                    saveMessage(sessionId,Role.ASSISTANT.getValue(), aiFullResponse);
                    //创建当前用户消息存到redis
                    Message curUserMsg = Message.builder().role(Role.USER.getValue()).content(dto.getUserMessage()).build();
                    //创建AI消息存到redis
                    Message aiMessage=Message.builder().role(Role.ASSISTANT.getValue()).content(aiFullResponse).build();
                    //更新redis，进行滑动窗口裁剪
                    chatContextManager.updateContext(sessionId,curUserMsg,aiMessage);
                    // 封装评分消息 DTO
                    QuestionScoreMessage scoreMsg = QuestionScoreMessage.builder()
                            .sessionId(sessionId)
                            .messageId(chatMessage.getId())
                            .aiQuestion(chatContext.getAiQuestion())
                            .userAnswer(userMessage)
                            .standardAnswer(chatContext.getStanderAnswer())
                            .build();
                    // 投递到 RabbitMQ
                    rabbitTemplate.convertAndSend(
                            RabbitConstants.INTERVIEW_SCORE_EXCHANGE,
                            RabbitConstants.INTERVIEW_SCORE_ROUTING_KEY,
                            scoreMsg
                    );
                    emitter.complete();
             });
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
       List<InterviewReport> reportList=interviewReportService.lambdaQuery()
               .ne(InterviewReport::getQuestionText,"")
                       .eq(InterviewReport::getSessionId,sessionId)
                               .list();
       if(reportList!=null){
          int avgScore=(int) Math.round( reportList.stream()
                  .mapToInt(r->r.getScore()==null?0:r.getScore())//转成int流
                  .average() //求平均数
                  .orElse(0) //集合为空时，没数据置0
          );
          session.setTotalScore(avgScore);
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
    //提取简历摘要
    public String getSummary(Long sessionId){
        InterviewSession session = getById(sessionId);
        if(session==null){
            return "简历找不到，请直接开始技术相关的面试";
        }
        Long resumeId = session.getResumeId();
        ResumeAnalysis resumeAnalysis =resumeAnalysisService.getById(resumeId);
        if(resumeAnalysis==null){
            return "简历找不到，请直接开始技术相关的面试";
        }
        String summary=resumeAnalysis.getSummary();
        return summary;
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
            scoreTrend.setScore(r.getTotalScore());
            return scoreTrend;
        }).collect(Collectors.toList());
        //计算平均分,返回值类型是double需要强转int
        int avgScore=Math.round((int) list.stream().mapToInt(r->r.getTotalScore()).average().orElse(0));
        InterviewStatsVO vo =new InterviewStatsVO();
        vo.setAvgScore(avgScore);
        vo.setTotalCount(list.size());
        vo.setScoreTrends(collect);
        return vo;
    }

}




