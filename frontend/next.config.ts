import type { NextConfig } from "next";
import { withSentryConfig } from "@sentry/nextjs/config";

const nextConfig: NextConfig = {
  /* config options here */
};

// Source maps are uploaded only when SENTRY_AUTH_TOKEN is set. The plugin reads it from the environment or from the
// gitignored .env.sentry-build-plugin.
export default withSentryConfig(nextConfig, {
  org: "polar-he",
  project: process.env.SENTRY_PROJECT ?? "codeturret-frontend",
  // Route browser events through our own server, so ad blockers don't drop them.
  tunnelRoute: "/monitoring",
  widenClientFileUpload: true,
  silent: !process.env.CI,
  telemetry: false,
});
