package com.smartinterview.service.impl;

import cn.hutool.core.util.StrUtil;
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
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;
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
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历切片服务：文本切片 → 生成向量Embedding → ES存储 + 关键词+向量混合检索
 */
@Service
@Slf4j
public class ResumeChunkServiceImpl implements ResumeChunkService {

    private static final String INDEX_NAME = "sys_resume_chunk_index";
    /** 单切片目标字符长度 */
    private static final int CHUNK_TARGET_SIZE = 250;
    /** 切片之间重叠字符，保证语义连贯 */
    private static final int CHUNK_OVERLAP = 50;
    /** Embedding向量维度 */
    private static final int DIMENSIONS = 1024;
    /** 向量相似度得分权重 */
    private static final float VECTOR_WEIGHT = 0.6f;
    /** 关键词匹配得分权重 */
    private static final float KEYWORD_WEIGHT = 0.4f;

    @Autowired
    private RestHighLevelClient client;
    @Autowired
    private EmbeddingService embeddingService;

    /**
     * 项目启动自动校验并创建索引
     * 如果索引已存在但 embedding 字段不是 dense_vector 类型，则删除重建
     */
    @PostConstruct
    public void initIndex() {
        try {
            boolean exists = client.indices().exists(new GetIndexRequest(INDEX_NAME), RequestOptions.DEFAULT);
            if (!exists) {
                createIndex();
                log.info("简历切片ES索引 [{}] 创建成功", INDEX_NAME);
            } else {
                log.info("索引 [{}] 已存在，跳过创建", INDEX_NAME);
            }
        } catch (IOException e) {
            log.error("检查/创建简历切片索引失败", e);
        }
    }

    /**
     * 创建索引Mapping，使用IK分词
     */
    private void createIndex() throws IOException {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                .startObject("mappings")   // <-- 加上这一层！
                .startObject("properties")
                .startObject("resume_id")
                .field("type", "long")
                .endObject()
                .startObject("user_id")
                .field("type", "long")
                .endObject()
                .startObject("content")
                .field("type", "text")
                .field("analyzer", "ik_max_word")
                .endObject()
                .startObject("embedding")
                .field("type", "dense_vector")
                .field("dims", DIMENSIONS)
                // 推荐加上索引参数，提升后续检索性能
                .field("index", true)
                .field("similarity", "cosine")
                .endObject()
                .startObject("chunk_index")
                .field("type", "integer")
                .endObject()
                .endObject()
                .endObject()
                .endObject();

        CreateIndexRequest request = new CreateIndexRequest(INDEX_NAME).source(mapping);
        CreateIndexResponse response = client.indices().create(request, RequestOptions.DEFAULT);
        if (!response.isAcknowledged()) {
            log.error("简历切片索引创建未收到ES确认");
        }
    }

    /**
     * 简历文本切片、向量化、批量写入ES
     * @param resumeId 简历ID
     * @param userId 用户ID
     * @param originalText 简历完整文本
     */
    @Override
    public void chunkAndIndex(Long resumeId, Long userId, String originalText) {
        if (StrUtil.isBlank(originalText)) {
            log.warn("简历原文为空，跳过切片, resumeId={}", resumeId);
            return;
        }

        // 1. 文本切片
        List<String> chunks = splitText(originalText);
        log.info("简历切片完成, resumeId={}, 切片总数={}", resumeId, chunks.size());

        // 2. 批量构建ES写入请求
        BulkRequest bulkRequest = new BulkRequest();
        int successCount = 0;

        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);
            float[] vector = embeddingService.embed(chunkText);
            if (vector == null) {
                log.warn("切片向量生成失败，跳过该片段, resumeId={}, chunkIndex={}", resumeId, i);
                continue;
            }

            String docId = resumeId + "_" + i;
            Map<String, Object> docMap = new HashMap<>();
            docMap.put("resume_id", resumeId);
            docMap.put("user_id", userId);
            docMap.put("content", chunkText);
            docMap.put("embedding", vector);
            docMap.put("chunk_index", i);

