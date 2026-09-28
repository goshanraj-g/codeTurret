package com.codeturret.config;

import com.codeturret.service.llm.GeminiProvider;
import com.codeturret.service.llm.LlmProvider;
import com.codeturret.service.llm.OpenAiProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    @Bean
    public LlmProvider llmProvider(LlmProperties props) {
        return create(props);
    }

    static LlmProvider create(LlmProperties props) {
        LlmProperties.Provider p = props.active();
        String name = props.getProvider().strip().toLowerCase();
        if (p.getApiKey() == null || p.getApiKey().isBlank()) {
            // Fail at startup rather than on the first scan, with the variable the user actually needs to set.
            throw new IllegalStateException("llm.provider is '" + name + "' but " + name.toUpperCase() + "_API_KEY is not set");
        }
        return name.equals("openai")
            ? new OpenAiProvider(p.getApiKey(), p.getBaseUrl())
            : new GeminiProvider(p.getApiKey(), p.getBaseUrl());
    }
}
