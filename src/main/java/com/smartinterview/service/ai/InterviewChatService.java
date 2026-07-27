package com.smartinterview.service.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        streamingChatModel = "interviewStreamChatModel",
        chatMemoryProvider = "redisChatMemoryProvider"
)
public interface InterviewChatService {

    @SystemMessage(fromResource = "prompts/interview-chat-system.st")
    @UserMessage("【候选人回答】：{{message}}")
    TokenStream chat(
            @MemoryId Long memoryId,
            @V("message") String message,
            @V("phasePrompt") String phasePrompt,
            @V("resumeChunks") String resumeChunks,
            @V("job") String job,
            @V("level") String level,
            @V("memory") String memory
    );
}
