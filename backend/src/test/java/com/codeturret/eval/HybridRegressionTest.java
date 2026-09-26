package com.codeturret.eval;

import com.codeturret.config.GitProperties;
import com.codeturret.engine.SourceLoader;
import com.codeturret.engine.ml.VulnClassifier;
import com.codeturret.engine.model.GitSignals;
import com.codeturret.engine.model.RepoSnapshot;
import com.codeturret.engine.parse.CodeParser;
import com.codeturret.engine.rank.RankerWeights;
import com.codeturret.engine.select.HybridSelector;
import com.codeturret.engine.select.LegacyRegexSelector;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the headline result offline (no Semgrep): the hybrid engine must keep beating v1 on a tight budget. */
class HybridRegressionTest {

    @Test
    void hybridWithoutStaticAnalysisBeatsLegacyAt250Lines() throws Exception {
        BenchmarkWorkspace ws = new BenchmarkWorkspace(BenchmarkWorkspace.findRepoRoot());
        Benchmark shop = ws.loadAll().stream().filter(b -> b.name().equals("polyglot-shop")).findFirst().orElseThrow();
        Path dir = ws.materialize(shop);
        var snapshot = new RepoSnapshot(dir, new SourceLoader(50_000).load(dir), GitSignals.empty());
        var evaluator = new CandidateEvaluator();

        int legacy = evaluator.evaluate(new LegacyRegexSelector(new GitProperties()).select(snapshot), shop.vulns(), 250)
            .coveredVulnIds().size();
        int hybrid;
        try (VulnClassifier classifier = VulnClassifier.load()) {
            var selector = new HybridSelector("hybrid-no-static", new CodeParser(), classifier, d -> List.of(), RankerWeights.defaults());
            hybrid = evaluator.evaluate(selector.select(snapshot), shop.vulns(), 250).coveredVulnIds().size();
        }

        assertThat(hybrid).isGreaterThan(legacy);
    }
}
