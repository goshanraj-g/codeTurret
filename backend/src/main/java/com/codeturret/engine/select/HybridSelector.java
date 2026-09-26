package com.codeturret.engine.select;

import com.codeturret.engine.ml.VulnClassifier;
import com.codeturret.engine.model.Candidate;
import com.codeturret.engine.model.RepoSnapshot;
import com.codeturret.engine.parse.CodeIndex;
import com.codeturret.engine.parse.CodeParser;
import com.codeturret.engine.rank.CandidateRanker;
import com.codeturret.engine.rank.RankerWeights;
import com.codeturret.engine.signals.StaticSignal;
import com.codeturret.engine.staticanalysis.StaticAnalyzer;

import java.util.List;

/** The production selector: every signal combined by {@link CandidateRanker}. */
public final class HybridSelector implements CandidateSelector {

    private final String name;
    private final CodeParser parser;
    private final VulnClassifier classifier;
    private final StaticAnalyzer analyzer;
    private final CandidateRanker ranker;

    public HybridSelector(String name, CodeParser parser, VulnClassifier classifier, StaticAnalyzer analyzer,
                          RankerWeights weights) {
        this.name = name;
        this.parser = parser;
        this.classifier = classifier;
        this.analyzer = analyzer;
        this.ranker = new CandidateRanker(weights);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<Candidate> select(RepoSnapshot repo) {
        CodeIndex index = CodeIndex.build(parser, repo.files());
        StaticSignal staticSignal = new StaticSignal(analyzer.scan(repo.root()));
        return ranker.rank(index, classifier.score(index.units()), staticSignal, repo.git());
    }
}
