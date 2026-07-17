package com.smartinterview.Listener;

import com.smartinterview.common.constants.RabbitConstants;
import com.smartinterview.common.manager.ResumeStateManager;
import com.smartinterview.common.util.ResumeParser;
import com.smartinterview.entity.ResumeScoreMessage;
import com.smartinterview.entity.ResumeAnalysis;
import com.smartinterview.service.AiAnalysisService;
import com.smartinterview.service.ResumeAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 给简历打分
 */
@Slf4j
@Component
public class ResumeListener {
    @Autowired
    private ResumeAnalysisService resumeAnalysisService;
    @Autowired
    private ResumeParser resumeParser;
    @Autowired
    private AiAnalysisService aiAnalysisService;
    @Autowired
    private ResumeStateManager resumeStateManager;

    @RabbitListener(queues = RabbitConstants.RESUME_PARSE_QUEUE)
    public void handlerResumeParse(Long resumeId) {
        log.info("消费者开始处理简历解析任务，简历ID: {}", resumeId);
        // 1. 从数据库查出对应的记录和 OSS 地址
        ResumeAnalysis resume = resumeAnalysisService.getById(resumeId);
        String fileUrl = resume.getFileUrl();
        try {
            // 2. 调用 PDFBox 工具类提取文本
            String text = resumeParser.parsePdfFromUrl(fileUrl);
            // 3. 模拟调用大模型或深度分析的超长耗时操作 (休眠 3 秒)
            //String s = aiAnalysisService.streamAnalyzeResume(text);
            //System.out.println("AI分析的内容："+s);
            // 4. 更新数据库状态为 1 (解析成功)，并保存纯文本
            resume.setOriginalText(text);
            resume.setStatus(1);
            resume.setUpdateTime(LocalDateTime.now());
            resumeAnalysisService.updateById(resume);
        } catch (Exception e) {
            // 解析失败，更新状态为 -1，方便前端展示“解析失败，请重试”
            log.error("简历解析发生致命异常，简历ID: {}", resumeId, e);
            resume.setStatus(-1);
            resumeAnalysisService.updateById(resume);
        }

    }
    @RabbitListener(queues = RabbitConstants.RESUME_SCORE_QUEUE)
    public void handleResumeScore(ResumeScoreMessage message) {
        Long resumeId = message.getResumeId();
        log.info("消费者监听到简历打分任务，resumeId={}", resumeId);

        try {
            // 调用 AI 获取 JSON 评分结果
            String jsonRaw = aiAnalysisService.analyzeResumeScore(message.getRawText());

            //  Manager 解析并更新状态 (状态置为 3)
            resumeStateManager.updateToScored(resumeId, jsonRaw);

        } catch (Exception e) {
            log.error("MQ后台轨打分消费异常，resumeId={}", resumeId, e);
            // 将状态置为 -1
            resumeStateManager.markAsFailed(resumeId);
            // 注意：如果你配置了 RabbitMQ 的重试机制，
            // 这里可以直接 throw e; 让消息重新入队或者进入死信队列。
            // 如果你选择 catch 并吃掉异常，消息会被认为是成功消费的。
        }
    }
}