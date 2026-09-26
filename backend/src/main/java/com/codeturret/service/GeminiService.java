package com.codeturret.service;

import com.codeturret.config.GeminiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiService {

    private final GeminiProperties geminiProperties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public record FindingRaw(
        Integer lineNumber, String severity, String vulnType,
        String description, String fixSuggestion, Double confidence,
        String codeSnippet, String attackVector, String cweId
    ) {}

    /** Sends a prompt that asks for JSON and returns the model's text. Used by the detection engine's verifier. */
    public String generateJson(String model, String prompt) {
        return extractText(callGemini(model, prompt));
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
            JsonNode response = callGemini(geminiProperties.getModel().getPro(), prompt);
            String text = extractText(response);
            // Strip markdown code fences if present
            text = text.strip();
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

    /** Ask a question about a repo's findings. */
    public String askAboutFindings(String findingsSummary, String question) {
        String prompt = "You are a security consultant analyzing scan results.\n\n" +
            "SCAN FINDINGS SUMMARY:\n" + findingsSummary + "\n\n" +
            "QUESTION: " + question + "\n\n" +
            "Provide a clear, concise answer based on the findings above.";
        try {
            JsonNode response = callGemini(geminiProperties.getModel().getFlash(), prompt);
            return extractText(response);
        } catch (Exception e) {
            return "Unable to answer: " + e.getMessage();
        }
    }

    // -- API call ------------------------------------------------------------

    private JsonNode callGemini(String model, String prompt) {
        String url = geminiProperties.getBaseUrl() + "/models/" + model + ":generateContent";

        Map<String, Object> body = Map.of(
            "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
            "generationConfig", Map.of(
                "responseMimeType", "application/json",
                "temperature", 0.1
            )
        );

        int attempts = geminiProperties.getMaxRetries() + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String json = webClient.post()
                    .uri(url)
                    // Header rather than ?key= so the key never lands in URLs, proxies, or access logs.
                    .header("x-goog-api-key", geminiProperties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(geminiProperties.getTimeoutSeconds()))
                    .block();
                return objectMapper.readTree(json);
            } catch (Exception e) {
                log.warn("Gemini attempt {}/{} failed: {}", attempt, attempts, e.getMessage());
                if (attempt == attempts) {
                    throw new RuntimeException("Gemini call failed after " + attempt + " attempts: " + e.getMessage(), e);
                }
                // Exponential backoff with jitter, so concurrent verifiers don't retry in lockstep after a 429.
                long delay = (1000L << attempt) + ThreadLocalRandom.current().nextLong(500);
                try { Thread.sleep(delay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private String extractText(JsonNode response) {
        return response.path("candidates").get(0)
            .path("content").path("parts").get(0)
            .path("text").asText();
    }
}
