package com.codeturret.engine.ml;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java implementation of the feature spec in {@code ml/codeturret_ml/features.py}. Read that module's docstring
 * for the steps. {@code CodeFeaturesTest} checks this class against golden vectors produced by the Python code,
 * so any change here must be mirrored there (and vice versa).
 */
public final class CodeFeatures {

    public static final int DIM = 1 << 18;
    static final int MAX_TOKENS = 2000;

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("(^|[ \\t])(#|//)[^\\n]*", Pattern.MULTILINE);
    private static final Pattern TOKEN = Pattern.compile(
        "[A-Za-z_$][A-Za-z0-9_$]*|[0-9]+(?:\\.[0-9]+)?|[^ \\t\\r\\n\\f\\x0BA-Za-z0-9_$]");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern NUMBER = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern PARTS = Pattern.compile("[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|[0-9]+");

    private static final int FNV_OFFSET = 0x811C9DC5;
    private static final int FNV_PRIME = 0x01000193;

    private CodeFeatures() {}

    static String stripComments(String code) {
        String s = BLOCK_COMMENT.matcher(code).replaceAll(" ");
        return LINE_COMMENT.matcher(s).replaceAll("$1");
    }

    static List<String> rawTokens(String code) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(stripComments(code));
        while (m.find() && out.size() < MAX_TOKENS) out.add(m.group());
        return out;
    }

    static String normalize(String raw) {
        if (IDENT.matcher(raw).matches()) return raw.toLowerCase(Locale.ROOT);
        if (NUMBER.matcher(raw).matches()) return "<num>";
        return raw;
    }

    static List<String> identifierParts(String identifier) {
        List<String> parts = new ArrayList<>();
        for (String chunk : identifier.split("[_$]+")) {
            Matcher m = PARTS.matcher(chunk);
            while (m.find()) parts.add(m.group().toLowerCase(Locale.ROOT));
        }
        return parts;
    }

    public static List<String> features(String code) {
        List<String> raws = rawTokens(code);
        List<String> base = new ArrayList<>(raws.size());
        for (String r : raws) base.add(normalize(r));

        List<String> feats = new ArrayList<>(base);
        for (String raw : raws) {
            if (IDENT.matcher(raw).matches()) {
                List<String> parts = identifierParts(raw);
                if (parts.size() > 1) for (String p : parts) feats.add("sub:" + p);
            }
        }
        for (int i = 0; i + 1 < base.size(); i++) feats.add(base.get(i) + " " + base.get(i + 1));
        return feats;
    }

    static int fnv1a(String text) {
        int h = FNV_OFFSET;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xFF);
            h *= FNV_PRIME;
        }
        return h;
    }

    /** Sparse vector as {bucket: value}, L2-normalized. Empty for code with no tokens. */
    public static Map<Integer, Double> vectorize(String code) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (String f : features(code)) counts.merge(fnv1a(f) & (DIM - 1), 1, Integer::sum);
        Map<Integer, Double> values = new HashMap<>();
        double norm = 0;
        for (var e : counts.entrySet()) {
            double v = Math.log1p(e.getValue());
            values.put(e.getKey(), v);
            norm += v * v;
        }
        if (norm == 0) return Map.of();
        double n = Math.sqrt(norm);
        values.replaceAll((k, v) -> v / n);
        return values;
    }
}
