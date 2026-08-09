package com.smartinterview.service.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "resumeChatModel",
        streamingChatModel = "resumeStreamChatModel"
)
public interface ResumeAiAnalyzerService {

    /**
     * 流式简历分析：生成核心优势、潜在不足、改进建议
     */
    @SystemMessage(fromResource = "prompts/resume-analysis-system.st")
    TokenStream streamAnalyzeResume(@UserMessage String resumeText);


}
