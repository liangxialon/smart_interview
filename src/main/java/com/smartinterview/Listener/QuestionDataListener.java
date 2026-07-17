package com.smartinterview.Listener;


import cn.hutool.core.bean.BeanUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.smartinterview.common.exception.ElasticSearchException;
import com.smartinterview.dto.QuestionImportDTO;
import com.smartinterview.entity.SysQuestion;
import com.smartinterview.service.SysQuestionService;
import org.elasticsearch.ElasticsearchCorruptionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


public class QuestionDataListener extends AnalysisEventListener<QuestionImportDTO> {
    // EasyExcel 的 Listener 每次读取文件时都是通过 new 关键字手动创建出来的。
    //    @Autowired不被spring容器管理
        private  SysQuestionService questionService;
    public QuestionDataListener(SysQuestionService sysQuestionService){
        this.questionService=sysQuestionService;
    }
    private final List<SysQuestion> questionList=new ArrayList();
    public void invoke(QuestionImportDTO data, AnalysisContext context){
        SysQuestion question=new SysQuestion();
        BeanUtil.copyProperties(data,question);
        questionList.add(question);
        //实时判断 ，元素大于=100就入库
        if(questionList.size()>=100){
            questionService.saveBatch(questionList);
            // 2. 将带有 ID 的数据同步写入 ES 索引
            questionService.syncToEsBatch(questionList);
            questionList.clear();
        }
    }
    public void doAfterAllAnalysed(AnalysisContext context){
        //最后剩余的入库
        if(!questionList.isEmpty()){
            questionService.saveBatch(questionList);
        }
            questionService.syncToEsBatch(questionList);

    }


}
