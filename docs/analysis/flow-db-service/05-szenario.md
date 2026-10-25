# 05 – Scenario: "erstelle Service der DB luest"

> Depth: **cross-cutting**. All levels from `00`–`04` on one concrete prompt.
> The prompt is literally `"erstelle Service der DB luest"` (probably
> "create a service that reads the DB"). Reference repo: this repository,
> DB layer = SQLite via Drizzle in `packages/core/src/database/`.

## The agent's target picture

A new core service that reads the DB follows a fixed pattern in the repo:

```
packages/core/src/<domain>/<name>.ts
  ├─ export const make = Effect.fn("<Domain>.make")(function* () { … })   // service logic
  ├─ export class Service extends Context.Service<Service, Interface>()("@opencode/<Domain>") {}
  ├─ export const layer = Layer.effect(Service, make())
  └─ export const node = makeLocationNode({ service: Service, layer, deps: [...] })
```

References in the repo: `packages/core/src/session/store.ts` (session store),
`packages/core/src/permission/saved.ts` (saved approvals),
`packages/core/src/database/database.ts` (the DB service itself).

## Expected tool-call sequence

The agent runs in **multiple steps** – each step is its own
`llm.stream`, and between steps the history is reloaded
(`packages/core/src/session/runner/llm.ts:185-192`).

### Step 1 – Orientation

| Tool | Action | Resource | Permission outcome | Effect |
|---|---|---|---|---|
| `glob` | `glob` | pattern, e.g. `packages/core/src/**/sql.ts` | `allow` | file list |
| `grep` | `grep` | **the regex**, not the path | `allow` | hits |
| `read` | `read` (+ possibly `external_directory`) | location-relative path | `allow` | file content |

`read` triggers **two** permission checks (`packages/core/src/file-access.ts:144-161`):

1. `external_directory` – only when the path lies outside the location.
2. `read` on the location-relative path.

Both default to `allow`
(`packages/schema/src/agent.ts:46-51`: `*:* = allow`) → **no prompt**.

> **Side effect:** After every successful `read` the tool searches for
> `AGENTS.md` files upward and injects them as a **durable synthetic message**
> (`packages/core/src/tool/plugin/read.ts:87-107`). So the context of the next
> step is not identical to the first.

### Step 2 – Plan

Usually a pure text step: the model describes what it will build. **No**
tool call, but `session.text.started/ended` and a `session.step.ended`.

If the model is unsure, it calls `question` (action `question`,
resource `*`) – for the agent `build` this defaults to `allow`
(`packages/core/src/plugin/agent.ts:60`). The answer blocks the step until the
user replies.

### Step 3 – Writing

| Tool | Action | Resource | Permission outcome | Effect |
|---|---|---|---|---|
| `write` | `edit` (shared with `edit`/`patch`) | target path, e.g. `packages/core/src/db/service.ts` | `allow` | new file |
| `edit` | `edit` | target path | `allow` | partial change |

The chain in `packages/core/src/tool/plugin/write.ts:64-92`:

```
access.resolve({path, kind:"file"})            → absolute path + resource
access.authorizeExternal([target])             → Permission.assert("external_directory")
FileMutation.readText(...)                     → current content (for the diff)
fileDiff(resource, alt, new, "added")          → preview
permission.assert({action:"edit", resources:[resource], save:["*"],
                    metadata:{files:[preview]}})   → ← THE approval point
fileMutation.writeTextPreservingBom(...)       → file written
formatter.file(target.absolute)                → formatter (if configured)
```

`save: ["*"]` means: if the user answers **"always"**, an approval for
*every* path is stored **for the project**
(`packages/core/src/permission.ts:303-309`).

The diff goes as `metadata` to the permission request – the UI shows it,
**before** it is allowed. If rejected, the model sees `permission.rejected`
and may try a different way.

### Step 4 – Verify (optional)

| Tool | Action | Note |
|---|---|---|
| `shell` | `shell` | command resource is the **scanned command string**; `save` suggests concrete prefixes (`packages/core/src/shell/parse.ts`) |
| `read` | `read` | check the result |

### What lands in the DB

```
session_inbox         1 row  (admission)      → deleted on delivery
session_message       1 row  user             (delivery)
session_message       1 row  assistant        (per step)
session_message       + assistant content (JSON in data)
session_v2            time_idle, idle_outcome, cost, tokens_*
event                 1 row  per durable event
event_sequence        seq per session
```

