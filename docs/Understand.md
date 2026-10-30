# Understand.md – LLM Call Flow & Tool Execution in QOC

> **Status:** Design note · 2026-10-30  
> **Target:** `de.augmentia.oc.engine` (Java 21 / Quarkus)  
> **Scope:** End-to-end LLM call from user input to final output (incl. prompt
> building), plus the minimal tool-execution path.

---

## 1. Simple LLM call: from input to output

The full path of a single turn, from user message to final assistant output:

| Step | Location | What happens |
|---|---|---|
| 1. User input | HTTP API (`/api/prompt` or SSE) | User message arrives; `Engine.prompt()` is called with session id, text, agent name |
| 2. Session resolve | `Engine.prompt()` | Resolve `SessionInfo`, `AgentProfile`, `ModelInfo`; merge agent + session permissions |
| 3. Tool snapshot | `Engine.prompt()` (before loop) | Build `ToolSnapshot` from agent tools, MCP, permissions, phase |
| 4. Context build | `ContextBuilder.prepare()` | Assemble system blocks: base prompt → AGENTS.md instructions → skill block (`<available_skills>`) → compaction checkpoint; history from `Conversation`; frozen tool list; output budget; cache affinity |
| 5. Request encode | `OpenAiRequestEncoder` | Serialize `Prepared` into OpenAI Chat Completions request: `messages[]`, `tools[]`, `tool_choice`, `max_tokens`, headers |
| 6. Provider call | `ProviderAdapter.stream()` | HTTP POST to provider; parse SSE/stream into `ModelEvent`s (`TextDelta`, `ToolCallRequested`, `Finished`, `ProviderFailed`) |
| 7. Step run | `Step.run()` | Collect events; if `ToolCallRequested` → execute tools via `ToolRuntime`; persist assistant turn (text + tool cards) to `Conversation`; repeat until no more tool calls or budget exhausted |
| 8. Persist | `Conversation.insert()` | Assistant message (text + tool results) stored with seq, usage, finish reason |
| 9. Output | SSE / HTTP response | `TextDelta` events streamed to client; final text returned; `StepResult.Finished` carries usage + tool calls |

### 1.1 Prompt building in detail (step 4)

`ContextBuilder.prepare()` assembles the system blocks in this order:

| Order | Block | Source |
|---|---|---|
| 1 | Base prompt | `agent.system()` or `basePrompt(tools)` (tool names + sys-guide) |
| 2 | AGENTS.md / instructions | `InstructionLoader.load(session.location().directory())` – project rules |
| 3 | Skill block | `SkillCatalog.instructions(rules)` → `<available_skills>` with id/name/description |
| 4 | Compaction checkpoint | `InstructionStore.state()` → summary of compacted history |
| 5 | History | `Conversation.messages()` folded via `ConversationFolding` (compaction-aware) |
| 6 | Step-limit reminder | Injected if `step >= agent.maxSteps()` |

### 1.2 Tool execution within a step (step 7)

When the model emits `ToolCallRequested`:

| Sub-step | What happens |
|---|---|
| a. Lookup | `ToolRuntime` finds tool by name in `ToolSnapshot` |
| b. Permission | `PermissionGate.assertAllowed()` checks rules for the tool |
| c. Execute | `ToolDefinition.executor.apply(args, context)` runs |
| d. Result | `ToolOutcome.Ok` → `ToolResult` with `ToolContent`; `ToolOutcome.Err` → error message |
| e. Persist | Tool card (input + result) added to assistant message content |
| f. Continue | If more tool calls or model wants another turn, loop repeats |

---

## 2. The end-to-end tool flow

A tool in QOC only works through this path:

| Step | Location | What happens |
|---|---|---|
| 1. Definition | `core/contract/tool/ToolDefinition` | Build the tool: `name`, `description`, `input` schema, `executor`, `options` |
| 2. Snapshot | `runtime/tool/*` + `ToolRegistry`/`ToolBundle` | Build a `ToolSnapshot` per request; filter by `agents[].tools.allow/deny`, MCP, permissions |
| 3. Context | `runtime/context/ContextBuilder.prepare()` | Pass `tools.list()` into `Prepared`; `OpenAiRequestEncoder` serializes them as native OpenAI `tools` |
| 4. Tool call | `runtime/loop/ToolLoop` (engine loop) | Read `tool_call` from provider event/response, look up tool by `ToolName` in the current `ToolSnapshot` |
| 5. Execution | `ToolDefinition.executor` | Invoke executor with `args` (`JsonValue`) + `ToolContext` |
| 6. Result | `ToolOutcome.Ok` / `Err` | Map to `ToolResult` with `ToolContent`, merge into history, next turn continues |

**Important:** QOC uses native tool calling. Tool schemas go into the API `tools`
field, not into the system prompt. Only the skill index is inlined as text.

