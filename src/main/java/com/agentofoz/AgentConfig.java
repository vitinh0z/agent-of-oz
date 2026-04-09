package com.agentofoz;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    @Value("${GROQ_API_KEY:demo}")
    private String groqApiKey;

    @Value("${TAVILY_API_KEY:demo}")
    private String tavilyApiKey;

    @Bean
    public BasicAgent basicAgent() {
        if ("demo".equals(groqApiKey) || groqApiKey.isBlank()) {
            // Mock behavior corresponding to the python default
            return question -> "This is a default answer.";
        }
        
        ChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.groq.com/openai/v1")
                .apiKey(groqApiKey)
                .modelName("llama-3.1-8b-instant")
                .temperature(0.0)
                .maxTokens(256)
                .maxRetries(0)
                .build();
                
        return AiServices.builder(BasicAgent.class)
                .chatModel(model)
                .tools(new ToolGaia(tavilyApiKey))
                .chatMemory(MessageWindowChatMemory.withMaxMessages(4))
                .build();
    }
}