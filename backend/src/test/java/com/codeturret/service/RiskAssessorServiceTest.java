package com.codeturret.service;

import com.codeturret.config.GitProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization tests for the legacy regex-based file prioritizer.
 * These pin down current behaviour so the new engine can be compared against it.
 */
class RiskAssessorServiceTest {

    private final RiskAssessorService assessor = new RiskAssessorService(new GitProperties());

    @Test
    void skipsLockfilesDocsAndVendoredCode() {
        assertThat(assessor.assessRisk("package-lock.json", "")).isZero();
        assertThat(assessor.assessRisk("README.md", "password")).isZero();
        assertThat(assessor.assessRisk("node_modules/x/index.js", "eval(")).isZero();
        assertThat(assessor.assessRisk("static/app.min.js", "")).isZero();
    }

    @Test
    void scoresSecuritySensitivePathsHigh() {
        assertThat(assessor.assessRisk("src/routes/users.js", "")).isEqualTo(3);
        assertThat(assessor.assessRisk(".env", "")).isEqualTo(3);
    }

    @Test
    void scoresByKeywordCount() {
        assertThat(assessor.assessRisk("lib/util.py", "x = 1")).isEqualTo(1);
        assertThat(assessor.assessRisk("lib/util.py", "token = get()")).isEqualTo(2);
        assertThat(assessor.assessRisk("lib/util.py", "password token secret")).isEqualTo(3);
    }

    @Test
    void keywordFreeVulnerableFileScoresLow() {
        // A path traversal with no keywords: the legacy scorer cannot tell it apart from a benign helper.
        String content = "def load(name):\n    return send_file(BASE + '/' + name)\n";
        assertThat(assessor.assessRisk("lib/files.py", content)).isEqualTo(1);
    }

    @Test
    void prioritizeAppliesGitBonusesAndCap() {
        GitProperties props = new GitProperties();
        props.setMaxScanFiles(2);
        RiskAssessorService capped = new RiskAssessorService(props);

        List<GitService.FileEntry> files = List.of(
            new GitService.FileEntry("a.py", Path.of("a.py")),
            new GitService.FileEntry("b.py", Path.of("b.py")),
            new GitService.FileEntry("c.py", Path.of("c.py"))
        );
        Map<String, String> contents = Map.of("a.py", "x", "b.py", "x", "c.py", "x");

        List<RiskAssessorService.ScoredFile> out = capped.prioritize(
            files, contents, Map.of("b.py", 5), Map.of("c.py", List.of("fix xss")));

        assertThat(out).extracting(RiskAssessorService.ScoredFile::relativePath).containsExactly("c.py", "b.py");
    }
}
