package com.agentofoz;

import com.google.common.util.concurrent.RateLimiter;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RateLimitedChatModel implements ChatModel {

    private final ChatModel delegate;
    @SuppressWarnings("UnstableApiUsage")
    private final RateLimiter rateLimiter;

    @SuppressWarnings("UnstableApiUsage")
    public RateLimitedChatModel(ChatModel delegate, double requestsPerSecond) {
        this.delegate = delegate;
        this.rateLimiter = RateLimiter.create(requestsPerSecond);
    }

    @Override
    @SuppressWarnings("UnstableApiUsage")
    public ChatResponse chat(ChatRequest chatRequest) {
        double waitTime = rateLimiter.acquire();
        if (waitTime > 0.1) {
            log.info("RateLimiter: aguardando {}s para chamada à API Gemini", String.format("%.1f", waitTime));
        }
        return delegate.chat(chatRequest);
    }
}