# Tools – specification

> **Subject:** The tool contract and the three tool families of the
> reference implementation: **filesystem**, **Bash (with HITL)** and **MCP**.
>
> **Status:** 2026-10-22 · Reference checkout `dev` @ `3108505b2f`
> **Relation:** This spec describes what a tool **does** – not what it is
> called in OpenCode. The session engine
> ([`../session-engine/`](../session-engine/README.md)) uses them but does
> not own them.

## Reading order

| File | Content |
|---|---|
| [`01-types.md`](./01-types.md) | The tool contract: definition, invocation, result, content, metadata, registration |
| [`02-filesystem.md`](./02-filesystem.md) | Path resolution, boundaries, `read`/`write`/`edit`/`patch`/`glob`/`grep`, snapshots, bounding |
| [`03-bash-hitl.md`](./03-bash-hitl.md) | Command scanner, permission check, foreground/background run, HITL, PTY |
| [`04-mcp.md`](./04-mcp.md) | Server types, protocol, namespace, discovery, contents, errors |
| [`05-contract-invariants.md`](./05-contract-invariants.md) | Execution chain, error channels, invariants `W-01…` |
| [`06-java.md`](./06-java.md) | Java 21 implementation: `sealed`/`record`, registry, scanner, MCP client |

## The three families at a glance

| Family | Tools | Permission action | Resource |
|---|---|---|---|
| **Filesystem** | `read`, `write`, `edit`, `patch`, `glob`, `grep` | `read`, `edit`, `glob`, `grep` | path or pattern |
| **Bash** | `shell` | `shell` | the **scanned command string** |
| **MCP** | `<server>_<tool>` | `<server>_<tool>` | `*` |

> `edit`, `write` and `patch` share the action **`edit`**
> (`packages/core/src/tool/AGENTS.md`).

## The five properties that make a tool

1. **Three values, three destinations.** `output` is the ephemeral
   machine value (Code Mode), `content` is the **durable** model value,
   `metadata` is compact UI information. None is ever used for another.
2. **The snapshot freezes.** Every model request gets definitions **and**
   executors frozen. Later registry changes take effect only on later
   requests.
3. **The chain is fixed.** Pre-hook → name resolution → input decoding →
   permissions → execution → output decoding → post-hook → normalization →
   bounding.
4. **Permissions are the leaf's job, not the registry's.** The registry
   filters only the **catalog**; actual authorization happens in the tool.
5. **Errors have channels.** Expected error → model text. Rejection →
   **no** model text, but abort. Defect → abort with log.

## What this spec does **not** contain

* Session state, event log, delivery → [`../session-engine/`](../session-engine/README.md)
* The full rule engine → [`../permissions/`](../permissions/README.md)
* Provider protocols, streaming transport → [`../provider/`](../provider/README.md)
* Concrete file operations of the operating systems, regex engines, shell dialects

## The central boundary

> **A tool is a value with an execution function.** It has no state, no
> own thread, no own lifecycle. Anyone who wants to give it state gives
> it via the context (session, agent, message, call).