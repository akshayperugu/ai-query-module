package com.aiquery.dashboard.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.client.RestClient;

import java.util.Map;

interface AiProvider {
    Map<String, Object> requestBody(String model, String prompt, int maxTokens, String reasoningEffort);

    void configureHeaders(RestClient.RequestBodySpec request, String anthropicVersion);

    String extractContent(JsonNode response);
}
