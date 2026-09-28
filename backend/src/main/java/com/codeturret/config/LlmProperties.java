package com.codeturret.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Which LLM provider to call, and the fast/strong model pair used for triage and escalation. */
@ConfigurationProperties(prefix = "llm")
@Data
public class LlmProperties {
    /** "openai" or "gemini". */
    private String provider = "openai";
    private int timeoutSeconds = 120;
    private int maxRetries = 2;
    /** Concurrent LLM calls per scan; 429 responses are retried with exponential backoff. */
    private int maxConcurrency = 4;
    private double deepScanThreshold = 0.7;
    private Provider openai = new Provider();
    private Provider gemini = new Provider();

    @Data
    public static class Provider {
        private String apiKey;
        private String baseUrl;
        /** Cheap model for the first pass over every candidate. */
        private String fastModel;
        /** Stronger model for HIGH/CRITICAL or low-confidence findings, and for fix generation. */
        private String strongModel;
    }

    public Provider active() {
        return switch (provider.strip().toLowerCase()) {
            case "openai" -> openai;
            case "gemini" -> gemini;
            default -> throw new IllegalStateException("llm.provider must be 'openai' or 'gemini', got '" + provider + "'");
        };
    }
}
