package com.smartinterview.controller;

import com.smartinterview.common.result.Result;
import com.smartinterview.dto.ChatDTO;
import com.smartinterview.dto.StartInterviewDTO;
import com.smartinterview.service.InterviewSessionService;
import com.smartinterview.vo.InterviewSessionVO;
import com.smartinterview.vo.InterviewStartVO;
import com.smartinterview.vo.InterviewStatsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.tags.Tags;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 面试会话管理 Controller
 * 负责面试的整个生命周期：开始 → 对话 → 结束
 */
@RestController
@RequestMapping("/interview/session")
@Slf4j
@Tag(name="面试会话管理")
public class InterviewSessionController {

    @Autowired
    private InterviewSessionService interviewSessionService;

    /**
     * 开始面试
     * 前端进入面试页时调用，创建 InterviewSession 记录，返回 sessionId
     * <p>
     * POST /interview/session/start
     * <p>
     * 请求体示例：
     * {
     * "resumeId": 1,
     * "category": "Java",
     * "difficulty": "medium",
     * "title": "Java 后端面试"
     * }
     * <p>
     * 响应示例：
     * { "code": 200, "data": { "sessionId": 1001 } }
     */
    @Operation(summary = "开始面试")
    @PostMapping("/start")
    public Result startInterview(@RequestBody StartInterviewDTO dto) {
        InterviewStartVO interviewStartVO = interviewSessionService.startInterview(dto);
        return Result.success(interviewStartVO);
    }

    /**
     * 面试对话（SSE 流式）
     * 每次用户发送消息时调用，AI 以流式方式逐字返回回复
     * <p>
     * POST /interview/session/chat
     * <p>
     * 响应：text/event-stream
     * 每个 chunk 是 AI 回复的片段，最后一条为 "DONE" 表示本轮结束
     */

    @Operation(summary = "发送面试消息获取AI流式回复")
    @PostMapping(value = "chat", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatDTO dto,
                           HttpServletResponse httpServletResponse) {
        httpServletResponse.setCharacterEncoding("UTF-8");
        httpServletResponse.setContentType("text/event-stream;charset=UTF-8");
        httpServletResponse.setHeader("Cache-Control", "no-cache");
        httpServletResponse.setHeader("Connection", "keep-alive");
        return interviewSessionService.chat(dto);

    }

    /**
     * 结束面试
     * 用户点击「结束面试」按钮时调用，将 session.status 置为 1
     * 结束后才可以调用报告接口查看结果
     * <p>
     * POST /interview/session/finish?sessionId=1001
     * <p>
     * 响应示例：
     * { "code": 200, "msg": "success", "data": null }
     */
    @Operation(summary = "结束面试")
    @PostMapping("finish/{sessionId}") //方法路径加/匹配剧决对路径
    public Result finishInterview(@PathVariable Long sessionId) {
        log.info("结束面试:{}", sessionId);
        interviewSessionService.finishInterview(sessionId);
        return Result.success();
    }

    @Operation(summary = "查询面试记录")
    @GetMapping("list")
    public Result queryInterview() {

        log.info("查询面试记录");
        List<InterviewSessionVO> vo = interviewSessionService.queryInterview();
        return Result.success(vo);
    }

    @Operation(summary = "逻辑删除面试记录")
    @DeleteMapping("{sessionId}")
    public Result logicalDelete(@PathVariable Long sessionId) {
        log.info("删除面试记录：{}", sessionId);
        interviewSessionService.logicalDelete(sessionId);
        return Result.success();
    }

    @Operation(summary = "统计面试记录")
    @GetMapping("stats")
    public Result getInterviewStats() {
        InterviewStatsVO vo = interviewSessionService.getInterviewStats();
        return Result.success(vo);
    }

}