---

## 3. Skills: loading, filtering, injection

Skills are loaded from the filesystem and injected into the system prompt as a
compact index. Full skill content is loaded on demand via the `skill` tool.

### 3.1 Skill loading flow

| Step | Location | What happens |
|---|---|---|
| 1. Discovery | `SkillScanner.scan(projectRoot)` | Scan `<project>/skill` and `<project>/skills` directories (max depth 6, max 10 files per skill) |
| 2. Parse | `SkillParser.parse(file)` | Parse SKILL.md frontmatter (id, name, description, listed, autoinvoke) + body |
| 3. Global filter | `SkillCatalog.of(skills, allow, deny, max)` | Apply `skills.allow`/`skills.deny` patterns; cap with `skills.max` (0 = unlimited) |
| 4. Reload | `SkillCatalog.reload(newSkills)` | Increment epoch; `guidanceDelta()` detects added/changed/removed |
| 5. Permission filter | `SkillCatalog.listed(rules)` | Remove skills where `PermissionRules.evaluate(rules, "skill", id) == DENY` |
| 6. Render | `SkillCatalog.instructions(rules, selection)` | Apply the per-agent `NONE`/`CORE`/`TOP_K`/`ALL` selection and produce the `<available_skills>` block |
| 7. Inject | `ContextBuilder.prepare()` | Add skill block to system blocks (after AGENTS.md, before checkpoint) |
| 8. Load on demand | `SkillTool.execute()` | Model calls `skill(id)` → catalog returns full `<skill_content>` with body + files |

### 3.2 Skill permission layers

| Layer | Mechanism | Config |
|---|---|---|
| 1. Discovery filter | `skills.enabled`, `skills.paths`, `skills.allow`, `skills.deny`, `skills.max` | `settings.json` → `skills` |
| 2. Per-agent filter | `agents[].skills.allow/deny` compiled into `skill` permission rules | `settings.json` → `agents[].skills` |
| 3. Prompt filter | `listed(rules)` removes `DENY` skills from `<available_skills>` | Agent + session permissions |
| 4. Execution guard | `ToolRuntime` permission gate checks `skill`/ID before `SkillTool` runs | Same rules; `ASK` is enforced there |

Permission action: `"skill"` with skill ID as pattern, effect `ALLOW`/`DENY`/`ASK`.

#### Per-agent skill configuration

```json
{
  "agents": [{
    "name": "plan",
    "tools": { "allow": ["read", "glob", "grep", "list", "skill", "skill_list"], "deny": [] },
    "skills": {
      "allow": ["plan-*", "review"],
      "deny": ["deploy-*"],
      "max": 8,
      "prompt": "top-k",
      "core": ["review"],
      "filter": { "topK": 5, "minScore": 0.3, "thresholdCount": 10 }
    }
  }]
}
```

| Field | Meaning |
|---|---|
| `allow` | empty = all; only matching IDs are visible/loadable |
| `deny` | wins over allow |
| `max` | cap for the prompt block (0 = unlimited) |
| `prompt` | `none` (no block, tool stays), `core`, `top-k` (default), `all` |
| `core` | patterns for `prompt: core` |
| `filter` | per-agent override of the global `skills.filter` |

`allow`/`deny` become permission rules in this order: `deny *` (when `allow`
is non-empty), one `allow` per allow pattern, one `deny` per deny pattern. The
last matching rule wins, so permissions and session rules stay authoritative.

### 3.3 Skill content loading (on demand)

When the model calls `skill(id)`:

| Sub-step | What happens |
|---|---|
| a. Permission check | `PermissionRules.evaluate(rules, "skill", id)` → if `DENY`, return error |
| b. Lookup | `SkillCatalog.find(SkillId.parse(id))` → if not found, return "unable to load" |
| c. Render | `SkillCatalog.content(skill)` → `<skill_content>` with name, body, base directory, file list |
| d. Return | `ToolOutcome.Ok` with `ToolContent.TextItem` containing full skill content |

### 3.4 Skill listing (including hidden)

`skill_list` tool returns all catalog entries (including `listed=false` and
`autoinvoke=false` skills), filtered only by `DENY` permissions. Each entry
shows id, description, and `(hidden)` marker if not listed.

### 3.5 Skill update delta

`SkillCatalog.guidanceDelta(previous)` computes added/changed/removed skill IDs
between two catalog epochs. This can be used for incremental prompt updates
instead of full re-render (currently available but not yet wired into
`ContextBuilder`).

---

## 4. MCP: loading, discovery, registration

MCP (Model Context Protocol) servers are managed by `McpServerManager`. Their
tools are discovered at startup/reload and registered into the `ToolRegistry`
with a server-prefixed name. Instructions about available MCP servers are
injected into the system prompt.

### 4.1 MCP loading flow

