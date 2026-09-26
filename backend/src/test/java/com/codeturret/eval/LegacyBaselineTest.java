package com.codeturret.eval;

import com.codeturret.config.GitProperties;
import com.codeturret.engine.SourceLoader;
import com.codeturret.engine.model.GitSignals;
import com.codeturret.engine.model.RepoSnapshot;
import com.codeturret.engine.select.LegacyRegexSelector;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the v1 baseline on the offline fixture so harness regressions are noticed. */
class LegacyBaselineTest {

    @Test
    void legacySelectorMissesJavaAndKeywordFreeCode() throws Exception {
        Path root = BenchmarkWorkspace.findRepoRoot();
        BenchmarkWorkspace ws = new BenchmarkWorkspace(root);
        Benchmark shop = ws.loadAll().stream().filter(b -> b.name().equals("polyglot-shop")).findFirst().orElseThrow();
        Path dir = ws.materialize(shop);

        var snapshot = new RepoSnapshot(dir, new SourceLoader(50_000).load(dir), GitSignals.empty());
        var ranked = new LegacyRegexSelector(new GitProperties()).select(snapshot);
        var result = new CandidateEvaluator().evaluate(ranked, shop.vulns(), CandidateEvaluator.UNLIMITED);

        assertThat(result.coveredVulnIds()).hasSize(9)
            .doesNotContain("java-sqli", "java-xxe", "ts-open-redirect");
    }
}