**Not** in the DB: the **content** of the written file. A replay shows
`session.tool.called` and `session.tool.success`, but not the bytes (TODO in
`packages/core/src/tool/plugin/write.ts:42-43`).

## What the user sees in the TUI

| Time | Display | Source |
|---|---|---|
| immediately | the prompt line (optimistic) | `packages/tui/src/component/prompt/index.tsx:1360-1378` |
| after admission | same line, now confirmed | SSE `session.inbox.delivered` |
| during work | text/reasoning blocks | `session.text.*`, `session.reasoning.*` |
| while writing | tool card with status "running" | `session.tool.called` (`message-updater.ts:326-342`) |
| on approval question | permission dock with diff | `permission.asked` (ephemeral) |
| after the tool | tool card with result or `outputPath` | `session.tool.success` (`:345-362`) |
| at the end | "idle" marker | `session.execution.succeeded` → `idle("succeeded")` (`:146`) |

## Typical deviations and their consequences

| Deviation | What happens | Where |
|---|---|---|
| Model uses `shell` instead of `read` | permission resource is the command string; the shell scanner may trigger `ask` | `packages/core/src/tool/plugin/shell.ts` |
| Model reads a `.env` | `read *.env = ask` → **prompt** | `packages/schema/src/agent.ts:48` |
| Model writes outside the location | `external_directory` is checked (default `allow`) | `file-access.ts:153` |
| User rejects | `DeclinedError` → **the step ends**, no tool output | `step.ts:274-277` |
| User rejects with feedback | `CorrectedError` → typed → `ToolFailure` → the model may continue | `permission.ts:280-299` |
| File already exists and model uses `edit` | patch does not fit → `Tool.Error` → model corrects | `packages/core/src/tool/plugin/edit.ts` |
| Tool output > 50 KiB / > 2000 lines | bounding: truncated + full output in `~/.local/share/opencode/tool-output/tool_*` | `packages/core/src/tool-output.ts:69-120` |
| Provider answers "too long" | one overflow compaction, then a new attempt | `step.ts:155-167` |
| Provider dies mid-stream | `incomplete-stream` → retry (before output) or `Continue` (after output) | `step.ts:189-204` |
| Server dies | claim stays → recovery on next start | `restart.ts:193-231` |
| Model loops endlessly | step limit: tools switched off, `MAX_STEPS_PROMPT` | `llm.ts:220`, `:234-238` |

## The flow as a timeline

```
t0  User presses Enter
t1  POST /api/session/:id/prompt            → HTTP 200 (admission confirmed)
t2  INSERT session_inbox                    (durable, before execution)
t3  session.execution.started + claim       (durable)
t4  promote → DELETE session_inbox + INSERT session_message(user)   (durable)
t5  select (agent, instructions, tools) → load (model, history) → ModelRequest
t6  llm.stream → text + tool calls
t7  session.tool.called (durable) → Permission.assert → file operation
t8  session.tool.success (durable) → ToolOutput.truncate
t9  session.step.ended (durable) → cost/tokens
t10 next step: history reloaded, new llm.stream
…
tn  session.execution.succeeded → idle
```

## What you can check on a real run

```sql
-- SQLite: ~/.local/share/opencode/opencode.db
SELECT type, seq, created FROM event
 WHERE aggregate_id = '<sessionID>' ORDER BY seq;

SELECT id, type, delivery, enqueued_seq FROM session_inbox WHERE session_id = '<sessionID>';

SELECT type, seq, json_extract(data,'$.finish') AS finish,
       json_extract(data,'$.error.type') AS error
  FROM session_message WHERE session_id = '<sessionID>' ORDER BY seq;

SELECT time_idle, idle_outcome, cost, tokens_input, tokens_output,
       time_suspended, resume_attempts
  FROM session_v2 WHERE id = '<sessionID>';
```

Expected pattern: `session.inbox.enqueued` **before** `session.execution.started`,
`session.inbox.delivered` **before** the first `session.step.started`, and
`session.execution.succeeded` **after** the last `session.step.ended`.

## When something does not work

1. Agent does not react at all? → check `session_inbox`: is the item still
   there, never promoted (`llm.ts:158-159`).
2. "Tool is not available for this request"? → the tool was not in the snapshot
   (`tool.ts:273-276`).
3. "Unknown tool"? → the tool name was not in the catalog
   (`publish-llm-event.ts:559-562`).
4. Turn ends with `aborted`? → interrupt or reject
   (`step.ts:207-225`).
5. Costs missing? → `session.usage.updated` is a projection, not an event
   (`packages/core/src/session/projector.ts:103-113`).