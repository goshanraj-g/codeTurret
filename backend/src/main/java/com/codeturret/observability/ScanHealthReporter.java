package com.codeturret.observability;

import com.codeturret.engine.verify.VerificationHealth;
import com.codeturret.engine.verify.VerificationHealth.Problem;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Turns the problems a scan absorbed into Sentry warnings. The engine degrades gracefully, so a scan whose LLM
 * calls all failed still ends COMPLETED; without this, an expired API key or a model that changed its output
 * format would go unnoticed.
 *
 * <p>Two alerts, each fingerprinted so repeats group into one Sentry issue:
 * <ul>
 *   <li><b>scan degraded</b>: at least {@value #DEGRADED_SHARE} of the verified files never got an LLM verdict.
 *       Grouped by the most common cause.</li>
 *   <li><b>unreadable LLM responses</b>: any response that wasn't the expected JSON, which usually means the model
 *       changed how it answers.</li>
 * </ul>
 */
@Component
@Slf4j
public class ScanHealthReporter {

    static final double DEGRADED_SHARE = 0.3;

    record Alert(String message, List<String> fingerprint) {}

    /** Called from the scan's Sentry scope, so alerts carry its tags. */
    public void report(int filesVerified, VerificationHealth health) {
        Map<Problem, Integer> counts = health.snapshot();
        for (Alert alert : alerts(filesVerified, health)) {
            log.warn("{} ({})", alert.message(), counts);
            Sentry.captureMessage(alert.message(), SentryLevel.WARNING, scope -> {
                scope.setFingerprint(alert.fingerprint());
                scope.setExtra("files_verified", String.valueOf(filesVerified));
                counts.forEach((p, n) -> scope.setExtra(p.name().toLowerCase(), String.valueOf(n)));
            });
        }
    }

    static List<Alert> alerts(int filesVerified, VerificationHealth health) {
        List<Alert> out = new ArrayList<>();
        int unverified = health.unverifiedFiles();
        if (filesVerified > 0 && unverified > 0 && (double) unverified / filesVerified >= DEGRADED_SHARE) {
            Problem cause = Stream.of(Problem.LLM_CALL_FAILED, Problem.LLM_UNPARSEABLE, Problem.TASK_FAILED)
                .max(Comparator.comparingInt(health::count)).orElseThrow();
            out.add(new Alert("Scan degraded: " + unverified + " of " + filesVerified
                + " files were not verified by the LLM (mostly " + cause + ")", List.of("scan-degraded", cause.name())));
        }
        if (health.unparseableResponses() > 0) {
            out.add(new Alert("LLM returned " + health.unparseableResponses() + " unreadable response(s)",
                List.of("llm-unparseable")));
        }
        return out;
    }
}
