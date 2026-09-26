package com.codeturret.engine.staticanalysis;

import java.nio.file.Path;
import java.util.List;

/** A static analysis tool run over a whole repo. Implementations must not throw; return no hits on failure. */
@FunctionalInterface
public interface StaticAnalyzer {

    List<StaticHit> scan(Path repoDir);
}
