package de.augmentia.oc.engine.core.model;

public record SkillSummary(SkillId id, String name, String description, Option<String> source) {
    public SkillSummary {
        if (id == null) throw new IllegalArgumentException("skill id must not be null");
        if (name == null) name = "";
        if (description == null) description = "";
        if (source == null) source = Option.none();
    }
}