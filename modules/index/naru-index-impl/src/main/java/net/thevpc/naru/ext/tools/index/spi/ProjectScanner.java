package net.thevpc.naru.ext.tools.index.spi;

import net.thevpc.nuts.ext.NExtensions;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class ProjectScanner {
    private Path projectRoot;
    private static volatile Map<String, CodeIndex> cache = new HashMap<>();

    public ProjectScanner(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    public CodeIndex scan() {
        return scanIfStale(new CodeIndex());
    }

    public CodeIndex scanIfStale(CodeIndex existing) {
        Map<String, LanguageIndexer> indexers = new HashMap<>();
        try {
            List<LanguageIndexer> list = NExtensions.of().createAllSupported(LanguageIndexer.class, null);
            for (LanguageIndexer idx : list) {
                for (String ext : idx.extensions()) {
                    indexers.put(ext.startsWith(".") ? ext : "." + ext, idx);
                }
            }
        } catch (Exception e) {
            // ignore
        }

        List<String> gitignorePatterns = loadGitignore();

        try {
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (name.startsWith(".") || name.equals("target") || name.equals("build") || name.equals("node_modules")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String relPath = projectRoot.relativize(dir).toString().replace('\\', '/');
                    if (!relPath.isEmpty() && isIgnored(relPath, gitignorePatterns, true)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    String relPath = projectRoot.relativize(file).toString().replace('\\', '/');
                    if (isIgnored(relPath, gitignorePatterns, false)) {
                        return FileVisitResult.CONTINUE;
                    }

                    int dotIdx = name.lastIndexOf('.');
                    if (dotIdx > 0) {
                        String ext = name.substring(dotIdx);
                        LanguageIndexer idx = indexers.get(ext);
                        if (idx != null) {
                            long lastMod = file.toFile().lastModified();
                            Long oldLastMod = existing.getLastModified(relPath);
                            if (oldLastMod == null || oldLastMod < lastMod) {
                                existing.removeFile(relPath);
                                try {
                                    List<CodeSymbol> symbols = idx.index(file);
                                    if (symbols != null) {
                                        existing.addSymbols(relPath, symbols);
                                    }
                                } catch (Exception e) {
                                    // Ignore index error
                                }
                                existing.setLastModified(relPath, lastMod);
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Ignore
        }

        cache.put(projectRoot.toString(), existing);
        return existing;
    }

    public static CodeIndex getCachedOrScan(Path projectRoot) {
        String key = projectRoot.toString();
        CodeIndex index = cache.get(key);
        if (index == null) {
            index = new CodeIndex();
        }
        return new ProjectScanner(projectRoot).scanIfStale(index);
    }

    private List<String> loadGitignore() {
        List<String> patterns = new ArrayList<>();
        Path gi = projectRoot.resolve(".gitignore");
        if (Files.exists(gi)) {
            try {
                for (String line : Files.readAllLines(gi)) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        patterns.add(line);
                    }
                }
            } catch (IOException e) {
            }
        }
        return patterns;
    }

    private boolean isIgnored(String path, List<String> patterns, boolean isDir) {
        for (String p : patterns) {
            boolean negate = p.startsWith("!");
            String pat = negate ? p.substring(1) : p;
            boolean dirOnly = pat.endsWith("/");
            if (dirOnly) pat = pat.substring(0, pat.length() - 1);

            if (isDir && dirOnly && matchPattern(path, pat)) {
                return !negate;
            }
            if (matchPattern(path, pat)) {
                return !negate;
            }
        }
        return false;
    }

    private boolean matchPattern(String path, String pattern) {
        String regex = pattern.replace(".", "\\.")
                .replace("**", ".*")
                .replace("*", "[^/]*")
                .replace("?", ".");
        if (!regex.startsWith(".*") && !regex.startsWith("/")) {
             regex = "(?:.*/)?" + regex;
        }
        if (regex.startsWith("/")) {
            regex = regex.substring(1);
        }
        return path.matches(regex) || path.matches(regex + "/.*");
    }
}
