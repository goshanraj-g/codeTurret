package com.codeturret.engine.model;

/** A source file loaded from the repo. {@code path} is repo-relative with forward slashes. */
public record SourceFile(String path, Language language, String content) {

    public int lineCount() {
        if (content.isEmpty()) return 0;
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n' && i < content.length() - 1) lines++;
        }
        return lines;
    }
}
