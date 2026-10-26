package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.runtime.compaction.CompactionPolicy;
import de.augmentia.oc.engine.runtime.compaction.CompactionWindow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompactionWindowTest {

    @Test
    void splitKeepsAtLeastOneMessage() {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            history.add(new Message.UserMessage(MessageId.create(), "msg " + i,
                    List.of(), List.of(), List.of(), Map.of(), Instant.EPOCH));
        }
        CompactionWindow.Window window = CompactionWindow.split(history, 1);
        assertThat(window.toSummarize()).hasSize(2);
        assertThat(window.toKeep()).hasSize(1);
    }

    @Test
    void tokenBudgetKeepsMoreThanTheMinimumMessageCount() {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(new Message.UserMessage(MessageId.create(), "x".repeat(1000),
                    List.of(), List.of(), List.of(), Map.of(), Instant.EPOCH));
        }

        CompactionWindow.Window window = CompactionWindow.split(history,
                new CompactionPolicy(2, 600, 100), 1_000);

        assertThat(window.toKeep()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(window.toKeep().getFirst()).isInstanceOf(Message.UserMessage.class);
        assertThat(window.toSummarize().size() + window.toKeep().size()).isEqualTo(10);
    }

    @Test
    void emptyHistoryYieldsEmptyWindow() {
        CompactionWindow.Window window = CompactionWindow.split(List.of(), 8);
        assertThat(window.toSummarize()).isEmpty();
        assertThat(window.toKeep()).isEmpty();
    }

    @Test
    void exactSplit() {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(new Message.UserMessage(MessageId.create(), "msg " + i,
                    List.of(), List.of(), List.of(), Map.of(), Instant.EPOCH));
        }
        CompactionWindow.Window window = CompactionWindow.split(history, 10);
        assertThat(window.toSummarize()).isEmpty();
        assertThat(window.toKeep()).hasSize(10);
    }
}
