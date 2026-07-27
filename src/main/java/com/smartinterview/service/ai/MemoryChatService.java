package com.smartinterview.service.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "memoryChatModel"
)
public interface MemoryChatService {

    /**
     * 将对话片段压缩为结构化摘要
     * @param existingMemoryPart 旧的长期记忆（可为空字符串）
     * @param messages           待压缩的对话片段
     */
    @SystemMessage(fromResource = "prompts/compress-memory.st")
    @UserMessage("{{existingMemoryPart}}【待压缩的对话片段】\n{{messages}}")
    String compress(@V("existingMemoryPart") String existingMemoryPart,
                    @V("messages") String messages);
}
