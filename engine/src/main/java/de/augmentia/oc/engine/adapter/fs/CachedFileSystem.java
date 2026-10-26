package de.augmentia.oc.engine.adapter.fs;

import de.augmentia.oc.engine.core.port.world.FileSystem;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Short-lived cache for directory scans. Only glob/search results are cached;
 * file contents are always read through. The TTL is deliberately short because
 * shell-created files are outside this decorator's invalidation knowledge.
 */
public final class CachedFileSystem implements FileSystem {

    private static final Duration TTL = Duration.ofSeconds(1);
    private static final int MAX_ENTRIES = 64;

    private final FileSystem delegate;
    private final Map<Object, Entry> cache = new LinkedHashMap<>();

    public CachedFileSystem(FileSystem delegate) {
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        this.delegate = delegate;
    }

    @Override
    public byte[] read(Path path) {
        return delegate.read(path);
    }

    @Override
    public String readText(Path path) {
        return delegate.readText(path);
    }

    @Override
    public void write(Path path, byte[] content) {
        delegate.write(path, content);
        invalidate();
    }

    @Override
    public FileStat stat(Path path) {
        return delegate.stat(path);
    }

    @Override
    public List<Path> list(Path directory) {
        return delegate.list(directory);
    }

    @Override
    public List<Path> glob(Path root, String pattern) {
        return delegate.glob(root, pattern);
    }

    @Override
    public List<SearchMatch> search(Path root, String regex) {
        return delegate.search(root, regex);
    }

    @Override
    public Path realPath(Path path) {
        return delegate.realPath(path);
    }

    @Override
    public GlobOutcome glob(Path root, String pattern, GlobOptions options) {
        Object key = new GlobKey(root, pattern, options);
        Entry cached = get(key);
        if (cached != null && cached.value instanceof GlobOutcome outcome) return outcome;
        GlobOutcome outcome = delegate.glob(root, pattern, options);
        put(key, outcome);
        return outcome;
    }

    @Override
    public SearchOutcome search(Path root, String regex, SearchOptions options) {
        Object key = new SearchKey(root, regex, options);
        Entry cached = get(key);
        if (cached != null && cached.value instanceof SearchOutcome outcome) return outcome;
        SearchOutcome outcome = delegate.search(root, regex, options);
        put(key, outcome);
        return outcome;
    }

    private synchronized Entry get(Object key) {
        Entry entry = cache.get(key);
        if (entry == null) return null;
        if (entry.expiresAtNanos - System.nanoTime() <= 0) {
            cache.remove(key);
            return null;
        }
        return entry;
    }

    private synchronized void put(Object key, Object value) {
        if (cache.size() >= MAX_ENTRIES) cache.clear();
        cache.put(key, new Entry(value, System.nanoTime() + TTL.toNanos()));
    }

    private synchronized void invalidate() {
        cache.clear();
    }

    private record Entry(Object value, long expiresAtNanos) {}

    private record GlobKey(Path root, String pattern, GlobOptions options) {}

    private record SearchKey(Path root, String regex, SearchOptions options) {}
}
