package com.codeturret.eval;

import com.codeturret.config.GitProperties;
import com.codeturret.engine.ml.VulnClassifier;
import com.codeturret.engine.parse.CodeParser;
import com.codeturret.engine.select.CandidateSelector;
import com.codeturret.engine.select.LegacyRegexSelector;
import com.codeturret.engine.select.MlSelector;
import com.codeturret.engine.select.StaticSelector;
import com.codeturret.engine.select.StructureSelector;
import com.codeturret.engine.staticanalysis.SemgrepRunner;
import com.codeturret.engine.staticanalysis.StaticAnalyzer;
import com.codeturret.engine.staticanalysis.StaticHit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Registry of selectors the eval harness can compare. Expensive signals are computed once per repo. */
final class Selectors {

    private final GitProperties gitProps;
    private final CodeParser parser = new CodeParser();
    private final VulnClassifier classifier = VulnClassifier.load();
    private final boolean semgrepAvailable;
    private final StaticAnalyzer semgrep;

    Selectors(GitProperties gitProps) {
        this.gitProps = gitProps;
        SemgrepRunner runner = new SemgrepRunner(SemgrepRunner.Config.fromEnv());
        this.semgrepAvailable = runner.isAvailable();
        Map<Path, List<StaticHit>> cache = new ConcurrentHashMap<>();
        this.semgrep = dir -> cache.computeIfAbsent(dir, runner::scan);
        if (!semgrepAvailable) {
            System.out.println("Semgrep not available: skipping selectors that need it (set SEMGREP_CMD to override)");
        }
    }

    /** Selectors that can run in this environment. */
    List<String> available() {
        List<String> names = new ArrayList<>(List.of("legacy-regex", "structure"));
        if (classifier.isLoaded()) names.add("ml");
        if (semgrepAvailable) names.add("semgrep");
        return names;
    }

    CandidateSelector create(String name) {
        return switch (name) {
            case "legacy-regex" -> new LegacyRegexSelector(gitProps);
            case "structure" -> new StructureSelector(parser);
            case "semgrep" -> new StaticSelector(parser, semgrep);
            case "ml" -> new MlSelector(parser, classifier);
            default -> throw new IllegalArgumentException("Unknown selector: " + name + " (available: " + available() + ")");
        };
    }
}
