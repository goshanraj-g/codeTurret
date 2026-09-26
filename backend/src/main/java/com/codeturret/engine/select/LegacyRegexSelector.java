package com.codeturret.engine.select;

import com.codeturret.config.GitProperties;
import com.codeturret.engine.model.*;
import com.codeturret.service.CodeExtractorService;
import com.codeturret.service.GitService;
import com.codeturret.service.RiskAssessorService;

import java.util.*;

/**
 * Reproduces the v1 pipeline's selection exactly: {@link GitService}'s extension filter, the
 * {@link RiskAssessorService} keyword score with its file cap, then {@link CodeExtractorService} snippets
 * (or the whole file when no pattern matches). Kept as the eval baseline.
 */
public final class LegacyRegexSelector implements CandidateSelector {

    private static final Set<String> V1_EXTENSIONS = Set.of(".py", ".js", ".ts", ".tsx", ".jsx");

    private final RiskAssessorService riskAssessor;
    private final CodeExtractorService extractor = new CodeExtractorService();

    public LegacyRegexSelector(GitProperties gitProperties) {
        this.riskAssessor = new RiskAssessorService(gitProperties);
    }

    @Override
    public String name() {
        return "legacy-regex";
    }

    @Override
    public List<Candidate> select(RepoSnapshot repo) {
        Map<String, SourceFile> byPath = new LinkedHashMap<>();
        Map<String, String> contents = new HashMap<>();
        List<GitService.FileEntry> entries = new ArrayList<>();
        for (SourceFile f : repo.files()) {
            if (!V1_EXTENSIONS.contains(extension(f.path()))) continue;
            byPath.put(f.path(), f);
            contents.put(f.path(), f.content());
            entries.add(new GitService.FileEntry(f.path(), repo.root().resolve(f.path())));
        }

        List<RiskAssessorService.ScoredFile> ranked = riskAssessor.prioritize(
            entries, contents, repo.git().changeCounts(), repo.git().securityCommits());

        List<Candidate> out = new ArrayList<>();
        int rank = 0;
        for (RiskAssessorService.ScoredFile sf : ranked) {
            SourceFile file = byPath.get(sf.relativePath());
            List<CodeExtractorService.Snippet> snippets = extractor.extractSnippets(file.content(), file.path());
            // Score decreases with rank so that ordering is preserved for budgeted evaluation.
            double score = 1.0 / (1 + rank++);
            if (snippets.isEmpty()) {
                out.add(new Candidate(wholeFile(file), score, Map.of("fileRisk", (double) sf.riskScore())));
                continue;
            }
            for (CodeExtractorService.Snippet s : snippets) {
                CodeUnit unit = new CodeUnit(file.path(), file.language(), s.name(), CodeUnit.Kind.SNIPPET,
                    s.startLine(), Math.max(s.startLine(), s.endLine()), s.code(), List.of(), false);
                out.add(new Candidate(unit, score, Map.of("fileRisk", (double) sf.riskScore())));
            }
        }
        return out;
    }

    private static CodeUnit wholeFile(SourceFile f) {
        return new CodeUnit(f.path(), f.language(), "<file>", CodeUnit.Kind.MODULE,
            1, Math.max(1, f.lineCount()), f.content(), List.of(), false);
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot) : "";
    }
}
