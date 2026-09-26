package com.codeturret.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Characterization tests for the legacy regex snippet extractor. */
class CodeExtractorServiceTest {

    private final CodeExtractorService extractor = new CodeExtractorService();

    @Test
    void extractsFirstJsFunctionContainingSecurityPattern() {
        String js = """
            function run(req) {
              return eval(req.body.code);
            }
            """;
        List<CodeExtractorService.Snippet> snippets = extractor.extractSnippets(js, "app.js");

        assertThat(snippets).extracting(CodeExtractorService.Snippet::name).containsExactly("function: run");
    }

    @Test
    void knownBug_dropsJsFunctionsAfterTheFirst() {
        // The function regex starts its match at the preceding '\n', so startLine lands on the previous
        // function's closing brace, brace counting ends immediately, and the real body is never inspected.
        // Everything then falls back to line windows. The tree-sitter parser replaces this.
        String js = """
            function safe(a) {
              return a + 1;
            }
            function run(req) {
              return eval(req.body.code);
            }
            """;
        List<CodeExtractorService.Snippet> snippets = extractor.extractSnippets(js, "app.js");

        assertThat(snippets).extracting(CodeExtractorService.Snippet::name).containsExactly("lines 1-7");
    }

    @Test
    void pythonFallsBackToLineWindows() {
        String py = "import os\n\ndef f(x):\n    os.system(x)\n";
        List<CodeExtractorService.Snippet> snippets = extractor.extractSnippets(py, "a.py");

        assertThat(snippets).hasSize(1);
        assertThat(snippets.get(0).name()).startsWith("lines ");
    }

    @Test
    void returnsNothingWhenNoPatternMatches() {
        // The caller (ScanWorker) then sends the whole file to the LLM.
        String py = "def load(name):\n    return send_file(BASE + '/' + name)\n";
        assertThat(extractor.extractSnippets(py, "files.py")).isEmpty();
    }
}
