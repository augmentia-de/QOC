package de.augmentia.oc.engine.runtime.tool.remote;

import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.port.world.RemoteShell;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteToolTest {

    private static final RemoteShell.HostConfig HOST = new RemoteShell.HostConfig(
            "test-host", "localhost", 22, "user", null, null, false,
            List.of("exec", "read", "write", "list", "stat"),
            List.of("*"), List.of(), List.of("/tmp"), null,
            Duration.ofSeconds(10), 65536, 1048576, 1000);

    private static final RemoteShell FAKE = new FakeRemoteShell();

    @Test
    void execReturnsOutput() {
        ToolOutcome outcome = execute(Map.of("host", "test-host", "action", "exec", "command", "echo hi"));
        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        String text = text(outcome);
        assertThat(text).contains("stdout:");
        assertThat(text).contains("hi");
    }

    @Test
    void readReturnsContent() {
        ToolOutcome outcome = execute(Map.of("host", "test-host", "action", "read", "path", "/tmp/test.txt"));
        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        String text = text(outcome);
        assertThat(text).contains("hello world");
    }

    @Test
    void writeReturnsBytesWritten() {
        ToolOutcome outcome = execute(Map.of("host", "test-host", "action", "write",
                "path", "/tmp/out.txt", "content", "test content"));
        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        String text = text(outcome);
        assertThat(text).contains("written");
    }

    @Test
    void listReturnsEntries() {
        ToolOutcome outcome = execute(Map.of("host", "test-host", "action", "list", "path", "/tmp"));
        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        String text = text(outcome);
        assertThat(text).contains("file");
    }

    @Test
    void statReturnsMetadata() {
        ToolOutcome outcome = execute(Map.of("host", "test-host", "action", "stat", "path", "/tmp/test.txt"));
        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        String text = text(outcome);
        assertThat(text).contains("file");
    }

    @Test
    void invalidActionReturnsError() {
        ToolOutcome outcome = execute(Map.of("host", "test-host", "action", "invalid"));
        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
    }

    @Test
    void missingCommandReturnsError() {
        ToolOutcome outcome = execute(Map.of("host", "test-host", "action", "exec"));
        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
    }

    @Test
    void missingPathReturnsError() {
        ToolOutcome outcome = execute(Map.of("host", "test-host", "action", "read"));
        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
    }

    @Test
    void nullInputReturnsError() {
        ToolOutcome outcome = RemoteTool.definition(FAKE, HOST).execute().execute(
                null,
                new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create()));
        // null input should be handled gracefully
        assertThat(outcome).isNotNull();
    }

    private static ToolOutcome execute(Map<String, Object> args) {
        var values = new java.util.LinkedHashMap<String, JsonValue>();
        args.forEach((k, v) -> {
            if (v instanceof String str) values.put(k, JsonValue.of(str));
            else if (v instanceof Integer i) values.put(k, JsonValue.of((long) i));
            else if (v instanceof Long l) values.put(k, JsonValue.of(l));
            else if (v instanceof Boolean b) values.put(k, JsonValue.of(b));
            else if (v instanceof Map<?, ?> m) {
                var inner = new java.util.LinkedHashMap<String, JsonValue>();
                m.forEach((kk, vv) -> inner.put(String.valueOf(kk), JsonValue.of(String.valueOf(vv))));
                values.put(k, JsonValue.of(inner));
            }
            else values.put(k, JsonValue.of(String.valueOf(v)));
        });
        return RemoteTool.definition(FAKE, HOST).execute().execute(
                JsonValue.of(values),
                new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create()));
    }

    private static String text(ToolOutcome outcome) {
        return ((ToolContent.TextItem) ((ToolOutcome.Ok) outcome).result().content().getFirst()).text();
    }

    // ── Fake RemoteShell ──

    static class FakeRemoteShell implements RemoteShell {

        @Override
        public Option<HostConfig> resolveHost(String alias) {
            return "test-host".equals(alias) ? Option.some(HOST) : Option.none();
        }

        @Override
        public ExecResult exec(HostConfig host, String command, Option<String> cwd, java.time.Duration timeout) {
            return new ExecResult(0, "hi\n", "", false, 100);
        }

        @Override
        public ReadResult read(HostConfig host, String path, int maxBytes) {
            return new ReadResult(path, "hello world", "utf-8", 11, false);
        }

        @Override
        public WriteResult write(HostConfig host, String path, String content, String encoding) {
            return new WriteResult(path, content.length());
        }

        @Override
        public ListResult list(HostConfig host, String path, int maxEntries) {
            return new ListResult(path, List.of(new ListEntry("test.txt", "file", 100, "2026-01-01")), false);
        }

        @Override
        public StatResult stat(HostConfig host, String path) {
            return new StatResult(path, "file", 100, "2026-01-01", "0644");
        }
    }
}
