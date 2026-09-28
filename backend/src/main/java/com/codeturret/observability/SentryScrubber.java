package com.codeturret.observability;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes secrets and scanned code from everything sent to Sentry. The Sentry starter picks this bean up as both
 * the before-send and before-breadcrumb callback.
 *
 * <p>Two rules: known credential shapes (GitHub tokens, OpenAI and Google keys, bearer tokens, URL credentials,
 * {@code ?key=} query parameters) are replaced with {@code [redacted]}, and long text is cut short. Error messages
 * from the LLM path can echo model output, which quotes the customer's code; a short prefix is enough to tell
 * what went wrong without shipping the code itself.
 */
@Component
public class SentryScrubber implements SentryOptions.BeforeSendCallback, SentryOptions.BeforeBreadcrumbCallback {

    static final int MAX_LENGTH = 200;
    static final String REDACTED = "[redacted]";

    private static final List<Pattern> SECRETS = List.of(
        Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}"),
        Pattern.compile("github_pat_[A-Za-z0-9_]{20,}"),
        Pattern.compile("sk-[A-Za-z0-9_-]{20,}"),
        Pattern.compile("AIza[0-9A-Za-z_-]{30,}"),
        Pattern.compile("(?i)(?<=bearer |token )[A-Za-z0-9._~+/=-]{16,}"),
        Pattern.compile("(?i)(?<=[?&](?:key|api_key|access_token|token)=)[^&\\s\"']+"),
        Pattern.compile("(?<=://)[^/\\s:@]+(?::[^/\\s@]*)?(?=@)"));

    public static String scrub(String text) {
        if (text == null) return null;
        String out = text;
        for (Pattern p : SECRETS) out = p.matcher(out).replaceAll(REDACTED);
        return out.length() > MAX_LENGTH ? out.substring(0, MAX_LENGTH) + "…[truncated]" : out;
    }

    @Override
    public SentryEvent execute(SentryEvent event, Hint hint) {
        Message m = event.getMessage();
        if (m != null) {
            m.setFormatted(scrub(m.getFormatted()));
            if (m.getParams() != null) m.setParams(m.getParams().stream().map(SentryScrubber::scrub).toList());
        }
        if (event.getExceptions() != null) {
            for (SentryException e : event.getExceptions()) e.setValue(scrub(e.getValue()));
        }
        return event;
    }

    @Override
    public Breadcrumb execute(Breadcrumb breadcrumb, Hint hint) {
        breadcrumb.setMessage(scrub(breadcrumb.getMessage()));
        return breadcrumb;
    }
}
