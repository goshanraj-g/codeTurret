package com.codeturret.engine.verify;

import com.codeturret.engine.model.Candidate;
import com.codeturret.engine.model.Language;
import com.codeturret.engine.model.SourceFile;
import com.codeturret.engine.parse.CodeIndex;
import com.codeturret.engine.parse.CodeParser;
import com.codeturret.engine.staticanalysis.StaticHit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmVerifierTest {

    private static final String CODE = """
        const { exec } = require("child_process");

        exports.backup = (req, res) => {
          const label = req.body.label;
          exec("tar czf /backups/" + label + ".tgz data", () => res.json({ ok: true }));
        };
        """;

    private final CodeIndex index = CodeIndex.fromParsed(List.of(
        new CodeParser().parse(new SourceFile("admin.js", Language.JAVASCRIPT, CODE)),
        new CodeParser().parse(new SourceFile("server.js", Language.JAVASCRIPT, "app.post(\"/b\", admin.backup);\n"))));

    private final List<Candidate> units = index.units().stream()
        .filter(u -> u.file().equals("admin.js"))
        .map(u -> new Candidate(u, 0.9, Map.of("ml", 0.81, "reachability", 1.0)))
        .toList();

    private final StaticHit hit = new StaticHit("admin.js", 5, 5, "detect-child-process", "exec with input",
        StaticHit.Severity.ERROR, "CWE-78");

    private final LlmVerifier.Models models = new LlmVerifier.Models("fast", "strong", 0.7);

    @Test
    void promptCarriesLineNumbersEntrypointContextAndHints() {
        String prompt = VerificationPrompt.triage("admin.js", units, index, List.of(hit), "Express app");
        assertThat(prompt)
            .contains("File: admin.js")
            .contains("route handler (receives request data directly)")
            .contains("Static hint: rule detect-child-process at line 5 (CWE-78)")
            .contains("5|   exec(\"tar czf")
            .contains("rejected_hints");
    }

    @Test
    void confirmedHintIsAttributedToStaticPlusLlmAndEscalatesHighSeverity() {
        List<String> calledModels = new ArrayList<>();
        LlmClient fake = (model, prompt) -> {
            calledModels.add(model);
            return """
                {"findings": [{"line_number": 5, "severity": "HIGH", "vuln_type": "Command Injection",
                  "cwe_id": "CWE-78", "description": "label flows into exec", "fix_suggestion": "use execFile",
                  "exploitability": "POST /b with label=;rm -rf /", "confidence": 0.93}]}
                """;
        };

        List<EngineFinding> out = new LlmVerifier(fake, models).verifyFile("admin.js", units, index, List.of(hit), "", false);

        assertThat(calledModels).containsExactly("fast", "strong");
        assertThat(out).hasSize(1);
        EngineFinding f = out.get(0);
        assertThat(f.source()).isEqualTo(EngineFinding.Source.STATIC_LLM);
        assertThat(f.modelUsed()).isEqualTo("strong");
        assertThat(f.mlScore()).isEqualTo(0.81);
        assertThat(f.codeSnippet()).startsWith("exec(");
        assertThat(f.description()).contains("Exploitability: POST /b");
    }

    @Test
    void findingWithoutMatchingHintIsLlmSourcedAndLowSeverityIsNotEscalated() {
        List<String> calledModels = new ArrayList<>();
        LlmClient fake = (model, prompt) -> {
            calledModels.add(model);
            return "```json\n{\"findings\": [{\"line_number\": 4, \"severity\": \"LOW\", \"vuln_type\": \"Missing validation\", \"confidence\": 0.9}]}\n```";
        };

        List<EngineFinding> out = new LlmVerifier(fake, models).verifyFile("admin.js", units, index, List.of(), "", false);

        assertThat(calledModels).containsExactly("fast");
        assertThat(out.get(0).source()).isEqualTo(EngineFinding.Source.LLM);
    }

    @Test
    void llmFailureFallsBackToStaticHits() {
        LlmClient broken = (model, prompt) -> { throw new RuntimeException("429"); };

        List<EngineFinding> out = new LlmVerifier(broken, models).verifyFile("admin.js", units, index, List.of(hit), "", false);

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.source()).isEqualTo(EngineFinding.Source.STATIC);
            assertThat(f.severity()).isEqualTo("HIGH");
            assertThat(f.confidence()).isEqualTo(LlmVerifier.STATIC_ONLY_CONFIDENCE);
        });
    }

    @Test
    void unparseableResponseYieldsNoFindings() {
        LlmClient garbage = (model, prompt) -> "not json";
        assertThat(new LlmVerifier(garbage, models).verifyFile("admin.js", units, index, List.of(), "", false)).isEmpty();
    }
}
