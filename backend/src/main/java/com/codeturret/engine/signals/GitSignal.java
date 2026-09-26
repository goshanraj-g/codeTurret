package com.codeturret.engine.signals;

import com.codeturret.engine.model.CodeUnit;
import com.codeturret.engine.model.GitSignals;

/** Recent churn and security-related commit messages, per file, normalized to [0, 1]. */
public final class GitSignal {

    static final int HOT_FILE_CHANGES = 5;

    private GitSignal() {}

    public static double score(CodeUnit unit, GitSignals git) {
        double churn = Math.min(1.0, git.changeCounts().getOrDefault(unit.file(), 0) / (double) HOT_FILE_CHANGES);
        double security = git.securityCommits().containsKey(unit.file()) ? 1.0 : 0.0;
        return 0.5 * churn + 0.5 * security;
    }
}
