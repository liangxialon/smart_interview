package com.smartinterview.common.manager;

import com.smartinterview.entity.ResumeAnalysis;
import com.smartinterview.mapper.ResumeAnalysisMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
@Slf4j
public class ResumeStateManager {

    @Autowired
    private ResumeAnalysisMapper resumeAnalysisMapper; // 你的原Service或Mapper

    /**
     * 简历分析完成：一次性保存诊断文本 + 评分，status 置为 2
     */
    public void updateToTextAndScore(Long resumeId, String aiResult, String scoreJson) {
        try {
            ResumeAnalysis resume = resumeAnalysisMapper.selectById(resumeId);
            if (resume != null) {
                resume.setAiResult(aiResult);
                if (scoreJson != null) {
                    resume.setScore(scoreJson);
                }
                resume.setStatus(2);
                resume.setUpdateTime(LocalDateTime.now());
                resumeAnalysisMapper.updateById(resume);
                log.info("简历分析+评分入库完成，resumeId={}", resumeId);
            }
        } catch (Exception e) {
            log.error("简历分析入库失败，resumeId={}", resumeId, e);
        }
    }

    /**
     * 标记为失败状态 (-1)
     */
    public void markAsFailed(Long resumeId) {
        ResumeAnalysis resume = resumeAnalysisMapper.selectById(resumeId);
        if (resume != null) {
            resume.setStatus(-1);
            resume.setUpdateTime(LocalDateTime.now());
            resumeAnalysisMapper.updateById(resume);
        }
    }
}