            IndexRequest indexRequest = new IndexRequest(INDEX_NAME)
                    .id(docId)
                    .source(docMap, XContentType.JSON);
            bulkRequest.add(indexRequest);
            successCount++;
        }

        if (bulkRequest.requests().isEmpty()) {
            log.warn("无有效切片可写入ES, resumeId={}", resumeId);
            return;
        }

        // 3. 执行批量写入
        try {
            BulkResponse response = client.bulk(bulkRequest, RequestOptions.DEFAULT);
            if (response.hasFailures()) {
                log.error("简历切片批量写入部分失败, resumeId={}, 失败信息={}",
                        resumeId, response.buildFailureMessage());
            } else {
                log.info("简历切片ES入库完成, resumeId={}, 成功{}片", resumeId, successCount);
            }
        } catch (IOException e) {
            log.error("简历切片批量写入ES异常, resumeId={}", resumeId, e);
        }
    }

    /**
     * 混合检索：关键词BM25 + 向量余弦相似度融合打分
     * @param userId 用户ID，数据隔离
     * @param queryText 查询关键词
     * @param topK 返回片段数量
     * @return 拼接后的简历片段文本
     */
    @Override
    public String searchRelevantChunks(Long userId, String queryText, int topK) {
        if (StrUtil.isBlank(queryText) || userId == null) {
            return "";
        }

        try {
            // 生成查询向量
            float[] queryVector = embeddingService.embed(queryText);
            if (queryVector == null) {
                log.warn("查询向量生成失败，自动降级纯关键词检索");
                return keywordOnlySearch(userId, queryText, topK);
            }

            Map<String, Object> params = new HashMap<>();
            params.put("query_vector", queryVector);
            // 余弦相似度脚本，值域[-1,1] +1映射至[0,2]避免负分
            Script cosineScript = new Script(ScriptType.INLINE, "painless",
                    "cosineSimilarity(params.query_vector, 'embedding') + 1.0", params);

            // BoolQuery：must强制用户隔离+向量存在性过滤，should仅参与打分，不做过滤
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery()
                    .must(QueryBuilders.termQuery("user_id", userId))
                    .must(QueryBuilders.existsQuery("embedding"))
                    .should(QueryBuilders.matchQuery("content", queryText).boost(KEYWORD_WEIGHT));

            // functionScore融合关键词分数+向量相似度分数
            FunctionScoreQueryBuilder functionScore = QueryBuilders.functionScoreQuery(
                            boolQuery,
                            ScoreFunctionBuilders.scriptFunction(cosineScript).setWeight(VECTOR_WEIGHT)
                    )
                    // 修复：直接使用枚举常量，移除valueOf字符串硬编码
                    .scoreMode(FunctionScoreQuery.ScoreMode.SUM)
                    .maxBoost(3.0f);

            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder()
                    .query(functionScore)
                    .size(topK)
                    .fetchSource(new String[]{"content", "chunk_index", "resume_id"}, null);

            SearchResponse response = client.search(new SearchRequest(INDEX_NAME).source(sourceBuilder), RequestOptions.DEFAULT);
            SearchHit[] hits = response.getHits().getHits();
            if (hits.length == 0) {
                return "";
            }

            // 拼接返回结果
            StringBuilder resultSb = new StringBuilder();
            for (int i = 0; i < hits.length; i++) {
                Map<String, Object> sourceMap = hits[i].getSourceAsMap();
                String content = (String) sourceMap.get("content");
                if (StrUtil.isNotBlank(content)) {
                    if (resultSb.length() > 0) {
                        resultSb.append("\n---\n");
                    }
                    resultSb.append("【简历片段").append(i + 1).append("】").append(content);
                }
            }
            return resultSb.toString();
        } catch (Exception e) {
            log.error("简历混合检索异常，userId={}", userId, e);
            return "";
        }
    }

    /**
     * 降级方案：向量服务不可用时，仅关键词检索
     */
    private String keywordOnlySearch(Long userId, String queryText, int topK) {
        try {
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery()
                    .must(QueryBuilders.termQuery("user_id", userId))
                    .must(QueryBuilders.matchQuery("content", queryText));

            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder()
                    .query(boolQuery)
                    .size(topK)
                    .fetchSource("content", null);

            SearchResponse response = client.search(new SearchRequest(INDEX_NAME).source(sourceBuilder), RequestOptions.DEFAULT);
            SearchHit[] hits = response.getHits().getHits();
            if (hits.length == 0) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hits.length; i++) {
                String content = (String) hits[i].getSourceAsMap().get("content");
                if (StrUtil.isNotBlank(content)) {
                    if (sb.length() > 0) {
                        sb.append("\n---\n");
                    }
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
     * 简历模块标题正则：匹配常见简历分区标题（支持中英文、冒号/空格等常见格式）
     * 例如：教育背景、工作经历、项目经验、专业技能、实习经历、自我评价 等
     */
    private static final java.util.regex.Pattern SECTION_PATTERN = java.util.regex.Pattern.compile(
            "(?m)^\\s*(教育背景|教育经历|学历信息|工作经历|工作经验|职业经历|实习经历|实习经验" +
            "|项目经验|项目经历|项目介绍|专业技能|技能清单|技术栈|技能特长" +
            "|自我评价|自我介绍|个人简介|个人评价|荣誉奖项|获奖经历|证书资质" +
            "|求职意向|基本信息|联系方式|个人亮点|核心优势|开源项目|科研经历" +
            "|Education|Work Experience|Project|Skills|Internship|Summary|About" +
            "|PROFESSIONAL EXPERIENCE|EDUCATION|SKILLS|PROJECTS|CERTIFICATIONS" +
            ")[：:（）()\\s]*",
            java.util.regex.Pattern.CASE_INSENSITIVE
    );

    /**
     * 核心切片方法：
     * 1. 优先按简历模块标题拆分（教育背景、工作经历、项目经验...）
     * 2. 段落累加至250字符切割，保留50重叠
     * 3. 兜底：任意超长单块强制滑动二次切割
     */
    private List<String> splitText(String text) {
        List<String> chunks = new ArrayList<>();
        if (StrUtil.isBlank(text)) {
            return chunks;
        }

        // 第一步：按简历模块标题拆分
        List<String> sections = splitBySectionTitle(text);
        log.info("第一阶段模块拆分完成，共{}个模块：", sections.size());
        for (int i = 0; i < sections.size(); i++) {
            String preview = sections.get(i).length() > 80
                    ? sections.get(i).substring(0, 80) + "..."
                    : sections.get(i);
            log.info("  模块[{}] 长度={} 内容预览: {}", i, sections.get(i).length(), preview.replace("\n", "\\n"));
        }

        // 第二步：对每个模块，按阈值累加切片
        StringBuilder current = new StringBuilder();
        for (String section : sections) {
            section = section.trim();
            if (StrUtil.isBlank(section)) {
                continue;
            }

            // 模块本身超长，先按空行再拆小段
            if (section.length() > CHUNK_TARGET_SIZE) {
                // 先把 current 里已有的内容切出去
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder();
                }
                // 按空行拆分模块内的子段落
                //在从一个section中构建多个字段落组成符合条件的chunk
                String[] subParas = section.split("\\n\\s*\\n|\\r\\n\\s*\\r\\n");
                for (String sub : subParas) {
                    sub = sub.trim();
                    if (StrUtil.isBlank(sub)) continue;
                    sub = sub + "\n";
                    if (current.length() + sub.length() > CHUNK_TARGET_SIZE && current.length() > 0) {
                        chunks.add(current.toString().trim());
                        String prevStr = current.toString();
                        int overlapStart = Math.max(0, prevStr.length() - CHUNK_OVERLAP);
                        current = new StringBuilder(prevStr.substring(overlapStart));
                    }
                    current.append(sub);
                }
            } else {
                // 模块不超长，累加section,组成符合条件的chunk
                section = section + "\n";
                if (current.length() + section.length() > CHUNK_TARGET_SIZE && current.length() > 0) {
                    chunks.add(current.toString().trim());
                    String prevStr = current.toString();
                    int overlapStart = Math.max(0, prevStr.length() - CHUNK_OVERLAP);
                    current = new StringBuilder(prevStr.substring(overlapStart));
                }
                current.append(section);
            }
        }

        //存入剩余的文本，字数太少直接添加到上一个切片上
        String remainBlock = current.toString().trim();
        if (StrUtil.isNotBlank(remainBlock)) {
            // 如果剩余的块太小（比如小于 30 字），且之前已经有切好的块了，直接拼到最后一个块后面
            if (remainBlock.length() < 30 && !chunks.isEmpty()) {
                int lastIdx = chunks.size() - 1;
                chunks.set(lastIdx, chunks.get(lastIdx) + "\n" + remainBlock);
            } else {
                chunks.add(remainBlock);
            }
        }

        // 第三步：滑动窗口兜底，超长块强制拆分
        // === 第三步：滑动窗口兜底（针对合并后和滑动窗口的优化版）===
        List<String> finalChunks = new ArrayList<>();
        int slideStep = CHUNK_TARGET_SIZE - CHUNK_OVERLAP; // 步长：250 - 50 = 200

// 定义一个容忍度（比如 15%）：如果合并后的 chunk 只比阈值大一点点（比如 <= 280 字），就不要再切了！
        int ALLOWED_MAX_SIZE = CHUNK_TARGET_SIZE + 30; // 容忍上限设为 280 字符

        for (String block : chunks) {
            // 【修改点 1】：引入容忍度判断！
            // 只要块大小在容忍范围内（包括刚才强行合并后变长了一点点的块），直接放行，不再拆切
            if (block.length() <= ALLOWED_MAX_SIZE) {
                finalChunks.add(block);
                continue;
            }

            // 【修改点 2】：真正超长（比如 500 字）时触发滑动窗口，同时防止窗口在末尾切出极小碎片
            int len = block.length();
            for (int i = 0; i < len; i += slideStep) {
                int endIdx = Math.min(i + CHUNK_TARGET_SIZE, len);
                String subChunk = block.substring(i, endIdx).trim();

                if (StrUtil.isNotBlank(subChunk)) {
                    finalChunks.add(subChunk);
                }

                // 关键防护：如果本次截取已经到了文本末尾，直接 break 退出！
                // 防止最后剩下几个字符又触发一次循环，切出一个极小碎片
                if (endIdx == len) {
                    break;
                }
            }
        }

        return finalChunks;
    }

    /**
     * 按简历模块标题拆分文本，保留标题在对应段落头部
     * 返回的每个元素 = 标题 + 该模块内容
     */
    /**
     * 按简历模块标题拆分文本，保留标题在对应段落头部
     * 返回的每个元素 = 标题 + 该模块内容
     */
    private List<String> splitBySectionTitle(String text) {
        List<String> sections = new ArrayList<>();
        java.util.regex.Matcher matcher = SECTION_PATTERN.matcher(text);

        // 1. 提取头部信息（只执行一次 if）
        if (matcher.find()) {
            int firstTitleStart = matcher.start();
            if (firstTitleStart > 0) {
                String before = text.substring(0, firstTitleStart).trim();
                if (StrUtil.isNotBlank(before)) {
                    sections.add(before);
                }
            }
        } else {
            // 如果连一个标题都没有，直接返回全篇
            sections.add(text);
            return sections;
        }

        // 2. 重新扫描，收集【所有】标题的位置
        List<Integer> positions = new ArrayList<>();
        // 注意：这里必须是一个全新的 Matcher (m2)
        java.util.regex.Matcher m2 = SECTION_PATTERN.matcher(text);

        // 注意：这里必须是 while 循环！不能是 if！
        while (m2.find()) {
            positions.add(m2.start());
            // 【关键调试日志】：把你匹配到的标题打印出来，看看是不是漏了
            log.info("正则捕获到的模块标题：[{}]", m2.group().replaceAll("[\\r\\n]", "").trim());
        }

        // 3. 根据标题位置截取内容
        for (int i = 0; i < positions.size(); i++) {
            int start = positions.get(i);
            int end = (i + 1 < positions.size()) ? positions.get(i + 1) : text.length();
            String section = text.substring(start, end).trim();

            if (StrUtil.isNotBlank(section)) {
                sections.add(section);
            }
        }

        return sections;
    }
}