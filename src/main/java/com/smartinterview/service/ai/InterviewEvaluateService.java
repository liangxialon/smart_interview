package com.smartinterview.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService(chatModel = "reportChatModel")
public interface InterviewEvaluateService {

    @SystemMessage(fromResource = "prompts/evaluate-system.st")
    @UserMessage("【面试官的问题】：{{aiQuestion}}\n【候选人的回答】：{{userAnswer}}\n【标准参考答案】：{{standardAnswer}}")
    String evaluate(
            @V("aiQuestion") String aiQuestion,
            @V("userAnswer") String userAnswer,
            @V("standardAnswer") String standardAnswer
    );
}
