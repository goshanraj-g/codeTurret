package com.codeturret.engine.rank;

import com.codeturret.engine.model.Candidate;
import com.codeturret.engine.model.CodeUnit;
import com.codeturret.engine.model.GitSignals;
import com.codeturret.engine.parse.CodeIndex;
import com.codeturret.engine.select.RankOrder;
import com.codeturret.engine.select.StructureSelector;
import com.codeturret.engine.signals.GitSignal;
import com.codeturret.engine.signals.SinkCatalog;
import com.codeturret.engine.signals.StaticSignal;

import java.util.*;

/**
 * Combines every signal into one score per unit:
 * <pre>score = (w_ml·ml + w_static·static + w_reach·reachability + w_git·git + w_struct·structure) / Σw</pre>
 * Units with a static-analysis hit are ordered first regardless of score, so Semgrep findings are always
 * verified; everything else competes on score for the remaining budget.
 */
public final class CandidateRanker {

    private final RankerWeights weights;

    public CandidateRanker(RankerWeights weights) {
        this.weights = weights;
    }

    public List<Candidate> rank(CodeIndex index, Map<String, Double> mlScores, StaticSignal staticSignal, GitSignals git) {
        List<Candidate> withHits = new ArrayList<>();
        List<Candidate> rest = new ArrayList<>();
        for (CodeUnit u : index.units()) {
            Map<String, Double> s = new LinkedHashMap<>();
            s.put("ml", mlScores.getOrDefault(u.id(), 0.5));
            s.put("static", staticSignal.score(u));
            s.put("reachability", StructureSelector.reachability(index.hopsFromEntrypoint(u)));
            s.put("git", GitSignal.score(u, git));
            s.put("structure", SinkCatalog.sinksIn(u).isEmpty() ? 0.0 : 1.0);

            double score = (weights.ml() * s.get("ml")
                + weights.staticAnalysis() * s.get("static")
                + weights.reachability() * s.get("reachability")
                + weights.git() * s.get("git")
                + weights.structure() * s.get("structure")) / weights.total();

            Candidate c = new Candidate(u, score, Collections.unmodifiableMap(s));
            (s.get("static") > 0 ? withHits : rest).add(c);
        }
        withHits.sort(RankOrder.INSTANCE);
        rest.sort(RankOrder.INSTANCE);
        List<Candidate> out = new ArrayList<>(withHits);
        out.addAll(rest);
        return out;
    }

    /** Takes candidates in order until {@code lineBudget} lines are spent, skipping any that don't fit. */
    public static List<Candidate> withinBudget(List<Candidate> ranked, int lineBudget) {
        List<Candidate> out = new ArrayList<>();
        int used = 0;
        for (Candidate c : ranked) {
            int lines = c.unit().lineCount();
            if (used + lines > lineBudget) continue;
            out.add(c);
            used += lines;
        }
        return out;
    }
}
