package com.codeturret.engine;

import com.codeturret.engine.ml.VulnClassifier;
import com.codeturret.engine.model.GitSignals;
import com.codeturret.engine.parse.CodeParser;
import com.codeturret.engine.rank.CandidateRanker;
import com.codeturret.engine.rank.RankerWeights;
import com.codeturret.engine.verify.EngineFinding;
import com.codeturret.engine.verify.LlmVerifier;
import com.codeturret.eval.BenchmarkWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** End to end over the polyglot fixture with a fake LLM, so no network or API key is needed. */
class DetectionEngineTest {

    private static final Pattern FILE = Pattern.compile("File: (\\S+)");

    @Test
    void verifiesSelectedFilesWithinBudgetAndReportsPerFile() throws Exception {
        Path fixture = BenchmarkWorkspace.findRepoRoot().resolve("eval/fixtures/polyglot-shop");
        Set<String> promptedFiles = ConcurrentHashMap.newKeySet();

        // Fake model: reports one finding on the first numbered line of every prompt.
        LlmVerifier verifier = new LlmVerifier((model, prompt) -> {
            Matcher m = FILE.matcher(prompt);
            if (m.find()) promptedFiles.add(m.group(1));
            Matcher line = Pattern.compile("\\n\\s*(\\d+)\\| ").matcher(prompt);
            int n = line.find() ? Integer.parseInt(line.group(1)) : 1;
            return "{\"findings\": [{\"line_number\": " + n + ", \"severity\": \"MEDIUM\", \"vuln_type\": \"X\", \"confidence\": 0.9}]}";
        }, new LlmVerifier.Models("fast", "strong", 0.7));

        try (VulnClassifier classifier = VulnClassifier.load()) {
            DetectionEngine engine = new DetectionEngine(new CodeParser(), classifier, dir -> List.of(),
                new CandidateRanker(RankerWeights.defaults()), verifier, new DetectionEngine.Options(150, 3, 50_000));

            List<String> verifiedFiles = new ArrayList<>();
            int[] started = {-1};
            DetectionEngine.Result result = engine.run(fixture, GitSignals.empty(), "", false, new DetectionEngine.Listener() {
                @Override
                public void started(int files, int units) {
                    started[0] = files;
                }

                @Override
                public void fileVerified(String file, List<EngineFinding> findings) {
                    verifiedFiles.add(file);
                }
            });

            assertThat(result.linesAnalyzed()).isLessThanOrEqualTo(150);
            assertThat(result.filesAnalyzed()).isEqualTo(started[0]).isEqualTo(verifiedFiles.size());
            assertThat(new HashSet<>(verifiedFiles)).isEqualTo(promptedFiles);
            assertThat(result.findings()).hasSize(verifiedFiles.size())
                .allSatisfy(f -> assertThat(f.source()).isEqualTo(EngineFinding.Source.LLM));
            assertThat(result.mlEnabled()).isTrue();
        }
    }
}
