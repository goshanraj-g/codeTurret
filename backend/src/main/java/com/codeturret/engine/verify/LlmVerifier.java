package com.codeturret.engine.verify;

import com.codeturret.engine.model.Candidate;
import com.codeturret.engine.model.CodeUnit;
import com.codeturret.engine.parse.CodeIndex;
import com.codeturret.engine.staticanalysis.StaticHit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Verifies one file's selected units with the LLM: a fast-model pass, escalated to the stronger model when the
 * fast pass reports HIGH/CRITICAL or low-confidence findings (or on a deep scan). If the LLM is unavailable,
 * static-analysis hits are still reported as {@link EngineFinding.Source#STATIC} findings at capped confidence.
 */
public final class LlmVerifier {

    public record Models(String fast, String strong, double escalationConfidence) {}

    static final double STATIC_ONLY_CONFIDENCE = 0.4;
    private static final int HINT_MATCH_LINES = 2;
    private static final Logger log = LoggerFactory.getLogger(LlmVerifier.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmClient client;
    private final Models models;

    public LlmVerifier(LlmClient client, Models models) {
        this.client = client;
        this.models = models;
    }

    record Raw(Integer line, String severity, String vulnType, String cwe, String description, String fix, double confidence) {}

    public List<EngineFinding> verifyFile(String file, List<Candidate> units, CodeIndex index, List<StaticHit> hits,
                                          String repoContext, boolean deepScan) {
        String prompt = VerificationPrompt.triage(file, units, index, hits, repoContext);
        String json;
        try {
            json = client.completeJson(models.fast(), prompt);
        } catch (Exception e) {
            log.warn("LLM verification failed for {} ({}); reporting static hits only", file, e.getMessage());
            return staticOnly(file, units, hits);
        }

        List<Raw> raws = parse(json);
        String modelUsed = models.fast();
        boolean escalate = deepScan || raws.stream().anyMatch(r ->
            r.confidence() < models.escalationConfidence()
                || "CRITICAL".equals(r.severity()) || "HIGH".equals(r.severity()));
        if (escalate && !raws.isEmpty()) {
            try {
                raws = parse(client.completeJson(models.strong(), VerificationPrompt.deep(prompt, json)));
                modelUsed = models.strong();
            } catch (Exception e) {
                log.warn("Escalation failed for {} ({}); keeping first-pass results", file, e.getMessage());
            }
        }

        List<EngineFinding> out = new ArrayList<>();
        for (Raw r : raws) {
            Candidate owner = owner(units, r.line());
            boolean confirmsHint = r.line() != null && hits.stream().anyMatch(h ->
                r.line() >= h.startLine() - HINT_MATCH_LINES && r.line() <= h.endLine() + HINT_MATCH_LINES);
            out.add(new EngineFinding(file, r.line(), r.severity(), r.vulnType(), r.cwe(), r.description(), r.fix(),
                r.confidence(), lineText(owner.unit(), r.line()), modelUsed,
                confirmsHint ? EngineFinding.Source.STATIC_LLM : EngineFinding.Source.LLM,
                owner.signals().getOrDefault("ml", 0.5), owner.signals()));
        }
        return out;
    }

    List<EngineFinding> staticOnly(String file, List<Candidate> units, List<StaticHit> hits) {
        List<EngineFinding> out = new ArrayList<>();
        for (StaticHit h : hits) {
            Candidate owner = owner(units, h.startLine());
            String severity = switch (h.severity()) {
                case ERROR -> "HIGH";
                case WARNING -> "MEDIUM";
                case INFO -> "LOW";
            };
            out.add(new EngineFinding(file, h.startLine(), severity, h.ruleId(), h.cwe(), h.message(), null,
                STATIC_ONLY_CONFIDENCE, lineText(owner.unit(), h.startLine()), "semgrep",
                EngineFinding.Source.STATIC, owner.signals().getOrDefault("ml", 0.5), owner.signals()));
        }
        return out;
    }

    static List<Raw> parse(String json) {
        try {
            String text = json.strip();
            if (text.startsWith("```")) text = text.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "");
            JsonNode root = JSON.readTree(text);
            JsonNode arr = root.isArray() ? root : root.path("findings");
            List<Raw> out = new ArrayList<>();
            for (JsonNode n : arr) {
                JsonNode line = n.path("line_number");
                out.add(new Raw(
                    line.isNumber() ? line.asInt() : null,
                    n.path("severity").asText("MEDIUM").toUpperCase(Locale.ROOT),
                    n.path("vuln_type").asText("Unknown"),
                    n.path("cwe_id").isMissingNode() || n.path("cwe_id").isNull() ? null : n.path("cwe_id").asText(),
                    n.path("description").asText("") + exploitability(n),
                    n.path("fix_suggestion").isMissingNode() || n.path("fix_suggestion").isNull() ? null : n.path("fix_suggestion").asText(),
                    Math.max(0, Math.min(1, n.path("confidence").asDouble(0.5)))));
            }
            return out;
        } catch (Exception e) {
            log.warn("Could not parse LLM response: {}", e.getMessage());
            return List.of();
        }
    }

    private static String exploitability(JsonNode n) {
        String e = n.path("exploitability").asText("");
        return e.isBlank() ? "" : "\n\nExploitability: " + e;
    }

    /** The unit containing {@code line}, or the nearest one when the model reports a line outside every unit. */
    static Candidate owner(List<Candidate> units, Integer line) {
        if (line == null) return units.get(0);
        Candidate best = units.get(0);
        int bestDistance = Integer.MAX_VALUE;
        for (Candidate c : units) {
            CodeUnit u = c.unit();
            int d = line < u.startLine() ? u.startLine() - line : line > u.endLine() ? line - u.endLine() : 0;
            if (d < bestDistance) {
                bestDistance = d;
                best = c;
            }
        }
        return best;
    }

    private static String lineText(CodeUnit u, Integer line) {
        if (line == null || line < u.startLine() || line > u.endLine()) return null;
        String[] lines = u.code().split("\n", -1);
        int idx = line - u.startLine();
        return idx < lines.length ? lines[idx].strip() : null;
    }
}
