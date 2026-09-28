package com.codeturret.observability;

import com.codeturret.engine.verify.VerificationHealth;
import com.codeturret.engine.verify.VerificationHealth.Problem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScanHealthReporterTest {

    private static VerificationHealth health(Problem... problems) {
        VerificationHealth h = new VerificationHealth();
        for (Problem p : problems) h.record(p);
        return h;
    }

    @Test
    void healthyScanRaisesNothing() {
        assertThat(ScanHealthReporter.alerts(10, health())).isEmpty();
        assertThat(ScanHealthReporter.alerts(0, health())).isEmpty();
    }

    @Test
    void aFewFailedFilesAreTolerated() {
        assertThat(ScanHealthReporter.alerts(10, health(Problem.LLM_CALL_FAILED, Problem.TASK_FAILED))).isEmpty();
    }

    @Test
    void degradedScanIsGroupedByItsMainCause() {
        var alerts = ScanHealthReporter.alerts(10,
            health(Problem.LLM_CALL_FAILED, Problem.LLM_CALL_FAILED, Problem.LLM_CALL_FAILED));

        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.message()).startsWith("Scan degraded: 3 of 10 files");
            assertThat(a.fingerprint()).containsExactly("scan-degraded", "LLM_CALL_FAILED");
        });
    }

    @Test
    void anyUnreadableResponseRaisesItsOwnAlert() {
        var alerts = ScanHealthReporter.alerts(10, health(Problem.ESCALATION_UNPARSEABLE));

        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.message()).isEqualTo("LLM returned 1 unreadable response(s)");
            assertThat(a.fingerprint()).containsExactly("llm-unparseable");
        });
    }

    @Test
    void unreadableFirstPassesCanRaiseBothAlerts() {
        var alerts = ScanHealthReporter.alerts(2, health(Problem.LLM_UNPARSEABLE, Problem.LLM_UNPARSEABLE));

        assertThat(alerts).extracting(a -> a.fingerprint().get(0)).containsExactly("scan-degraded", "llm-unparseable");
    }
}
