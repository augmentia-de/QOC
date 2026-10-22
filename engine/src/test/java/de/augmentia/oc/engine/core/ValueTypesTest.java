package de.augmentia.oc.engine.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueTypesTest {

    @Test
    void sessionIdRoundTrip() {
        SessionId id = SessionId.create();
        SessionId parsed = SessionId.parse(id.toString());
        assertThat(parsed).isEqualTo(id);
        assertThat(SessionId.of(id.toString())).isEqualTo(id);
    }

    @Test
    void sessionIdInvalid() {
        assertThatThrownBy(() -> SessionId.parse("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionId.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idsInvalidParse() {
        assertThatThrownBy(() -> ProjectId.parse("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageId.parse("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CallId.parse("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InboxId.parse("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PermissionRequestId.parse("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SnapshotId.parse("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentId.parse("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderId.parse("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelId.parse("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolNameNull() {
        assertThatThrownBy(() -> new ToolName(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moneyInvalid() {
        assertThatThrownBy(() -> new Money(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("not-a-number")).isInstanceOf(NumberFormatException.class);
    }

    @Test
    void modelRefInvalid() {
        assertThatThrownBy(() -> ModelRef.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelRef.parse("no-slash")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void seqInvalid() {
        assertThatThrownBy(() -> new Seq(-5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idsCreateAndParse() {
        // ProjectId tested below individually
        // simpler: equality of parse(create)
        ProjectId p = ProjectId.create();
        assertThat(ProjectId.parse(p.toString())).isEqualTo(p);
        MessageId m = MessageId.create();
        assertThat(MessageId.parse(m.toString())).isEqualTo(m);
        CallId c = CallId.create();
        assertThat(CallId.parse(c.toString())).isEqualTo(c);
        InboxId i = InboxId.create();
        assertThat(InboxId.parse(i.toString())).isEqualTo(i);
        PermissionRequestId r = PermissionRequestId.create();
        assertThat(PermissionRequestId.parse(r.toString())).isEqualTo(r);
        SnapshotId s = SnapshotId.create();
        assertThat(SnapshotId.parse(s.toString())).isEqualTo(s);
        AgentId a = AgentId.create();
        assertThat(AgentId.parse(a.toString())).isEqualTo(a);
        ProviderId pr = ProviderId.create();
        assertThat(ProviderId.parse(pr.toString())).isEqualTo(pr);
        ModelId md = ModelId.create();
        assertThat(ModelId.parse(md.toString())).isEqualTo(md);
    }

    @Test
    void toolNameValidation() {
        ToolName t = new ToolName("read");
        assertThat(t.value()).isEqualTo("read");
        assertThatThrownBy(() -> new ToolName(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void seqValidation() {
        Seq z = Seq.zero();
        assertThat(z.value()).isZero();
        assertThat(Seq.of(5).value()).isEqualTo(5);
        assertThatThrownBy(() -> new Seq(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moneyOperations() {
        Money a = Money.of("10.5");
        Money b = Money.of(2);
        assertThat(a.add(b).amount()).isEqualByComparingTo(new BigDecimal("12.5"));
        assertThat(Money.zero().amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(Money.of(3.0).amount()).isEqualByComparingTo(new BigDecimal("3.0"));
        assertThat(Money.of(new BigDecimal("7")).amount()).isEqualByComparingTo(new BigDecimal("7"));
    }

    @Test
    void tokenCountAndUsage() {
        TokenCount tc = TokenCount.of(3, 4);
        assertThat(tc.total()).isEqualTo(7);
        assertThat(TokenCount.zero()).isEqualTo(new TokenCount(0, 0));
        assertThatThrownBy(() -> new TokenCount(-1, 0)).isInstanceOf(IllegalArgumentException.class);

        Usage u = new Usage(1, 2, 3);
        assertThat(u.totalTokens()).isEqualTo(6);
        assertThat(u.toTokenCount()).isEqualTo(new TokenCount(1, 2));
        assertThat(Usage.zero().totalTokens()).isZero();
        assertThatThrownBy(() -> new Usage(1, -1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelRefParse() {
        ProviderId p = ProviderId.create();
        ModelId m = ModelId.create();
        ModelRef ref = ModelRef.of(p, m);
        assertThat(ModelRef.parse(ref.toString())).isEqualTo(ref);
        assertThatThrownBy(() -> ModelRef.parse("nope")).isInstanceOf(IllegalArgumentException.class);
    }
}