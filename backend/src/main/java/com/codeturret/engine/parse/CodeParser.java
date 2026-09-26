package com.codeturret.engine.parse;

import com.codeturret.engine.model.CodeUnit;
import com.codeturret.engine.model.Language;
import com.codeturret.engine.model.SourceFile;
import org.treesitter.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Splits a source file into {@link CodeUnit}s using tree-sitter.
 *
 * <ul>
 *   <li>Every named function or method becomes a unit. Functions that mostly contain other functions
 *       (constructor-style "classes" in JS) are dropped in favour of their children.</li>
 *   <li>Anonymous functions passed directly to a route registration ({@code app.get("/x", (req, res) => ...)})
 *       become entrypoint units.</li>
 *   <li>Lines not covered by any function (module-level code, class fields, app setup) are grouped into
 *       {@code MODULE} units so nothing is silently skipped.</li>
 * </ul>
 *
 * Stateless and thread-safe: a fresh {@link TSParser} is created per call.
 */
public final class CodeParser {

    static final int MAX_MODULE_CHUNK_LINES = 60;
    private static final double CONTAINER_THRESHOLD = 0.6;

    private static final Set<String> ROUTE_METHODS = Set.of(
        "get", "post", "put", "patch", "delete", "del", "all", "use", "route", "options", "head",
        "add_get", "add_post", "add_put", "add_patch", "add_delete", "add_route");

    private static final Set<String> JAVA_ROUTE_ANNOTATIONS = Set.of(
        "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping", "RequestMapping");

    public boolean supports(Language language) {
        return language != Language.OTHER;
    }

    public ParsedFile parse(SourceFile file) {
        String[] lines = file.content().split("\n", -1);
        if (!supports(file.language())) {
            return new ParsedFile(file, moduleUnits(file, lines, new BitSet()), Set.of());
        }
        TSParser parser = new TSParser();
        parser.setLanguage(grammar(file.language()));
        TSTree tree = parser.parseString(null, file.content());
        Walker w = new Walker(file, lines, file.content().getBytes(StandardCharsets.UTF_8));
        w.visit(tree.getRootNode(), null);

        List<CodeUnit> functions = w.dropContainers();
        BitSet covered = new BitSet();
        for (CodeUnit u : functions) covered.set(u.startLine(), u.endLine() + 1);

        List<CodeUnit> units = new ArrayList<>(functions);
        units.addAll(moduleUnits(file, lines, covered));
        units.sort(Comparator.comparingInt(CodeUnit::startLine));
        return new ParsedFile(file, units, w.routeHandlerRefs);
    }

    private static TSLanguage grammar(Language lang) {
        return switch (lang) {
            case PYTHON -> new TreeSitterPython();
            case JAVASCRIPT -> new TreeSitterJavascript();
            case TYPESCRIPT -> new TreeSitterTypescript();
            case TSX -> new TreeSitterTsx();
            case JAVA -> new TreeSitterJava();
            case OTHER -> throw new IllegalArgumentException("unsupported");
        };
    }

    /** Groups uncovered, non-trivial lines into MODULE units of at most {@link #MAX_MODULE_CHUNK_LINES}. */
    static List<CodeUnit> moduleUnits(SourceFile file, String[] lines, BitSet covered) {
        List<CodeUnit> out = new ArrayList<>();
        int n = lines.length;
        int i = 1;
        while (i <= n) {
            if (covered.get(i) || isTrivial(lines[i - 1])) { i++; continue; }
            int start = i;
            int end = i;
            int meaningful = 0;
            while (i <= n && !covered.get(i) && i - start < MAX_MODULE_CHUNK_LINES) {
                if (!isTrivial(lines[i - 1])) { end = i; meaningful++; }
                i++;
            }
            if (meaningful >= 2 || file.language() == Language.OTHER) {
                out.add(new CodeUnit(file.path(), file.language(), "<module>", CodeUnit.Kind.MODULE,
                    start, end, slice(lines, start, end), callsInText(slice(lines, start, end)), false));
            }
        }
        return out;
    }

    private static boolean isTrivial(String line) {
        String t = line.strip();
        return t.isEmpty() || t.startsWith("import ") || t.startsWith("from ") || t.startsWith("package ")
            || t.startsWith("//") || t.startsWith("#") || t.startsWith("*") || t.startsWith("/*")
            || t.equals("}") || t.equals("};") || t.equals(")") || t.equals("});");
    }

