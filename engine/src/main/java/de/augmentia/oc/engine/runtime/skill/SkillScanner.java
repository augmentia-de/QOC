package de.augmentia.oc.engine.runtime.skill;

import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.port.world.FileSystem;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Discovers skills under {@code <project>/skill} and {@code <project>/skills}
 * (SK-02/SK-03).
 */
public final class SkillScanner {

    public static final int MAX_DEPTH = 6;
    public static final int MAX_FILES = 10;

    private final FileSystem fileSystem;

    public SkillScanner(FileSystem fileSystem) {
        if (fileSystem == null) throw new IllegalArgumentException("fileSystem must not be null");
        this.fileSystem = fileSystem;
    }

    /** Candidate skill files, sorted by path. */
    public List<Path> scan(Path projectRoot) {
        if (projectRoot == null) throw new IllegalArgumentException("projectRoot must not be null");
        List<Path> found = new ArrayList<>();
        for (String name : List.of("skill", "skills")) {
            Path root = projectRoot.resolve(name);
            if (exists(root)) {
                walk(root, 0, found);
            }
        }
        found.sort(Comparator.naturalOrder());
        return List.copyOf(found);
    }

    /** Scans and parses; invalid skills are skipped, not fatal (SK-11). */
    public List<Skill> load(Path projectRoot, SkillParser parser) {
        if (parser == null) throw new IllegalArgumentException("parser must not be null");
        List<Skill> skills = new ArrayList<>();
        for (Path file : scan(projectRoot)) {
            try {
                Result<Skill> parsed = parser.parse(file, fileSystem.readText(file));
                if (parsed instanceof Result.Ok<Skill> ok) {
                    skills.add(withFiles(ok.value()));
                }
            } catch (UncheckedIOException ignored) {
                // The file disappeared between list and read: next reload sees it.
            }
        }
        return List.copyOf(skills);
    }

    private void walk(Path directory, int depth, List<Path> found) {
        List<Path> entries;
        try {
            entries = fileSystem.list(directory);
        } catch (UncheckedIOException e) {
            return;
        }
        entries = entries.stream()
                .filter(path -> path.getFileName() != null
                        && !path.getFileName().toString().startsWith("."))
                .toList();

        Path skillFile = entries.stream()
                .filter(path -> path.getFileName().toString().equals("SKILL.md"))
                .findFirst()
                .orElse(null);
        if (skillFile != null) {
            found.add(skillFile);
            return;
        }

        if (depth == 0) {
            entries.stream()
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .forEach(found::add);
        }

        if (depth >= MAX_DEPTH) return;
        for (Path entry : entries) {
            if (isDirectory(entry)) {
                walk(entry, depth + 1, found);
            }
        }
    }

    private Skill withFiles(Skill skill) {
        if (skill.baseDirectory() == null || skill.baseDirectory().getFileName() == null) return skill;
        // Only directory skills (SKILL.md) carry a file list; flat *.md skills do not.
        if (!skill.baseDirectory().getFileName().toString().equals(skill.id().value())) return skill;
        if (skill.files().size() > 0) return skill;
        List<Path> files = new ArrayList<>();
        collectFiles(skill, skill.baseDirectory(), 0, files, new HashSet<>());
        files.sort(Comparator.naturalOrder());
        if (files.size() > MAX_FILES) files = files.subList(0, MAX_FILES);
        return skill.withFiles(files);
    }

    private void collectFiles(Skill skill, Path directory, int depth, List<Path> files, Set<Path> seen) {
        if (depth > MAX_DEPTH) return;
        for (Path entry : fileSystem.list(directory)) {
            if (entry.getFileName() == null || entry.getFileName().toString().startsWith(".")) continue;
            if (isDirectory(entry)) {
                collectFiles(skill, entry, depth + 1, files, seen);
            } else if (!entry.getFileName().toString().equals("SKILL.md") && seen.add(entry)) {
                files.add(entry);
            }
        }
    }

    private boolean isDirectory(Path path) {
        try {
            return fileSystem.stat(path).directory();
        } catch (UncheckedIOException e) {
            return false;
        }
    }

    private boolean exists(Path path) {
        try {
            fileSystem.stat(path);
            return true;
        } catch (UncheckedIOException e) {
            return false;
        }
    }
}