| Step | Location | What happens |
|---|---|---|
| 1. Config | `EngineConfig` (settings.json → `mcp`) | Parse `mcp.<server>` entries (local stdio or remote HTTP/SSE) |
| 2. Add | `McpServerManager.add(config)` | Create `ServerEntry`; if disabled → status `DISABLED`; otherwise discover |
| 3. Connect | `McpServerManager.client(config)` | Local: `McpStdioClient` (process runner); Remote: `McpDiscoveryClient` (HTTP transport) |
| 4. Discover | `McpClient.listTools()` | JSON-RPC `tools/list` → returns `McpToolSpec(name, description, inputSchema)` |
| 5. Register | `McpRegistrar.register(registry, server, tools, client)` | Create `ToolDefinition` per tool with effective name `server_tool`; register in `ToolRegistry` |
| 6. Status | `ServerEntry.status` | `CONNECTED` on success, `FAILED` on error (with message), `PENDING` during discovery |
| 7. Instructions | `McpInstructions.build(discovered, rules)` | Build `<mcp_instructions>` block listing servers + tools (only those not entirely denied) |
| 8. Inject | `ContextBuilder.prepare()` | Add MCP instructions block to system blocks (if non-empty) |
| 9. Reload | `McpServerManager.reload(name)` | Close previous registrations, re-discover, re-register |

### 4.2 MCP tool execution

When the model calls an MCP tool (e.g., `github_create_issue`):

| Sub-step | What happens |
|---|---|
| a. Lookup | `ToolRuntime` finds tool in `ToolSnapshot` by effective name |
| b. Permission | `PermissionGate.assertAllowed()` checks rules for `server_tool` |
| c. Execute | `McpTool.execute(client, tool, args)` → `client.call(tool, arguments)` (JSON-RPC `tools/call`) |
| d. Map | `McpContentMapper.map(result)` converts MCP content to `ToolContent` items |
| e. Error handling | If MCP returns `isError=true` → `ToolOutcome.Failed`; if `McpFailure` → `ReadTool.failure` |
| f. Return | `ToolOutcome.Ok` with mapped content |

### 4.3 MCP resource access

MCP servers can expose resources (files, data) beyond tools:

| Operation | Location | Description |
|---|---|---|
| List resources | `McpServerManager.resources()` | Aggregate `listResources()` from all connected servers |
| List templates | `McpServerManager.resourceTemplates()` | Aggregate `listResourceTemplates()` |
| Read resource | `McpServerManager.readResource(server, uri)` | `client.readResource(uri)` → content list |
| Resource tools | `McpResourceTools` | Exposes `list_mcp_resources` and `read_mcp_resource` as QOC tools |

### 4.4 MCP permission model

| Aspect | Detail |
|---|---|
| Tool permission | `"mcp:<server>"` or `"<server>_<tool>"` as permission action pattern |
| Instructions filter | `McpInstructions.build()` filters tools where `PermissionRules.evaluate(rules, "server_tool", "*") == DENY` |
| Server allowlist | `agents[].mcpServers` lists which MCP servers an agent may use |
| OAuth | `McpOAuth` handles OAuth2 flow for remote servers requiring auth |
| Needs auth | `McpNeedsAuth` signals when a server requires authentication |

### 4.5 MCP server types

| Type | Client | Transport | Config |
|---|---|---|---|
| Local (stdio) | `McpStdioClient` | Process runner (stdin/stdout) | `mcp.<name>.command`, `mcp.<name>.args` |
| Remote (HTTP/SSE) | `McpDiscoveryClient` | HTTP transport | `mcp.<name>.url`, optional OAuth |

---

## 5. Core contracts (for reference)

```java
// core/contract/tool/ToolDefinition.java
public record ToolDefinition(
        ToolName name,
        String description,
        Schema input,
        Option<Schema> output,
        BiFunction<JsonValue, ToolContext, ToolOutcome> executor,
        ToolOptions options
) {}

// core/contract/tool/ToolOutcome.java
public sealed interface ToolOutcome permits ToolOutcome.Ok, ToolOutcome.Err {
    record Ok(ToolResult result) implements ToolOutcome {}
    record Err(String message, Option<Map<String, JsonValue>> meta) implements ToolOutcome {}
}

// core/contract/tool/ToolResult.java
public record ToolResult(Option<String> title,
                         List<ToolContent> content,
                         Map<String, JsonValue> metadata) {}

// core/contract/tool/ToolContext.java (simplified)
public interface ToolContext {
    SessionId sessionId();
    AgentId agentId();
    ProjectId projectId();
    Path directory();
    Map<String, Object> metadata(); // e.g. PrincipalContext
}
```

---

## 6. Minimal working example: `echo` tool

Create the tool in `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/misc/EchoTool.java`:

