import type { BrowserOptions, ErrorEvent, Breadcrumb } from "@sentry/nextjs";

// Shared Sentry settings for the browser, Node and edge runtimes. With no NEXT_PUBLIC_SENTRY_DSN, nothing is sent.
// Pages show scanned code and repos are registered with GitHub tokens, so the same rules as the backend's
// SentryScrubber apply: redact credential shapes and cut long text short.

const MAX_LENGTH = 200;

const SECRETS = [
  /gh[pousr]_[A-Za-z0-9]{20,}/g,
  /github_pat_[A-Za-z0-9_]{20,}/g,
  /sk-[A-Za-z0-9_-]{20,}/g,
  /AIza[0-9A-Za-z_-]{30,}/g,
  /(?<=bearer |token )[A-Za-z0-9._~+/=-]{16,}/gi,
  /(?<=[?&](?:key|api_key|access_token|token)=)[^&\s"']+/gi,
  /(?<=:\/\/)[^/\s:@]+(?::[^/\s@]*)?(?=@)/g,
];

export function scrub(text: string | undefined): string | undefined {
  if (!text) return text;
  let out = text;
  for (const pattern of SECRETS) out = out.replace(pattern, "[redacted]");
  return out.length > MAX_LENGTH ? out.slice(0, MAX_LENGTH) + "…[truncated]" : out;
}

function beforeSend(event: ErrorEvent): ErrorEvent {
  event.message = scrub(event.message);
  for (const ex of event.exception?.values ?? []) ex.value = scrub(ex.value);
  return event;
}

function beforeBreadcrumb(crumb: Breadcrumb): Breadcrumb | null {
  // console.log arguments can be whole findings objects; keep only the message text.
  if (crumb.category === "console") delete crumb.data;
  crumb.message = scrub(crumb.message);
  return crumb;
}

export const sentryOptions = {
  dsn: process.env.NEXT_PUBLIC_SENTRY_DSN,
  environment: process.env.NEXT_PUBLIC_SENTRY_ENVIRONMENT ?? process.env.NODE_ENV,
  // SDK v11 collects request/response bodies, headers and stack-frame variables by default. Bodies here carry
  // GitHub tokens (repo registration) and scanned code (findings), so collect none of it.
  dataCollection: {
    userInfo: false,
    cookies: false,
    httpHeaders: false,
    httpBodies: [],
    urlQueryParams: false,
    stackFrameVariables: false,
    databaseQueryData: false,
    genAI: { inputs: false, outputs: false },
  },
  tracesSampleRate: process.env.NODE_ENV === "development" ? 1.0 : 0.1,
  beforeSend,
  beforeBreadcrumb,
} satisfies BrowserOptions;
