package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.Conversation;
import de.augmentia.oc.engine.testing.InMemoryConversation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract of {@link Conversation}. */
class ConversationContract {

    private InMemoryConversation conversation;
    private SessionId session;

    @BeforeEach
    void setUp() {
        conversation = new InMemoryConversation();
        session = SessionId.create();
    }

    @Test
    void messagesAreOrderedAndFilteredBySequence() {
        Message first = message("first");
        Message second = message("second");
        conversation.add(session, Seq.of(1), first);
        conversation.add(session, Seq.of(2), second);

        assertThat(conversation.messages(session, Seq.zero())).containsExactly(first, second);
        assertThat(conversation.messages(session, Seq.of(1))).containsExactly(second);
        assertThat(conversation.messages(session, Seq.of(2))).isEmpty();
    }

    @Test
    void sessionsAreSeparated() {
        Message message = message("only");
        conversation.add(session, Seq.of(1), message);
        assertThat(conversation.messages(SessionId.create(), Seq.zero())).isEmpty();
    }

    @Test
    void messageIsFoundById() {
        Message message = message("find me");
        conversation.add(session, Seq.of(1), message);

        assertThat(conversation.message(session, message.id()).orElseThrow()).isEqualTo(message);
        assertThat(conversation.message(session, MessageId.create()).isEmpty()).isTrue();
    }

    @Test
    void unknownSessionHasNoMessages() {
        assertThat(conversation.messages(SessionId.create(), Seq.zero())).isEmpty();
        assertThat(conversation.size(session)).isZero();
    }

    private static Message message(String text) {
        return new Message.UserMessage(MessageId.create(), text, List.of(), List.of(), List.of(),
                Map.of(), Instant.EPOCH);
    }
}
