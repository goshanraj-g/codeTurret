package com.codeturret.engine.select;

import com.codeturret.engine.model.Candidate;
import com.codeturret.engine.model.CodeUnit;
import com.codeturret.engine.model.RepoSnapshot;
import com.codeturret.engine.parse.CodeIndex;
import com.codeturret.engine.parse.CodeParser;
import com.codeturret.engine.signals.StaticSignal;
import com.codeturret.engine.staticanalysis.StaticAnalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Ranks parsed units by Semgrep hits alone. Measures what the static analyzer contributes on its own. */
public final class StaticSelector implements CandidateSelector {

    private final CodeParser parser;
    private final StaticAnalyzer analyzer;

    public StaticSelector(CodeParser parser, StaticAnalyzer analyzer) {
        this.parser = parser;
        this.analyzer = analyzer;
    }

    @Override
    public String name() {
        return "semgrep";
    }

    @Override
    public List<Candidate> select(RepoSnapshot repo) {
        StaticSignal signal = new StaticSignal(analyzer.scan(repo.root()));
        List<Candidate> out = new ArrayList<>();
        for (CodeUnit u : CodeIndex.build(parser, repo.files()).units()) {
            double s = signal.score(u);
            out.add(new Candidate(u, s, Map.of("static", s)));
        }
        out.sort(RankOrder.INSTANCE);
        return out;
    }
}
