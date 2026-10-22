package de.augmentia.oc.engine.core.model;

import java.util.List;
import java.util.Map;

public sealed interface InboxItem {
    record UserItem(String text, List<Attachment> files, List<AgentId> agents,
                    List<SkillId> skills, Map<String, Object> metadata) implements InboxItem {
        public UserItem {
            if (text == null) text = "";
            files = files == null ? List.of() : List.copyOf(files);
            agents = agents == null ? List.of() : List.copyOf(agents);
            skills = skills == null ? List.of() : List.copyOf(skills);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    record SyntheticItem(String text, Option<String> description, Map<String, Object> metadata) implements InboxItem {
        public SyntheticItem {
            if (text == null) text = "";
            if (description == null) description = Option.none();
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    record CompactionItem(CompactionReason reason) implements InboxItem {
        public CompactionItem {
            if (reason == null) throw new IllegalArgumentException("reason must not be null");
        }
    }

    record MoveItem(String directory) implements InboxItem {
        public MoveItem {
            if (directory == null || directory.isBlank()) throw new IllegalArgumentException("directory must not be blank");
        }
    }
}