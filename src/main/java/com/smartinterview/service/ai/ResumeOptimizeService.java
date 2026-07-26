package com.smartinterview.service.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        streamingChatModel = "optimizeStreamChatModel"
)
public interface ResumeOptimizeService {

    @SystemMessage(fromResource = "prompts/resume-optimize-system.st")
    @UserMessage("请根据以上信息优化简历")
    TokenStream optimize(
            @V("originalText") String originalText,
            @V("aiReport") String aiReport,
            @V("jobDescription") String jobDescription
    );
}
