package de.augmentia.oc.engine.core;
import de.augmentia.oc.engine.core.error.Aborted;
import de.augmentia.oc.engine.core.error.Defect;
import de.augmentia.oc.engine.core.error.StoreFailure;
import de.augmentia.oc.engine.core.model.*;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelTypesTest {

    @Test
    void eventIdRoundTrip() {
        EventId id = EventId.create();
        assertThat(EventId.parse(id.toString())).isEqualTo(id);
        assertThat(EventId.of(id.toString())).isEqualTo(id);
        assertThatThrownBy(() -> EventId.parse("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventId.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionInfoDefaults() {
        SessionInfo info = new SessionInfo(SessionId.create(), ProjectId.create(),
                new Location(Path.of("/tmp"), ProjectId.create(), Option.none()), "title",
                AgentId.create(), Option.none(), List.of(), null, null, null, null, null, null, 0, null);
        assertThat(info.title()).isEqualTo("title");
        assertThat(info.model().isEmpty()).isTrue();
        assertThat(info.cost().amount()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
        assertThat(info.tokens().total()).isZero();
        assertThat(info.created()).isEqualTo(Instant.EPOCH);
        assertThat(info.idle().isEmpty()).isTrue();
        assertThat(info.archived().isEmpty()).isTrue();
        assertThat(info.resumeAttempts()).isZero();
    }

    @Test
    void sessionInfoRejectsNegativeResumeAttempts() {
        assertThatThrownBy(() -> new SessionInfo(SessionId.create(), ProjectId.create(),
                new Location(Path.of("/tmp"), ProjectId.create(), Option.none()), "t",
                AgentId.create(), Option.none(), List.of(), null, null, null, null, null, null, -1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inboxEntryAndClaim() {
        InboxEntry entry = new InboxEntry(InboxId.create(), SessionId.create(),
                new InboxItem.UserItem("hi", List.of(), List.of(), List.of(), Map.of()),
                Delivery.QUEUE, Seq.of(4), null);
        assertThat(entry.delivery()).isEqualTo(Delivery.QUEUE);
        assertThat(entry.created()).isEqualTo(Instant.EPOCH);

        Claim claim = new Claim(SessionId.create(), null, 2);
        assertThat(claim.resumeAttempts()).isEqualTo(2);
        assertThat(claim.claimedAt()).isEqualTo(Instant.EPOCH);
        assertThatThrownBy(() -> new Claim(SessionId.create(), Instant.EPOCH, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void savedApprovalRequiresActionAndResource() {
        SavedApproval approval = new SavedApproval(PermissionRequestId.create(), ProjectId.create(),
                "shell", "git status *");
        assertThat(approval.resource()).isEqualTo("git status *");
        assertThatThrownBy(() -> new SavedApproval(PermissionRequestId.create(), ProjectId.create(), " ", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SavedApproval(PermissionRequestId.create(), ProjectId.create(), "x", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void credentialAndConnection() {
        Credential credential = new Credential("cred-1", "openai", "api-key", Option.some("secret"), true, null);
        assertThat(credential.secret().orElse("")).isEqualTo("secret");
        assertThat(credential.created()).isEqualTo(Instant.EPOCH);
        assertThatThrownBy(() -> new Credential("", "openai", "api-key", Option.none(), true, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Credential("c", " ", "api-key", Option.none(), true, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);

        Connection connection = new Connection("conn-1", "github", true);
        assertThat(connection.integration()).isEqualTo("github");
        assertThatThrownBy(() -> new Connection(" ", "github", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skillSummaryAndInstructions() {
        SkillSummary skill = new SkillSummary(SkillId.create(), "review", "reviews code", Option.none());
        assertThat(skill.description()).isEqualTo("reviews code");
        assertThat(skill.source().isEmpty()).isTrue();

        InstructionState state = new InstructionState(Seq.of(1), Seq.of(5),
                Map.of("a", "1"), Map.of("a", "2"));
        assertThat(state.initialValues()).containsEntry("a", "1");
        assertThat(state.currentValues()).containsEntry("a", "2");

        InstructionEntry entry = new InstructionEntry("key", "value", "hash");
        assertThat(entry.hash()).isEqualTo("hash");
        assertThatThrownBy(() -> new InstructionEntry(" ", "value", "hash"))
                .isInstanceOf(IllegalArgumentException.class);

        InstructionBlob blob = new InstructionBlob("hash", "value");
        assertThat(blob.value()).isEqualTo("value");
        assertThatThrownBy(() -> new InstructionBlob("", "value"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fileDiffKinds() {
        FileDiff diff = new FileDiff("a.txt", "old", "new", FileDiff.Kind.MODIFIED);
        assertThat(diff.kind()).isEqualTo(FileDiff.Kind.MODIFIED);
        assertThatThrownBy(() -> new FileDiff(" ", "old", "new", FileDiff.Kind.ADDED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventDraftAndStoredEvent() {
        SessionEvent payload = new SessionEvent.ExecutionStarted(SessionId.create());
        EventDraft draft = EventDraft.of("session-1", payload);
        assertThat(draft.type()).isEqualTo("ExecutionStarted");
        assertThat(draft.version()).isEqualTo(1);
        assertThatThrownBy(() -> new EventDraft(" ", 1, payload))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventDraft("session-1", 0, payload))
                .isInstanceOf(IllegalArgumentException.class);

        StoredEvent stored = StoredEvent.from("session-1", Seq.of(7), draft, Instant.EPOCH);
        assertThat(stored.type()).isEqualTo("ExecutionStarted");
        assertThat(stored.seq().value()).isEqualTo(7);
        assertThatThrownBy(() -> new StoredEvent("session-1", Seq.of(1), "type", 0, payload, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void messageVariants() {
        MessageId id = MessageId.create();
        Message.SyntheticMessage synthetic = new Message.SyntheticMessage(id, "s", Option.some("d"), Map.of(), null);
        assertThat(synthetic.description().orElse("")).isEqualTo("d");

        Message.SystemMessage system = new Message.SystemMessage(id, "sys", Option.none(), Map.of(), null);
        assertThat(system.text()).isEqualTo("sys");

        Message.SkillMessage skill = new Message.SkillMessage(id, SkillId.create(), "review", "body", Map.of(), null);
        assertThat(skill.name()).isEqualTo("review");

        Message.ShellMessage shell = new Message.ShellMessage(id,
                new ShellInfo("s1", "ls", "done", Option.of(0)), null);
        assertThat(shell.shell().command()).isEqualTo("ls");

        Message.CompactionMessage compaction = new Message.CompactionMessage(id,
                CompactionStatus.COMPLETED, CompactionReason.MANUAL, "summary", Option.none(), null, null, null);
        assertThat(compaction.status()).isEqualTo(CompactionStatus.COMPLETED);
        assertThat(compaction.cost().amount()).isEqualByComparingTo(java.math.BigDecimal.ZERO);

        Message.AgentSwitched agent = new Message.AgentSwitched(id, AgentId.create(), AgentId.create(), null);
        assertThat(agent.agent()).isNotNull();

        Message.ModelSwitched model = new Message.ModelSwitched(id,
                ModelRef.of(ProviderId.create(), ModelId.create()),
                ModelRef.of(ProviderId.create(), ModelId.create()), null);
        assertThat(model.model()).isNotNull();

        Message.LocationSwitched location = new Message.LocationSwitched(id,
                new Location(Path.of("/a"), ProjectId.create(), Option.none()),
                new Location(Path.of("/b"), ProjectId.create(), Option.none()), null);
        assertThat(location.location().directory()).isEqualTo(Path.of("/a"));

        Message.IdleMessage idle = new Message.IdleMessage(id, IdleOutcome.SUCCEEDED, Option.none(), null);
        assertThat(idle.outcome()).isEqualTo(IdleOutcome.SUCCEEDED);
    }

    @Test
    void inboxItemVariants() {
        InboxItem.SyntheticItem synthetic = new InboxItem.SyntheticItem("text", Option.some("desc"), Map.of());
        assertThat(synthetic.description().orElse("")).isEqualTo("desc");

        InboxItem compaction = new InboxItem.CompactionItem(CompactionReason.OVERFLOW);
        assertThat(compaction).isInstanceOf(InboxItem.class);

        InboxItem move = new InboxItem.MoveItem("/tmp");
        assertThat(move).isInstanceOf(InboxItem.class);
        assertThatThrownBy(() -> new InboxItem.MoveItem(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void optionSemantics() {
        Option<String> some = Option.some("x");
        Option<String> none = Option.none();
        assertThat(some.isPresent()).isTrue();
        assertThat(some.isEmpty()).isFalse();
        assertThat(none.isEmpty()).isTrue();
        assertThat(some.orElse("fallback")).isEqualTo("x");
        assertThat(none.orElse("fallback")).isEqualTo("fallback");
        assertThat(some.map(String::length).orElse(0)).isEqualTo(1);
        assertThat(none.map(String::length).isEmpty()).isTrue();
        assertThat(Option.of(null).isEmpty()).isTrue();
        assertThatThrownBy(() -> none.orElseThrow()).isInstanceOf(java.util.NoSuchElementException.class);
        assertThatThrownBy(() -> none.orElseThrow(() -> new IllegalArgumentException("empty")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorChannelsAreDistinct() {
        assertThat(new Aborted("stop")).isInstanceOf(RuntimeException.class);
        assertThat(new Defect("broken")).isInstanceOf(RuntimeException.class);
        assertThat(new Defect("broken", new IllegalStateException())).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(new StoreFailure("store")).isInstanceOf(RuntimeException.class);
        assertThat(new StoreFailure("store", new IllegalStateException())).hasCauseInstanceOf(IllegalStateException.class);
    }
}
