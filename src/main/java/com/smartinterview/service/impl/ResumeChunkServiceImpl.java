package com.smartinterview.service.impl;

import cn.hutool.core.util.StrUtil;
import com.smartinterview.entity.ResumeChunk;
import com.smartinterview.service.EmbeddingService;
import com.smartinterview.service.ResumeChunkService;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.*;

/**
 * 简历切片服务：切片 → embedding → ES 索引 → 混合检索
 */
@Service
@Slf4j
public class ResumeChunkServiceImpl implements ResumeChunkService {

    private static final String INDEX_NAME = "sys_resume_chunk_index";
    /** 每片目标字数 */
    private static final int CHUNK_TARGET_SIZE = 250;
    /** 片间重叠字数 */
    private static final int CHUNK_OVERLAP = 50;
    /** 向量维度 */
    private static final int DIMENSIONS = 1024;
    /** 向量检索权重 */
    private static final float VECTOR_WEIGHT = 0.6f;
    /** 关键词检索权重 */
    private static final float KEYWORD_WEIGHT = 0.4f;

    @Autowired
    private RestHighLevelClient client;
    @Autowired
    private EmbeddingService embeddingService;

    /**
     * 启动时确保 ES 索引存在
     */
    @PostConstruct
    public void initIndex() {
        try {
            boolean exists = client.indices().exists(
                    new GetIndexRequest(INDEX_NAME), RequestOptions.DEFAULT);
            if (!exists) {
                createIndex();
                log.info("简历切片 ES 索引 [{}] 创建成功", INDEX_NAME);
            }
        } catch (IOException e) {
            log.error("检查/创建简历切片索引失败", e);
        }
    }

    private void createIndex() throws IOException {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("properties")
                        .startObject("resume_id")
                            .field("type", "long")
                        .endObject()
                        .startObject("user_id")
                            .field("type", "long")
                        .endObject()
                        .startObject("content")
                            .field("type", "text")
                            .startObject("analyzer")
                                .field("type", "ik_max_word")
                            .endObject()
                        .endObject()
                        .startObject("embedding")
                            .field("type", "dense_vector")
                            .field("dims", DIMENSIONS)
                        .endObject()
                        .startObject("chunk_index")
                            .field("type", "integer")
                        .endObject()
                    .endObject()
                .endObject();

        CreateIndexRequest request = new CreateIndexRequest(INDEX_NAME).source(mapping);
        CreateIndexResponse response = client.indices().create(request, RequestOptions.DEFAULT);
        if (!response.isAcknowledged()) {
            log.error("简历切片索引创建未确认");
        }
    }

    @Override
    public void chunkAndIndex(Long resumeId, Long userId, String originalText) {
        if (StrUtil.isBlank(originalText)) {
            log.warn("简历原文为空，跳过切片, resumeId={}", resumeId);
            return;
        }

        // 1. 切片
        List<String> chunks = splitText(originalText);
        log.info("简历切片完成, resumeId={}, 共{}片", resumeId, chunks.size());

        // 2. 为每个切片生成 embedding 并批量写入 ES
        BulkRequest bulkRequest = new BulkRequest();
        int success = 0;

        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);
            float[] vector = embeddingService.embed(chunkText);
            if (vector == null) {
                log.warn("切片 embedding 生成失败, resumeId={}, chunkIndex={}", resumeId, i);
                continue;
            }

            String docId = resumeId + "_" + i;
            Map<String, Object> doc = new HashMap<>();
            doc.put("resume_id", resumeId);
            doc.put("user_id", userId);
            doc.put("content", chunkText);
            doc.put("embedding", vector);
            doc.put("chunk_index", i);

