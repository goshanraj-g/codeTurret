package com.codeturret.engine.rank;

import com.codeturret.engine.model.Candidate;
import com.codeturret.engine.model.GitSignals;
import com.codeturret.engine.model.Language;
import com.codeturret.engine.model.SourceFile;
import com.codeturret.engine.parse.CodeIndex;
import com.codeturret.engine.parse.CodeParser;
import com.codeturret.engine.signals.StaticSignal;
import com.codeturret.engine.staticanalysis.StaticHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateRankerTest {

    private static final String APP = """
        const { exec } = require("child_process");

        function formatName(first, last) {
          return first + " " + last;
        }

        function backup(label) {
          exec("tar czf /backups/" + label + ".tgz data");
        }

        exports.handler = (req, res) => {
          backup(req.body.label);
          res.json({ ok: true });
        };

        function unrelated(a) {
          return a * 2;
        }
        """;

    private final CodeIndex index = CodeIndex.fromParsed(List.of(
        new CodeParser().parse(new SourceFile("app.js", Language.JAVASCRIPT, APP)),
        new CodeParser().parse(new SourceFile("server.js", Language.JAVASCRIPT, "app.post(\"/b\", h.handler);\n"))));

    private List<String> order(List<Candidate> ranked) {
        return ranked.stream().map(c -> c.unit().name()).toList();
    }

    @Test
    void reachableSinkOutranksUnreachableHelpers() {
        var ranked = new CandidateRanker(RankerWeights.defaults())
            .rank(index, Map.of(), new StaticSignal(List.of()), GitSignals.empty());

        List<String> names = order(ranked);
        assertThat(names.indexOf("backup")).isLessThan(names.indexOf("formatName"));
        assertThat(names.indexOf("handler")).isLessThan(names.indexOf("unrelated"));
        assertThat(ranked.get(0).signals()).containsKeys("ml", "static", "reachability", "git", "structure");
    }

    @Test
    void unitsWithStaticHitsComeFirstRegardlessOfScore() {
        var hit = new StaticHit("app.js", 17, 17, "rule", "", StaticHit.Severity.INFO, null);
        var ranked = new CandidateRanker(RankerWeights.defaults())
            .rank(index, Map.of(), new StaticSignal(List.of(hit)), GitSignals.empty());

        assertThat(order(ranked).get(0)).isEqualTo("unrelated");
    }

    @Test
    void mlScoreBreaksTiesAndGitBoostsHotFiles() {
        var onlyMl = new RankerWeights(1, 0, 0, 0, 0);
        String formatId = index.units().stream().filter(u -> u.name().equals("formatName")).findFirst().orElseThrow().id();
        var ranked = new CandidateRanker(onlyMl).rank(index, Map.of(formatId, 0.99), new StaticSignal(List.of()), GitSignals.empty());
        assertThat(order(ranked).get(0)).isEqualTo("formatName");

        var git = new GitSignals(Map.of("app.js", 10), Map.of("app.js", List.of("fix injection")));
        var gitOnly = new CandidateRanker(new RankerWeights(0, 0, 0, 1, 0)).rank(index, Map.of(), new StaticSignal(List.of()), git);
        assertThat(gitOnly.get(0).signals().get("git")).isEqualTo(1.0);
        assertThat(gitOnly.get(0).unit().file()).isEqualTo("app.js");
    }

    @Test
    void withinBudgetSkipsUnitsThatDoNotFit() {
        var ranked = new CandidateRanker(RankerWeights.defaults())
            .rank(index, Map.of(), new StaticSignal(List.of()), GitSignals.empty());
        var picked = CandidateRanker.withinBudget(ranked, 6);
        assertThat(picked.stream().mapToInt(c -> c.unit().lineCount()).sum()).isLessThanOrEqualTo(6);
        assertThat(picked).isNotEmpty();
    }
}
