package com.codeturret.service.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** Google Gemini generateContent API. */
public class GeminiProvider implements LlmProvider {

    private final String apiKey;
    private final String baseUrl;

    public GeminiProvider(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    @Override
    public String url(String model) {
        return baseUrl + "/models/" + model + ":generateContent";
    }

    @Override
    public Map<String, String> headers() {
        // Header rather than ?key= so the key never lands in URLs, proxies, or access logs.
        return Map.of("x-goog-api-key", apiKey);
    }

    @Override
    public Map<String, Object> requestBody(String model, String prompt, boolean json) {
        return Map.of(
            "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
            "generationConfig", json
                ? Map.of("responseMimeType", "application/json", "temperature", 0.1)
                : Map.of("temperature", 0.1)
        );
    }

    @Override
    public String extractText(JsonNode response) {
        return response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
    }
}
