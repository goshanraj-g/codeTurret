package com.codeturret.engine.parse;

import com.codeturret.engine.model.CodeUnit;
import com.codeturret.engine.model.SourceFile;

import java.util.List;
import java.util.Set;

/**
 * @param routeHandlerRefs simple names passed as handlers to route registrations in this file,
 *                         e.g. {@code displayAllocations} from {@code app.get("/a", h.displayAllocations)}
 */
public record ParsedFile(SourceFile file, List<CodeUnit> units, Set<String> routeHandlerRefs) {}
