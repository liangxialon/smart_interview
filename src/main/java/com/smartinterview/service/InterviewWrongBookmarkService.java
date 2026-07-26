package com.smartinterview.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartinterview.entity.InterviewWrongBookmark;
import com.smartinterview.vo.WrongBookmarkVO;

import java.util.List;
import java.util.Set;

public interface InterviewWrongBookmarkService extends IService<InterviewWrongBookmark> {

    boolean toggle(Long questionReportId);

    List<WrongBookmarkVO> listBookmarks();

    Set<Long> getBookmarkedReportIds(List<Long> questionReportIds);
}
