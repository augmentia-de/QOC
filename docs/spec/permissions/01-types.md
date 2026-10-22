# 01 – Types

## 1. Rule and ruleset

```
type Effect = Allow | Deny | Ask

type Rule =
  { action: Text          # FREE string, not a closed enum
  , resource: Text        # wildcard pattern, not a glob
  , effect: Effect }

type Ruleset = List<Rule>   # ordered – the order is significant
```

> **Why `action` is a free string:** tools can introduce new actions
> (an MCP server brings `<server>_<tool>`). A closed enumeration would
> have to be extended for every server – and a rule that cannot be
> parsed would be an error in the wrong place.
>
> **Why the order is significant:** the **last** matching rule wins.
> That is why a ruleset is a list, not a set.

## 2. Request

```
type Request =
  { id: PermissionRequestID
  , sessionID: SessionID
  , action: Text
  , resources: List<Text>        # one or more
  , save: Option<List<Text>>     # suggestion for “always allow"
  , metadata: Option<Map>
  , source: Option<Source>
  , message: Option<Text> }      # set by a hook

type Source =
  { type: "tool"
  , messageID: MessageID
  , id: CallID }
```

> **`resources` is a list because an action can have several targets.**
> A filesystem access checks two resources (directory boundary + file),
> a shell command N (one subcommand per chain).
>
> **`save` comes from the tool, not the user.** The tool knows what a
> sensible save pattern is: `read` suggests `["*"]`, `shell` suggests
> `["git status *"]`. The user only decides whether it is stored.

## 3. Reply

```
type Reply = Once | Always | Reject
```

| Reply | Effect |
|---|---|
| `Once` | only this call is approved |
| `Always` | the `save` pattern is stored **per project**; afterwards all other open requests are re-evaluated and approved automatically where applicable |
| `Reject` | **all** parallel requests of the same session are rejected |

> **`Reject` is a batch.** Whoever gets three actions to approve at once
> and rejects one has expressed the intent to execute **none** of them.
> That is the reason for the batch abort.

## 4. Stored approval

```
type SavedApproval =
  { id: PermissionRequestID
  , projectID: ProjectID
  , action: Text
  , resource: Text }     # stored pattern
```

**Uniqueness:** `(projectID, action, resource)` is unique.

> **Project-wide, not session-wide.** An approval applies to the whole
> project – in all sessions working on it. This is a deliberate breadth:
> it keeps operation fluid and is at the same time the reason why it must
> be **visible and revocable** in the UI.

## 5. Events

```
type PermissionEvent =
  | Asked   { request: Request }      # EPHEMERAL
  | Replied { sessionID, requestID, reply: Reply }   # EPHEMERAL
```

> **Both are ephemeral.** They are delivered live but **not** logged.
> Consequences:
>
> * There is **no** permission audit log.
> * A restart loses open requests – the calling execution is aborted,
>   not resumed.
> * The stored approvals (`SavedApproval`) are the **only** durable trace.

## 6. Extension hook

```
type PermissionEvaluation =
  { sessionID: SessionID
  , agent: Option<AgentID>
  , action: Text
  , resources: List<Text>
  , metadata: Option<Map>
  , effect: Effect }
```

The hook may change **effect and message**:

```
hook("permission.evaluate", evaluation) -> { effect: Effect, message: Option<Text> }
```

> **It is the only place where an extension can reverse the decision**
> – even from “allowed" to “denied". This is intended: an
> organization-wide rule (e.g. “this tool is blocked in this
> organization") must be enforceable beyond normal configuration.
>
> **But it must not blur the channel separation:** a hook that produces
> a rejection produces a **denial** (error, model-facing) – not a
> rejection by the user.

## 7. Approval request (HITL)

```
type ApprovalRequest =
  { action: Text
  , context: Text           # what the user sees
  , reviewActions: List<Text> }   # only these actions are checked at all

type ApprovalResult =
  | Approved
  | Denied { feedback: Option<Text> }
```

> **Mapping to the permission automaton:** HITL is a **branch in the
> `ask` step**, not a separate mechanism. Instead of “the user answers
> in the prompt dock", the HITL provider asks (console, UI, ticket,
> email). The effect of the result is identical.
>
> **For actions outside the review list** the normal rule applies – HITL
> does not replace it; it answers the same question differently.

## 8. Type flow rules

| Rule | Why |
|---|---|
| `action` is `Text`, not an enum | tools bring their own actions |
| `resource` is `Text`, not a path type | commands, patterns, regexes and `*` are also resources |
| `Request.id` is opaque | requests are referenced via the ID (reply) |
| `Reply` has **no** feedback field | feedback belongs in a **separate** message; otherwise “reject with reason" is indistinguishable from “reject" |
| `SavedApproval` knows **no** session | it is project-wide |
| `PermissionEvent` is ephemeral | no audit log (deliberate) |

## 9. Verified semantics (reference checkout)

| Concept | Location |
|---|---|
| `Rule`, `Ruleset`, `Effect` | `packages/schema/src/permission.ts:56-67` |
| `Request`, `Reply`, `Source` | `packages/schema/src/permission.ts:17-43` |
| `asked`/`replied` as ephemeral | `packages/schema/src/permission.ts:45-54` |
| Stored approval (per project, unique) | `packages/core/src/permission/sql.ts:7-20` |
| Request from the tool (`save` suggestion) | `packages/core/src/tool/plugin/write.ts:78-86` |
| `ask` holds the call until answered | `packages/core/src/permission.ts:227-263` |