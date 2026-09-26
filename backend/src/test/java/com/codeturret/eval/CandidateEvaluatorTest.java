package com.codeturret.eval;

import com.codeturret.engine.model.Candidate;
import com.codeturret.engine.model.CodeUnit;
import com.codeturret.engine.model.Language;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateEvaluatorTest {

    private final CandidateEvaluator evaluator = new CandidateEvaluator();

    private static Candidate unit(String file, int start, int end) {
        return new Candidate(new CodeUnit(file, Language.PYTHON, "f", CodeUnit.Kind.FUNCTION,
            start, end, "", List.of(), false), 1.0, Map.of());
    }

    private static Benchmark.Vuln vuln(String id, String file, int start, int end) {
        return new Benchmark.Vuln(id, file, start, end, "CWE-0", "");
    }

    @Test
    void coversVulnWhenAtLeastHalfItsLinesAreSent() {
        var vulns = List.of(vuln("v", "a.py", 10, 13));
        assertThat(evaluator.evaluate(List.of(unit("a.py", 12, 20)), vulns, CandidateEvaluator.UNLIMITED)
            .coveredVulnIds()).containsExactly("v");
        assertThat(evaluator.evaluate(List.of(unit("a.py", 13, 20)), vulns, CandidateEvaluator.UNLIMITED)
            .coveredVulnIds()).isEmpty();
    }

    @Test
    void overlappingUnitsArePaidForOnce() {
        var result = evaluator.evaluate(List.of(unit("a.py", 1, 10), unit("a.py", 5, 14)), List.of(),
            CandidateEvaluator.UNLIMITED);
        assertThat(result.linesSent()).isEqualTo(14);
        assertThat(result.unitsSent()).isEqualTo(2);
    }

    @Test
    void skipsCandidatesThatDoNotFitButKeepsGoing() {
        var vulns = List.of(vuln("small", "b.py", 1, 1));
        var result = evaluator.evaluate(List.of(unit("a.py", 1, 100), unit("b.py", 1, 5)), vulns, 50);
        assertThat(result.linesSent()).isEqualTo(5);
        assertThat(result.coveredVulnIds()).containsExactly("small");
    }
}
