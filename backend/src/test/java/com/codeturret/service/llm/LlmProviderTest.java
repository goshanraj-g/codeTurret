package com.codeturret.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmProviderTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void openAiRequestUsesJsonModeOnlyWhenAskedAndNoTemperature() {
        OpenAiProvider p = new OpenAiProvider("sk-test", "https://api.openai.com/v1");

        Map<String, Object> jsonBody = p.requestBody("gpt-5.6-luna", "Return JSON only", true);
        assertThat(jsonBody).containsEntry("model", "gpt-5.6-luna")
            .containsEntry("response_format", Map.of("type", "json_object"))
            .doesNotContainKey("temperature");

        assertThat(p.requestBody("gpt-5.5", "fix this file", false)).doesNotContainKey("response_format");
        assertThat(p.url("any")).isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(p.headers()).containsEntry("Authorization", "Bearer sk-test");
    }

    @Test
    void openAiExtractsMessageContent() throws Exception {
        var response = json.readTree("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"findings\\\":[]}\"}}]}");
        assertThat(new OpenAiProvider("k", "u").extractText(response)).isEqualTo("{\"findings\":[]}");
    }

    @Test
    void geminiKeepsKeyOutOfUrlAndExtractsText() throws Exception {
        GeminiProvider p = new GeminiProvider("g-key", "https://generativelanguage.googleapis.com/v1beta");
        assertThat(p.url("gemini-2.5-flash")).doesNotContain("g-key").endsWith("/models/gemini-2.5-flash:generateContent");
        assertThat(p.headers()).containsEntry("x-goog-api-key", "g-key");

        var response = json.readTree("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}");
        assertThat(p.extractText(response)).isEqualTo("ok");
    }
}
