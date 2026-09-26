package com.codeturret.engine.staticanalysis;

import com.codeturret.engine.model.CodeUnit;
import com.codeturret.engine.model.Language;
import com.codeturret.engine.signals.StaticSignal;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemgrepRunnerTest {

    private static final String OUTPUT = """
        {"results": [
          {"check_id": "python.lang.security.audit.formatted-sql-query",
           "path": "./shop/reports.py",
           "start": {"line": 17, "col": 9}, "end": {"line": 19, "col": 10},
           "extra": {"message": "Detected possible formatted SQL query",
                     "severity": "WARNING",
                     "metadata": {"cwe": ["CWE-89: Improper Neutralization of Special Elements used in an SQL Command"]}}},
          {"check_id": "javascript.lang.security.audit.code-string-concat",
           "path": "web/routes/admin.js",
           "start": {"line": 10}, "end": {"line": 10},
           "extra": {"message": "exec with concatenation", "severity": "ERROR", "metadata": {"cwe": "CWE-78"}}}
        ],
        "errors": []}
        """;

    @Test
    void parsesResultsIntoRepoRelativeHits() throws Exception {
        SemgrepRunner runner = new SemgrepRunner(SemgrepRunner.Config.defaults());
        List<StaticHit> hits = runner.parse(OUTPUT);

        assertThat(hits).hasSize(2);
        StaticHit sql = hits.get(0);
        assertThat(sql.file()).isEqualTo("shop/reports.py");
        assertThat(sql.startLine()).isEqualTo(17);
        assertThat(sql.endLine()).isEqualTo(19);
        assertThat(sql.severity()).isEqualTo(StaticHit.Severity.WARNING);
        assertThat(sql.cwe()).isEqualTo("CWE-89");
        assertThat(hits.get(1).file()).isEqualTo("web/routes/admin.js");
        assertThat(hits.get(1).cwe()).isEqualTo("CWE-78");
    }

    @Test
    void missingBinaryDegradesToNoHits() {
        var config = new SemgrepRunner.Config(List.of("definitely-not-semgrep-xyz"), List.of("p/python"), Duration.ofSeconds(5));
        assertThat(new SemgrepRunner(config).scan(Path.of("."))).isEmpty();
    }

    @Test
    void staticSignalScoresUnitsByMostSevereHitInside() {
        StaticSignal signal = new StaticSignal(List.of(
            new StaticHit("a.py", 5, 5, "r1", "", StaticHit.Severity.INFO, null),
            new StaticHit("a.py", 7, 8, "r2", "", StaticHit.Severity.ERROR, null)));

        assertThat(signal.score(unit("a.py", 1, 6))).isEqualTo(0.4);
        assertThat(signal.score(unit("a.py", 1, 10))).isEqualTo(1.0);
        assertThat(signal.score(unit("a.py", 20, 30))).isZero();
        assertThat(signal.score(unit("b.py", 1, 10))).isZero();
    }

    private static CodeUnit unit(String file, int start, int end) {
        return new CodeUnit(file, Language.PYTHON, "f", CodeUnit.Kind.FUNCTION, start, end, "", List.of(), false);
    }
}