    private static final java.util.regex.Pattern CALL_IN_TEXT = java.util.regex.Pattern.compile("([A-Za-z_$][\\w$]*)\\s*\\(");

    private static List<String> callsInText(String code) {
        Set<String> names = new LinkedHashSet<>();
        var m = CALL_IN_TEXT.matcher(code);
        while (m.find()) names.add(m.group(1));
        return List.copyOf(names);
    }

    static String slice(String[] lines, int start, int end) {
        return String.join("\n", Arrays.copyOfRange(lines, start - 1, Math.min(end, lines.length)));
    }

    // ---------------------------------------------------------------------------------------------

    private static final class Walker {
        final SourceFile file;
        final String[] lines;
        final byte[] bytes;
        final List<CodeUnit> units = new ArrayList<>();
        final Set<String> routeHandlerRefs = new LinkedHashSet<>();

        Walker(SourceFile file, String[] lines, byte[] bytes) {
            this.file = file;
            this.lines = lines;
            this.bytes = bytes;
        }

        void visit(TSNode node, String enclosingClass) {
            String type = node.getType();
            String cls = enclosingClass;
            switch (type) {
                case "class_definition", "class_declaration", "class" -> {
                    TSNode name = node.getChildByFieldName("name");
                    cls = name.isNull() ? enclosingClass : text(name);
                }
                case "function_definition" -> addFunction(node, cls, pythonEntrypoint(node));
                case "function_declaration", "generator_function_declaration" -> addFunction(node, null, false);
                case "method_definition" -> addFunction(node, cls, false);
                case "method_declaration", "constructor_declaration" -> addFunction(node, cls, javaEntrypoint(node));
                case "variable_declarator" -> {
                    TSNode value = node.getChildByFieldName("value");
                    if (isFunctionNode(value)) addUnit(value, text(node.getChildByFieldName("name")), null, false);
                }
                case "assignment_expression" -> {
                    TSNode right = node.getChildByFieldName("right");
                    if (isFunctionNode(right)) addUnit(right, lastSegment(text(node.getChildByFieldName("left"))), null, false);
                }
                case "pair" -> {
                    TSNode value = node.getChildByFieldName("value");
                    if (isFunctionNode(value)) addUnit(value, stripQuotes(text(node.getChildByFieldName("key"))), null, false);
                }
                case "call_expression", "call" -> collectRouteRegistration(node);
                default -> { }
            }
            for (int i = 0; i < node.getNamedChildCount(); i++) visit(node.getNamedChild(i), cls);
        }

        private void addFunction(TSNode node, String cls, boolean entrypoint) {
            TSNode name = node.getChildByFieldName("name");
            addUnit(node, name.isNull() ? "<anonymous>" : text(name), cls, entrypoint);
        }

        private void addUnit(TSNode node, String name, String cls, boolean entrypoint) {
            // Decorators belong to the function in Python; include them in the unit's range.
            TSNode span = node;
            TSNode parent = node.getParent();
            if (!parent.isNull() && parent.getType().equals("decorated_definition")) span = parent;

            int start = span.getStartPoint().getRow() + 1;
            int end = span.getEndPoint().getRow() + 1;
            boolean method = cls != null || node.getType().startsWith("method") || node.getType().startsWith("constructor");
            String display = cls != null ? cls + "." + name : name;
            units.add(new CodeUnit(file.path(), file.language(), display,
                method ? CodeUnit.Kind.METHOD : CodeUnit.Kind.FUNCTION,
                start, end, slice(lines, start, end), calls(node), entrypoint));
        }

        /** {@code app.get("/x", auth, handler.method)} and {@code router.add_get("/x", view)}. */
        private void collectRouteRegistration(TSNode call) {
            TSNode fn = call.getChildByFieldName("function");
            if (fn.isNull()) return;
            String callee = fn.getType().equals("member_expression") || fn.getType().equals("attribute")
                ? lastSegment(text(fn)) : "";
            if (!ROUTE_METHODS.contains(callee)) return;
            TSNode args = call.getChildByFieldName("arguments");
            if (args.isNull() || args.getNamedChildCount() < 2) return;
            TSNode first = args.getNamedChild(0);
            if (!first.getType().equals("string") && !first.getType().equals("template_string")) return;

            for (int i = 1; i < args.getNamedChildCount(); i++) {
                TSNode arg = args.getNamedChild(i);
                switch (arg.getType()) {
                    case "identifier", "member_expression", "attribute" -> routeHandlerRefs.add(lastSegment(text(arg)));
                    case "arrow_function", "function_expression", "function" ->
                        addUnit(arg, callee.toUpperCase(Locale.ROOT) + " " + stripQuotes(text(first)), null, true);
                    default -> { }
                }
            }
        }

