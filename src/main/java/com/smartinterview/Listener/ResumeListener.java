package com.smartinterview.Listener;

import com.smartinterview.common.constants.RabbitConstants;
import com.smartinterview.common.util.ResumeParser;
import com.smartinterview.entity.ResumeAnalysis;
import com.smartinterview.service.ResumeAnalysisService;
import com.smartinterview.service.ResumeChunkService;
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
    private ResumeChunkService resumeChunkService;

    @RabbitListener(queues = RabbitConstants.RESUME_PARSE_QUEUE)
    public void handlerResumeParse(Long resumeId) {
        log.info("消费者开始处理简历解析任务，简历ID: {}", resumeId);
        ResumeAnalysis resume = resumeAnalysisService.getById(resumeId);
        String fileUrl = resume.getFileUrl();
        try {
            // 1. PDF 文本提取
            String text = resumeParser.parsePdfFromUrl(fileUrl);

            // 2. 切片 + 向量化 + 写入 ES
            try {
                resumeChunkService.chunkAndIndex(resumeId, resume.getUserId(), text);
            } catch (Exception ex) {
                log.error("简历切片索引失败（不影响解析流程），resumeId={}", resumeId, ex);
            }

            // 3. 更新数据库：解析成功（评分在前台轨SSE流中完成）
            resume.setOriginalText(text);
            resume.setStatus(1);
            resume.setUpdateTime(LocalDateTime.now());
            resumeAnalysisService.updateById(resume);

        } catch (Exception e) {
            log.error("简历解析发生致命异常，简历ID: {}", resumeId, e);
            resume.setStatus(-1);
            resumeAnalysisService.updateById(resume);
        }
    }
}