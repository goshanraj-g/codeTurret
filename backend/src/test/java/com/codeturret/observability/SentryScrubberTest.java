package com.codeturret.observability;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SentryScrubberTest {

    private final SentryScrubber scrubber = new SentryScrubber();

    @Test
    void redactsKnownCredentialShapes() {
        assertThat(SentryScrubber.scrub("push failed for ghp_abcdefghijklmnopqrstuvwxyz0123456789"))
            .isEqualTo("push failed for [redacted]");
        assertThat(SentryScrubber.scrub("pat github_pat_11ABCDEFG0123456789_abcdefghij")).isEqualTo("pat [redacted]");
        assertThat(SentryScrubber.scrub("401 for sk-proj-abcdefghijklmnopqrstuvwx")).isEqualTo("401 for [redacted]");
        assertThat(SentryScrubber.scrub("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload"))
            .isEqualTo("Authorization: Bearer [redacted]");
        assertThat(SentryScrubber.scrub("GET /v1beta/models/x:generate?key=AIzaSyA-secret&alt=json"))
            .isEqualTo("GET /v1beta/models/x:generate?key=[redacted]&alt=json");
        assertThat(SentryScrubber.scrub("clone https://user:hunter2@github.com/o/r.git failed"))
            .isEqualTo("clone https://[redacted]@github.com/o/r.git failed");
    }

    @Test
    void leavesOrdinaryTextAloneAndTruncatesLongText() {
        assertThat(SentryScrubber.scrub("Scan 42 failed: connection refused")).isEqualTo("Scan 42 failed: connection refused");
        assertThat(SentryScrubber.scrub("x".repeat(500)))
            .hasSize(SentryScrubber.MAX_LENGTH + "…[truncated]".length())
            .endsWith("…[truncated]");
        assertThat(SentryScrubber.scrub(null)).isNull();
    }

    @Test
    void scrubsEventMessageParamsExceptionsAndBreadcrumbs() {
        SentryEvent event = new SentryEvent();
        Message m = new Message();
        m.setMessage("Failed to push fix: {}");
        m.setFormatted("Failed to push fix: token ghs_abcdefghijklmnopqrstuvwxyz");
        m.setParams(List.of("token ghs_abcdefghijklmnopqrstuvwxyz"));
        event.setMessage(m);
        SentryException ex = new SentryException();
        ex.setValue("LLM call failed: sk-abcdefghijklmnopqrstuvwxyz");
        event.setExceptions(List.of(ex));

        scrubber.execute(event, new Hint());

        assertThat(m.getMessage()).isEqualTo("Failed to push fix: {}");
        assertThat(m.getFormatted()).doesNotContain("ghs_").contains(SentryScrubber.REDACTED);
        assertThat(m.getParams()).singleElement().asString().doesNotContain("ghs_");
        assertThat(ex.getValue()).isEqualTo("LLM call failed: [redacted]");

        Breadcrumb crumb = new Breadcrumb("Could not parse LLM response: " + "def handler(req): ".repeat(40));
        assertThat(scrubber.execute(crumb, new Hint()).getMessage()).endsWith("…[truncated]");
    }
}
