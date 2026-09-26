package com.codeturret.eval;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.codeturret.config.GitProperties;
import com.codeturret.engine.SourceLoader;
import com.codeturret.engine.model.Candidate;
import com.codeturret.engine.model.GitSignals;
import com.codeturret.engine.model.RepoSnapshot;
import com.codeturret.engine.model.SourceFile;
import com.codeturret.engine.select.CandidateSelector;
import com.codeturret.service.GitService;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Offline benchmark runner.
 *
 * <pre>
 * cd backend
 * ./mvnw -q compile exec:java -Dexec.args="--selectors legacy-regex"
 * </pre>
 *
 * Writes {@code eval/results/candidates.md} at the repo root.
 */
public final class EvalRunner {

    static final int[] BUDGETS = {250, 500, 1000, 2000, 3000, CandidateEvaluator.UNLIMITED};

    public static void main(String[] args) throws Exception {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.WARN);

        Map<String, String> opts = parseArgs(args);
        Path root = BenchmarkWorkspace.findRepoRoot();
        BenchmarkWorkspace workspace = new BenchmarkWorkspace(root);
        GitProperties gitProps = new GitProperties();

        Selectors registry = new Selectors(gitProps);
        List<String> selectorNames = List.of(opts.getOrDefault("selectors", String.join(",", registry.available())).split(","));
        List<CandidateSelector> selectors = selectorNames.stream().map(n -> registry.create(n.strip())).toList();

        List<Benchmark> benchmarks = workspace.loadAll();
        String only = opts.get("benchmark");
        if (only != null) benchmarks = benchmarks.stream().filter(b -> b.name().equals(only)).toList();

        EvalReport report = new EvalReport(BUDGETS);
        CandidateEvaluator evaluator = new CandidateEvaluator();
        GitService gitService = new GitService(gitProps);

        for (Benchmark b : benchmarks) {
            Path dir = workspace.materialize(b);
            List<SourceFile> files = new SourceLoader(gitProps.getMaxFileSize()).load(dir);
            GitSignals git = workspace.hasOwnGitHistory(dir)
                ? new GitSignals(gitService.getHotFiles(dir), gitService.getSecurityCommits(dir))
                : GitSignals.empty();
            RepoSnapshot snapshot = new RepoSnapshot(dir, files, git);

            for (CandidateSelector selector : selectors) {
                long t0 = System.nanoTime();
                List<Candidate> ranked = selector.select(snapshot);
                long millis = (System.nanoTime() - t0) / 1_000_000;
                List<CandidateEvaluator.Result> results = new ArrayList<>();
                for (int budget : BUDGETS) results.add(evaluator.evaluate(ranked, b.vulns(), budget));
                report.add(b, repoLines(files), selector.name(), results, millis);
                System.out.printf("%-14s %-16s recall@all=%d/%d  lines=%d  (%d ms)%n",
                    b.name(), selector.name(), results.get(results.size() - 1).coveredVulnIds().size(),
                    b.vulns().size(), results.get(results.size() - 1).linesSent(), millis);
            }
        }

        Path out = root.resolve(opts.getOrDefault("out", "eval/results/candidates.md"));
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toMarkdown());
        System.out.println("Wrote " + root.relativize(out));
    }

    private static int repoLines(List<SourceFile> files) {
        return files.stream().mapToInt(SourceFile::lineCount).sum();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) opts.put(args[i].substring(2), args[++i]);
        }
        return opts;
    }
}
