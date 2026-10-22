# 06 – Java 21 implementation

> What the tool contract looks like in Java 21. No framework mandate, but
> with the tools that Quarkus brings along.

## 1. Types

```java
public sealed interface ToolContext permits ToolContext.Default {
    record Default(String sessionId, String agentId, String messageId, String callId)
            implements ToolContext {}
}

/** Channel 1: expected failure. */
public sealed interface ToolFailure extends RuntimeException {
    String message();
    Optional<Throwable> cause();
}

public record ToolResult(JsonNode output, List<ToolContent> content, Map<String, Object> metadata) {
    public static ToolResult of(String text) {
        return new ToolResult(null, List.of(new ToolContent.Text(text)), Map.of());
    }
}

public sealed interface ToolContent {
    record Text(String text) implements ToolContent {}
    record File(String uri, String mime, String name) implements ToolContent {}
}

public record ToolDefinition(
        String name,
        String description,
        ToolSchema input,
        Optional<ToolSchema> output,
        BiFunction<JsonNode, ToolContext, Outcome> execute,
        ToolOptions options) {}
```

> **`ToolContext` is a `record`**, not an interface with many fields – the
> handover is a value object, and the four identifiers are **different
> types** in practice (`record SessionId(String)`, `record CallId(String)` …).

## 2. Registry with snapshot

```java
public final class ToolRegistry {

    private final List<Registration> registrations = new CopyOnWriteArrayList<>();
    private volatile Snapshot cached;

    public Registration register(ToolDefinition definition) {
        Registration reg = new Registration(definition);
        registrations.add(reg);
        invalidate();
        return reg;
    }

    /** Rebuilds the state from all registrations – in registration order. */
    public void reload() {
        Map<String, ToolDefinition> byName = new LinkedHashMap<>();
        for (Registration r : registrations) {
            if (!r.closed) byName.put(effectiveName(r.definition), r.definition);
        }
        cached = new Snapshot(Map.copyOf(byName));
        invalidate();
    }

    public Snapshot snapshot(PermissionRules rules) {
        Snapshot current = cached;
        if (current == null) { reload(); current = cached; }
        return current.filtered(rules);   // catalog visibility only
    }
}
```

**Two subtleties not to be overlooked:**

1. **`reload()` produces a new state**, without changing precedence.
   A snapshot held by a running request remains valid – otherwise a
   request mid-execution would lose its tool.
2. **Closing a registration re-exposes the predecessor**, because
   `reload()` applies all still-open registrations in order.

```java
public final class Registration implements AutoCloseable {
    private final ToolDefinition definition;
    private volatile boolean closed;

    @Override public void close() { closed = true; registry.reload(); }
}
```

## 3. Execution chain

```java
public final class ToolExecutor {

    private final ToolRegistry registry;
    private final PermissionEngine permissions;
    private final HookRegistry hooks;
    private final ToolOutput outputLimiter;

    public ToolOutcome execute(ToolCall call, ToolContext ctx, ToolSnapshot snapshot) {
        ToolDefinition definition;
        JsonNode input;
        ToolResult raw;

        try {
            // 1. name resolution IN THE SNAPSHOT
            definition = snapshot.require(call.name());
            if (definition == null) {
                return failed(new ToolFailure("Tool is not available for this request: " + call.name()) { });
            }

            // 2. pre-hook (may fail)
            ToolCallEvent before = hooks.beforeToolCall(ctx, call);
            if (before.cancelled()) return failed(new ToolFailure("Tool call cancelled: " + before.reason()) { });
            call = before.call();

            // 3. input decoding
            input = decodeInput(definition, call.input());      // throws ToolFailure

            // 4. permissions: a rejection LEAVES the method (no ToolFailure!)
            permissions.assert(ctx, definition.action(), call.resource(), definition.savePattern());

            // 5. execution
            raw = definition.execute().apply(input, ctx);       // throws ToolFailure

        } catch (ToolFailure failure) {
            // ONLY this family. Aborted and defects pass through untouched.
            return failed(failure);
        }

        // 6. output decoding
        JsonNode outputValue = definition.output()
                .map(schema -> encodeOutput(schema, raw.output()))
                .orElse(null);

        // 7. post-hook (must never fail)
        ToolResult after = hooks.afterToolCall(ctx, raw);

        // 8. normalization
        List<ToolContent> content = normalize(after.content(), outputValue);

        return new ToolOutcome.Ok(outputValue, content, after.metadata());
    }
}
```

