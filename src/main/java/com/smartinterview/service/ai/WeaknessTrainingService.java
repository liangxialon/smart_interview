package com.smartinterview.service.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "reportChatModel"
)
public interface WeaknessTrainingService {

    @SystemMessage(fromResource = "prompts/weakness-training-system.st")
    @UserMessage("请根据以上错题生成薄弱项练习题")
    String generateTraining(@V("wrongQuestions") String wrongQuestions);
}
