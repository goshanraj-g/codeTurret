package com.codeturret.engine.parse;

import com.codeturret.engine.model.CodeUnit;
import com.codeturret.engine.model.SourceFile;

import java.util.*;

/**
 * All units in a repo plus a name-based call graph.
 *
 * <p>Calls are resolved by simple name only (no type or import resolution), so {@code a.save()} links to
 * every unit named {@code save}. That over-approximates, which is the safe direction for "could attacker
 * input reach this?".
 */
public final class CodeIndex {

    public static final int UNREACHABLE = Integer.MAX_VALUE;

    private final List<CodeUnit> units;
    private final Map<String, List<CodeUnit>> byName = new HashMap<>();
    private final Map<String, List<CodeUnit>> callersById = new HashMap<>();
    private final Map<String, Integer> hopsById = new HashMap<>();

    private CodeIndex(List<CodeUnit> units) {
        this.units = List.copyOf(units);
        for (CodeUnit u : units) {
            if (u.kind() != CodeUnit.Kind.MODULE) byName.computeIfAbsent(u.simpleName(), k -> new ArrayList<>()).add(u);
        }
        for (CodeUnit caller : units) {
            for (String callee : caller.calls()) {
                for (CodeUnit target : byName.getOrDefault(CodeParser.lastSegment(callee), List.of())) {
                    if (target != caller) callersById.computeIfAbsent(target.id(), k -> new ArrayList<>()).add(caller);
                }
            }
        }
        computeHops();
    }

    public static CodeIndex build(CodeParser parser, List<SourceFile> files) {
        List<ParsedFile> parsed = new ArrayList<>();
        for (SourceFile f : files) parsed.add(parser.parse(f));
        return fromParsed(parsed);
    }

    /** Marks units referenced as route handlers anywhere in the repo as entrypoints. */
    public static CodeIndex fromParsed(List<ParsedFile> parsed) {
        Set<String> handlerNames = new HashSet<>();
        for (ParsedFile p : parsed) handlerNames.addAll(p.routeHandlerRefs());
        List<CodeUnit> all = new ArrayList<>();
        for (ParsedFile p : parsed) {
            for (CodeUnit u : p.units()) {
                boolean referenced = u.kind() != CodeUnit.Kind.MODULE && handlerNames.contains(u.simpleName());
                all.add(referenced && !u.entrypoint() ? u.withEntrypoint(true) : u);
            }
        }
        return new CodeIndex(all);
    }

    public List<CodeUnit> units() {
        return units;
    }

    public List<CodeUnit> callers(CodeUnit unit) {
        return callersById.getOrDefault(unit.id(), List.of());
    }

    /** 0 for entrypoints, 1 for units they call, and so on; {@link #UNREACHABLE} if no path is known. */
    public int hopsFromEntrypoint(CodeUnit unit) {
        return hopsById.getOrDefault(unit.id(), UNREACHABLE);
    }

    private void computeHops() {
        Deque<CodeUnit> queue = new ArrayDeque<>();
        for (CodeUnit u : units) {
            if (u.entrypoint()) {
                hopsById.put(u.id(), 0);
                queue.add(u);
            }
        }
        while (!queue.isEmpty()) {
            CodeUnit u = queue.poll();
            int next = hopsById.get(u.id()) + 1;
            for (String callee : u.calls()) {
                for (CodeUnit target : byName.getOrDefault(CodeParser.lastSegment(callee), List.of())) {
                    if (!hopsById.containsKey(target.id())) {
                        hopsById.put(target.id(), next);
                        queue.add(target);
                    }
                }
            }
        }
    }
}