            IndexRequest indexRequest = new IndexRequest(INDEX_NAME)
                    .id(docId)
                    .source(doc, XContentType.JSON);
            bulkRequest.add(indexRequest);
            success++;
        }

        if (bulkRequest.requests().isEmpty()) {
            log.warn("无有效切片可索引, resumeId={}", resumeId);
            return;
        }

        try {
            BulkResponse response = client.bulk(bulkRequest, RequestOptions.DEFAULT);
            if (response.hasFailures()) {
                log.error("简历切片批量索引部分失败, resumeId={}, 错误={}", resumeId, response.buildFailureMessage());
            } else {
                log.info("简历切片索引完成, resumeId={}, 成功{}片", resumeId, success);
            }
        } catch (IOException e) {
            log.error("简历切片批量索引异常, resumeId={}", resumeId, e);
        }
    }

    @Override
    public String searchRelevantChunks(Long userId, String queryText, int topK) {
        if (StrUtil.isBlank(queryText) || userId == null) {
            return "";
        }

        try {
            // 生成查询向量
            float[] queryVector = embeddingService.embed(queryText);
            if (queryVector == null) {
                log.warn("查询向量生成失败，降级为纯关键词检索");
                return keywordOnlySearch(userId, queryText, topK);
            }

            // 混合检索：script_score 向量余弦 + bool 关键词
            String vectorScript = String.format(
                    "cosineSimilarity(params.query_vector, 'embedding') + 1.0",
                    VECTOR_WEIGHT, KEYWORD_WEIGHT
            );

            // 构建 script_score 查询
            Map<String, Object> params = new HashMap<>();
            params.put("query_vector", queryVector);

            Script script = new Script(ScriptType.INLINE, "painless",
                    "cosineSimilarity(params.query_vector, 'embedding') + 1.0", params);

            FunctionScoreQueryBuilder functionScore = QueryBuilders.functionScoreQuery(
                    // 基础过滤：同用户
                    QueryBuilders.boolQuery()
                            .must(QueryBuilders.termQuery("user_id", userId))
                            .should(QueryBuilders.matchQuery("content", queryText).boost(KEYWORD_WEIGHT)),
                    // 向量相似度函数
                    ScoreFunctionBuilders.scriptFunction(script)
            ).scoreMode("sum").maxBoost(3.0f);

            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder()
                    .query(functionScore)
                    .size(topK)
                    .fetchSource(new String[]{"content", "chunk_index", "resume_id"}, null);

            SearchRequest searchRequest = new SearchRequest(INDEX_NAME).source(sourceBuilder);
            SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);

            SearchHit[] hits = response.getHits().getHits();
            if (hits.length == 0) {
                return "";
            }

            // 拼接检索结果
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hits.length; i++) {
                Map<String, Object> source = hits[i].getSourceAsMap();
                String content = (String) source.get("content");
                if (StrUtil.isNotBlank(content)) {
                    if (sb.length() > 0) sb.append("\n---\n");
                    sb.append("【简历片段").append(i + 1).append("】").append(content);
                }
            }
            return sb.toString();

        } catch (Exception e) {
            log.error("简历切片检索异常，降级跳过, userId={}", userId, e);
            return "";
        }
    }

    /**
     * 降级：纯关键词检索（向量生成失败时使用）
     */
    private String keywordOnlySearch(Long userId, String queryText, int topK) {
        try {
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery()
                    .must(QueryBuilders.termQuery("user_id", userId))
                    .must(QueryBuilders.matchQuery("content", queryText));

            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder()
                    .query(boolQuery)
                    .size(topK)
                    .fetchSource(new String[]{"content"}, null);

            SearchRequest searchRequest = new SearchRequest(INDEX_NAME).source(sourceBuilder);
            SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);

            SearchHit[] hits = response.getHits().getHits();
            if (hits.length == 0) return "";

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hits.length; i++) {
                String content = (String) hits[i].getSourceAsMap().get("content");
                if (StrUtil.isNotBlank(content)) {
                    if (sb.length() > 0) sb.append("\n---\n");
                    sb.append("【简历片段").append(i + 1).append("】").append(content);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("关键词降级检索失败", e);
            return "";
        }
    }

    /**
     * 按段落切片，目标 250 字/片，重叠 50 字
     */
    private List<String> splitText(String text) {
        List<String> chunks = new ArrayList<>();
        // 先按自然段落分割
        String[] paragraphs = text.split("\\n\\s*\\n|\\r\\n\\s*\\r\\n");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            para = para.trim();
            if (para.isEmpty()) continue;

            if (current.length() + para.length() > CHUNK_TARGET_SIZE && current.length() > 0) {
                chunks.add(current.toString().trim());
                // 保留尾部 overlap
                String prev = current.toString();
                int overlapStart = Math.max(0, prev.length() - CHUNK_OVERLAP);
                current = new StringBuilder(prev.substring(overlapStart));
            }
            current.append(para).append("\n");
        }

        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        // 如果整篇只有一段且超长，按字数硬切
        if (chunks.isEmpty() && text.length() > 0) {
            for (int i = 0; i < text.length(); i += CHUNK_TARGET_SIZE - CHUNK_OVERLAP) {
                int end = Math.min(i + CHUNK_TARGET_SIZE, text.length());
                chunks.add(text.substring(i, end));
            }
        }

        return chunks;
    }
}
