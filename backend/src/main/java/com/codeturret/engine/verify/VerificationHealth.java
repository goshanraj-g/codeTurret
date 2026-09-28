package com.codeturret.engine.verify;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Problems the engine absorbed during one run instead of failing it. Each one means a file got less checking than
 * it should have, while the scan still completes. Thread-safe: files are verified in parallel.
 */
public final class VerificationHealth {

    public enum Problem {
        /** The fast-model call failed; the file fell back to static-analysis hits. */
        LLM_CALL_FAILED,
        /** The fast model's answer wasn't the expected JSON; the file got no findings. */
        LLM_UNPARSEABLE,
        /** The strong-model call failed; the first-pass findings were kept. */
        ESCALATION_CALL_FAILED,
        /** The strong model's answer wasn't the expected JSON; the first-pass findings were kept. */
        ESCALATION_UNPARSEABLE,
        /** Verifying the file threw; the file got no findings. */
        TASK_FAILED
    }

    private final Map<Problem, AtomicInteger> counts = new EnumMap<>(Problem.class);

    public VerificationHealth() {
        for (Problem p : Problem.values()) counts.put(p, new AtomicInteger());
    }

    public void record(Problem problem) {
        counts.get(problem).incrementAndGet();
    }

    public int count(Problem problem) {
        return counts.get(problem).get();
    }

    /** Files the LLM never judged: they got static hits only, or nothing. */
    public int unverifiedFiles() {
        return count(Problem.LLM_CALL_FAILED) + count(Problem.LLM_UNPARSEABLE) + count(Problem.TASK_FAILED);
    }

    public int unparseableResponses() {
        return count(Problem.LLM_UNPARSEABLE) + count(Problem.ESCALATION_UNPARSEABLE);
    }

    public Map<Problem, Integer> snapshot() {
        Map<Problem, Integer> out = new EnumMap<>(Problem.class);
        counts.forEach((p, n) -> out.put(p, n.get()));
        return out;
    }
}
