package com.codeturret.service;

import com.codeturret.config.LlmProperties;
import com.codeturret.service.llm.LlmProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Provider-neutral LLM calls (prompts, retries). The vendor-specific HTTP shape lives in {@link LlmProvider}. */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmService {

    private final LlmProperties llmProperties;
    private final LlmProvider provider;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public record FindingRaw(
        Integer lineNumber, String severity, String vulnType,
        String description, String fixSuggestion, Double confidence,
        String codeSnippet, String attackVector, String cweId
    ) {}

    /** Sends a prompt that asks for JSON and returns the model's text. Used by the detection engine's verifier. */
    public String generateJson(String model, String prompt) {
        return call(model, prompt, true);
    }

    /** Generate a corrected version of a file given findings. Returns patched content or null. */
    public String generateFix(String fileContent, String filePath, List<FindingRaw> findings) {
        StringBuilder findingsText = new StringBuilder();
        for (int i = 0; i < findings.size(); i++) {
            FindingRaw f = findings.get(i);
            findingsText.append(i + 1).append(". ")
                .append(f.severity()).append(" - ").append(f.vulnType())
                .append(" at line ").append(f.lineNumber() != null ? f.lineNumber() : "?").append("\n")
                .append("   Description: ").append(f.description()).append("\n");
            if (f.fixSuggestion() != null) {
                findingsText.append("   Suggested fix: ").append(f.fixSuggestion()).append("\n");
            }
        }

        String prompt = "You are a security engineer. The following file has security vulnerabilities.\n\n" +
            "File: " + filePath + "\n\n" +
            "VULNERABILITIES TO FIX:\n" + findingsText + "\n" +
            "SOURCE CODE:\n```\n" + fileContent + "\n```\n\n" +
            "Return ONLY the complete corrected file content with all vulnerabilities fixed. " +
            "Do not include any explanation, markdown, or code fences. " +
            "Return only the raw source code.";

        try {
            // Plain text, not JSON mode: the answer is a source file.
            String text = call(llmProperties.active().getStrongModel(), prompt, false).strip();
            // Strip markdown code fences if present
            if (text.startsWith("```")) {
                text = text.replaceFirst("```[a-zA-Z]*\\n?", "");
                int end = text.lastIndexOf("```");
                if (end >= 0) text = text.substring(0, end).strip();
            }
            return text;
        } catch (Exception e) {
            log.warn("Fix generation failed for {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    // -- API call ------------------------------------------------------------

    private String call(String model, String prompt, boolean json) {
        int attempts = llmProperties.getMaxRetries() + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String response = webClient.post()
                    .uri(provider.url(model))
                    .headers(h -> provider.headers().forEach(h::set))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(provider.requestBody(model, prompt, json))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(llmProperties.getTimeoutSeconds()))
                    .block();
                JsonNode tree = objectMapper.readTree(response);
                return provider.extractText(tree);
            } catch (Exception e) {
                log.warn("LLM attempt {}/{} ({}) failed: {}", attempt, attempts, model, e.getMessage());
                if (attempt == attempts) {
                    throw new RuntimeException("LLM call failed after " + attempt + " attempts: " + e.getMessage(), e);
                }
                // Exponential backoff with jitter, so concurrent verifiers don't retry in lockstep after a 429.
                long delay = (1000L << attempt) + ThreadLocalRandom.current().nextLong(500);
                try { Thread.sleep(delay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        throw new IllegalStateException("unreachable");
    }
}
