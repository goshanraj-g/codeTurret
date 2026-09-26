package com.codeturret.engine.verify;

import com.codeturret.engine.model.Candidate;
import com.codeturret.engine.model.CodeUnit;
import com.codeturret.engine.parse.CodeIndex;
import com.codeturret.engine.staticanalysis.StaticHit;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Builds the per-file verification prompts. Pure functions of their inputs, so they are easy to test. */
public final class VerificationPrompt {

    static final String RESPONSE_SCHEMA = """
        Return JSON only:
        {"findings": [{"line_number": int, "severity": "CRITICAL|HIGH|MEDIUM|LOW", "vuln_type": string,
          "cwe_id": string, "description": string, "exploitability": string, "fix_suggestion": string,
          "confidence": float between 0 and 1}],
         "rejected_hints": [{"rule_id": string, "reason": string}]}
        """;

    private VerificationPrompt() {}

    public static String triage(String file, List<Candidate> units, CodeIndex index, List<StaticHit> hits,
                                String repoContext) {
        StringBuilder p = new StringBuilder();
        p.append("You are an application security engineer verifying candidate code for real, exploitable ")
         .append("vulnerabilities.\n\n");
        if (repoContext != null && !repoContext.isBlank()) p.append("Project: ").append(repoContext).append("\n");
        p.append("File: ").append(file).append("\n\n")
         .append("The units below were selected by a ranking model because they look risky. Most selected code is ")
         .append("still safe. Report a finding only if attacker-controlled data can plausibly reach the dangerous ")
         .append("operation, using the entrypoint and caller information. Use the real line numbers shown. ")
         .append("Static-analysis hints are leads, not facts: confirm each one as a finding or list it in ")
         .append("rejected_hints with a reason.\n\n");

        int n = 1;
        for (Candidate c : units) {
            CodeUnit u = c.unit();
            p.append("### Unit ").append(n++).append(": ").append(u.name())
             .append(" (lines ").append(u.startLine()).append('-').append(u.endLine()).append(")\n");
            p.append("Context: ").append(context(u, index)).append('\n');
            List<StaticHit> unitHits = hits.stream().filter(h -> h.overlaps(u.startLine(), u.endLine())).toList();
            for (StaticHit h : unitHits) {
                p.append("Static hint: rule ").append(h.ruleId()).append(" at line ").append(h.startLine())
                 .append(h.cwe() != null ? " (" + h.cwe() + ")" : "").append(": ").append(oneLine(h.message())).append('\n');
            }
            p.append("```\n").append(numbered(u)).append("\n```\n\n");
        }
        p.append(RESPONSE_SCHEMA);
        return p.toString();
    }

    public static String deep(String triagePrompt, String triageJson) {
        return triagePrompt + "\n\nA first-pass reviewer produced these preliminary results:\n" + triageJson + "\n\n"
            + "Re-examine the code carefully. Confirm or reject each preliminary finding, correct severities and "
            + "line numbers, add anything that was missed, and give a concrete fix for each confirmed finding.\n";
    }

    static String context(CodeUnit u, CodeIndex index) {
        StringBuilder c = new StringBuilder();
        if (u.entrypoint()) c.append("route handler (receives request data directly)");
        int hops = index.hopsFromEntrypoint(u);
        if (!u.entrypoint() && hops != CodeIndex.UNREACHABLE) c.append(hops).append(" call(s) from a route handler");
        if (hops == CodeIndex.UNREACHABLE && !u.entrypoint()) c.append("no known path from a route handler");
        List<CodeUnit> callers = index.callers(u);
        if (!callers.isEmpty()) {
            c.append("; called by ").append(callers.stream().limit(5)
                .map(x -> x.name() + " (" + x.file() + ":" + x.startLine() + ")").collect(Collectors.joining(", ")));
        }
        return c.toString();
    }

    static String numbered(CodeUnit u) {
        String[] lines = u.code().split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int width = String.valueOf(u.endLine()).length();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(String.format(Locale.ROOT, "%" + width + "d| ", u.startLine() + i)).append(lines[i]);
        }
        return sb.toString();
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }
}
