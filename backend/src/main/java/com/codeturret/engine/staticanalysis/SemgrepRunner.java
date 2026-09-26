package com.codeturret.engine.staticanalysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the Semgrep CLI over a checked-out repo and maps its JSON output to {@link StaticHit}s.
 *
 * <p>Optional by design: if Semgrep is missing, fails, or times out, {@link #scan} returns an empty list and the
 * engine continues without the static signal. Semgrep runs with the repo as its working directory and target
 * {@code .}, so reported paths are already repo-relative. That also makes routing through WSL on Windows work
 * unchanged ({@code SEMGREP_CMD="wsl.exe -e /home/me/.venvs/semgrep/bin/semgrep"}), because {@code wsl.exe}
 * translates the working directory itself.
 */
public final class SemgrepRunner implements StaticAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SemgrepRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Config(List<String> command, List<String> rulesets, Duration timeout) {

        public static Config defaults() {
            return new Config(List.of("semgrep"),
                List.of("p/security-audit", "p/owasp-top-ten", "p/python", "p/javascript", "p/typescript", "p/java"),
                Duration.ofMinutes(5));
        }

        /** {@code SEMGREP_CMD} overrides the command (space-separated). */
        public static Config fromEnv() {
            Config d = defaults();
            String cmd = System.getenv("SEMGREP_CMD");
            return cmd == null || cmd.isBlank() ? d : new Config(List.of(cmd.strip().split(" +")), d.rulesets(), d.timeout());
        }
    }

    private final Config config;

    public SemgrepRunner(Config config) {
        this.config = config;
    }

    /** True if the configured command answers {@code --version} within a minute. */
    public boolean isAvailable() {
        List<String> cmd = new ArrayList<>(config.command());
        cmd.add("--version");
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public List<StaticHit> scan(Path repoDir) {
        List<String> cmd = new ArrayList<>(config.command());
        cmd.addAll(List.of("scan", "--json", "--metrics=off", "--quiet", "--disable-version-check",
            "--timeout", "30", "--max-target-bytes", "500000"));
        for (String r : config.rulesets()) cmd.addAll(List.of("--config", r));
        cmd.add(".");

        Path out = null;
        try {
            out = Files.createTempFile("semgrep", ".json");
            Process p = new ProcessBuilder(cmd)
                .directory(repoDir.toFile())
                .redirectOutput(out.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            if (!p.waitFor(config.timeout().toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("Semgrep timed out after {}; continuing without static analysis", config.timeout());
                return List.of();
            }
            // Semgrep's exit code depends on flags and findings; only the JSON is trusted.
            List<StaticHit> hits = parse(Files.readString(out, StandardCharsets.UTF_8));
            log.info("Semgrep reported {} hits", hits.size());
            return hits;
        } catch (IOException e) {
            log.warn("Semgrep unavailable ({}); continuing without static analysis", e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            if (out != null) {
                try { Files.deleteIfExists(out); } catch (IOException ignored) { }
            }
        }
    }

    /** Parses Semgrep's {@code --json} output produced with the repo as working directory. */
    public List<StaticHit> parse(String json) throws IOException {
        if (json.isBlank()) return List.of();
        List<StaticHit> hits = new ArrayList<>();
        for (JsonNode r : JSON.readTree(json).path("results")) {
            String path = r.path("path").asText().replace('\\', '/');
            if (path.startsWith("./")) path = path.substring(2);
            JsonNode extra = r.path("extra");
            hits.add(new StaticHit(
                path,
                r.path("start").path("line").asInt(),
                r.path("end").path("line").asInt(),
                r.path("check_id").asText(),
                extra.path("message").asText(),
                StaticHit.Severity.parse(extra.path("severity").asText()),
                firstCwe(extra.path("metadata").path("cwe"))));
        }
        return hits;
    }

    /** {@code ["CWE-89: Improper ..."]} or {@code "CWE-78"} become {@code CWE-89} / {@code CWE-78}. */
    private static String firstCwe(JsonNode node) {
        String cwe = node.isArray() && !node.isEmpty() ? node.get(0).asText() : node.isTextual() ? node.asText() : null;
        if (cwe == null) return null;
        int colon = cwe.indexOf(':');
        return (colon > 0 ? cwe.substring(0, colon) : cwe).strip();
    }
}
