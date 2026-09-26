package com.codeturret.engine.parse;

import com.codeturret.engine.model.CodeUnit;
import com.codeturret.engine.model.Language;
import com.codeturret.engine.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeIndexTest {

    private static SourceFile js(String path, String content) {
        return new SourceFile(path, Language.JAVASCRIPT, content);
    }

    @Test
    void handlersReferencedInOtherFilesBecomeEntrypointsAndHopsPropagate() {
        CodeIndex index = CodeIndex.build(new CodeParser(), List.of(
            js("server.js", "app.get(\"/search\", search.results);\n"),
            js("routes/search.js", """
                exports.results = async (req, res) => {
                  const items = await find(req.query.q);
                  res.json(items);
                };
                """),
            js("lib/catalog.js", """
                async function find(term) {
                  return buildQuery(term);
                }
                function buildQuery(term) {
                  return db.query("SELECT 1 WHERE name = '" + term + "'");
                }
                function unused() {
                  return 1;
                }
                """)
        ));

        CodeUnit results = byName(index, "results");
        CodeUnit find = byName(index, "find");
        CodeUnit buildQuery = byName(index, "buildQuery");
        CodeUnit unused = byName(index, "unused");

        assertThat(results.entrypoint()).isTrue();
        assertThat(index.hopsFromEntrypoint(results)).isZero();
        assertThat(index.hopsFromEntrypoint(find)).isEqualTo(1);
        assertThat(index.hopsFromEntrypoint(buildQuery)).isEqualTo(2);
        assertThat(index.hopsFromEntrypoint(unused)).isEqualTo(CodeIndex.UNREACHABLE);
        assertThat(index.callers(buildQuery)).extracting(CodeUnit::name).containsExactly("find");
    }

    private static CodeUnit byName(CodeIndex index, String name) {
        return index.units().stream().filter(u -> u.name().equals(name)).findFirst().orElseThrow();
    }
}
