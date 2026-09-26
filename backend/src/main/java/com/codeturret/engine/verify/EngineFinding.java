package com.codeturret.engine.verify;

import java.util.Map;

/**
 * A verified finding produced by the engine.
 *
 * @param source  {@link Source#LLM}: found by the LLM in ranked code; {@link Source#STATIC_LLM}: a static-analysis
 *                hit confirmed by the LLM; {@link Source#STATIC}: a static hit reported without LLM confirmation
 * @param mlScore classifier probability for the unit containing the finding
 * @param signals ranking signals of that unit, explaining why it was analysed
 */
public record EngineFinding(
    String file,
    Integer lineNumber,
    String severity,
    String vulnType,
    String cweId,
    String description,
    String fixSuggestion,
    double confidence,
    String codeSnippet,
    String modelUsed,
    Source source,
    double mlScore,
    Map<String, Double> signals
) {
    public enum Source { LLM, STATIC_LLM, STATIC }
}
