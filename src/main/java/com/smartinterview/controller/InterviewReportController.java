package com.smartinterview.controller;

import com.smartinterview.common.result.Result;
import com.smartinterview.service.InterviewReportService;
import com.smartinterview.service.InterviewWrongBookmarkService;
import com.smartinterview.vo.InterviewReportVO;
import com.smartinterview.vo.WrongBookmarkVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("interview/report")
@Tag(name="面试报告模块")
public class InterviewReportController {

    @Autowired
    private InterviewReportService interviewReportService;
    @Autowired
    private InterviewWrongBookmarkService bookmarkService;

    @Operation(summary="查看面试报告")
    @PostMapping("{sessionId}")
    public Result getReport(@PathVariable(value="sessionId") Long sessionId){
        log.info("查看面试报告：{}",sessionId);
        InterviewReportVO interviewReportVO = interviewReportService.buildReport(sessionId);
        return Result.success(interviewReportVO);
    }
    @Operation(summary="导出PDF报告")
    @GetMapping("export/{sessionId}")
    public void exportReport(@PathVariable(value="sessionId")Long sessionId, HttpServletResponse response){
        log.info("导出面试报告：{}",sessionId);
        interviewReportService.exportReport(sessionId,response);
    }

    @Operation(summary="薄弱项专项训练")
    @GetMapping("weakness-training/{sessionId}")
    public Result weaknessTraining(@PathVariable Long sessionId){
        log.info("薄弱项专项训练，sessionId={}", sessionId);
        String result = interviewReportService.generateWeaknessTraining(sessionId);
        return Result.success(result);
    }

    @Operation(summary="收藏/取消收藏错题")
    @PostMapping("bookmark/{questionReportId}")
    public Result toggleBookmark(@PathVariable Long questionReportId){
        log.info("切换收藏状态，questionReportId={}", questionReportId);
        boolean bookmarked = bookmarkService.toggle(questionReportId);
        return Result.success(bookmarked);
    }

    @Operation(summary="收藏夹列表")
    @GetMapping("bookmark/list")
    public Result listBookmarks(){
        log.info("查询收藏夹");
        List<WrongBookmarkVO> list = bookmarkService.listBookmarks();
        return Result.success(list);
    }

}
