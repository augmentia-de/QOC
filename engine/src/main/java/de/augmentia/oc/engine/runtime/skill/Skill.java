package de.augmentia.oc.engine.runtime.skill;

import de.augmentia.oc.engine.core.model.SkillId;

import java.nio.file.Path;
import java.util.List;

/**
 * A skill is text with metadata: it is loaded, never executed.
 *
 * @param id          the contract (directory name or flat file name)
 * @param name        display name, defaults to the ID
 * @param description the only text the model sees beforehand; empty = unlisted
 */
public record Skill(SkillId id, String name, String description, String body,
                    Path baseDirectory, List<Path> files, boolean autoinvoke) {

    public Skill {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        if (name == null || name.isBlank()) name = id.value();
        if (description == null) description = "";
        if (body == null) body = "";
        if (baseDirectory == null) baseDirectory = Path.of(".");
        files = files == null ? List.of() : List.copyOf(files);
    }

    /** Listed means: description set and autoinvoke not disabled (SK-06). */
    public boolean listed() {
        return !description.isBlank() && autoinvoke;
    }

    public Skill withFiles(List<Path> newFiles) {
        return new Skill(id, name, description, body, baseDirectory, newFiles, autoinvoke);
    }

    /** Stable fingerprint for the guidance delta. */
    public String fingerprint() {
        return id + "|" + name + "|" + description + "|" + body.length() + "|" + body.hashCode();
    }
}
