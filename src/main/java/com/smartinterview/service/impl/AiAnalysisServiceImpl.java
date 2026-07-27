package com.smartinterview.service.impl;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.smartinterview.common.exception.AiServiceException;

import com.smartinterview.common.manager.PromptManager;
import com.smartinterview.service.AiAnalysisService;
import io.reactivex.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * folwable对象 异步非阻塞式接收大模型逐段生成的简历分析数据，一有数据就返回给前端
 */
@Slf4j
@Service
public class AiAnalysisServiceImpl implements AiAnalysisService {
    @Value("${aliyun.dashscope.api-key}")
    private String apiKey;
    @Autowired
    private PromptManager promptManager;

    /**
     * 分析简历
     *  前台轨：流式分析简历，只生成优势/不足/建议
     * @param rawText
     * @return
     */
    public Flowable<GenerationResult> streamAnalyzeResume(String rawText){
        try {
            //gen对象用于跟通义千问通信
            Generation gen=new Generation();
            //定义 System Prompt（系统指令）：给 AI 设定“身份和工作规则只生成优势/不足/建议
            String systemPrompt =promptManager.getResumeAnalysisSystemPrompt();
            //构建系统消息
            Message systemMsg=Message.builder()
                    .role(Role.SYSTEM.getValue()) //消息角色就是告诉AI是谁发送的消息
                    .content(systemPrompt) //消息内容
                    .build();
            //构建用户消息
            Message userMsg=Message.builder()
                    .role(Role.USER.getValue())
                    .content("这是候选人的简历纯文本，请进行诊断："+rawText)
                    .build();
            //封装调用AI需要的参数
            GenerationParam param=GenerationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen-turbo") //调用的大模型类型
                    .messages(Arrays.asList(systemMsg,userMsg))
                    //指定AI的返回结果为结构化消息
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    //开启增量输出,每次只返回最新生成的字，false的话每次从头到尾返回所有数据
                    .incrementalOutput(true)
                    .build();
            log.info("前台轨：简历分析SSE流式生成中...");
            //streamCall流式输出
            return gen.streamCall(param);
        } catch (Exception e) {
            log.error("简历流式分析失败", e);
            throw new AiServiceException("AI 智能分析引擎开小差了，请稍后重试");
        }
    }
    /**mq中代码调用
     * 后台轨：同步调用，强制输出 JSON（评分）
     * 由 @Async 异步线程调用，不阻塞主流程
     *
     * @param rawText 简历原始文本
     * @return 严格 JSON 字符串，格式：{"score":{...}}
     */
    public String analyzeResumeScore(String rawText) {
        try {
            //生成评分，直接返回字符串
            Generation gen = new Generation();
            Message systemMsg = Message.builder()
                    .role(Role.SYSTEM.getValue())
                    .content(promptManager.getResumeScoreSystemPrompt())
                    .build();
            Message userMsg = Message.builder()
                    .role(Role.USER.getValue())
                    .content("这是候选人的简历纯文本，请按要求输出JSON：" + rawText)
                    .build();
            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen-turbo")   // 后台轨用更强的模型，精准度更高
                    .messages(Arrays.asList(systemMsg, userMsg))
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .build();
            log.info("后台轨：简历评分同步调用中...");
            //call同步阻塞等待AI生成结果，streamCall流式输出
            GenerationResult result = gen.call(param);
            return result.getOutput().getChoices().get(0).getMessage().getContent();
        } catch (Exception e) {
            log.error("后台轨简历评分失败", e);
            // 返回降级默认值，不影响主流程
            return "{\"score\":{\"total\":0,\"technical\":0,\"project\":0,\"clarity\":0,\"potential\":0}}";
        }
    }

    /**
     * 从redis拼接历史会话记录
     * @param messages
     * @return
     */
    public Flowable<GenerationResult> streamChat(List<Message> messages){
        try {
            Generation gen=new Generation();
            GenerationParam param=GenerationParam.builder()
                    .apiKey(apiKey)
                    .messages(messages)
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .incrementalOutput(true)
                    .model("qwen-plus")
                    .build();
            log.info("开启多轮对话SSE流生成中....");
            return gen.streamCall(param);
        } catch (Exception e) {
            log.info("AI多轮对话生成失败：{}",e);
            throw new AiServiceException("面试官开小差了，请再说一遍");
        }
    }

    /**
     * 同步调用，对单道题目进行评分，返回json串
     * @param aiQuestion
     * @param userAnswer
     * @param standardAnswer
     * @return
     */
    public String evaluateAnswer(String aiQuestion,String userAnswer,String standardAnswer){
        try {
            Generation gen=new Generation();
            String prompt=promptManager.buildEvaluationPrompt(aiQuestion,userAnswer,standardAnswer);
            Message userMsg=Message.builder().role(Role.USER.getValue()).content(prompt).build();
            GenerationParam param=GenerationParam.builder()
                    .model("qwen-plus")
                    .messages(Arrays.asList(userMsg))
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .apiKey(apiKey)
                    .build();
            //非流式，一次性返回所有结果。
            GenerationResult result = gen.call(param);
            return result.getOutput().getChoices().get(0).getMessage().getContent();
        }catch (Exception e) {
            log.error("AI评分失败",e);
            return "{\"score\":0,\"comment\":\"评分异常\",\"isCorrect\":false}";
        }


    }
}
