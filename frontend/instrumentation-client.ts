import * as Sentry from "@sentry/nextjs";
import { sentryOptions } from "@/lib/sentry";

// No Session Replay: pages show scanned code, and a recording would ship it to Sentry.
Sentry.init(sentryOptions);

export const onRouterTransitionStart = Sentry.captureRouterTransitionStart;
