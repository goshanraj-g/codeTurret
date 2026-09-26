package com.codeturret.engine.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Parity with the Python feature spec. Golden vectors come from {@code python -m codeturret_ml.golden}.
 * If this fails after a Python change, mirror the change in {@link CodeFeatures}.
 */
class CodeFeaturesTest {

    @Test
    void matchesPythonGoldenVectors() throws Exception {
        JsonNode golden;
        try (InputStream in = getClass().getResourceAsStream("/ml/golden-features.json")) {
            golden = new ObjectMapper().readTree(in);
        }
        assertThat(golden.size()).isGreaterThan(5);

        for (JsonNode sample : golden) {
            String code = sample.get("code").asText();

            List<String> expectedFeatures = new ArrayList<>();
            sample.get("features").forEach(f -> expectedFeatures.add(f.asText()));
            assertThat(CodeFeatures.features(code)).as("features of %s", code).isEqualTo(expectedFeatures);

            Map<Integer, Double> actual = CodeFeatures.vectorize(code);
            JsonNode expected = sample.get("vector");
            assertThat(actual.keySet()).as("buckets of %s", code).hasSize(expected.size());
            expected.fields().forEachRemaining(e ->
                assertThat(actual.get(Integer.parseInt(e.getKey()))).as("bucket %s of %s", e.getKey(), code)
                    .isCloseTo(e.getValue().asDouble(), within(1e-6)));
        }
    }

    @Test
    void fnv1aMatchesReferenceValues() {
        assertThat(CodeFeatures.fnv1a("")).isEqualTo(0x811C9DC5);
        assertThat(CodeFeatures.fnv1a("a")).isEqualTo(0xE40C292C);
        assertThat(CodeFeatures.fnv1a("foobar")).isEqualTo(0xBF9CF968);
    }

    @Test
    void bundledModelLoadsAndRanksAnObviousSinkAboveArithmetic() {
        try (VulnClassifier classifier = VulnClassifier.load()) {
            assertThat(classifier.isLoaded()).isTrue();
            double[] p = classifier.predict(List.of(
                "exports.run = (req, res) => { eval(req.body.code); }",
                "function add(a, b) { return a + b; }"));
            assertThat(p[0]).isGreaterThan(p[1]);
        }
    }

    @Test
    void missingModelFallsBackToNeutral() {
        try (VulnClassifier classifier = VulnClassifier.load("/ml/does-not-exist.onnx")) {
            assertThat(classifier.isLoaded()).isFalse();
            assertThat(classifier.predict(List.of("x"))[0]).isEqualTo(VulnClassifier.NEUTRAL);
        }
    }
}
