package de.augmentia.oc.engine.core.model;

public record ShellInfo(String id, String command, String status, Option<Integer> exit) {
    public ShellInfo {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("shell id must not be blank");
        if (command == null) command = "";
        if (status == null) status = "unknown";
        if (exit == null) exit = Option.none();
    }
}