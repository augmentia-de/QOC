# 05 – The tool contract: chain, errors, invariants

> The binding rules for **all** tools – regardless of family.
> The invariants are numbered `W-xx`.

## 1. The execution chain

```
Pre-hook            → may rewrite the input; the ONLY hook that may fail
Name resolution     → in the frozen snapshot, not the live registry
Input decoding      → on failure: NO execution
Permission check    → in the leaf, before the side effect
Execution           → the side effect
Output decoding     → on failure: tool error
Post-hook           → may change the result; must NEVER fail
Normalization       → content never empty (derivable from output)
Bounding            → in the runner, after execution
```

### What happens where

| Step | Place | Error channel |
|---|---|---|
| Pre-hook | Registry/runner | tool error (the call is rejected) |
| Name resolution | Snapshot | tool error (“not available in this request") |
| Input decoding | Runtime | tool error (formulated so it can be corrected) |
| Permission check | Leaf | **denial** (error), **rejection** (abort or error with feedback) |
| Execution | Leaf | tool error |
| Output decoding | Runtime | tool error |
| Post-hook | Runtime | **must not fail** |
| Normalization | Runtime | – |
| Bounding | Runner | – |

## 2. The three error channels at the tool

```
Tool error       → model-facing (text in the tool result)
Rejection (no reason) → ABORT: the step ends, the model sees NOTHING
Defect           → run abort, loud log, no model effect
```

> **Why the separation matters so much:** An rejection text in the
> tool result causes the model to retry the action (up to the step
> limit). That is a mistake made in almost every agent implementation.

## 3. Invariants – general contract

| # | Rule | Check type |
|---|---|---|
| **W-01** | WHEN a tool runs, THEN **exactly that** version runs that was advertised in the model request. | Effect |
| **W-02** | WHEN the input is not decoded, THEN the tool is **not** executed. | Effect |
| **W-03** | WHEN the output value is not decoded, THEN there is **no** successful run. | Effect |
| **W-04** | WHEN a tool declares an output value, THEN it MUST return one. | Effect |
| **W-05** | WHEN a tool declares no output value, THEN it **should** deliver non-empty model content (obligation, not enforced). | State |
| **W-06** | WHEN an extension module throws a foreign error, THEN it is normalized to a tool error at the tool boundary. | Effect |
| **W-07** | WHEN a tool call begins, THEN it is **durably logged before** the side effect begins. | Log |
| **W-08** | WHEN a tool call ends, THEN there is **exactly one** terminal event. | Log |
| **W-09** | WHEN a tool needs an approval, THEN the approval is checked **before** the side effect. | Flow |
| **W-10** | WHEN a rejection without feedback occurs, THEN the model sees **nothing** and the step ends. | State |
| **W-11** | WHEN a rejection with feedback occurs, THEN the model sees the feedback text and may continue. | State |
| **W-12** | WHEN a tool is filtered from the catalog, THEN that is **catalog visibility**, not execution authorization. | Flow |
| **W-13** | WHEN a tool is filtered from the catalog, THEN its **nested** tools keep their own rules. | State |
| **W-14** | WHEN a registration is closed, THEN **only this one** disappears; the previous one becomes visible again. | State |
| **W-15** | WHEN the output is truncated, THEN the **machine output value does not change** – only the visible content. | State |
| **W-16** | WHEN the output is truncated, THEN the **full** output remains findable (path in the metadata). | Effect |
| **W-17** | WHEN a post-hook runs, THEN it must **not** fail. | Effect |
| **W-18** | WHEN a pre-hook rejects a tool, THEN the call is **not** executed. | Effect |
| **W-19** | WHEN a tool produces a **defect**, THEN it is **not** converted into a tool error. | Effect |
| **W-20** | WHEN a tool from a foreign module throws a foreign error family, THEN **only that family** is caught (no `catch (Exception)`). | Effect |
| **W-21** | WHEN a tool mutates a file, THEN the **byte content** is not durably logged. | Log |
| **W-22** | WHEN a `read` finds an `AGENTS.md`, THEN it is injected as a **durable** instruction – not via a redirect that creates a layer cycle. | Log |

## 4. Invariants – filesystem

| # | Rule | Check type |
|---|---|---|
| **W-30** | WHEN a path lies outside location and project worktree, THEN the **directory boundary** is checked first, then the file rule. | State |
| **W-31** | WHEN the directory-boundary check fails, THEN the file rule is **not** checked. | Flow |
| **W-32** | WHEN `write`, `edit` or `patch` runs, THEN the permission action is **`edit`** – for all three. | State |
| **W-33** | WHEN `grep` runs, THEN the permission resource is the **regex**, not the search path. | State |
| **W-34** | WHEN `glob` runs, THEN the permission resource is the **pattern**. | State |
| **W-35** | WHEN a `read` does not find a file, THEN it **fails** – it delivers **no** empty content. | Effect |
| **W-36** | WHEN a `read` reads a binary file without image/PDF, THEN it **fails**. | Effect |
| **W-37** | WHEN a mutation of several files partially fails, THEN the **already applied** changes are **reported**, not hidden. | State |
| **W-38** | WHEN two mutations affect the same path, THEN they are **serialized** (process-local). | Flow |
| **W-39** | WHEN a mutation begins, THEN a snapshot exists **beforehand** (if Undo is active). | Effect |
| **W-40** | WHEN a `read` delivers an image or PDF, THEN the content is passed **inline** (base64), not into managed storage. | State |
| **W-41** | WHEN the directory boundary is not expressly governed, THEN “allow" applies – external accesses raise **no** prompt. | State |

## 5. Invariants – Bash and HITL

| # | Rule | Check type |
|---|---|---|
| **W-50** | WHEN a shell command is chained, THEN **every** subcommand is checked **individually**. | State |
| **W-51** | WHEN a command touches an external directory, THEN the directory boundary is **additionally** checked. | State |
| **W-52** | WHEN a command is not analyzable, THEN that is a **technical error**, not a rejection. | Effect |
| **W-53** | WHEN approval for a command has been granted, THEN the working directory is checked **immediately before** start. | Flow |
| **W-54** | WHEN a command runs in the background, THEN it has **no** timeout and the call returns **immediately**. | Flow |
| **W-55** | WHEN a background command ends, THEN the session is **notified** (synthetic message) – the agent need not poll. | State |
| **W-56** | WHEN a foreground command exceeds the timeout, THEN the message is **augmented with a possible larger timeout**. | State |
| **W-57** | WHEN a HITL checkpoint is open, THEN the agent is **paused**, not terminated. | Flow |
| **W-58** | WHEN a HITL checkpoint is rejected, THEN the agent run ends with the rejection. | Flow |
| **W-59** | WHEN a PTY access occurs, THEN it runs via a **short-lived ticket** (TTL 60 s), not via a durable permission. | State |
| **W-60** | WHEN the session ends, THEN its PTYs end too. | Flow |

## 6. Invariants – MCP

| # | Rule | Check type |
|---|---|---|
| **W-70** | WHEN an MCP tool is registered, THEN its name carries the **server's namespace**. | State |
| **W-71** | WHEN an MCP tool runs, THEN its permission action is **identical** to its name. | State |
| **W-72** | WHEN a server's tool list changes, THEN the registry state is **reloaded** – not re-registered at the end of the transform order. | State |
| **W-73** | WHEN a tool list changes quickly, THEN the changes are **debounced** (100 ms) and **aggregated**. | Flow |
| **W-74** | WHEN the first discovery runs, THEN the first step of a session waits for it to finish. | Flow |
| **W-75** | WHEN an MCP server fails, THEN the **other** servers remain functional. | Flow |
| **W-76** | WHEN an MCP server needs authorization, THEN its status is **`needs_auth`** – not an error. | State |
| **W-77** | WHEN an MCP result reports `isError`, THEN it becomes a **tool error** with the text content. | State |
| **W-78** | WHEN an MCP result contains binary media, THEN the content is written to **managed storage** and only referenced. | State |
| **W-79** | WHEN a server's tools are blocked, THEN its **instructions** do not appear in the prompt. | State |
| **W-80** | WHEN a server is disabled, THEN it is **not** connected. | Flow |

## 7. What does **not** belong to the tools

| Thing | Belongs to |
|---|---|
| Session state, event log, delivery | [`../session-engine/`](../session-engine/README.md) |
| The rule engine, stored approvals, HITL authority | [`../permissions/`](../permissions/README.md) |
| The model call, error classification | [`../provider/`](../provider/README.md) |
| Managed storage for large outputs | Tool contract (§Bounding) – but file management is operations |
| Snapshots (Undo) | Session engine (revert) + filesystem (file format) |

## 8. Verified semantics (reference checkout)

| Concept | Location |
|---|---|
| Chain: Decode → execute → Encode → Normalize | `packages/core/src/tool/runtime.ts:30-62` |
| Pre-/post-hook, only `execute.before` may fail | `packages/core/src/tool.ts:103-156`, `packages/core/src/plugin/hooks.ts:23` |
| Execution in the frozen snapshot | `packages/core/src/tool.ts:264-285` |
| Scoped registration, restore of predecessor | `packages/core/src/state.ts:160-251`, `packages/core/src/tool/AGENTS.md` |
| Permissions before side effect, rejection as defect | `packages/core/src/permission.ts:235-267`, `packages/core/src/session/model-request.ts:388-402` |
| Bounding | `packages/core/src/tool-output.ts:69-120` |
| Scanner, `save` patterns | `packages/core/src/shell/parse.ts:18-21`, `:195-199` |
| Approval holds the call (`ask`) | `packages/core/src/permission.ts:227-263` |
| MCP namespace and action | `packages/core/src/tool/mcp.ts:20-21`, `:56-68` |