package com.aiquery.dashboard.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

final class AnthropicProvider implements AiProvider {
    @Override
    public Map<String, Object> requestBody(String model, String prompt, int maxTokens, String reasoningEffort) {
        return Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );
    }

    @Override
    public void configureHeaders(RestClient.RequestBodySpec request, String anthropicVersion) {
        request.header("anthropic-version", anthropicVersion);
    }

    @Override
    public String extractContent(JsonNode response) {
        JsonNode content = response.path("content");
        if (content.isArray()) {
            return content.findValuesAsText("text").stream()
                    .filter(text -> !text.isBlank())
                    .findFirst()
                    .orElse("");
        }
        return content.isTextual() ? content.asText() : "";
    }
}
