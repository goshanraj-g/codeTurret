package com.codeturret.service.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** OpenAI Chat Completions API. */
public class OpenAiProvider implements LlmProvider {

    private final String apiKey;
    private final String baseUrl;

    public OpenAiProvider(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    @Override
    public String url(String model) {
        return baseUrl + "/chat/completions";
    }

    @Override
    public Map<String, String> headers() {
        return Map.of("Authorization", "Bearer " + apiKey);
    }

    @Override
    public Map<String, Object> requestBody(String model, String prompt, boolean json) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        // No temperature: GPT-5-family reasoning models reject anything but the default.
        // JSON mode requires the word "JSON" in the prompt; the verifier prompt has it, the fix prompt doesn't use it.
        if (json) body.put("response_format", Map.of("type", "json_object"));
        return body;
    }

    @Override
    public String extractText(JsonNode response) {
        return response.path("choices").path(0).path("message").path("content").asText();
    }
}
