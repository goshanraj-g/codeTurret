package com.codeturret.eval;

import java.util.List;

/** A repo plus its ground-truth vulnerabilities, loaded from {@code eval/benchmarks/*.json}. */
public record Benchmark(String name, String description, Source source, List<Vuln> vulns) {

    /** {@code type} is {@code fixture} (path relative to repo root) or {@code git} (url + pinned commit). */
    public record Source(String type, String path, String url, String commit) {}

    /** Line range is 1-based and inclusive. */
    public record Vuln(String id, String file, int startLine, int endLine, String cwe, String note) {

        public int length() {
            return endLine - startLine + 1;
        }
    }
}
