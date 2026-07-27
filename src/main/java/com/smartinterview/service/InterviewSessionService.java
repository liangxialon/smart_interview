package com.smartinterview.service;

import com.smartinterview.dto.ChatDTO;
import com.smartinterview.dto.StartInterviewDTO;
import com.smartinterview.entity.InterviewSession;
import com.baomidou.mybatisplus.extension.service.IService;
import com.smartinterview.vo.InterviewSessionVO;
import com.smartinterview.vo.InterviewStartVO;
import com.smartinterview.vo.InterviewStatsVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
* @author 32341
* @description 针对表【interview_session(面试会话主表)】的数据库操作Service
* @createDate 2026-02-26 16:36:05
*/
public interface InterviewSessionService extends IService<InterviewSession> {

    SseEmitter chat(ChatDTO dto);

    void finishInterview(Long sessionId);

    InterviewStartVO startInterview(StartInterviewDTO dto);

    List<InterviewSessionVO> queryInterview();

    void logicalDelete(Long sessionId);

    InterviewStatsVO getInterviewStats();
}
