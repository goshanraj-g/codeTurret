package com.codeturret.config;

import com.codeturret.service.llm.GeminiProvider;
import com.codeturret.service.llm.OpenAiProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmConfigTest {

    @Test
    void picksProviderFromConfig() {
        LlmProperties props = new LlmProperties();
        props.getOpenai().setApiKey("sk-test");
        props.getGemini().setApiKey("g-key");

        assertThat(LlmConfig.create(props)).isInstanceOf(OpenAiProvider.class);
        props.setProvider(" Gemini ");
        assertThat(LlmConfig.create(props)).isInstanceOf(GeminiProvider.class);
    }

    @Test
    void failsFastNamingTheMissingKey() {
        LlmProperties props = new LlmProperties();
        assertThatThrownBy(() -> LlmConfig.create(props)).hasMessageContaining("OPENAI_API_KEY");

        props.setProvider("anthropic");
        assertThatThrownBy(() -> LlmConfig.create(props)).hasMessageContaining("'openai' or 'gemini'");
    }
}
