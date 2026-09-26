package com.codeturret.engine.ml;

import ai.onnxruntime.*;
import com.codeturret.engine.model.CodeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.*;

/**
 * Scores code units with the ONNX model trained in {@code ml/}: {@code P(vulnerable | unit code)}.
 *
 * <p>The model is a ranking signal, not a verdict (see docs/architecture/detection-engine.md, section 3.3).
 * If the model can't be loaded, every unit gets {@link #NEUTRAL} so the other signals decide.
 */
public final class VulnClassifier implements AutoCloseable {

    public static final double NEUTRAL = 0.5;
    public static final String DEFAULT_MODEL = "/ml/vuln-classifier.onnx";
    private static final int BATCH = 32;
    private static final Logger log = LoggerFactory.getLogger(VulnClassifier.class);

    private final OrtEnvironment env;
    private final OrtSession session;

    private VulnClassifier(OrtEnvironment env, OrtSession session) {
        this.env = env;
        this.session = session;
    }

    /** Loads the bundled model; never throws. */
    public static VulnClassifier load() {
        return load(DEFAULT_MODEL);
    }

    public static VulnClassifier load(String classpathResource) {
        try (InputStream in = VulnClassifier.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                log.warn("Classifier model {} not found; ML signal disabled", classpathResource);
                return new VulnClassifier(null, null);
            }
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            return new VulnClassifier(env, env.createSession(in.readAllBytes(), new OrtSession.SessionOptions()));
        } catch (IOException | OrtException | UnsatisfiedLinkError e) {
            log.warn("Could not load classifier model ({}); ML signal disabled", e.getMessage());
            return new VulnClassifier(null, null);
        }
    }

    public boolean isLoaded() {
        return session != null;
    }

    public Map<String, Double> score(List<CodeUnit> units) {
        Map<String, Double> out = new HashMap<>();
        if (session == null) {
            for (CodeUnit u : units) out.put(u.id(), NEUTRAL);
            return out;
        }
        for (int from = 0; from < units.size(); from += BATCH) {
            List<CodeUnit> batch = units.subList(from, Math.min(units.size(), from + BATCH));
            double[] p = predict(batch.stream().map(CodeUnit::code).toList());
            for (int i = 0; i < batch.size(); i++) out.put(batch.get(i).id(), p[i]);
        }
        return out;
    }

    /** Probability of the "vulnerable" class for each code string. */
    public double[] predict(List<String> codes) {
        double[] result = new double[codes.size()];
        if (session == null) {
            Arrays.fill(result, NEUTRAL);
            return result;
        }
        float[] input = new float[codes.size() * CodeFeatures.DIM];
        for (int row = 0; row < codes.size(); row++) {
            int offset = row * CodeFeatures.DIM;
            for (var e : CodeFeatures.vectorize(codes.get(row)).entrySet()) {
                input[offset + e.getKey()] = e.getValue().floatValue();
            }
        }
        long[] shape = {codes.size(), CodeFeatures.DIM};
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);
             OrtSession.Result r = session.run(Map.of("features", tensor))) {
            float[][] probs = (float[][]) r.get("probabilities").orElseThrow().getValue();
            for (int i = 0; i < probs.length; i++) result[i] = probs[i][1];
        } catch (OrtException e) {
            log.warn("Classifier inference failed ({}); using neutral scores", e.getMessage());
            Arrays.fill(result, NEUTRAL);
        }
        return result;
    }

    @Override
    public void close() {
        if (session != null) {
            try { session.close(); } catch (OrtException ignored) { }
        }
    }
}
