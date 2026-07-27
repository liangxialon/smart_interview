package com.smartinterview.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartinterview.common.result.Result;
import com.smartinterview.entity.InterviewReport;
import com.smartinterview.vo.InterviewReportVO;
import jakarta.servlet.http.HttpServletResponse;

public interface InterviewReportService extends IService<InterviewReport> {
    InterviewReportVO buildReport(Long sessionId);

    void exportReport(Long sessionId, HttpServletResponse response);

    String generateWeaknessTraining(Long sessionId);
}
