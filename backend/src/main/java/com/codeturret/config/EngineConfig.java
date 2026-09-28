package com.codeturret.config;

import com.codeturret.engine.DetectionEngine;
import com.codeturret.engine.ml.VulnClassifier;
import com.codeturret.engine.parse.CodeParser;
import com.codeturret.engine.rank.CandidateRanker;
import com.codeturret.engine.rank.RankerWeights;
import com.codeturret.engine.staticanalysis.SemgrepRunner;
import com.codeturret.engine.staticanalysis.StaticAnalyzer;
import com.codeturret.engine.verify.LlmVerifier;
import com.codeturret.service.LlmService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
public class EngineConfig {

    @Bean(destroyMethod = "close")
    public VulnClassifier vulnClassifier() {
        return VulnClassifier.load();
    }

    @Bean
    public StaticAnalyzer staticAnalyzer(EngineProperties props) {
        EngineProperties.Semgrep s = props.getSemgrep();
        if (!s.isEnabled()) return dir -> List.of();
        return new SemgrepRunner(new SemgrepRunner.Config(
            List.of(s.getCommand().strip().split(" +")), s.getRulesets(), Duration.ofSeconds(s.getTimeoutSeconds())));
    }

    @Bean
    public DetectionEngine detectionEngine(EngineProperties props, LlmProperties llm, GitProperties git,
                                           VulnClassifier classifier, StaticAnalyzer staticAnalyzer,
                                           LlmService llmService) {
        EngineProperties.Weights w = props.getWeights();
        RankerWeights weights = new RankerWeights(w.getMl(), w.getStaticAnalysis(), w.getReachability(), w.getGit(), w.getStructure());
        LlmVerifier verifier = new LlmVerifier(llmService::generateJson, new LlmVerifier.Models(
            llm.active().getFastModel(), llm.active().getStrongModel(), llm.getDeepScanThreshold()));
        return new DetectionEngine(new CodeParser(), classifier, staticAnalyzer, new CandidateRanker(weights), verifier,
            new DetectionEngine.Options(props.getLineBudget(), llm.getMaxConcurrency(), git.getMaxFileSize()));
    }
}
