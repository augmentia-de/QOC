package de.augmentia.oc.engine.adapter.fs;

import de.augmentia.oc.engine.core.port.world.InstructionLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Filesystem instruction loader. Discovery mirrors the reference agent:
 * per directory {@code AGENTS.md} wins over {@code CLAUDE.md}; global first,
 * then every ancestor from the filesystem root down to the working directory.
 * {@code SYSTEM.md} / {@code APPEND_SYSTEM.md} live in the working directory
 * (or its {@code .opencode} subdirectory) and in the global config directory.
 */
public final class NioInstructionLoader implements InstructionLoader {

    public static final int MAX_FILE_BYTES = 32 * 1024;

    private static final Logger LOG = Logger.getLogger(NioInstructionLoader.class.getName());

    private final Path globalDirectory;

    public NioInstructionLoader(Path globalDirectory) {
        if (globalDirectory == null) throw new IllegalArgumentException("globalDirectory must not be null");
        this.globalDirectory = globalDirectory.toAbsolutePath().normalize();
    }

    @Override
    public List<InstructionFile> load(Path startDirectory) {
        if (startDirectory == null) throw new IllegalArgumentException("startDirectory must not be null");
        Path directory = startDirectory.toAbsolutePath().normalize();

        List<InstructionFile> files = new ArrayList<>();
        addSingle(files, Kind.SYSTEM, directory.resolve(".opencode").resolve("SYSTEM.md"),
                directory.resolve("SYSTEM.md"), globalDirectory.resolve("SYSTEM.md"));
        addContextFiles(files, directory);
        addSingle(files, Kind.RULES, directory.resolve(".opencode").resolve("RULES.md"),
                directory.resolve("RULES.md"), globalDirectory.resolve("RULES.md"));
        addSingle(files, Kind.APPEND_SYSTEM, directory.resolve(".opencode").resolve("APPEND_SYSTEM.md"),
                directory.resolve("APPEND_SYSTEM.md"), globalDirectory.resolve("APPEND_SYSTEM.md"));
        return List.copyOf(files);
    }

    private void addContextFiles(List<InstructionFile> files, Path directory) {
        Path global = firstReadable(globalDirectory.resolve("AGENTS.md"),
                globalDirectory.resolve("CLAUDE.md"));
        if (global != null) {
            files.add(new InstructionFile(global, read(global), Kind.AGENTS));
        }

        List<Path> ancestors = new ArrayList<>();
        Path current = directory;
        while (true) {
            Path context = firstReadable(current.resolve("AGENTS.md"), current.resolve("CLAUDE.md"));
            if (context != null && !same(context, global)) {
                ancestors.add(context);
            }
            Path parent = current.getParent();
            if (parent == null || parent.equals(current)) break;
            current = parent;
        }
        Collections.reverse(ancestors);
        for (Path context : ancestors) {
            files.add(new InstructionFile(context, read(context), Kind.AGENTS));
        }
    }

    /** Adds the first existing candidate (project .opencode, project, global). */
    private static void addSingle(List<InstructionFile> files, Kind kind, Path... candidates) {
        Path found = firstReadable(candidates);
        if (found == null) return;
        String text = read(found);
        if (text.isBlank()) return;
        files.add(new InstructionFile(found, text, kind));
    }

    private static Path firstReadable(Path... candidates) {
        for (Path candidate : candidates) {
            if (candidate == null) continue;
            try {
                if (Files.isRegularFile(candidate) && Files.size(candidate) <= MAX_FILE_BYTES) {
                    return candidate;
                }
            } catch (IOException e) {
                LOG.fine("cannot stat instruction file " + candidate + ": " + e.getMessage());
            }
        }
        return null;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warning("cannot read instruction file " + path + ": " + e.getMessage());
            return "";
        }
    }

    private static boolean same(Path left, Path right) {
        return right != null && left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());
    }
}
