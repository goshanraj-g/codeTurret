package com.codeturret.engine.select;

import com.codeturret.engine.ml.VulnClassifier;
import com.codeturret.engine.model.Candidate;
import com.codeturret.engine.model.CodeUnit;
import com.codeturret.engine.model.RepoSnapshot;
import com.codeturret.engine.parse.CodeIndex;
import com.codeturret.engine.parse.CodeParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Ranks parsed units by the trained classifier alone. Measures what the model contributes on its own. */
public final class MlSelector implements CandidateSelector {

    private final CodeParser parser;
    private final VulnClassifier classifier;

    public MlSelector(CodeParser parser, VulnClassifier classifier) {
        this.parser = parser;
        this.classifier = classifier;
    }

    @Override
    public String name() {
        return "ml";
    }

    @Override
    public List<Candidate> select(RepoSnapshot repo) {
        List<CodeUnit> units = CodeIndex.build(parser, repo.files()).units();
        Map<String, Double> scores = classifier.score(units);
        List<Candidate> out = new ArrayList<>();
        for (CodeUnit u : units) {
            double p = scores.get(u.id());
            out.add(new Candidate(u, p, Map.of("ml", p)));
        }
        out.sort(RankOrder.INSTANCE);
        return out;
    }
}