> **Line 4 is the critical one.** A rejection is **not** a `ToolFailure` –
> it is the “abort" channel and leaves the method. That is why it stands
> **outside** the `try` block that only catches `ToolFailure`.
>
> **In Java this means:** `Aborted` and `Defect` are unchecked
> exceptions, but `catch (ToolFailure f)` does not catch them. Anyone
> writing `catch (Exception e)` destroys exactly this separation.

## 4. Bounding

```java
public final class ToolOutput {

    private static final int MAX_LINES = 2000;
    private static final int MAX_BYTES = 50 * 1024;
    private static final Duration RETENTION = Duration.ofDays(7);

    public ToolResult truncate(ToolResult result) {
        if (Boolean.TRUE.equals(result.metadata().get("truncated"))) return result;   // producer truncated

        String text = result.content().stream()
                .filter(ToolContent.Text.class::isInstance)
                .map(ToolContent.Text.class::cast)
                .map(ToolContent.Text::text)
                .collect(Collectors.joining("\n"));

        if (text.lines().count() <= MAX_LINES && text.getBytes(UTF_8).length <= MAX_BYTES) {
            return withMetadata(result, Map.of("truncated", false));
        }

        Path file = toolOutputDirectory.resolve("tool_" + Identifier.create());
        fs.writeString(file, text);                       // full output
        String marker = "[showing lines 1-%d of %d; full output saved to %s]"
                .formatted(kept, total, file);
        return withMetadata(result, Map.of("truncated", true, "outputPath", file.toString()));
    }
}
```

> **Bounding never changes `output`** – only `content`. That is why
> `output` and `content` are normalized separately.

## 5. Filesystem tools

```java
public final class FileAccess {

    /** Resolves a path and classifies it as internal or external. */
    public Target resolve(String path, Kind kind) {
        String absolute = resolvePath(location.directory(), path);
        boolean internal = FSUtil.contains(location.directory(), absolute)
                || (worktree != null && !worktree.equals("/") && FSUtil.contains(worktree, absolute));
        if (internal) {
            return new Target(absolute, relative(location.directory(), absolute), null);
        }
        String directory = kind == Kind.DIRECTORY ? absolute : parentOf(absolute);
        return new Target(absolute, absolute, new ExternalBoundary(
                directory, directory + "/*", projectRoot(directory) + "/*"));
    }
}
```

**Order in the leaf:**

```java
// read
Target target = access.resolve(input.path(), Kind.FILE);
access.authorizeExternal(List.of(target), ctx);        // 1. directory boundary
permission.assert(ctx, "read", List.of(target.resource()), List.of("*"));  // 2. file rule
```

> **The directory boundary comes first** – in both tools. Swapping it
> reports the wrong missing permission to the user.

## 6. Bash scanner

```java
public interface ShellScanner {
    ScanResult scan(String command, String shell, Path cwd);
}

public record ScanResult(List<ScannedCommand> commands, List<Path> directories) {}

public record ScannedCommand(String resource, String save) {}
```

**Two implementations, one interface:**

```java
public final class TreeSitterScanner implements ShellScanner {
    @Override public ScanResult scan(String command, String shell, Path cwd) {
        // parser tree: chained commands, redirections, pipes
        // per command: resource = command text, save = "<prefix> *"
    }
}

public final class PortableScanner implements ShellScanner {
    @Override public ScanResult scan(String command, String shell, Path cwd) {
        // heuristic: tokenization, known commands with argument counts
    }
}
```

**The save-pattern rule:**

```java
private static String savePattern(List<String> literalTokens) {
    return String.join(" ", literalTokens) + " *";
}
```

> **The scanner is interchangeable, the semantics are not.** Both deliver
> `commands` (per command `resource` + `save`) and `directories`.

**Order in the bash tool:**

```java
Target cwd = access.resolve(input.workdir(), Kind.DIRECTORY);
ScanResult scan = scanner.scan(input.command, shell, cwd);

List<Target> dirs = scan.directories().stream()
        .map(d -> access.resolve(d.toString(), Kind.DIRECTORY))
        .toList();
access.authorizeExternal(concat(List.of(cwd), dirs), ctx);      // 1. directory boundaries

if (!scan.commands().isEmpty()) {
    permission.assert(ctx, "shell",
            scan.commands().stream().map(ScannedCommand::resource).toList(),
            scan.commands().stream().map(ScannedCommand::save).toList());   // 2. commands
}

// 3. check working directory immediately before start
Files.checkDirectoryExists(cwd.absolute());
```

## 7. HITL

