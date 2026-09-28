package com.codeturret.engine;

import com.codeturret.engine.ml.VulnClassifier;
import com.codeturret.engine.model.Candidate;
import com.codeturret.engine.model.GitSignals;
import com.codeturret.engine.model.SourceFile;
import com.codeturret.engine.parse.CodeIndex;
import com.codeturret.engine.parse.CodeParser;
import com.codeturret.engine.rank.CandidateRanker;
import com.codeturret.engine.signals.StaticSignal;
import com.codeturret.engine.staticanalysis.StaticAnalyzer;
import com.codeturret.engine.staticanalysis.StaticHit;
import com.codeturret.engine.verify.EngineFinding;
import com.codeturret.engine.verify.LlmVerifier;
import com.codeturret.engine.verify.VerificationHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * The detection pipeline: parse, compute signals, rank under a line budget, verify with the LLM.
 * See docs/architecture/detection-engine.md.
 */
public final class DetectionEngine {

    public record Options(int lineBudget, int maxConcurrency, int maxFileBytes) {}

    public record Result(List<EngineFinding> findings, int filesAnalyzed, int unitsAnalyzed, int linesAnalyzed,
                         int staticHits, boolean mlEnabled, VerificationHealth health) {}

    /** Callbacks are invoked on the thread that called {@link #run}, in completion order. */
    public interface Listener {
        default void started(int filesToVerify, int unitsSelected) {}

        default void fileVerified(String file, List<EngineFinding> findings) {}
    }

    private static final Logger log = LoggerFactory.getLogger(DetectionEngine.class);

    private final CodeParser parser;
    private final VulnClassifier classifier;
    private final StaticAnalyzer staticAnalyzer;
    private final CandidateRanker ranker;
    private final LlmVerifier verifier;
    private final Options options;

    public DetectionEngine(CodeParser parser, VulnClassifier classifier, StaticAnalyzer staticAnalyzer,
                           CandidateRanker ranker, LlmVerifier verifier, Options options) {
        this.parser = parser;
        this.classifier = classifier;
        this.staticAnalyzer = staticAnalyzer;
        this.ranker = ranker;
        this.verifier = verifier;
        this.options = options;
    }

    public Result run(Path repoDir, GitSignals git, String repoContext, boolean deepScan, Listener listener)
            throws IOException, InterruptedException {
        List<SourceFile> files = new SourceLoader(options.maxFileBytes()).load(repoDir);
        CodeIndex index = CodeIndex.build(parser, files);
        List<StaticHit> hits = staticAnalyzer.scan(repoDir);
        StaticSignal staticSignal = new StaticSignal(hits);

        List<Candidate> ranked = ranker.rank(index, classifier.score(index.units()), staticSignal, git);
        List<Candidate> selected = CandidateRanker.withinBudget(ranked, options.lineBudget());

        // Group by file, keeping files in order of their best candidate and units in source order.
        Map<String, List<Candidate>> byFile = new LinkedHashMap<>();
        for (Candidate c : selected) byFile.computeIfAbsent(c.unit().file(), k -> new ArrayList<>()).add(c);
        byFile.values().forEach(list -> list.sort(Comparator.comparingInt(c -> c.unit().startLine())));

        int lines = selected.stream().mapToInt(c -> c.unit().lineCount()).sum();
        log.info("Engine: {} files, {} units, {} static hits; verifying {} units ({} lines) in {} files",
            files.size(), index.units().size(), hits.size(), selected.size(), lines, byFile.size());
        listener.started(byFile.size(), selected.size());

        VerificationHealth health = new VerificationHealth();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, options.maxConcurrency()));
        CompletionService<Map.Entry<String, List<EngineFinding>>> done = new ExecutorCompletionService<>(pool);
        try {
            for (var e : byFile.entrySet()) {
                String file = e.getKey();
                List<StaticHit> fileHits = hits.stream().filter(h -> h.file().equals(file)).toList();
                done.submit(() -> Map.entry(file,
                    verifier.verifyFile(file, e.getValue(), index, fileHits, repoContext, deepScan, health)));
            }
            List<EngineFinding> all = new ArrayList<>();
            for (int i = 0; i < byFile.size(); i++) {
                Map.Entry<String, List<EngineFinding>> r;
                try {
                    r = done.take().get();
                } catch (ExecutionException ex) {
                    log.warn("Verification task failed: {}", ex.getCause().getMessage());
                    health.record(VerificationHealth.Problem.TASK_FAILED);
                    continue;
                }
                all.addAll(r.getValue());
                listener.fileVerified(r.getKey(), r.getValue());
            }
            return new Result(all, byFile.size(), selected.size(), lines, hits.size(), classifier.isLoaded(), health);
        } finally {
            pool.shutdownNow();
        }
    }
}
