package com.codeturret.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gemini")
@Data
public class GeminiProperties {
    private String apiKey;
    private String baseUrl;
    private Model model = new Model();
    private int timeoutSeconds = 60;
    private int maxRetries = 2;
    /** Concurrent LLM calls per scan; 429 responses are retried with exponential backoff. */
    private int maxConcurrency = 4;
    private double deepScanThreshold = 0.7;

    @Data
    public static class Model {
        private String flash = "gemini-2.5-flash";
        private String pro = "gemini-2.5-pro";
    }
}
