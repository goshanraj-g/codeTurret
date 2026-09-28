package com.codeturret.service.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/** One LLM vendor's HTTP API: how to build a request and where the text sits in the response. */
public interface LlmProvider {

    String url(String model);

    Map<String, String> headers();

    /** {@code json} asks the model for a JSON object; plain text otherwise. */
    Map<String, Object> requestBody(String model, String prompt, boolean json);

    String extractText(JsonNode response);
}
