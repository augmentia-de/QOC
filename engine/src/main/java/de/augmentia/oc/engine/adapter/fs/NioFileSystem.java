package de.augmentia.oc.engine.adapter.fs;

import de.augmentia.oc.engine.core.port.world.FileSystem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/** Real filesystem via java.nio. */
public final class NioFileSystem implements FileSystem {

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
        Path normalized = normalize(root);
        PathMatcher matcher;
        try {
            matcher = Paths.get("").getFileSystem().getPathMatcher("glob:" + pattern);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid glob: " + pattern, e);
        }
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(normalized)) {
            stream.filter(path -> !path.equals(normalized))
                    .forEach(path -> {
                        if (matcher.matches(normalized.relativize(path))) matches.add(path);
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        matches.sort(Comparator.naturalOrder());
        return List.copyOf(matches);
    }

    @Override
    public List<SearchMatch> search(Path root, String regex) {
        Path normalized = normalize(root);
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex == null ? "" : regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid regex: " + regex, e);
        }
        List<SearchMatch> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(normalized)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        if (pattern.matcher(lines.get(i)).find()) {
                            matches.add(new SearchMatch(path, i + 1, lines.get(i)));
                        }
                    }
                } catch (IOException ignored) {
                    // Binary or unreadable file: skip.
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(matches);
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

    private static Path normalize(Path path) {
        if (path == null) throw new IllegalArgumentException("path must not be null");
        return path.toAbsolutePath().normalize();
    }
}
