package com.codeturret.engine.model;

import java.nio.file.Path;
import java.util.List;

/** Everything the engine knows about a repo before analysis starts. */
public record RepoSnapshot(Path root, List<SourceFile> files, GitSignals git) {}
