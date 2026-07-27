package com.smartinterview.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.smartinterview.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * 通义千问 Embedding 服务
 * 模型：text-embedding-v3（免费额度：500万 token/月）
 * 输出维度：1024
 * 文档：https://help.aliyun.com/zh/model-studio/text-embedding
 */
@Service
@Slf4j
public class EmbeddingServiceImpl implements EmbeddingService {

    @Value("${ai.api-key}")
    private String apiKey;

    @Value("${ai.base-url}")
    private String baseUrl;
    @Value("${ai.embedding.model-name}")
    private String MODEL ;

    private String getEmbeddingUrl() {
        return baseUrl + "/embeddings";
    }

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 将文本转换为向量
     *
     * @param text 输入文本（题目内容）
     * @return 1024 维 float 向量，失败返回 null
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        // 截断超长文本，embedding-v3 最大 8192 token
        if (text.length() > 2000) {
            text = text.substring(0, 2000);
        }
        try {
            // 构建请求体（OpenAI 兼容格式）
            JSONObject body = JSONUtil.createObj()
                    .set("model", MODEL)
                    .set("input", text);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getEmbeddingUrl()))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Embedding API 返回异常状态：{}，body：{}", response.statusCode(), response.body());
                return null;
            }

            // 解析响应（OpenAI 兼容格式）
            JSONObject resp = JSONUtil.parseObj(response.body());
            JSONArray embedding = resp
                    .getJSONArray("data")
                    .getJSONObject(0)
                    .getJSONArray("embedding");

            float[] result = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                result[i] = embedding.getFloat(i);
            }
            return result;

        } catch (Exception e) {
            log.error("Embedding 生成失败，text={}", text.substring(0, Math.min(50, text.length())), e);
            return null;
        }
    }

    /**
     * 将 float[] 向量序列化为 JSON 字符串，存入 MySQL
     */
    public String toJson(float[] vector) {
        if (vector == null) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 将 MySQL 中存储的 JSON 字符串反序列化为 float[]
     */
    public float[] fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JSONArray arr = JSONUtil.parseArray(json);
            float[] result = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                result[i] = arr.getFloat(i);
            }
            return result;
        } catch (Exception e) {
            log.error("向量JSON反序列化失败", e);
            return null;
        }
    }

    /**
     * 计算两个向量的余弦相似度
     * 返回值范围 [-1, 1]，越接近 1 越相似
     */
    public double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -1;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}