package com.smartinterview.service;

import com.smartinterview.common.result.PageResult;
import com.smartinterview.entity.SysQuestion;
import com.baomidou.mybatisplus.extension.service.IService;

import java.io.IOException;
import java.util.List;

/**
* @author 32341
* @description 针对表【sys_question(系统面试题库)】的数据库操作Service
* @createDate 2026-02-26 16:36:05
*/
public interface SysQuestionService extends IService<SysQuestion> {

     String searchStanderAnswer(String userMessage);
     void syncToEsBatch(List<SysQuestion> questions) ;
     void batchGenerateEmbeddingForQuestions(List<SysQuestion> questions);
     PageResult pageQuery(Integer page, Integer pageSize, String category, String question, Integer difficulty);
}
