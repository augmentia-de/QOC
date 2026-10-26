package de.augmentia.oc.engine.core.port.world;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Filesystem access needed by the tools, bounding and revert. */
public interface FileSystem {

    byte[] read(Path path);

    String readText(Path path);

    void write(Path path, byte[] content);

    FileStat stat(Path path);

    List<Path> list(Path directory);

    List<Path> glob(Path root, String pattern);

    List<SearchMatch> search(Path root, String regex);

    Path realPath(Path path);

    /**
     * Options for a bounded search. A zero or negative timeout disables the
     * deadline; {@code maxResults} is a page size, not a hard scan limit.
     */
    record SearchOptions(boolean caseSensitive, int offset, int maxResults, int context,
                         int maxDepth, boolean includeHidden, boolean respectIgnore,
                         long maxFileBytes, int maxPerFile, Duration timeout) {
        public SearchOptions {
            if (offset < 0) offset = 0;
            if (maxResults < 1) maxResults = 1;
            if (context < 0) context = 0;
            if (maxDepth < 1) maxDepth = 1;
            if (maxFileBytes < 1) maxFileBytes = 1;
            if (maxPerFile < 1) maxPerFile = 1;
            if (timeout == null) timeout = Duration.ZERO;
        }

        public static SearchOptions defaults() {
            return new SearchOptions(true, 0, 100, 0, 64, false, true,
                    8L * 1024L * 1024L, 20, Duration.ofSeconds(10));
        }
    }

    record SearchOutcome(List<SearchMatch> matches, int total, boolean truncated,
                         boolean timedOut, int skipped) {
        public SearchOutcome {
            matches = matches == null ? List.of() : List.copyOf(matches);
            if (total < matches.size()) total = matches.size();
            if (skipped < 0) skipped = 0;
        }
    }

    record GlobOptions(int offset, int maxResults, int maxDepth, boolean includeHidden,
                       boolean respectIgnore, Duration timeout) {
        public GlobOptions {
            if (offset < 0) offset = 0;
            if (maxResults < 1) maxResults = 1;
            if (maxDepth < 1) maxDepth = 1;
            if (timeout == null) timeout = Duration.ZERO;
        }

        public static GlobOptions defaults() {
            return new GlobOptions(0, 100, 64, false, true, Duration.ofSeconds(10));
        }
    }

    record GlobOutcome(List<Path> matches, int total, boolean truncated,
                       boolean timedOut, int skipped) {
        public GlobOutcome {
            matches = matches == null ? List.of() : List.copyOf(matches);
            if (total < matches.size()) total = matches.size();
            if (skipped < 0) skipped = 0;
        }
    }

    /**
     * Default implementation for adapters that only provide the unbounded
     * variants. Real adapters should override this with the option-aware walk.
     */
    default SearchOutcome search(Path root, String regex, SearchOptions options) {
        SearchOptions effective = options == null ? SearchOptions.defaults() : options;
        List<SearchMatch> all = search(root, regex);
        int from = Math.min(effective.offset(), all.size());
        int to = Math.min(from + effective.maxResults(), all.size());
        return new SearchOutcome(all.subList(from, to), all.size(), to < all.size(), false, 0);
    }

    /**
     * Default implementation for adapters that only provide the unbounded
     * variants. Real adapters should override this with the option-aware walk.
     */
    default GlobOutcome glob(Path root, String pattern, GlobOptions options) {
        GlobOptions effective = options == null ? GlobOptions.defaults() : options;
        List<Path> all = glob(root, pattern);
        int from = Math.min(effective.offset(), all.size());
        int to = Math.min(from + effective.maxResults(), all.size());
        return new GlobOutcome(all.subList(from, to), all.size(), to < all.size(), false, 0);
    }

    record FileStat(Path path, boolean directory, long size, Instant modified) {
        public FileStat {
            if (path == null) throw new IllegalArgumentException("path must not be null");
            if (modified == null) modified = Instant.EPOCH;
            if (size < 0) throw new IllegalArgumentException("size must not be negative");
        }
    }

    record SearchMatch(Path path, int line, String text, List<String> before, List<String> after) {
        public SearchMatch {
            if (path == null) throw new IllegalArgumentException("path must not be null");
            if (line < 1) throw new IllegalArgumentException("line must be positive");
            if (text == null) text = "";
            before = before == null ? List.of() : List.copyOf(before);
            after = after == null ? List.of() : List.copyOf(after);
        }

        public SearchMatch(Path path, int line, String text) {
            this(path, line, text, List.of(), List.of());
        }
    }
}
