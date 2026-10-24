package de.augmentia.oc.engine.core.port.world;

import java.nio.file.Path;
import java.util.List;

/**
 * Loads project and global instruction files (AGENTS.md/CLAUDE.md,
 * SYSTEM.md, APPEND_SYSTEM.md) for the system prompt.
 */
public interface InstructionLoader {

    /**
     * Returns files in application order: {@code SYSTEM} first, then
     * {@code AGENTS} (global, then root to working directory), then
     * {@code APPEND_SYSTEM}. Missing or unreadable files are skipped.
     */
    List<InstructionFile> load(Path startDirectory);

    record InstructionFile(Path path, String text, Kind kind) {
        public InstructionFile {
            if (path == null) throw new IllegalArgumentException("path must not be null");
            if (text == null) text = "";
            if (kind == null) throw new IllegalArgumentException("kind must not be null");
        }
    }

    enum Kind { SYSTEM, AGENTS, APPEND_SYSTEM }

    /** No instruction files (used by tests and when the feature is disabled). */
    static InstructionLoader none() {
        return startDirectory -> List.of();
    }
}
