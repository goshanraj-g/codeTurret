package com.codeturret.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Loads benchmark definitions and materializes their source trees (cloning git sources into a cache). */
public final class BenchmarkWorkspace {

    private final Path repoRoot;
    private final Path cacheDir;
    private final ObjectMapper json = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public BenchmarkWorkspace(Path repoRoot) {
        this.repoRoot = repoRoot;
        this.cacheDir = repoRoot.resolve("eval/.cache");
    }

    /** Walks up from the working directory to the directory containing {@code eval/benchmarks}. */
    public static Path findRepoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("eval/benchmarks"))) p = p.getParent();
        if (p == null) throw new IllegalStateException("Could not find eval/benchmarks above the working directory");
        return p;
    }

    public List<Benchmark> loadAll() throws IOException {
        List<Benchmark> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(repoRoot.resolve("eval/benchmarks"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
                out.add(json.readValue(f.toFile(), Benchmark.class));
            }
        }
        return out;
    }

    /** Returns a directory containing the benchmark's source at the pinned revision. */
    public Path materialize(Benchmark b) throws IOException, InterruptedException {
        Benchmark.Source s = b.source();
        if ("fixture".equals(s.type())) return repoRoot.resolve(s.path());
        if (!"git".equals(s.type())) throw new IllegalArgumentException("Unknown source type: " + s.type());

        Path dir = cacheDir.resolve(b.name());
        if (!Files.isDirectory(dir.resolve(".git"))) {
            Files.createDirectories(cacheDir);
            run(cacheDir, "git", "clone", "--quiet", s.url(), dir.getFileName().toString());
        }
        run(dir, "git", "checkout", "--quiet", s.commit());
        return dir;
    }

    public boolean hasOwnGitHistory(Path dir) {
        return Files.isDirectory(dir.resolve(".git"));
    }

    private static void run(Path cwd, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).inheritIO().start();
        if (p.waitFor() != 0) throw new IOException("Command failed: " + String.join(" ", cmd));
    }
}
