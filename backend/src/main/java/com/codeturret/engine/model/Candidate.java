package com.codeturret.engine.model;

import java.util.Map;

/**
 * A unit proposed for LLM verification.
 *
 * @param score   overall priority in [0, 1]; selectors return candidates sorted by this, descending
 * @param signals the individual signal values that produced {@code score}, for explainability
 */
public record Candidate(CodeUnit unit, double score, Map<String, Double> signals) {}
