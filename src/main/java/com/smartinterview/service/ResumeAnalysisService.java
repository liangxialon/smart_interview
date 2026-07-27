package com.smartinterview.service;

import com.smartinterview.common.result.PageResult;
import com.smartinterview.entity.ResumeAnalysis;
import com.baomidou.mybatisplus.extension.service.IService;
import com.smartinterview.vo.ResumeDetailVO;
import com.smartinterview.vo.ResumeUploadVO;
import com.smartinterview.vo.ResumeVO;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
* @author 32341
* @description 针对表【resume_analysis(简历智能分析表)】的数据库操作Service
* @createDate 2026-02-26 16:36:05
*/
public interface ResumeAnalysisService extends IService<ResumeAnalysis> {

    ResumeUploadVO upload(MultipartFile file);

    SseEmitter streamAiAnalysis(Long resumeId);

    List<ResumeVO> queryResume();

    void logicalDelete(Long resumeId);

    ResumeDetailVO getResumeDetail(Long resumeId);
    //void asyncGenerateScore(Long resumeId,String rawText);

    String queryReport(Long resumeId);
    SseEmitter streamOptimize(Long resumeId, String jobDescription);
    String queryOptimize(Long resumeId);



    // PageResult pageQuery(Integer current, Integer size);
}