```java
public interface ApprovalProvider {
    ApprovalResult requestApproval(String action, String context);
}

public sealed interface ApprovalResult {
    record Approved() implements ApprovalResult {}
    record Denied(Optional<String> feedback) implements ApprovalResult {}
}
```

**The branch in the permission automaton:**

```java
case ASK -> {
    ApprovalResult approval = hitl.enabled()
            ? hitl.requestApproval(request.action(), describe(request))
            : askUser(request);                       // normal prompt dock

    switch (approval) {
        case Approved a -> { }
        case Denied d -> {
            if (d.feedback().isEmpty()) throw new Aborted("declined");   // channel: abort
            throw new PermissionRejectedException(request, d.feedback().get());  // channel: error
        }
    }
}
```

> **The one line that makes the difference:** `throw new Aborted(...)`
> instead of a tool error. With a tool error the model sees the
> rejection and retries.

**Checkpoint variant (long approvals):**

```java
public final class CheckpointService {
    public <T> T awaitApproval(String sessionId, String action, String arguments, Supplier<T> body) {
        Checkpoint cp = create(sessionId, action, arguments);
        agent.pause();
        try {
            Resolution r = await(cp);
            if (r.status() == APPROVED) return body.get();
            throw new Aborted(r.feedback() == null ? "rejected" : r.feedback());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Aborted("interrupted");
        } finally {
            complete(cp);
        }
    }
}
```

## 8. MCP

```java
public interface McpClient {
    /** Connection + catalog. */
    McpServer connect(McpServerConfig config) throws McpException;

    interface McpServer {
        String name();
        Status status();
        List<McpTool> tools();
        McpResult callTool(String toolName, Map<String, Object> args) throws McpException;
        void close();
    }
}
```

**Registration with debounce:**

```java
public final class McpToolRegistrar {

    private final Map<String, List<McpTool>> discovered = new ConcurrentHashMap<>();
    private final ScheduledExecutorService debounce = Executors.newSingleThreadScheduledExecutor();
    private volatile PendingChange pending;

    /** Exactly ONE stable registration that reads the current set. */
    public void install(ToolRegistry registry) {
        registry.register(definition);   // a single, durable tool
    }

    void onToolsChanged(String server) {
        pending = new PendingChange(server);
        debounce.schedule(() -> {
            discovered.computeIfAbsent(server, k -> client.tools(k));
            registry.reload();          // RELOAD, do not re-register
        }, 100, TimeUnit.MILLISECONDS);
    }
}
```

> **This is the point where MCP implementations usually break.** Anyone
> who registers a new tool on every tool-list change shifts the
> precedence relative to later plugin overrides.

**Content conversion:**

```java
private ToolContent toContent(McpContent part) {
    if (part instanceof McpContent.Text t) return new ToolContent.Text(t.text());
    String mime = part.mimeType().split(";")[0].trim().toLowerCase(Locale.ROOT);
    if (SUPPORTED_MEDIA.contains(mime)) {
        return new ToolContent.File("data:" + part.mimeType() + ";base64," + part.data(), part.mimeType(), null);
    }
    byte[] bytes = Base64.getDecoder().decode(part.data());
    if (isTextual(mime)) {
        return new ToolContent.Text("[Resource " + part.uri() + " (" + part.mimeType() + ")]\n"
                + new String(bytes, UTF_8));
    }
    Path file = toolOutput.save(bytes, extensionOf(mime));
    return new ToolContent.Text("[Binary resource " + part.uri() + " (" + part.mimeType()
            + ", " + bytes.length + " bytes) saved to " + file + "]");
}
```

## 9. The Java pitfalls this spec avoids

| # | Pitfall | Countermeasure |
|---|---|---|
| 1 | `catch (Exception)` at the tool boundary also catches **defects** | only `catch (ToolFailure f)` – and `Aborted`/`Defect` as own unchecked roots |
| 2 | `Optional` as field type | own `sealed interface Option<T>` |
| 3 | Registry read at call time | freeze the snapshot: definitions **and** executor |
| 4 | `InterruptedException` converted into a tool error | convert to `Aborted` **and** restore the interrupt flag |
| 5 | `CompletableFuture.cancel` does not abort if the target code does not check the interrupt | target code must check `Thread.interrupted()` |
| 6 | `StructuredTaskScope` is **preview** in Java 21 | `ExecutorService` (virtual threads) + own aggregation |
| 7 | A **global** `ReentrantLock` serializes all sessions | lock per session ID |
| 8 | Transaction across the tool call's thread boundary | three transactions: event → tool → result |