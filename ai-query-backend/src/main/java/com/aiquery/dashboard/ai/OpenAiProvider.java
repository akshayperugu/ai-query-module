package com.aiquery.dashboard.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

final class OpenAiProvider implements AiProvider {
    @Override
    public Map<String, Object> requestBody(String model, String prompt, int maxTokens, String reasoningEffort) {
        return Map.of(
                "model", model,
                "max_completion_tokens", maxTokens,
                "reasoning_effort", reasoningEffort,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );
    }

    @Override
    public void configureHeaders(RestClient.RequestBodySpec request, String anthropicVersion) {
    }

    @Override
    public String extractContent(JsonNode response) {
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        return content.isTextual() ? content.asText() : "";
    }
}
