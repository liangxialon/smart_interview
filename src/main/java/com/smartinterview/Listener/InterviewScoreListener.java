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
            // ================== 修改代码：获取占位记录并执行更新 ==================
            InterviewReport report = interviewReportService.getById(msg.getReportId());
            if (report != null) {
                report.setAiRaw(aiRaw);
                report.setScore(json.getInt("score"));
                report.setIsCorrect(json.getBool("isCorrect"));
                report.setComment(json.getStr("comment"));
                // 可选：report.setUpdateTime(LocalDateTime.now());
                interviewReportService.updateById(report);
            } else {
                log.warn("未找到评分占位记录，sessionId:{}, reportId:{}", msg.getSessionId(), msg.getReportId());
            }
            // ======================================================================

        } catch (Exception e) {
            log.error("单题评分失败，准备触发 MQ 重试: {}", msg.getSessionId(), e);
            throw new QuestionScoreException("单题评分失败"+e);
        }

    }
}
