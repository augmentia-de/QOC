package de.augmentia.oc.engine.core;

import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.model.Principal;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.CallId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrincipalToolContextTest {
    @Test
    void anonymousDefaultWhenNoPrincipal() {
        ToolContext ctx = new ToolContext(
                SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());
        assertEquals("anonymous", ctx.principal().id());
    }

    @Test
    void principalPropagated() {
        Principal p = new Principal("alice", "Alice", java.util.List.of("admin"), java.util.List.of(), java.util.Map.of(), "tok");
        ToolContext ctx = new ToolContext(
                SessionId.create(), AgentId.create(), MessageId.create(), CallId.create(),
                null, p);
        assertEquals("alice", ctx.principal().id());
        assertTrue(ctx.principal().isAdmin());
        assertEquals("tok", ctx.principal().accessToken());
    }

    @Test
    void anonymousStatic() {
        assertEquals("anonymous", Principal.anonymous().id());
    }
}
