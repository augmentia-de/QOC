package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionId;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract of Conversation, run against the fake and PostgreSQL. */
class ConversationContract {

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#conversations")
    void messagesAreOrderedAndFilteredBySequence(Contracts.ConversationFixture fixture) {
        Message first = message("first");
        Message second = message("second");
        fixture.writer().insert(fixture.session(), Seq.of(1), first);
        fixture.writer().insert(fixture.session(), Seq.of(2), second);

        assertThat(fixture.conversation().messages(fixture.session(), Seq.zero()))
                .containsExactly(first, second);
        assertThat(fixture.conversation().messages(fixture.session(), Seq.of(1))).containsExactly(second);
        assertThat(fixture.conversation().messages(fixture.session(), Seq.of(2))).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#conversations")
    void sessionsAreSeparated(Contracts.ConversationFixture fixture) {
        Message message = message("only");
        fixture.writer().insert(fixture.session(), Seq.of(1), message);
        assertThat(fixture.conversation().messages(SessionId.create(), Seq.zero())).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#conversations")
    void messageIsFoundById(Contracts.ConversationFixture fixture) {
        Message message = message("find me");
        fixture.writer().insert(fixture.session(), Seq.of(1), message);

        assertThat(fixture.conversation().message(fixture.session(), message.id()).orElseThrow())
                .isEqualTo(message);
        assertThat(fixture.conversation().message(fixture.session(), MessageId.create()).isEmpty()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#conversations")
    void unknownSessionHasNoMessages(Contracts.ConversationFixture fixture) {
        assertThat(fixture.conversation().messages(SessionId.create(), Seq.zero())).isEmpty();
    }

    private static Message message(String text) {
        return new Message.UserMessage(MessageId.create(), text, List.of(), List.of(), List.of(),
                Map.of(), Instant.EPOCH);
    }
}
