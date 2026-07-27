package com.smartinterview.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class AiConfig {

    // ──────────── 向量模型配置 ────────────
    @Bean("embeddingModelName")
    public String embeddingModelName(@Value("${ai.embedding.model-name}") String modelName) {
        return modelName;
    }

    // ──────────── 语音合成模型配置 ────────────
    @Bean("voiceModelName")
    public String voiceModelName(@Value("${ai.voice.model-name}") String modelName) {
        return modelName;
    }

    // ──────────── 简历模块（qwen3.7-max-2026-05-20）────────────
    @Bean("resumeChatModel")
    public ChatModel resumeChatModel(
            @Value("${ai.base-url}") String baseUrl,
            @Value("${ai.api-key}") String apiKey,
            @Value("${ai.resume.model-name}") String modelName) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.3)
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    @Bean("resumeStreamChatModel")
    public StreamingChatModel resumeStreamChatModel(
            @Value("${ai.base-url}") String baseUrl,
            @Value("${ai.api-key}") String apiKey,
            @Value("${ai.resume.model-name}") String modelName) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.3)
                .timeout(Duration.ofMinutes(3))
                .build();
    }

    // ──────────── 面试会话模块（qwen3.7-max）────────────
    @Bean("interviewChatModel")
    public ChatModel interviewChatModel(
            @Value("${ai.base-url}") String baseUrl,
            @Value("${ai.api-key}") String apiKey,
            @Value("${ai.interview.model-name}") String modelName) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.3)
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    @Bean("interviewStreamChatModel")
    public StreamingChatModel interviewStreamChatModel(
            @Value("${ai.base-url}") String baseUrl,
            @Value("${ai.api-key}") String apiKey,
            @Value("${ai.interview.model-name}") String modelName) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .logRequests(true)
                .logResponses(true)
                .temperature(0.3)
                .timeout(Duration.ofMinutes(3))
                .build();
    }

    // ──────────── 面试报告评分模块（qwen3.7-max-2026-05-17）────────────
    @Bean("reportChatModel")
    public ChatModel reportChatModel(
            @Value("${ai.base-url}") String baseUrl,
            @Value("${ai.api-key}") String apiKey,
            @Value("${ai.report.model-name}") String modelName) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.3)
                .logRequests(true)
                .logResponses(true)
                .timeout(Duration.ofMinutes(3))
                .build();
    }

    // ──────────── 记忆压缩模块（qwen3.7-plus）────────────
    @Bean("memoryChatModel")
    public ChatModel memoryChatModel(
            @Value("${ai.base-url}") String baseUrl,
            @Value("${ai.api-key}") String apiKey,
            @Value("${ai.memory.model-name}") String modelName) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.3)
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    // ──────────── 简历优化模块（qwen3.7-max-preview）────────────
    @Bean("optimizeStreamChatModel")
    public StreamingChatModel optimizeStreamChatModel(
            @Value("${ai.base-url}") String baseUrl,
            @Value("${ai.api-key}") String apiKey,
            @Value("${ai.optimize.model-name}") String modelName) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.3)
                .timeout(Duration.ofMinutes(5))
                .build();
    }

}
