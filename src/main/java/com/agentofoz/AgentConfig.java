package com.agentofoz;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
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
    public ChatModel chatModel() {
        if ("demo".equals(groqApiKey) || groqApiKey.isBlank()) {
            return null;
        }

        return OpenAiChatModel.builder()
                .baseUrl("https://api.groq.com/openai/v1")
                .apiKey(groqApiKey)
                .modelName("llama-3.1-8b-instant")
                .temperature(0.0)
                .maxTokens(256)
                .maxRetries(0)
                .build();
    }

    @Bean
    public BasicAgent basicAgent() {
        return question -> "This is a default answer.";
    }

    @Bean
    public ToolGaia toolGaia() {
        return new ToolGaia(tavilyApiKey);
    }
}
