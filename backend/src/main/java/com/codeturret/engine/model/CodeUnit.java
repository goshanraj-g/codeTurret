package com.codeturret.engine.model;

import java.util.List;

/**
 * The unit of analysis: a function, method, or module body. Line numbers are 1-based and inclusive.
 *
 * @param calls      simple names of functions called inside this unit
 * @param entrypoint true if attacker input arrives here directly (route handler, controller method)
 */
public record CodeUnit(
    String file,
    Language language,
    String name,
    Kind kind,
    int startLine,
    int endLine,
    String code,
    List<String> calls,
    boolean entrypoint
) {
    public enum Kind { FUNCTION, METHOD, MODULE, SNIPPET }

    public int lineCount() {
        return endLine - startLine + 1;
    }

    public String id() {
        return file + ":" + startLine + "-" + endLine;
    }
}
