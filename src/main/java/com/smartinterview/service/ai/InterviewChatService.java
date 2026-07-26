package com.smartinterview.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService(chatModel = "interviewStreamChatModel")
public interface InterviewChatService {

    @SystemMessage(fromResource = "prompts/interview-chat-system.st")
    @UserMessage("【参考答案】：{{rag}}\n【候选人回答】：{{message}}")
    TokenStream chat(
            @MemoryId Long memoryId,
            @V("message") String message,
            @V("candidate") String candidate,
            @V("job") String job,
            @V("level") String level,
            @V("rag") String rag,
            @V("memory") String memory
    );
}