```java
package de.augmentia.oc.engine.runtime.tool.misc;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.tool.JsonArgs;

import java.util.List;
import java.util.Map;

public final class EchoTool {
    private EchoTool() {}

    public static ToolDefinition definition() {
        Schema input = Schema.obj(Map.of(
                "text", Schema.str("Text to echo")
        ));

        return new ToolDefinition(
                new ToolName("echo"),
                "Echo the provided text back to the model.",
                input,
                Option.none(),
                (value, context) -> {
                    try {
                        String text = JsonArgs.requiredString(value, "text");
                        ToolResult result = new ToolResult(
                                Option.some("echo"),
                                List.of(new ToolContent.TextItem(text)),
                                Map.of("source", JsonValue.str("echo"))
                        );
                        return new ToolOutcome.Ok(result);
                    } catch (IllegalArgumentException e) {
                        return new ToolOutcome.Err(e.getMessage(), Option.none());
                    }
                },
                null
        );
    }
}
```

Notes:

- Use `JsonArgs` instead of direct casting – consistent error messages.
- Prefer `ToolOutcome.Err` over throwing; the ToolLoop translates it cleanly.
- `ToolContent.TextItem` is enough for a minimal tool.
- `ToolOptions` can be `null` unless you need approval/hidden flags.

---

## 7. Registration (so the tool is actually callable)

The tool must reach the `ToolSnapshot`. A typical wiring point looks like this:

```java
@ApplicationScoped
public class DefaultToolBundle {
    public List<ToolDefinition> tools(AgentProfile agent, List<Rule> rules) {
        List<ToolDefinition> list = new ArrayList<>();
        list.add(ReadTool.definition(fs));
        list.add(WriteTool.definition(fs, rules));
        list.add(EditTool.definition(fs, rules));
        list.add(GlobTool.definition(fs));
        list.add(GrepTool.definition(fs));
        list.add(ShellTool.definition(shell, rules));
        list.add(SkillTool.definition(skills, agentId -> rulesFor(agentId)));
        list.add(SkillTool.listDefinition(skills, agentId -> rulesFor(agentId)));
        // NEW
        list.add(EchoTool.definition());
        return list;
    }
}
```

Then the filtered list becomes a `ToolSnapshot`:

```java
ToolSnapshot snapshot = ToolSnapshot.of(filteredTools);
```

Only tools present in the snapshot are callable. If the model sends a name
that is not in the snapshot, the ToolLoop rejects it.

---

## 8. Permissions

For a harmless test tool, no explicit permission is needed. For
agent-specific control, add an entry in `settings.json`:

```json
{
  "agents": [
    {
      "name": "build",
      "permissions": [
        { "permission": "tool", "pattern": "echo", "action": "allow" }
      ]
    }
  ]
}
```

The permission key for tools is typically `"tool"` with the tool name as
pattern (analogous to `"skill"`). Always verify the actual key used in your
agent/bundle wiring.

---

## 9. End-to-end test

Once registered:

1. Create a session / start the agent.
2. Send: *"Call the tool `echo` with {"text":"hello world"}."*
3. The model should emit a `tool_call` with `name: "echo"` and correct args.
4. `ToolLoop` finds `echo` in the snapshot → invokes `EchoTool.definition().executor(...)`.
5. Result appears as a tool result in the stream/history.
6. The model can react to the echoed text.

For a faster check, a unit test close to real execution works:

```java
@Test
void echoesText() {
    var def = EchoTool.definition();
    var ctx = mock(ToolContext.class);
    var out = def.executor().apply(
            JsonValue.obj(Map.of("text", JsonValue.str("hi"))), ctx);
    assertTrue(out instanceof ToolOutcome.Ok);
    var res = ((ToolOutcome.Ok) out).result();
    assertEquals("hi", ((ToolContent.TextItem) res.content().get(0)).text());
}
```

---

## 10. Common pitfalls

| Pitfall | Fix |
|---|---|
| Tool not in snapshot | Register it + check permissions/agent config. Only snapshot tools are callable. |
| Wrong schema | Use `Schema.obj(...)` consistently; this is what reaches the model. |
| Arg parsing | Use `JsonArgs`, not direct casts – consistent error text. |
| Exception instead of `ToolOutcome.Err` | Prefer `Err`; otherwise the loop may abort unexpectedly. |
| Empty `ToolContext` | Not needed for pure echo; fetch FS/session via `ToolContext` when needed. |
| Approval/hidden flags | Start with `null` for `ToolOptions`; set only when required. |

---

## 11. Summary

**Absolute minimum:**

1. Build a `ToolDefinition` with an `executor`.
2. Put it into the `ToolSnapshot`.
3. Expose it to the model as a native tool.
4. Let `ToolLoop` run the executor.

That is the entire `echo` path. Everything else (permissions, filtering,
logging, approval) is scaffolding around this single function call.
