package com.codeturret.engine.rank;

/** Relative importance of each ranking signal. Only ratios matter; they need not sum to 1. */
public record RankerWeights(double ml, double staticAnalysis, double reachability, double git, double structure) {

    public static RankerWeights defaults() {
        return new RankerWeights(0.35, 0.30, 0.15, 0.10, 0.10);
    }

    double total() {
        return ml + staticAnalysis + reachability + git + structure;
    }
}
