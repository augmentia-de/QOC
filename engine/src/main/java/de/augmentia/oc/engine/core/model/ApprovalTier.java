package de.augmentia.oc.engine.core.model;

/** Capability tier used by approvals and audit. */
public enum ApprovalTier {
    READ,
    WRITE,
    EXEC;

    public static ApprovalTier forTool(String tool) {
        if (tool == null) return EXEC;
        return switch (tool) {
            case "read", "glob", "grep", "list", "webfetch", "skill", "skill_list",
                 "question", "todowrite", "models" -> READ;
            case "write", "edit", "patch", "worktree_create", "worktree_remove",
                 "session_rename", "session_move" -> WRITE;
            default -> EXEC;
        };
    }
}
