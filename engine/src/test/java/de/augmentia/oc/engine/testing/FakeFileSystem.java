package de.augmentia.oc.engine.testing;

import de.augmentia.oc.engine.core.port.world.FileSystem;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Map {@code path -> bytes}; directories are derived from the file paths. */
public final class FakeFileSystem implements FileSystem {

    private final Map<Path, byte[]> files = new LinkedHashMap<>();
    private final Set<Path> directories = new LinkedHashSet<>();

    public FakeFileSystem() {
        directories.add(Path.of("/"));
    }

    public synchronized void put(Path path, byte[] content) {
        Path normalized = normalize(path);
        files.put(normalized, content == null ? new byte[0] : content.clone());
        addParents(normalized);
    }

    public synchronized void putText(Path path, String content) {
        put(path, content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void mkdir(Path directory) {
        Path normalized = normalize(directory);
        directories.add(normalized);
        addParents(normalized);
    }

    @Override
    public synchronized byte[] read(Path path) {
        Path normalized = normalize(path);
        byte[] content = files.get(normalized);
        if (content == null) throw missing(normalized);
        return content.clone();
    }

    @Override
    public synchronized String readText(Path path) {
        return new String(read(path), StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void write(Path path, byte[] content) {
        put(path, content);
    }

    @Override
    public synchronized FileStat stat(Path path) {
        Path normalized = normalize(path);
        byte[] content = files.get(normalized);
        if (content != null) {
            return new FileStat(normalized, false, content.length, Instant.EPOCH);
        }
        if (directories.contains(normalized)) {
            return new FileStat(normalized, true, 0, Instant.EPOCH);
        }
        throw missing(normalized);
    }

    @Override
    public synchronized List<Path> list(Path directory) {
        Path normalized = normalize(directory);
        if (!dirs().contains(normalized)) throw missing(normalized);
        List<Path> children = new ArrayList<>();
        for (Path path : files.keySet()) {
            if (normalized.equals(path.getParent())) children.add(path);
        }
        for (Path path : directories) {
            if (normalized.equals(path.getParent())) children.add(path);
        }
        children.sort(Path::compareTo);
        return List.copyOf(children);
    }

    @Override
    public synchronized List<Path> glob(Path root, String pattern) {
        Path normalized = normalize(root);
        if (pattern == null) throw new IllegalArgumentException("pattern must not be null");
        java.nio.file.PathMatcher matcher;
        try {
            matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid glob: " + pattern, e);
        }
        List<Path> matches = new ArrayList<>();
        Set<Path> all = new LinkedHashSet<>(files.keySet());
        all.addAll(directories);
        for (Path path : all) {
            if (!path.startsWith(normalized) || path.equals(normalized)) continue;
            if (matcher.matches(normalized.relativize(path))) matches.add(path);
        }
        matches.sort(Path::compareTo);
        return List.copyOf(matches);
    }

    @Override
    public synchronized List<SearchMatch> search(Path root, String regex) {
        Path normalized = normalize(root);
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex == null ? "" : regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid regex: " + regex, e);
        }
        List<SearchMatch> matches = new ArrayList<>();
        for (Map.Entry<Path, byte[]> entry : files.entrySet()) {
            if (!entry.getKey().startsWith(normalized)) continue;
            String text = new String(entry.getValue(), StandardCharsets.UTF_8);
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (pattern.matcher(lines[i]).find()) {
                    matches.add(new SearchMatch(entry.getKey(), i + 1, lines[i]));
                }
            }
        }
        return List.copyOf(matches);
    }

    @Override
    public synchronized Path realPath(Path path) {
        return normalize(path);
    }

    public synchronized boolean exists(Path path) {
        Path normalized = normalize(path);
        return files.containsKey(normalized) || directories.contains(normalized);
    }

    private Set<Path> dirs() {
        Set<Path> all = new LinkedHashSet<>(directories);
        for (Path path : files.keySet()) {
            Path parent = path.getParent();
            while (parent != null && all.add(parent)) {
                parent = parent.getParent();
            }
        }
        return all;
    }

    private void addParents(Path path) {
        Path parent = path.getParent();
        while (parent != null && directories.add(parent)) {
            parent = parent.getParent();
        }
    }

    private static Path normalize(Path path) {
        if (path == null) throw new IllegalArgumentException("path must not be null");
        return path.toAbsolutePath().normalize();
    }

    private static UncheckedIOException missing(Path path) {
        return new UncheckedIOException(new NoSuchFileException(path.toString()));
    }
}
