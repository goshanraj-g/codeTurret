package com.codeturret.engine.select;

import com.codeturret.engine.model.Candidate;

import java.util.Comparator;

/** Highest score first; among equal scores, smaller units first (more signal per line of budget). */
public final class RankOrder implements Comparator<Candidate> {

    public static final RankOrder INSTANCE = new RankOrder();

    @Override
    public int compare(Candidate a, Candidate b) {
        int byScore = Double.compare(b.score(), a.score());
        if (byScore != 0) return byScore;
        int bySize = Integer.compare(a.unit().lineCount(), b.unit().lineCount());
        return bySize != 0 ? bySize : a.unit().id().compareTo(b.unit().id());
    }
}
