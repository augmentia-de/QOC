# 01 – Types: the tool contract

## 1. Definition

```
type ToolDefinition =
  { name: ToolName
  , description: Text
  , inputSchema: JsonSchema
  , outputSchema: Option<JsonSchema>
  , execute: (input: Json, context: ToolContext) -> Outcome
  , options: Option<ToolOptions> }

type ToolOptions =
  { namespace: Option<Text>      # effective name: <namespace>_<tool>
  , permission: Option<Action>   # for catalog visibility only
  , codemode: Bool               # default true
  , pinned: Bool }

type ToolContext =
  { sessionID: SessionID
  , agent: AgentID
  , messageID: MessageID
  , callID: CallID }
```

**The context is the only connection to the rest of the system.** A tool
knows no session, no history, no rules – it receives identifiers.

### Effective name

```
effective_name(definition) =
    if definition.options.namespace is missing:
        normalize(definition.name)                       # invalid characters → "_"
    else:
        normalize(namespace) + "_" + normalize(definition.name)
```

> The name is what the provider sees. Two tools from different
> origins may claim the same effective name – then the **later**
> valid registration wins.

## 2. Invocation

```
type ToolCall =
  { id: CallID            # unique only WITHIN a step
  , name: ToolName        # the name used by the provider
  , input: Json }         # unchecked – the provider delivers arbitrary JSON
```

> `callID` is unique **only within a step**. Durable events
> additionally carry the message ID, because they must be unique
> across steps (`specs/v2/session.md:76`).

## 3. Result

```
type Outcome = Ok(ToolResult) | Error(ToolFailure)

type ToolResult =
  { output: Option<Json>             # ephemeral: machine value for Code Mode
  , content: List<ToolContent>       # durable: what the model sees
  , metadata: Option<Map> }          # compact: UI-only

type ToolContent =
  | TextItem { text: Text }
  | FileItem { uri: Text, mime: Text, name: Option<Text> }

type ToolFailure =
  { message: Text                    # model-facing
  , cause: Option<Defect> }          # technical cause
```

### The rule “output ≠ content ≠ metadata”

| Field | Lifetime | Who reads it | Stored |
|---|---|---|---|
| `output` | only the call | Code Mode | **no** |
| `content` | durable | the model | **yes** (in the message) |
| `metadata` | only the display | UI | **no** (except status fields) |

> A tool **without** `output` must deliver non-empty `content`
> (`specs/v2/tools.md:142`). The runtime does not enforce this – it is
> an obligation for tool authors.

## 4. Registry

```
type Registration = { dispose: Effect }   # idempotent, real
```

**Semantics:**

* The newest **valid** active registration for an effective name wins.
* `dispose` removes **only this** registration and re-exposes the previous one.
* Registrations are **scoped**: when the scope ends, the registration ends.
* `reload()` rebuilds the state from the registered sources, **without**
  changing precedence.

```
type Registry =
  { list: () -> List<ToolDefinition>
  , snapshot: (permissions: Ruleset) -> Snapshot
  , register: (ToolDefinition) -> Registration
  , reload: () -> Effect }
```

### Snapshot

```
type Snapshot =
  { definitions: List<ToolDefinition>      # what the provider sees
  , codeModeCatalog: Option<Catalog>       # JS API for Code Mode
  , execute: (ToolCall, ToolContext) -> Outcome }
```

> **The snapshot is the contract.** Every model request receives a
> frozen snapshot. Execution resolves the name **in the snapshot**, never
> in the live registry. Anyone who reads the registry at call time
> executes a different tool than the one advertised.

## 5. Catalog visibility ≠ authorization

```
snapshot(permissions) =
    for each registered tool w:
        if there is a rule with
            action = w.options.permission or w.name
            resource = "*"
            effect = DENY:
            skip w                       # visibility only
        keep w
    …
```

> **This is expressly not authorization.** A tool that drops out of
> the catalog is not callable. A tool that **is** in the catalog still
> checks permissions itself in its leaf. Both at once is the normal
> case.

## 6. Execution chain

The contract in one line:

```
Pre-hook → name resolution (snapshot) → input decoding → permission check →
execution → output decoding → post-hook → content normalization → bounding
```

Details and the associated invariants in
[`05-contract-invariants.md`](./05-contract-invariants.md).

## 7. Type flow rules

| Rule | Why |
|---|---|
| `callID` and `messageID` are different types | a call ID is unique only per step |
| `input` is **unchecked** JSON | validation belongs in the chain, not the type |
| `output` is optional, `content` is a list | `content` is never null, but may be empty |
| `ToolFailure.message` is **model-facing** | the text lands in the tool result |
| `metadata` is never model-facing | otherwise the model sees UI state |
| A tool has **no** state | otherwise execution is not reproducible |

## 8. Verified semantics (reference checkout)

| Concept | Location |
|---|---|
| Definition, registry, snapshot, effective name | `packages/core/src/tool.ts:22-63`, `:227-296` |
| Execution chain (Decode → execute → Encode → Normalize) | `packages/core/src/tool/runtime.ts:30-62` |
| Name resolution in the snapshot, “not available in this request" | `packages/core/src/tool.ts:264-285` |
| Pre-/post-hook, only `execute.before` may fail | `packages/core/src/tool.ts:103-156`, `packages/core/src/plugin/hooks.ts:23` |
| Three values `output`/`content`/`metadata` | `packages/schema/src/tool.ts:86-101` |
| Catalog filter is visibility, not authorization | `packages/core/src/tool/AGENTS.md`, section “Permissions" |