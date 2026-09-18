package com.aiquery.dashboard.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class AiClient {
    private static final Logger logger = LoggerFactory.getLogger(AiClient.class);
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final AiProvider provider;
    private final String apiKey;
    private final String endpointPath;
    private final String anthropicVersion;
    private final String model;
    private final int maxTokens;
    private final String reasoningEffort;

    public AiClient(
            ObjectMapper objectMapper,
            @Value("${ai.base-url}") String baseUrl,
            @Value("${ai.endpoint-path}") String endpointPath,
            @Value("${ai.provider:anthropic}") String provider,
            @Value("${ai.api-key}") String apiKey,
            @Value("${ai.anthropic-version:2023-06-01}") String anthropicVersion,
            @Value("${ai.model}") String model,
            @Value("${ai.max-tokens:8192}") int maxTokens,
            @Value("${ai.reasoning-effort:medium}") String reasoningEffort
    ) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.provider = createProvider(provider);
        this.apiKey = apiKey;
        this.endpointPath = endpointPath;
        this.anthropicVersion = anthropicVersion;
        this.model = model;
        this.maxTokens = maxTokens;
        this.reasoningEffort = reasoningEffort;
        logger.info("AI client initialized: provider={}, model={}, endpointPath={}, maxTokens={}",
            provider, model, endpointPath, maxTokens);
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    public String complete(String prompt) throws Exception {
        try {
            logger.info("AI request started: provider={}, model={}, promptLength={}",
                provider.getClass().getSimpleName(), model, prompt == null ? 0 : prompt.length());
            Map<String, Object> requestBodyMap = provider.requestBody(model, prompt, maxTokens, reasoningEffort);
            String requestBody = objectMapper.writeValueAsString(requestBodyMap);
            int contentLength = requestBody.getBytes(StandardCharsets.UTF_8).length;
            logger.info("AI request prepared: endpointPath={}, contentLengthBytes={}", endpointPath, contentLength);
            RestClient.RequestBodySpec requestSpec = restClient.post().uri(endpointPath)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Content-Length", String.valueOf(contentLength))
                .header("Authorization", "Bearer " + apiKey);
            provider.configureHeaders(requestSpec, anthropicVersion);
            String responseBody = requestSpec.body(requestBody).retrieve().body(String.class);
            String content = extractContent(responseBody);
            logger.info("AI request completed: provider={}, responseLength={}, contentLength={}",
                provider.getClass().getSimpleName(), responseBody == null ? 0 : responseBody.length(), content.length());
            return content;
        } catch (Exception exception) {
            logger.error("AI request failed: provider={}, model={}, endpointPath={}",
                provider.getClass().getSimpleName(), model, endpointPath, exception);
            throw exception;
        }
        }

        public String providerName() {
        return provider.getClass().getSimpleName();
    }

    private String extractContent(String responseBody) throws Exception {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("AI provider returned an empty response");
        }
        JsonNode response = objectMapper.readTree(responseBody);
        String content = provider.extractContent(response);
        if (content.isBlank()) {
            throw new IllegalStateException("AI provider returned no text content (responseLength=" + responseBody.length() + ")");
        }
        return content;
    }

    private AiProvider createProvider(String configuredProvider) {
        return switch (configuredProvider.toLowerCase()) {
            case "openai", "chatgpt" -> new OpenAiProvider();
            case "anthropic" -> new AnthropicProvider();
            default -> throw new IllegalArgumentException("Unsupported AI provider: " + configuredProvider);
        };
    }
}
