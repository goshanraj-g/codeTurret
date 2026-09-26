package com.codeturret.eval;

import com.codeturret.config.GitProperties;
import com.codeturret.engine.select.CandidateSelector;
import com.codeturret.engine.select.LegacyRegexSelector;

import java.util.List;

/** Registry of selectors the eval harness can compare. */
final class Selectors {

    private Selectors() {}

    static List<String> names() {
        return List.of("legacy-regex");
    }

    static CandidateSelector create(String name, GitProperties gitProps) {
        return switch (name) {
            case "legacy-regex" -> new LegacyRegexSelector(gitProps);
            default -> throw new IllegalArgumentException("Unknown selector: " + name + " (known: " + names() + ")");
        };
    }
}
