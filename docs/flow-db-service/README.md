# Flow: "erstelle Service der DB luest"

> **Goal:** make the complete flow of a prompt through OpenCode V2 traceable –
> from pressing Enter to the written file, including all calls, events, DB
> rows and error paths.
>
> **Status:** 2026-10-22, repo state `dev` (working tree identical to
> `docs/README.md`: `dev` @ `3108505b2f`, v2.1.2).
> **Method:** the code was **read directly**, not derived from the
> documentation. Every statement below carries evidence `path:line` against this
> checkout. The codebase-memory graph is indexed under
> `/home/torsten/dev/my-projects/oc` (different path) and was therefore **not**
> used as a source.
>
> **Assumptions:** the prompt is literally `"erstelle Service der DB luest"` –
> presumably "create a service that reads the DB". In this repo the database
> lives in `packages/core/src/database/` (SQLite via Drizzle). The scenario
> part (`05-szenario.md`) is therefore specific to this repo; the other files
> are repo-agnostic.

## Reading order

| File | Depth | Content |
|---|---|---|
| [`00-grob.md`](./00-grob.md) | **overview** | The whole picture in 6 phases, one diagram, the 8 key insights |
| [`01-stufe-1-transport-admission.md`](./01-stufe-1-transport-admission.md) | Level 1 | TUI/App → HTTP → middleware → handler → `Session.prompt` → admission |
| [`02-stufe-2-execution-runner.md`](./02-stufe-2-execution-runner.md) | Level 2 | `wake` → RunCoordinator → `drain` → context building → ModelRequest |
| [`03-stufe-3-toolcall.md`](./03-stufe-3-toolcall.md) | Level 3 | `llm.stream` → tool call → permission → leaf execution → bounding |
| [`04-stufe-4-fehler.md`](./04-stufe-4-fehler.md) | Level 4 | Error handling at every level, including recovery |
| [`05-szenario.md`](./05-szenario.md) | cross-cutting | The concrete prompt: expected tool calls, permissions, typical errors |
| [`06-rundlauf.md`](./06-rundlauf.md) | exact | Session → LLM → permissions → tool → session, with all transaction boundaries |

## The six phases at a glance

```
① Key press       TUI/App → HTTP POST /api/session/:id/prompt
② Admission       reconcile → prepare → publish(session.inbox.enqueued) → INSERT session_inbox
③ Execution       wake → RunCoordinator → drain → location-scoped runner
④ Context         select (agent/instructions/tools) → load (model/history) → ModelRequest
⑤ Step            llm.stream → provider stream → tool calls → tool execution
⑥ Settlement      Step.Ended → cost/tokens → Execution.Succeeded → idle
```

## The eight key insights

1. **Admission and execution are separate.** `Session.prompt` first writes a
   durable inbox row and only then schedules execution. Without a
   `session_inbox` row no model runs. (`core/src/session/session.ts:176-184`)
2. **One step = exactly one `llm.stream`.** After every local tool result the
   projected history is reloaded and a *new* step starts. There is no
   in-memory tool loop. (`core/src/session/runner/llm.ts:185-192`)
3. **Tool calls are durable *before* the side effect.** `session.tool.called`
   is committed before the leaf tool touches the filesystem.
   (`core/src/session/runner/step.ts:121-134`)
4. **The registry knows no permissions.** Tool filtering is catalog
   visibility; actual authorization happens in the leaf through
   `Permission.assert`. (`core/src/tool/AGENTS.md`, section "Permissions")
5. **The permission check is `findLast`.** The last matching rule decides;
   `deny` beats every saved approval, `ask` beats `allow`.
   (`core/src/permission.ts:87-97`, `:165-178`)
6. **Every durable event needs two projections.** Table projection
   (`session/projector.ts`) *and* message fold (`session/message-updater.ts`).
   Otherwise the DB is right but the message stays incomplete.
7. **Errors are typed, not swallowed.** `toSessionError`
   (`core/src/session/to-session-error.ts:31-93`) maps every error onto the
   `SessionError` taxonomy; the UI sees `type` + `message`.
8. **Two layers must never be missing.** A new durable event needs both
   projections; a new tool action needs schema + defaults + leaf.

## Conventions in this series

* Every statement has a **source reference** `path:line`.
* Interpretation is marked as such and separated from evidenced facts.
* Diagrams as Mermaid in code blocks so they render in Markdown/GitHub.
* Diagrams show **logic**, not every `Effect` operator.

## Verification

```
bun run check                    # lint + typecheck in root
bun typecheck                    # in the affected package
node docs/tools/coupling-analysis.mjs /home/torsten/dev/my-projects/2oc
```

Tests do **not** run from the repo root (guard `do-not-run-tests-from-root`),
but per package, e.g. `packages/core`.