        private boolean pythonEntrypoint(TSNode fn) {
            TSNode parent = fn.getParent();
            if (parent.isNull() || !parent.getType().equals("decorated_definition")) return false;
            for (int i = 0; i < parent.getNamedChildCount(); i++) {
                TSNode d = parent.getNamedChild(i);
                if (!d.getType().equals("decorator")) continue;
                String t = text(d);
                for (String m : ROUTE_METHODS) if (t.contains("." + m + "(")) return true;
            }
            return false;
        }

        private boolean javaEntrypoint(TSNode method) {
            for (int i = 0; i < method.getNamedChildCount(); i++) {
                TSNode c = method.getNamedChild(i);
                if (!c.getType().equals("modifiers")) continue;
                for (int j = 0; j < c.getNamedChildCount(); j++) {
                    TSNode a = c.getNamedChild(j);
                    if (a.getType().endsWith("annotation")) {
                        TSNode name = a.getChildByFieldName("name");
                        if (!name.isNull() && JAVA_ROUTE_ANNOTATIONS.contains(text(name))) return true;
                    }
                }
            }
            return false;
        }

        private List<String> calls(TSNode root) {
            Set<String> names = new LinkedHashSet<>();
            collectCalls(root, names);
            return List.copyOf(names);
        }

        private void collectCalls(TSNode node, Set<String> out) {
            switch (node.getType()) {
                case "call", "call_expression" -> {
                    TSNode fn = node.getChildByFieldName("function");
                    if (!fn.isNull()) out.add(lastTwoSegments(text(fn)));
                }
                case "method_invocation" -> {
                    TSNode name = node.getChildByFieldName("name");
                    TSNode object = node.getChildByFieldName("object");
                    if (!name.isNull()) {
                        out.add(object.isNull() ? text(name) : lastSegment(text(object)) + "." + text(name));
                    }
                }
                case "new_expression" -> {
                    TSNode ctor = node.getChildByFieldName("constructor");
                    if (!ctor.isNull()) out.add(lastSegment(text(ctor)));
                }
                case "object_creation_expression" -> {
                    TSNode t = node.getChildByFieldName("type");
                    if (!t.isNull()) out.add(lastSegment(text(t)));
                }
                default -> { }
            }
            for (int i = 0; i < node.getNamedChildCount(); i++) collectCalls(node.getNamedChild(i), out);
        }

        List<CodeUnit> dropContainers() {
            List<CodeUnit> kept = new ArrayList<>();
            for (CodeUnit outer : units) {
                BitSet inner = new BitSet();
                for (CodeUnit u : units) {
                    if (u != outer && u.startLine() >= outer.startLine() && u.endLine() <= outer.endLine()
                        && !(u.startLine() == outer.startLine() && u.endLine() == outer.endLine())) {
                        inner.set(u.startLine(), u.endLine() + 1);
                    }
                }
                if (inner.cardinality() < CONTAINER_THRESHOLD * outer.lineCount()) kept.add(outer);
            }
            return kept;
        }

        private String text(TSNode n) {
            if (n == null || n.isNull()) return "";
            int s = Math.max(0, n.getStartByte());
            int e = Math.min(bytes.length, n.getEndByte());
            return new String(bytes, s, Math.max(0, e - s), StandardCharsets.UTF_8);
        }

        private static boolean isFunctionNode(TSNode n) {
            if (n == null || n.isNull()) return false;
            String t = n.getType();
            return t.equals("arrow_function") || t.equals("function_expression") || t.equals("function")
                || t.equals("generator_function") || t.equals("lambda");
        }
    }

    public static String lastSegment(String expr) {
        String s = expr.strip();
        int paren = s.indexOf('(');
        if (paren > 0) s = s.substring(0, paren);
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    /** {@code self.db.cursor().execute} becomes {@code cursor.execute}; plain names are unchanged. */
    static String lastTwoSegments(String expr) {
        String s = expr.strip().replaceAll("\\([^()]*\\)", "");
        String[] parts = s.split("\\.");
        return parts.length >= 2 ? parts[parts.length - 2] + "." + parts[parts.length - 1] : s;
    }

    private static String stripQuotes(String s) {
        return s.replaceAll("^[\"'`]|[\"'`]$", "");
    }
}
