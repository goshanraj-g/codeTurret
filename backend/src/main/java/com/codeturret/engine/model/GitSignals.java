package com.codeturret.engine.model;

import java.util.List;
import java.util.Map;

/** Git-history signals per repo-relative file path. */
public record GitSignals(Map<String, Integer> changeCounts, Map<String, List<String>> securityCommits) {

    public static GitSignals empty() {
        return new GitSignals(Map.of(), Map.of());
    }
}
