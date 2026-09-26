package com.codeturret.config;

import com.codeturret.engine.DetectionEngine;
import com.codeturret.engine.staticanalysis.SemgrepRunner;
import com.codeturret.engine.staticanalysis.StaticAnalyzer;
import com.codeturret.service.GeminiService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Wiring of the engine beans without a database or message broker. */
class EngineConfigTest {

    @EnableConfigurationProperties({EngineProperties.class, GeminiProperties.class, GitProperties.class})
    static class Props {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Props.class, EngineConfig.class)
        .withBean(GeminiService.class, () -> mock(GeminiService.class));

    @Test
    void buildsEngineWithSemgrepByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(DetectionEngine.class);
            assertThat(ctx.getBean(StaticAnalyzer.class)).isInstanceOf(SemgrepRunner.class);
        });
    }

    @Test
    void semgrepCanBeDisabled() {
        runner.withPropertyValues("engine.semgrep.enabled=false", "engine.line-budget=500").run(ctx -> {
            assertThat(ctx.getBean(StaticAnalyzer.class).scan(Path.of("."))).isEmpty();
            assertThat(ctx.getBean(EngineProperties.class).getLineBudget()).isEqualTo(500);
        });
    }
}
