package com.codeturret.engine.signals;

import com.codeturret.engine.model.CodeUnit;
import com.codeturret.engine.staticanalysis.StaticHit;

import java.util.*;

/** Indexes static hits by file and scores units by the most severe hit inside them. */
public final class StaticSignal {

    private final Map<String, List<StaticHit>> byFile = new HashMap<>();

    public StaticSignal(List<StaticHit> hits) {
        for (StaticHit h : hits) byFile.computeIfAbsent(h.file(), k -> new ArrayList<>()).add(h);
    }

    public List<StaticHit> hitsIn(CodeUnit unit) {
        return byFile.getOrDefault(unit.file(), List.of()).stream()
            .filter(h -> h.overlaps(unit.startLine(), unit.endLine()))
            .toList();
    }

    /** 1.0 for an ERROR hit, 0.7 WARNING, 0.4 INFO, 0 when no hit falls inside the unit. */
    public double score(CodeUnit unit) {
        return hitsIn(unit).stream().mapToDouble(h -> h.severity().weight).max().orElse(0.0);
    }
}
