package com.codeturret.engine.select;

import com.codeturret.engine.model.Candidate;
import com.codeturret.engine.model.CodeUnit;
import com.codeturret.engine.model.RepoSnapshot;
import com.codeturret.engine.parse.CodeIndex;
import com.codeturret.engine.parse.CodeParser;
import com.codeturret.engine.signals.SinkCatalog;

import java.util.*;

/**
 * Ranks parsed units by code structure alone: does the unit call a dangerous sink, and how close is it to
 * a route handler? No ML, no static analyzer. Used to measure what parsing buys on its own.
 */
public final class StructureSelector implements CandidateSelector {

    private final CodeParser parser;

    public StructureSelector(CodeParser parser) {
        this.parser = parser;
    }

    @Override
    public String name() {
        return "structure";
    }

    @Override
    public List<Candidate> select(RepoSnapshot repo) {
        CodeIndex index = CodeIndex.build(parser, repo.files());
        List<Candidate> out = new ArrayList<>();
        for (CodeUnit u : index.units()) {
            double reach = reachability(index.hopsFromEntrypoint(u));
            double sink = SinkCatalog.sinksIn(u).isEmpty() ? 0.0 : 1.0;
            out.add(new Candidate(u, 0.5 * reach + 0.5 * sink, Map.of("reachability", reach, "structure", sink)));
        }
        out.sort(RankOrder.INSTANCE);
        return out;
    }

    public static double reachability(int hops) {
        return switch (hops) {
            case 0 -> 1.0;
            case 1 -> 0.6;
            case 2 -> 0.3;
            default -> 0.0;
        };
    }
}
