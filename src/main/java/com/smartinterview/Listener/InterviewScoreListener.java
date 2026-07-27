package com.smartinterview.Listener;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.smartinterview.common.constants.RabbitConstants;
import com.smartinterview.common.exception.QuestionScoreException;
import com.smartinterview.entity.InterviewReport;
import com.smartinterview.entity.QuestionScoreMessage;
import com.smartinterview.service.ai.InterviewEvaluateService;
import com.smartinterview.service.InterviewReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 给用户回答打分
 */
@Component
@Slf4j
public class InterviewScoreListener {
    @Autowired
    private InterviewEvaluateService interviewEvaluateService;
    @Autowired
    InterviewReportService interviewReportService;

    @RabbitListener(queues = RabbitConstants.INTERVIEW_SCORE_QUEUE)
    public void handleQuestionScore(QuestionScoreMessage msg) {
        try {
            //通过langchain4j AIService调用AI进行评分
            String aiRaw = interviewEvaluateService.evaluate(
                    msg.getAiQuestion(), msg.getUserAnswer(), msg.getStandardAnswer());

            JSONObject json = JSONUtil.parseObj(aiRaw);
            //将报告保存到数据库
            InterviewReport interviewReport = InterviewReport.builder()
                    .sessionId(msg.getSessionId())
                    .messageId(msg.getMessageId())
                    .questionText(msg.getAiQuestion())
                    .aiRaw(aiRaw)
                    .userAnswer(msg.getUserAnswer())
                    .standardAnswer(msg.getStandardAnswer())
                    .score(json.getInt("score"))
                    .isCorrect(json.getBool("isCorrect"))
                    .comment(json.getStr("comment"))
                    .createTime(LocalDateTime.now())
                    .build();
            interviewReportService.save(interviewReport);
        } catch (Exception e) {
            log.error("单题评分失败，准备触发 MQ 重试: {}", msg.getSessionId());
            throw new QuestionScoreException("单题评分失败"+e);
        }
    }
}
