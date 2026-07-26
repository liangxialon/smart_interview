package com.smartinterview.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService(
        chatModel = "resumeChatModel",
        streamingChatModel = "resumeStreamChatModel"
)
public interface ResumeAiAnalyzerService {
    //@V在参数上动态传入
    @SystemMessage("{{systemPrompt}}")
    TokenStream streamAnalyzeResume(
            @V("systemPrompt") String systemPrompt,
            @UserMessage String userText
    );


    @SystemMessage("{{systemPrompt}}")
    String analyzeResumeScore(
            @V("systemPrompt") String systemPrompt,
            @UserMessage String userText
    );

}
