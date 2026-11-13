package de.augmentia.qoc.workflowrunner.runner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests für die deterministischen Transformationen des compact-Nodes:
 * truncate, filterLines, filterKeys, bulletize. Gleiche Eingaben müssen
 * immer gleiche Ausgaben liefern (Replay-Sicherheit).
 */
class CompactNodeTransformTest {

    // ---------- truncate ----------

    @Test
    void truncateShortInputIsUnchanged() {
        String raw = "line1\nline2";
        assertEquals(raw, DefaultNodeDispatcher.truncate(raw, Map.of()));
    }

    @Test
    void truncateKeepsHeadAndTailWithSeparator() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("x").append(i % 10);
        String raw = sb.toString(); // 200 chars
        String out = DefaultNodeDispatcher.truncate(raw, Map.of("maxChars", 100));
        assertTrue(out.length() < 160, "output should be much shorter, was " + out.length());
        assertTrue(out.startsWith(raw.substring(0, 50)), "head preserved");
        assertTrue(out.endsWith(raw.substring(raw.length() - 50)), "tail preserved");
        assertTrue(out.contains("[..."), "separator present");
        assertTrue(out.contains("chars omitted"), "omitted count present");
    }

    @Test
    void truncateIsDeterministic() {
        String raw = "a".repeat(1000) + "b".repeat(1000);
        Map<String, Object> opts = Map.of("maxChars", 500, "keepHead", 300, "keepTail", 150);
        assertEquals(DefaultNodeDispatcher.truncate(raw, opts),
                DefaultNodeDispatcher.truncate(raw, opts));
    }

    @Test
    void truncateWithZeroTail() {
        String raw = "a".repeat(1000);
        String out = DefaultNodeDispatcher.truncate(raw,
                Map.of("maxChars", 400, "keepHead", 300, "keepTail", 0));
        assertTrue(out.startsWith("a".repeat(300)));
        assertTrue(out.contains("chars omitted"));
        assertFalse(out.endsWith("a".repeat(300)));
    }

    // ---------- filterLines ----------

    @Test
    void filterLinesIncludeOnly() {
        String raw = "error: null pointer\ninfo: ok\nerror: timeout\nwarn: hmm";
        String out = DefaultNodeDispatcher.filterLines(raw, Map.of("include", "error"));
        assertEquals("error: null pointer\nerror: timeout", out);
    }

    @Test
    void filterLinesIncludeList() {
        String raw = "error: a\ninfo: b\nwarn: c";
        String out = DefaultNodeDispatcher.filterLines(raw, Map.of("include", List.of("error", "warn")));
        assertEquals("error: a\nwarn: c", out);
    }

    @Test
    void filterLinesExclude() {
        String raw = "### section\ncontent line\n### another\nmore content";
        String out = DefaultNodeDispatcher.filterLines(raw, Map.of("exclude", "###"));
        assertEquals("content line\nmore content", out);
    }

    @Test
    void filterLinesNoOptsIsIdentity() {
        String raw = "keep\nall";
        assertEquals(raw, DefaultNodeDispatcher.filterLines(raw, Map.of()));
    }

    // ---------- filterKeys ----------

    @Test
    void filterKeysKeepsSubsetOfJsonObject() {
        String raw = "{\"status\":\"ok\",\"data\":[1,2],\"noise\":\"xxxxxxxx\"}";
        String out = DefaultNodeDispatcher.filterKeys(raw, Map.of("keys", List.of("status", "data")));
        assertTrue(out.contains("\"status\""));
        assertTrue(out.contains("\"data\""));
        assertFalse(out.contains("noise"));
        // deterministic
        assertEquals(out, DefaultNodeDispatcher.filterKeys(raw, Map.of("keys", List.of("status", "data"))));
    }

    @Test
    void filterKeysAppliesToListOfObjects() {
        String raw = "[{\"id\":1,\"payload\":\"aaaa\"},{\"id\":2,\"payload\":\"bbbb\"}]";
        String out = DefaultNodeDispatcher.filterKeys(raw, Map.of("keys", "id"));
        assertTrue(out.contains("\"id\":1"));
        assertTrue(out.contains("\"id\":2"));
        assertFalse(out.contains("payload"));
    }

    @Test
    void filterKeysNonJsonFallsBackToRaw() {
        String raw = "plain text not json";
        assertEquals(raw, DefaultNodeDispatcher.filterKeys(raw, Map.of("keys", "x")));
    }

    @Test
    void filterKeysWithoutKeysIsIdentity() {
        String raw = "{\"a\":1}";
        assertEquals(raw, DefaultNodeDispatcher.filterKeys(raw, Map.of()));
    }

    // ---------- bulletize ----------

    @Test
    void bulletizeProducesBulletsAndSkipsHeaders() {
        String raw = "### node1 (agent)\nresult one\n\n### node2 (end)\nresult two";
        String out = DefaultNodeDispatcher.bulletize(raw, Map.of());
        assertEquals("- result one\n- result two", out);
    }

    @Test
    void bulletizeWithIncludeFilter() {
        String raw = "error: x\nok: y\nerror: z";
        String out = DefaultNodeDispatcher.bulletize(raw, Map.of("include", "error"));
        assertEquals("- error: x\n- error: z", out);
    }

    @Test
    void bulletizeRespectsMaxChars() {
        String raw = "line-one-very-long-content\nline-two-very-long-content\nline-three";
        String out = DefaultNodeDispatcher.bulletize(raw, Map.of("maxChars", 40));
        assertTrue(out.contains("[... truncated ...]"));
        assertFalse(out.contains("line-three"));
    }

    @Test
    void bulletizeIsDeterministic() {
        String raw = "a: 1\nb: 2\nc: 3";
        assertEquals(DefaultNodeDispatcher.bulletize(raw, Map.of("include", "a")),
                DefaultNodeDispatcher.bulletize(raw, Map.of("include", "a")));
    }
}
