package de.augmentia.oc.engine.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public sealed interface Message {

    MessageId id();

    Instant time();

    record UserMessage(MessageId id, String text, List<Attachment> files, List<AgentId> agents,
                       List<SkillId> skills, Map<String, Object> metadata, Instant time) implements Message {
        public UserMessage {
            if (id == null) throw new IllegalArgumentException("id must not be null");
            if (text == null) text = "";
            files = files == null ? List.of() : List.copyOf(files);
            agents = agents == null ? List.of() : List.copyOf(agents);
            skills = skills == null ? List.of() : List.copyOf(skills);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            if (time == null) time = Instant.EPOCH;
        }
    }

    record SyntheticMessage(MessageId id, String text, Option<String> description,
                            Map<String, Object> metadata, Instant time) implements Message {
        public SyntheticMessage {
            if (id == null) throw new IllegalArgumentException("id must not be null");
            if (text == null) text = "";
            if (description == null) description = Option.none();
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            if (time == null) time = Instant.EPOCH;
        }
    }

    record SystemMessage(MessageId id, String text, Option<String> description,
                         Map<String, Object> metadata, Instant time) implements Message {
        public SystemMessage {
            if (id == null) throw new IllegalArgumentException("id must not be null");
            if (text == null) text = "";
            if (description == null) description = Option.none();
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            if (time == null) time = Instant.EPOCH;
        }
    }

    record SkillMessage(MessageId id, SkillId skill, String name, String text,
                        Map<String, Object> metadata, Instant time) implements Message {
        public SkillMessage {
            if (id == null || skill == null) throw new IllegalArgumentException("id and skill must not be null");
            if (name == null) name = "";
            if (text == null) text = "";
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            if (time == null) time = Instant.EPOCH;
        }
    }

    record ShellMessage(MessageId id, ShellInfo shell, Instant time) implements Message {
        public ShellMessage {
            if (id == null || shell == null) throw new IllegalArgumentException("id and shell must not be null");
            if (time == null) time = Instant.EPOCH;
        }
    }

    record AssistantMessage(MessageId id, AgentId agent, ModelRef model, List<Content> content,
                            Option<FinishReason> finish, Option<SessionError> error,
                            Option<RetryState> retry, Option<SnapshotRange> snapshot,
                            Money cost, TokenCount tokens, AssistantTime assistantTime) implements Message {
        public AssistantMessage {
            if (id == null || agent == null || model == null) throw new IllegalArgumentException("id, agent and model must not be null");
            content = content == null ? List.of() : List.copyOf(content);
            if (finish == null) finish = Option.none();
            if (error == null) error = Option.none();
            if (retry == null) retry = Option.none();
            if (snapshot == null) snapshot = Option.none();
            if (cost == null) cost = Money.zero();
            if (tokens == null) tokens = TokenCount.zero();
            if (assistantTime == null) assistantTime = AssistantTime.started(Instant.EPOCH);
        }

        @Override
        public Instant time() {
            return assistantTime.created();
        }
    }

    record CompactionMessage(MessageId id, CompactionStatus status, CompactionReason reason,
                             String summary, Option<SessionError> error, Money cost,
                             TokenCount tokens, Instant time) implements Message {
        public CompactionMessage {
            if (id == null || status == null || reason == null) throw new IllegalArgumentException("id, status and reason must not be null");
            if (summary == null) summary = "";
            if (error == null) error = Option.none();
            if (cost == null) cost = Money.zero();
            if (tokens == null) tokens = TokenCount.zero();
            if (time == null) time = Instant.EPOCH;
        }
    }

    record AgentSwitched(MessageId id, AgentId agent, AgentId previous, Instant time) implements Message {
        public AgentSwitched {
            if (id == null || agent == null || previous == null) throw new IllegalArgumentException("ids must not be null");
            if (time == null) time = Instant.EPOCH;
        }
    }

    record ModelSwitched(MessageId id, ModelRef model, ModelRef previous, Instant time) implements Message {
        public ModelSwitched {
            if (id == null || model == null || previous == null) throw new IllegalArgumentException("ids must not be null");
            if (time == null) time = Instant.EPOCH;
        }
    }

    record LocationSwitched(MessageId id, Location location, Location previous, Instant time) implements Message {
        public LocationSwitched {
            if (id == null || location == null || previous == null) throw new IllegalArgumentException("ids must not be null");
            if (time == null) time = Instant.EPOCH;
        }
    }

    record IdleMessage(MessageId id, IdleOutcome outcome, Option<SessionError> error, Instant time) implements Message {
        public IdleMessage {
            if (id == null || outcome == null) throw new IllegalArgumentException("id and outcome must not be null");
            if (error == null) error = Option.none();
            if (time == null) time = Instant.EPOCH;
        }
    }
}