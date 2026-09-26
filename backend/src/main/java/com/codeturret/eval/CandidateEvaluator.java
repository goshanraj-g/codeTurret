package com.codeturret.eval;

import com.codeturret.engine.model.Candidate;
import com.codeturret.engine.model.CodeUnit;

import java.util.*;

/**
 * Measures how much ground truth ends up in front of the LLM when a selector's candidates are taken
 * greedily, most important first, until a line budget is spent.
 *
 * <p>A vulnerability counts as <em>covered</em> when at least half of its labelled lines (minimum one)
 * were sent. Lines shared by overlapping candidates are only paid for once.
 */
public final class CandidateEvaluator {

    public static final int UNLIMITED = Integer.MAX_VALUE;

    public record Result(int budget, int linesSent, int unitsSent, List<String> coveredVulnIds) {}

    public Result evaluate(List<Candidate> ranked, List<Benchmark.Vuln> vulns, int budget) {
        Map<String, BitSet> sent = new HashMap<>();
        int used = 0;
        int units = 0;

        for (Candidate c : ranked) {
            CodeUnit u = c.unit();
            BitSet lines = sent.computeIfAbsent(u.file(), k -> new BitSet());
            int fresh = 0;
            for (int l = u.startLine(); l <= u.endLine(); l++) if (!lines.get(l)) fresh++;
            if (fresh == 0) continue;
            if (budget != UNLIMITED && used + fresh > budget) continue;
            lines.set(u.startLine(), u.endLine() + 1);
            used += fresh;
            units++;
        }

        List<String> covered = new ArrayList<>();
        for (Benchmark.Vuln v : vulns) {
            BitSet lines = sent.get(v.file());
            if (lines == null) continue;
            int hit = lines.get(v.startLine(), v.endLine() + 1).cardinality();
            if (hit >= Math.max(1, (v.length() + 1) / 2)) covered.add(v.id());
        }
        return new Result(budget, used, units, covered);
    }
}
