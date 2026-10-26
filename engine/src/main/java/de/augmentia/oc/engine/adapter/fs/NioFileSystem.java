package de.augmentia.oc.engine.adapter.fs;

import de.augmentia.oc.engine.core.port.world.FileSystem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/** Real filesystem via java.nio. */
public final class NioFileSystem implements FileSystem {

    private static final int BINARY_PROBE_BYTES = 8 * 1024;
    private static final List<String> DEFAULT_IGNORES = List.of(
            ".git", "node_modules", "target", "build", "dist", "out",
            ".gradle", ".idea", ".venv", "__pycache__");

    private final Path root;

    public NioFileSystem() {
        this(Path.of("."));
    }

    public NioFileSystem(Path root) {
        if (root == null) throw new IllegalArgumentException("root must not be null");
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public byte[] read(Path path) {
        try {
            return Files.readAllBytes(normalize(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String readText(Path path) {
        try {
            return Files.readString(normalize(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void write(Path path, byte[] content) {
        Path normalized = normalize(path);
        try {
            Path parent = normalized.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(normalized, content == null ? new byte[0] : content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public FileStat stat(Path path) {
        Path normalized = normalize(path);
        if (!Files.exists(normalized)) {
            throw new UncheckedIOException(new java.nio.file.NoSuchFileException(normalized.toString()));
        }
        try {
            boolean directory = Files.isDirectory(normalized);
            long size = directory ? 0L : Files.size(normalized);
            Instant modified = Files.getLastModifiedTime(normalized).toInstant();
            return new FileStat(normalized, directory, size, modified);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<Path> list(Path directory) {
        Path normalized = normalize(directory);
        try (Stream<Path> stream = Files.list(normalized)) {
            return stream.sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<Path> glob(Path root, String pattern) {
        return glob(root, pattern, GlobOptions.defaults()).matches();
    }

    @Override
    public GlobOutcome glob(Path root, String pattern, GlobOptions options) {
        GlobOptions effective = options == null ? GlobOptions.defaults() : options;
        Path normalized = normalize(root);
        PathMatcher matcher;
        try {
            matcher = Paths.get("").getFileSystem().getPathMatcher("glob:" + pattern);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid glob: " + pattern, e);
        }
        IgnoreRules ignore = IgnoreRules.load(normalized, effective.respectIgnore());
        long deadline = deadline(effective.timeout());
        TreeSet<Path> candidates = new TreeSet<>();
        int total = 0;
        int skipped = 0;
        boolean truncated = false;
        boolean timedOut = false;
        long keep = Math.min(1_000_000L, (long) effective.offset() + effective.maxResults());

        try (Stream<Path> stream = Files.walk(normalized, effective.maxDepth())) {
            Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                if (expired(deadline)) {
                    timedOut = true;
                    break;
                }
                Path path;
                try {
                    path = iterator.next();
                } catch (UncheckedIOException e) {
                    skipped++;
                    continue;
                }
                if (path.equals(normalized)) continue;
                Path relative = normalized.relativize(path);
                if (!effective.includeHidden() && hidden(relative)) {
                    skipped++;
                    continue;
                }
                if (ignore.matches(relative)) {
                    skipped++;
                    continue;
                }
                if (!matcher.matches(relative)) continue;
                total++;
                candidates.add(path);
                if (candidates.size() > keep) candidates.pollLast();
                if (total > keep) truncated = true;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (UncheckedIOException e) {
            skipped++;
        }
        List<Path> matches = candidates.stream()
                .skip(effective.offset())
                .limit(effective.maxResults())
                .toList();
        return new GlobOutcome(matches, total, truncated, timedOut, skipped);
    }

    @Override
    public List<SearchMatch> search(Path root, String regex) {
        return search(root, regex, SearchOptions.defaults()).matches();
    }

    @Override
    public SearchOutcome search(Path root, String regex, SearchOptions options) {
        SearchOptions effective = options == null ? SearchOptions.defaults() : options;
        Path normalized = normalize(root);
        int flags = effective.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex == null ? "" : regex, flags);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid regex: " + regex, e);
        }
        IgnoreRules ignore = IgnoreRules.load(normalized, effective.respectIgnore());
        long deadline = deadline(effective.timeout());
        TreeSet<SearchMatch> candidates = new TreeSet<>(
                Comparator.comparing(SearchMatch::path).thenComparingInt(SearchMatch::line));
        int total = 0;
        int skipped = 0;
        boolean truncated = false;
        boolean timedOut = false;
        long keep = Math.min(1_000_000L, (long) effective.offset() + effective.maxResults());

        try (Stream<Path> stream = Files.walk(normalized, effective.maxDepth())) {
            Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                if (expired(deadline)) {
                    timedOut = true;
                    break;
                }
                Path path;
                try {
                    path = iterator.next();
                } catch (UncheckedIOException e) {
                    skipped++;
                    continue;
                }
                if (path.equals(normalized)) continue;
                Path relative = normalized.relativize(path);
                if (!effective.includeHidden() && hidden(relative)) {
                    skipped++;
                    continue;
                }
                if (ignore.matches(relative)) {
                    skipped++;
                    continue;
                }
                if (!Files.isRegularFile(path)) continue;
                String[] lines = readLines(path, effective.maxFileBytes());
                if (lines == null) {
                    skipped++;
                    continue;
                }
                boolean fileTimedOut = false;
                int perFile = 0;
                for (int i = 0; i < lines.length; i++) {
                    if ((i & 1023) == 0 && expired(deadline)) {
                        fileTimedOut = true;
                        break;
                    }
                    if (!pattern.matcher(lines[i]).find()) continue;
                    perFile++;
                    if (perFile > effective.maxPerFile()) {
                        truncated = true;
                        break;
                    }
                    total++;
                    int beforeFrom = Math.max(0, i - effective.context());
                    int afterTo = Math.min(lines.length, i + effective.context() + 1);
                    List<String> allLines = Arrays.asList(lines);
                    candidates.add(new SearchMatch(path, i + 1, lines[i],
                            allLines.subList(beforeFrom, i),
                            allLines.subList(i + 1, afterTo)));
                    if (candidates.size() > keep) candidates.pollLast();
                    if (total > keep) truncated = true;
                }
                if (fileTimedOut) {
                    timedOut = true;
                    break;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (UncheckedIOException e) {
            skipped++;
        }
        List<SearchMatch> matches = candidates.stream()
                .skip(effective.offset())
                .limit(effective.maxResults())
                .toList();
        return new SearchOutcome(matches, total, truncated, timedOut, skipped);
    }

    @Override
    public Path realPath(Path path) {
        Path normalized = normalize(path);
        try {
            return normalized.toRealPath();
        } catch (IOException e) {
            return normalized;
        }
    }

    private Path normalize(Path path) {
        if (path == null) throw new IllegalArgumentException("path must not be null");
        Path resolved = path.isAbsolute() ? path : root.resolve(path);
        return resolved.toAbsolutePath().normalize();
    }

    private static String[] readLines(Path path, long maxFileBytes) {
        try {
            if (Files.size(path) > maxFileBytes) return null;
            byte[] bytes = Files.readAllBytes(path);
            if (binary(bytes)) return null;
            return new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean binary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_PROBE_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }

    private static boolean hidden(Path relative) {
        for (Path part : relative) {
            if (part.toString().startsWith(".")) return true;
        }
        return false;
    }

    private static long deadline(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) return 0L;
        return System.nanoTime() + timeout.toNanos();
    }

    private static boolean expired(long deadline) {
        return deadline != 0L && System.nanoTime() > deadline;
    }

    /** Minimal gitignore/ignore reader: no negation, but safe and dependency-free. */
    private static final class IgnoreRules {

        private final List<PathMatcher> matchers;

        private IgnoreRules(List<PathMatcher> matchers) {
            this.matchers = List.copyOf(matchers);
        }

        private static IgnoreRules load(Path root, boolean respect) {
            if (!respect) return new IgnoreRules(List.of());
            List<PathMatcher> matchers = new ArrayList<>();
            for (String pattern : DEFAULT_IGNORES) {
                add(matchers, pattern);
            }
            for (String file : List.of(".gitignore", ".ignore")) {
                Path candidate = root.resolve(file);
                if (!Files.isRegularFile(candidate)) continue;
                try {
                    for (String line : Files.readAllLines(candidate, StandardCharsets.UTF_8)) {
                        String pattern = line.trim();
                        if (pattern.isEmpty() || pattern.startsWith("#") || pattern.startsWith("!")) continue;
                        while (pattern.startsWith("/")) pattern = pattern.substring(1);
                        while (pattern.endsWith("/")) pattern = pattern.substring(0, pattern.length() - 1);
                        if (!pattern.isEmpty()) add(matchers, pattern);
                    }
                } catch (IOException ignored) {
                    // A malformed or unreadable ignore file must not break search.
                }
            }
            return new IgnoreRules(matchers);
        }

        private static void add(List<PathMatcher> matchers, String pattern) {
            try {
                matchers.add(Paths.get("").getFileSystem().getPathMatcher("glob:" + pattern));
                if (!pattern.contains("/")) {
                    matchers.add(Paths.get("").getFileSystem().getPathMatcher("glob:**/" + pattern));
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore unsupported gitignore syntax instead of failing the scan.
            }
        }

        private boolean matches(Path relative) {
            if (matchers.isEmpty()) return false;
            Path current = relative;
            while (current != null) {
                for (PathMatcher matcher : matchers) {
                    if (matcher.matches(current)) return true;
                }
                current = current.getParent();
            }
            Path name = relative.getFileName();
            if (name != null) {
                for (PathMatcher matcher : matchers) {
                    if (matcher.matches(name)) return true;
                }
            }
            return false;
        }
    }
}
