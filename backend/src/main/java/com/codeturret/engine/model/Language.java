package com.codeturret.engine.model;

import java.util.Locale;

public enum Language {
    PYTHON, JAVASCRIPT, TYPESCRIPT, TSX, JAVA, OTHER;

    public static Language fromPath(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".py")) return PYTHON;
        if (p.endsWith(".js") || p.endsWith(".jsx") || p.endsWith(".mjs") || p.endsWith(".cjs")) return JAVASCRIPT;
        if (p.endsWith(".tsx")) return TSX;
        if (p.endsWith(".ts")) return TYPESCRIPT;
        if (p.endsWith(".java")) return JAVA;
        return OTHER;
    }
}
