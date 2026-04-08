package com.agentofoz;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    @Value("${GEMINI_API_KEY:demo}")
    private String geminiApiKey;

    @Value("${TAVILY_API_KEY:demo}")
    private String tavilyApiKey;

    @Bean
    public BasicAgent basicAgent() {
        if ("demo".equals(geminiApiKey) || geminiApiKey.isBlank()) {
            // Mock behavior corresponding to the python default
            return question -> "This is a default answer.";
        }
        
        ChatModel model = GoogleAiGeminiChatModel.builder()
                .apiKey(geminiApiKey)
                .modelName("gemini-2.0-flash")
                .build();
                
        return AiServices.builder(BasicAgent.class)
                .chatModel(model)
                .tools(new ToolGaia(tavilyApiKey))
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();
    }
}