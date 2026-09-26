package com.codeturret.engine;

import com.codeturret.engine.model.Language;
import com.codeturret.engine.model.SourceFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Walks a checked-out repo and loads every file the engine can analyse. */
public final class SourceLoader {

    private static final Set<String> SKIP_DIRS = Set.of(
        "node_modules", "__pycache__", "dist", "build", "vendor", "target", "coverage", "venv", "site-packages");

    private final int maxFileBytes;

    public SourceLoader(int maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public List<SourceFile> load(Path root) throws IOException {
        List<SourceFile> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                String name = dir.getFileName().toString();
                return name.startsWith(".") || SKIP_DIRS.contains(name)
                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String rel = root.relativize(file).toString().replace('\\', '/');
                Language lang = Language.fromPath(rel);
                if (lang == Language.OTHER || isGenerated(rel) || attrs.size() > maxFileBytes) {
                    return FileVisitResult.CONTINUE;
                }
                String content = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
                files.add(new SourceFile(rel, lang, content));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(SourceFile::path));
        return files;
    }

    private static boolean isGenerated(String path) {
        return path.endsWith(".min.js") || path.endsWith(".d.ts") || path.endsWith(".bundle.js");
    }
}
