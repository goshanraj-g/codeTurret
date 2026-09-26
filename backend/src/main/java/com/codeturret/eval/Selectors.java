package com.codeturret.eval;

import com.codeturret.config.GitProperties;
import com.codeturret.engine.ml.VulnClassifier;
import com.codeturret.engine.parse.CodeParser;
import com.codeturret.engine.rank.RankerWeights;
import com.codeturret.engine.select.CandidateSelector;
import com.codeturret.engine.select.HybridSelector;
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
    private final RankerWeights weights = RankerWeights.defaults();
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
        names.add("hybrid-no-static");
        if (semgrepAvailable) names.add("hybrid");
        return names;
    }

    static final List<String> ABLATIONS = List.of("hybrid", "hybrid-minus-ml", "hybrid-minus-static",
        "hybrid-minus-reachability", "hybrid-minus-git", "hybrid-minus-structure");

    private RankerWeights w() {
        return weights;
    }

    private CandidateSelector ablation(String name, RankerWeights w) {
        return new HybridSelector(name, parser, classifier, semgrep, w);
    }

    CandidateSelector create(String name) {
        return switch (name) {
            case "legacy-regex" -> new LegacyRegexSelector(gitProps);
            case "structure" -> new StructureSelector(parser);
            case "semgrep" -> new StaticSelector(parser, semgrep);
            case "ml" -> new MlSelector(parser, classifier);
            case "hybrid" -> new HybridSelector(name, parser, classifier, semgrep, weights);
            case "hybrid-no-static" -> new HybridSelector(name, parser, classifier, dir -> List.of(), weights);
            // Ablations: the full hybrid with one signal's weight set to zero.
            case "hybrid-minus-ml" -> ablation(name, new RankerWeights(0, w().staticAnalysis(), w().reachability(), w().git(), w().structure()));
            case "hybrid-minus-static" -> ablation(name, new RankerWeights(w().ml(), 0, w().reachability(), w().git(), w().structure()));
            case "hybrid-minus-reachability" -> ablation(name, new RankerWeights(w().ml(), w().staticAnalysis(), 0, w().git(), w().structure()));
            case "hybrid-minus-git" -> ablation(name, new RankerWeights(w().ml(), w().staticAnalysis(), w().reachability(), 0, w().structure()));
            case "hybrid-minus-structure" -> ablation(name, new RankerWeights(w().ml(), w().staticAnalysis(), w().reachability(), w().git(), 0));
            default -> throw new IllegalArgumentException("Unknown selector: " + name + " (available: " + available() + ")");
        };
    }
}
