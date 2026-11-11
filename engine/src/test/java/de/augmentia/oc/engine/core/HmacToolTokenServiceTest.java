package de.augmentia.oc.engine.core;

import de.augmentia.oc.engine.adapter.security.HmacToolTokenService;
import de.augmentia.oc.engine.core.model.Principal;
import de.augmentia.oc.engine.core.port.world.ToolTokenService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HmacToolTokenServiceTest {
    @Test
    void issueAndValidate() {
        ToolTokenService svc = new HmacToolTokenService("secret-key");
        Principal p = new Principal("alice", "Alice", java.util.List.of("admin"), java.util.List.of(), java.util.Map.of(), null);
        String tok = svc.issue(p, "mcp_call", "ses_1", java.util.List.of("read"), 60);
        assertNotNull(tok);
        Principal v = svc.validate(tok);
        assertNotNull(v);
        assertEquals("alice", v.id());
    }

    @Test
    void expiredTokenInvalid() {
        HmacToolTokenService svc = new HmacToolTokenService("secret-key");
        // issue with 0 ttl -> exp = now (already expired at validation time+)
        Principal p = Principal.anonymous();
        String tok = svc.issue(p, "t", "s", java.util.List.of(), 1);
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        assertNull(svc.validate(tok));
    }

    @Test
    void tamperedTokenInvalid() {
        HmacToolTokenService svc = new HmacToolTokenService("secret-key");
        String tok = svc.issue(Principal.anonymous(), "t", "s", java.util.List.of(), 60);
        String bad = tok.substring(0, tok.length() - 2) + "xx";
        assertNull(svc.validate(bad));
    }

    @Test
    void disabledReturnsNull() {
        assertNull(ToolTokenService.disabled().issue(Principal.anonymous(), "t", "s", java.util.List.of(), 60));
    }
}
