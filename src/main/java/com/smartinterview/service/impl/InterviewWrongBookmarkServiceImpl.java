package com.smartinterview.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartinterview.common.util.UserHolder;
import com.smartinterview.dto.UserDTO;
import com.smartinterview.entity.InterviewReport;
import com.smartinterview.entity.InterviewSession;
import com.smartinterview.entity.InterviewWrongBookmark;
import com.smartinterview.mapper.InterviewReportMapper;
import com.smartinterview.mapper.InterviewSessionMapper;
import com.smartinterview.mapper.InterviewWrongBookmarkMapper;
import com.smartinterview.service.InterviewWrongBookmarkService;
import com.smartinterview.vo.WrongBookmarkVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class InterviewWrongBookmarkServiceImpl extends ServiceImpl<InterviewWrongBookmarkMapper, InterviewWrongBookmark>
        implements InterviewWrongBookmarkService {

    @Autowired
    private InterviewReportMapper interviewReportMapper;
    @Autowired
    private InterviewSessionMapper interviewSessionMapper;

    @Override
    public boolean toggle(Long questionReportId) {
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();

        LambdaQueryWrapper<InterviewWrongBookmark> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InterviewWrongBookmark::getUserId, userId)
                .eq(InterviewWrongBookmark::getQuestionReportId, questionReportId);
        InterviewWrongBookmark existing = getOne(wrapper);

        if (existing != null) {
            removeById(existing.getId());
            log.info("取消收藏，userId={}，reportId={}", userId, questionReportId);
            return false;
        } else {
            InterviewWrongBookmark bookmark = InterviewWrongBookmark.builder()
                    .userId(userId)
                    .questionReportId(questionReportId)
                    .createTime(LocalDateTime.now())
                    .build();
            save(bookmark);
            log.info("收藏错题，userId={}，reportId={}", userId, questionReportId);
            return true;
        }
    }

    @Override
    public List<WrongBookmarkVO> listBookmarks() {
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();

        // 1. 查询该用户所有收藏
        LambdaQueryWrapper<InterviewWrongBookmark> bookmarkWrapper = new LambdaQueryWrapper<>();
        bookmarkWrapper.eq(InterviewWrongBookmark::getUserId, userId)
                .orderByDesc(InterviewWrongBookmark::getCreateTime);
        List<InterviewWrongBookmark> bookmarks = list(bookmarkWrapper);

        if (bookmarks.isEmpty()) {
            return List.of();
        }

        // 2. 批量查询关联的报告记录
        List<Long> reportIds = bookmarks.stream()
                .map(InterviewWrongBookmark::getQuestionReportId)
                .collect(Collectors.toList());
        Map<Long, InterviewReport> reportMap = interviewReportMapper.selectBatchIds(reportIds)
                .stream().collect(Collectors.toMap(InterviewReport::getId, r -> r));

        // 3. 收集sessionId，批量查询面试信息
        Set<Long> sessionIds = reportMap.values().stream()
                .map(InterviewReport::getSessionId)
                .collect(Collectors.toSet());
        Map<Long, InterviewSession> sessionMap = interviewSessionMapper.selectBatchIds(sessionIds)
                .stream().collect(Collectors.toMap(InterviewSession::getId, s -> s));

        // 4. 组装VO
        List<WrongBookmarkVO> result = new ArrayList<>();
        for (InterviewWrongBookmark bookmark : bookmarks) {
            InterviewReport report = reportMap.get(bookmark.getQuestionReportId());
            if (report == null) continue;

            WrongBookmarkVO vo = new WrongBookmarkVO();
            vo.setBookmarkId(bookmark.getId());
            vo.setQuestionReportId(bookmark.getQuestionReportId());
            vo.setQuestionText(report.getQuestionText());
            vo.setUserAnswer(report.getUserAnswer());
            vo.setScore(report.getScore());
            vo.setComment(report.getComment());
            vo.setIsCorrect(report.getIsCorrect());
            vo.setSessionId(report.getSessionId());
            vo.setBookmarkTime(bookmark.getCreateTime());

            InterviewSession session = sessionMap.get(report.getSessionId());
            if (session != null) {
                vo.setSessionTitle(session.getTitle());
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    public Set<Long> getBookmarkedReportIds(List<Long> questionReportIds) {
        if (questionReportIds == null || questionReportIds.isEmpty()) {
            return Set.of();
        }
        UserDTO user = UserHolder.getUser();
        LambdaQueryWrapper<InterviewWrongBookmark> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InterviewWrongBookmark::getUserId, user.getId())
                .in(InterviewWrongBookmark::getQuestionReportId, questionReportIds);
        return list(wrapper).stream()
                .map(InterviewWrongBookmark::getQuestionReportId)
                .collect(Collectors.toSet());
    }
}
