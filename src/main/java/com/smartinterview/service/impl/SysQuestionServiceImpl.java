package com.smartinterview.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartinterview.common.exception.ElasticSearchException;
import com.smartinterview.common.result.PageResult;
import com.smartinterview.entity.SysQuestion;
import com.smartinterview.mapper.SysQuestionMapper;
import com.smartinterview.service.SysQuestionService;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.lucene.search.function.FunctionScoreQuery;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 题库服务：ES 混合检索（关键词 + 向量余弦相似度）
 *
 * 架构：
 * - 题目元数据存储在 MySQL sys_question 表
 * - 题目向量存储在 ES 索引 question_embedding 字段
 * - 检索时使用 ES function_score 混合查询：关键词得分 + 向量余弦相似度
 * - 题库导入后调用 batchGenerateEmbedding 生成向量并写入 ES
 */
@Service
@Slf4j
public class SysQuestionServiceImpl extends ServiceImpl<SysQuestionMapper, SysQuestion>
        implements SysQuestionService {
    @Autowired
    private RestHighLevelClient client;
    private static final String questionIndex = "sys_question_index";
    private static final int VECTOR_DIM = 1024;
    private static final float VECTOR_WEIGHT = 0.6f;
    private static final float KEYWORD_WEIGHT = 1.5f;

    @Autowired
    private EmbeddingServiceImpl embeddingService;

    /**
     * 启动时初始化 ES 索引库     */
    @PostConstruct
    public void initIndex() {
        try {
            boolean exists = client.indices().exists(
                    new GetIndexRequest(questionIndex), RequestOptions.DEFAULT);
            if (exists) {
                log.info("题库索引 {} 已存在，跳过创建", questionIndex);
                return;
            }
            CreateIndexRequest request = new CreateIndexRequest(questionIndex);
            XContentBuilder mapping = XContentFactory.jsonBuilder()
                    .startObject().startObject("properties")
                    // 题目文本：ik 分词 + keyword 短语
                    .startObject("question")
                    .field("type", "text")
                    .field("analyzer", "ik_max_word")
                    .field("search_analyzer", "ik_smart")
                    .endObject()
                    // 标准答案
                    .startObject("answer")
                    .field("type", "text")
                    .field("analyzer", "ik_max_word")
                    .field("search_analyzer", "ik_smart")
                    .endObject()
                    // 题目语义向量
                    .startObject("question_embedding")
                    .field("type", "dense_vector")
                    .field("dims", VECTOR_DIM)
                    .endObject()
                    .endObject().endObject();
            request.mapping(mapping);
            client.indices().create(request, RequestOptions.DEFAULT);
            log.info("题库 ES 索引 {} 创建成功", questionIndex);
        } catch (Exception e) {
            log.error("题库 ES 索引初始化失败", e);
        }
    }

    // ─────────────────────────────────────────────────────────
    //  核心方法：ES 混合检索标准答案（关键词 + 向量）
    // ─────────────────────────────────────────────────────────

    @Override
    public String searchStanderAnswer(String aiQuestion) {
        if (StrUtil.isBlank(aiQuestion)) {
            return null;
        }
        try {
            // 1. 生成查询向量
            float[] vector = embeddingService.embed(aiQuestion);
            if (vector == null) {
                log.warn("标准答案查询向量生成失败，降级为纯关键词检索");
                return keywordOnlySearch(aiQuestion);
            }
            // 2. 构建 function_score 混合检索
            // 2a. 关键词基础查询：question + answer 匹配
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery()
                    .should(QueryBuilders.matchQuery("question", aiQuestion).boost(KEYWORD_WEIGHT))
                    .should(QueryBuilders.matchQuery("answer", aiQuestion).boost(1.0f))//boost权重
                    .minimumShouldMatch("1");//至少命中其中一个 should 条件。都命中的话总体得分相加更高
            // 2b. 余弦相似度评分函数（painless 脚本）
            Map<String, Object> params = new HashMap<>();
            params.put("query_vector", vector);
            String cosineScript = buildCosineScript();
            FunctionScoreQueryBuilder.FilterFunctionBuilder cosineFunction =
                    new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                            QueryBuilders.existsQuery("question_embedding"),
                            ScoreFunctionBuilders.scriptFunction(
                                    new Script(ScriptType.INLINE, "painless", cosineScript, params)
                            ).setWeight(VECTOR_WEIGHT)
                    );
            // 2c. 组装 function_score：关键词分数 + 向量余弦相似度
            FunctionScoreQueryBuilder functionScoreQuery = QueryBuilders.functionScoreQuery(
                    boolQuery,
                    new FunctionScoreQueryBuilder.FilterFunctionBuilder[]{cosineFunction}
            ).scoreMode(FunctionScoreQuery.ScoreMode.SUM);
            //finalScore = 关键词文本得分 + 向量相似度得分 × VECTOR_WEIGHT
            // 3. 执行查询
            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder()
                    .query(functionScoreQuery)
                    .size(1)
                    .fetchSource("answer", null);
            SearchRequest searchRequest = new SearchRequest(questionIndex);
            searchRequest.source(sourceBuilder);
            SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);
            // 4. 解析结果，加最低分数阈值防止误匹配
            SearchHit[] hits = response.getHits().getHits();
            if (hits.length > 0 && hits[0].getScore() >= 80.0f) {
                String standerAnswer = (String) hits[0].getSourceAsMap().get("answer");
                log.info("标准答案命中：score={}, query={}", hits[0].getScore(),
                        aiQuestion.substring(0, Math.min(30, aiQuestion.length())));
                return standerAnswer;
            }
            log.info("标准答案未命中或分数过低：score={}, query={}",
                    hits.length > 0 ? hits[0].getScore() : 0,
                    aiQuestion.substring(0, Math.min(30, aiQuestion.length())));
            return null;
        } catch (Exception e) {
            log.error("混合检索标准答案异常，降级跳过", e);
            return null;
        }
    }

    /**
     * 向量生成失败时的降级方案：纯关键词检索
     */
    private String keywordOnlySearch(String aiQuestion) {
        try {
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery()
                    .should(QueryBuilders.matchQuery("question", aiQuestion).boost(1.5f))
                    .should(QueryBuilders.matchQuery("answer", aiQuestion).boost(1.0f))
                    .minimumShouldMatch("1");
            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder()
                    .query(boolQuery)
                    .size(1)
                    .fetchSource("answer", null);
            SearchRequest searchRequest = new SearchRequest(questionIndex);
            searchRequest.source(sourceBuilder);
            SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);
            SearchHit[] hits = response.getHits().getHits();
            if (hits.length > 0) {
                return (String) hits[0].getSourceAsMap().get("answer");
            }
        } catch (IOException e) {
            log.error("关键词降级检索失败", e);
        }
        return null;
    }

    private String buildCosineScript() {
        return "cosineSimilarity(params.query_vector, 'question_embedding') + 1.0";
    }

    /**
     * 将题目批量同步到 Elasticsearch（含 embedding 一次性写入）
     * embedding 生成失败不影响题目入库，后续可手动触发补全
     * @param questions 题目列表
     */
    int i=1;
    public void syncToEsBatch(List<SysQuestion> questions)  {
        if(questions==null||questions.isEmpty()){
            return ;
        }

        try {
            //批量插入请求对象
            BulkRequest bulkRequest=new BulkRequest();
            int embedSuccess = 0, embedFail = 0;
            for(SysQuestion q:questions){
                IndexRequest indexRequest=new IndexRequest(questionIndex)
                        .id(q.getId().toString());
                Map<String,Object> map=new HashMap();
                map.put("answer",q.getAnswer());
                map.put("question",q.getQuestion());
                // 生成 embedding，失败则跳过，不影响入库
                try {
                    float[] vector = embeddingService.embed(q.getQuestion());
                    if (vector != null) {
                        map.put("question_embedding", vector);
                        embedSuccess++;
                    } else {
                        embedFail++;
                    }
                } catch (Exception e) {
                    log.warn("题目 {} 生成向量失败，跳过 embedding: {}", q.getId(), e.getMessage());
                    embedFail++;
                }
                indexRequest.source(map, XContentType.JSON);
                bulkRequest.add(indexRequest);
            }
            //批量发送请求插入
            BulkResponse bulkResponse=client.bulk(bulkRequest,RequestOptions.DEFAULT);
            if (bulkResponse.hasFailures()) {
                throw new ElasticSearchException("ES 题库批量同步部分失败");
            } else {
                log.info("第{}轮：同步 {} 条到ES，向量成功{}失败{}", i++, questions.size(), embedSuccess, embedFail);
            }
        } catch (Exception e) {
            throw new ElasticSearchException("ES 题库批量同步异常：" + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    //  管理方法：为题目批量生成 embedding
    // ─────────────────────────────────────────────────────────

    /**
     * 为指定题目列表生成向量（导入流程调用）
     */

    public void batchGenerateEmbeddingForQuestions(List<SysQuestion> questions) {
        if (questions == null || questions.isEmpty()) {
            return;
        }
        log.info("开始为 {} 道新导入题目生成向量", questions.size());
        int success = 0, fail = 0;
        for (SysQuestion q : questions) {
            try {
                float[] vector = embeddingService.embed(q.getQuestion());
                if (vector != null) {
                    Map<String, Object> doc = new HashMap<>();
                    doc.put("question_embedding", vector);
                    Map<String, Object> upsert = new HashMap<>();
                    upsert.put("question", q.getQuestion());
                    upsert.put("answer", q.getAnswer());
                    upsert.put("question_embedding", vector);
                    UpdateRequest updateRequest = new UpdateRequest(questionIndex, q.getId().toString())
                            .doc(doc)
                            .upsert(upsert);
                    client.update(updateRequest, RequestOptions.DEFAULT);
                    success++;
                } else {
                    fail++;
                }
                Thread.sleep(100);
            } catch (Exception e) {
                log.error("题目 {} 生成向量失败", q.getId(), e);
                fail++;
            }
        }
        log.info("新导入题目向量生成完成：成功 {}，失败 {}", success, fail);
    }

    /**
     * 为所有题目批量生成向量（手动全量重建索引）
     */
    @Async
    public void batchGenerateEmbedding() {
        List<SysQuestion> questions = list();

        log.info("开始批量生成向量，共 {} 道题目", questions.size());
        int success = 0, fail = 0;

        for (SysQuestion q : questions) {
            try {
                String text = q.getQuestion();
                float[] vector = embeddingService.embed(text);

                if (vector != null) {
                    // 直接更新 ES 文档的 question_embedding 字段
                    Map<String, Object> doc = new HashMap<>();
                    doc.put("question_embedding", vector);
                    // 文档不存在时用完整数据创建，存在时只更新 embedding
                    Map<String, Object> upsert = new HashMap<>();
                    upsert.put("question", q.getQuestion());
                    upsert.put("answer", q.getAnswer());
                    upsert.put("question_embedding", vector);
                    UpdateRequest updateRequest = new UpdateRequest(questionIndex, q.getId().toString())
                            .doc(doc)
                            .upsert(upsert);
                    client.update(updateRequest, RequestOptions.DEFAULT);
                    success++;
                } else {
                    fail++;
                }

                Thread.sleep(100);

            } catch (Exception e) {
                log.error("题目 {} 生成向量失败", q.getId(), e);
                fail++;
            }
        }
        log.info("批量生成向量完成：成功 {}，失败 {}", success, fail);
    }

    /**
     * 为单道题目生成 embedding（题库导入后调用）
     * 直接写入 ES 索引，不再存 MySQL
     */
    public void generateEmbeddingForQuestion(SysQuestion q) {
        try {
            String text = q.getQuestion();
            float[] vector = embeddingService.embed(text);
            if (vector != null) {
                Map<String, Object> doc = new HashMap<>();
                doc.put("question_embedding", vector);
                Map<String, Object> upsert = new HashMap<>();
                upsert.put("question", q.getQuestion());
                upsert.put("answer", q.getAnswer());
                upsert.put("question_embedding", vector);
                UpdateRequest updateRequest = new UpdateRequest(questionIndex, q.getId().toString())
                        .doc(doc)
                        .upsert(upsert);
                client.update(updateRequest, RequestOptions.DEFAULT);
            }
        } catch (Exception e) {
            log.error("单题生成向量失败，questionId={}", q.getId(), e);
        }
    }

    @Override
    public PageResult pageQuery(Integer page, Integer pageSize, String category, String question, Integer difficulty) {
        LambdaQueryWrapper<SysQuestion> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StrUtil.isNotBlank(category), SysQuestion::getCategory, category)
                .eq(difficulty != null, SysQuestion::getDifficulty, difficulty)
                .like(StrUtil.isNotBlank(question), SysQuestion::getQuestion, question)
                .orderByDesc(SysQuestion::getCreateTime);
        Page<SysQuestion> pageResult = page(new Page<>(page, pageSize), wrapper);
        PageResult result = new PageResult();
        result.setTotal(pageResult.getTotal());
        result.setPages(pageResult.getPages());
        result.setCurrent((int) pageResult.getCurrent());
        result.setSize(pageResult.getSize());
        result.setRecords(pageResult.getRecords());
        return result;
    }
}



