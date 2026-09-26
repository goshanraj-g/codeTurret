package com.codeturret.eval;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.codeturret.engine.SourceLoader;
import com.codeturret.engine.model.CodeUnit;
import com.codeturret.engine.model.SourceFile;
import com.codeturret.engine.parse.CodeIndex;
import com.codeturret.engine.parse.CodeParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Exports code units from the benign corpus ({@code ml/benign_corpus.json}) to {@code ml/.data/benign_units.jsonl},
 * using the same parser the engine uses at scan time.
 *
 * <pre>
 * cd backend
 * ./mvnw -q compile exec:java -Dexec.mainClass=com.codeturret.eval.UnitExporter
 * </pre>
 */
public final class UnitExporter {

    private static final int MAX_UNITS_PER_REPO = 2500;
    private static final int MAX_UNIT_LINES = 200;
    private static final Pattern NON_PRODUCTION = Pattern.compile(
        "(^|/)(tests?|__tests__|spec|benchmarks?|docs?|fixtures?)/|/src/test/|\\.(test|spec)\\.[jt]sx?$");

    public static void main(String[] args) throws Exception {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.WARN);
        Path root = BenchmarkWorkspace.findRepoRoot();
        ObjectMapper json = new ObjectMapper();
        JsonNode corpus = json.readTree(root.resolve("ml/benign_corpus.json").toFile());
        BenchmarkWorkspace workspace = new BenchmarkWorkspace(root);

        Path out = root.resolve("ml/.data/benign_units.jsonl");
        Files.createDirectories(out.getParent());
        CodeParser parser = new CodeParser();
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (JsonNode repo : corpus.get("repos")) {
                String name = repo.get("name").asText();
                Benchmark asBenchmark = new Benchmark("corpus-" + name, "", new Benchmark.Source(
                    "git", null, repo.get("url").asText(), repo.get("commit").asText()), List.of());
                Path dir = workspace.materialize(asBenchmark);

                List<SourceFile> files = new SourceLoader(200_000).load(dir).stream()
                    .filter(f -> !NON_PRODUCTION.matcher(f.path()).find())
                    .toList();
                List<CodeUnit> units = new ArrayList<>(CodeIndex.build(parser, files).units().stream()
                    .filter(u -> u.lineCount() <= MAX_UNIT_LINES && u.lineCount() >= 2)
                    .toList());
                Collections.shuffle(units, new Random(name.hashCode()));
                units = units.subList(0, Math.min(MAX_UNITS_PER_REPO, units.size()));

                for (CodeUnit u : units) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("repo", name);
                    row.put("split", repo.get("split").asText());
                    row.put("language", u.language().name());
                    row.put("file", u.file());
                    row.put("name", u.name());
                    row.put("code", u.code());
                    w.write(json.writeValueAsString(row));
                    w.newLine();
                }
                System.out.printf("%-22s %5d files  %5d units%n", name, files.size(), units.size());
            }
        }
        System.out.println("Wrote " + root.relativize(out));
    }
}
