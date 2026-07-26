package com.smartinterview.service;

import java.util.List;

public interface ResumeChunkService {

    /**
     * 将简历原文切片 → 生成 embedding → 批量写入 ES
     * 在简历 PDF 解析完成后由 MQ 消费者调用
     */
    void chunkAndIndex(Long resumeId, Long userId, String originalText);

    /**
     * 根据当前话题文本，从 ES 混合检索（向量余弦 + 关键词）最相关的简历切片
     *
     * @param userId    当前用户（隔离不同用户的简历）
     * @param queryText 当前面试话题（用户回答 or AI 问题）
     * @param topK      返回条数（推荐 2）
     * @return 拼接好的切片文本，无结果返回空串
     */
    String searchRelevantChunks(Long userId, String queryText, int topK);
}
