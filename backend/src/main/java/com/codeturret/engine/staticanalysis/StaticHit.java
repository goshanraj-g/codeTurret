package com.codeturret.engine.staticanalysis;

import java.util.Locale;

/** One static-analysis result. Lines are 1-based and inclusive; {@code file} is repo-relative. */
public record StaticHit(String file, int startLine, int endLine, String ruleId, String message,
                        Severity severity, String cwe) {

    public enum Severity {
        ERROR(1.0), WARNING(0.7), INFO(0.4);

        public final double weight;

        Severity(double weight) {
            this.weight = weight;
        }

        public static Severity parse(String s) {
            try {
                return valueOf(s.toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                return INFO;
            }
        }
    }

    public boolean overlaps(int start, int end) {
        return startLine <= end && endLine >= start;
    }
}
