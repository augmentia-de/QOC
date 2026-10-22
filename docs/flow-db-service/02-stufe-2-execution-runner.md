# 02 – Level 2: Execution, Drain, Context

> Depth: **Level 2**. The prompt is admitted in `session_inbox`. How does it
> become a model request, and how is the concrete HTTP request to the provider
> built?

## 1. `wake` – the doorbell

```
Session.prompt                (session.ts:184)
  └─ execution.wake(sessionID)          (execution.ts:169 → coordinator.wake)
       ├─ Session idle?   → start(key, force=false, scope)   (run-coordinator.ts:131-140)
       └─ Session busy?   → execution.pendingWake = scope    (idem)
```

`SessionRunCoordinator` (`packages/core/src/session/run-coordinator.ts`) is the
entire process localization:

| Concept | Meaning | Line |
|---|---|---|
| Execution | one busy period per session ID: one fiber | `:35-42` |
| `done` | Deferred that resolves joiners | `:36` |
| `pendingWake` | the doorbell: work that arrived during the drain | `:39` |
| `stopping` | interrupt in progress | `:40` |

The fiber (`run-coordinator.ts:82-107`):

```
fork(
  yieldNow                                    // :93  → owner is assigned
  → started(key)                             // :94  → execution.started + claim
  → loop(key, execution, force)              // :95  → drain, then possibly again
  → onExit → settled(key, exit, reason)      // :96-100
  → onExit → settle(key, execution, exit)    // :101
)
```

`loop` (`:69-80`): one drain runs; afterward it is checked whether the
doorbell rang during it – then **another drain** follows in the same
execution, with the broader scope (`"input"` subsumes `"steer"`, `:136`).
`settle` (`:111-115`): if the doorbell is still set after the loop, a
**follow-up execution** is started; otherwise the entry is deleted.

### The write-ahead claim

`started` publishes `session.execution.started` **and** sets the claim in the
same transaction (`packages/core/src/session/execution.ts:78-83`, `:109-113`):

```ts
const claimOnCommit = (sessionID) => ({ commit: () => store.claim(sessionID) })
```

The claim is the durable signal "work is currently running in this session". It
is released on success, failure, and user interrupt
(`execution.ts:121-143`), **not** on shutdown interrupt (`:125-132`) – that
survives intentionally, so the next server start continues the session.

The columns behind it (`packages/core/src/session/store.ts:208-227`):

```ts
claim:   UPDATE session_v2 SET time_suspended = now()  WHERE id = ? AND time_suspended IS NULL
release: UPDATE session_v2 SET time_suspended = null, resume_attempts = 0 WHERE id = ?
```

`claim` is a **null guard**: re-claiming a session is a no-op
(`store.ts:209-210`). `release` also resets the recovery counter – atomically
with the terminal event, because both run through the `commit` hook of the
same transaction.

### `settled` – exactly one terminal event per busy period

```ts
// packages/core/src/session/execution.ts:116-144
settled: (sessionID, exit, reason) => {
  const outcome = terminal(exit, reason)          // :47-51
  succeeded   → publish Execution.Succeeded   (+ releaseOnCommit)
  interrupted → publish Execution.Interrupted (+ releaseOnCommit, except shutdown)
  failed      → publish Execution.Failed      (+ releaseOnCommit)
}
```

`terminal` (`:47-51`) distinguishes: `Exit.isSuccess` → succeeded;
`Cause.hasInterrupts` → interrupted; otherwise failed with
`toSessionError(Cause.squash(cause))`.

## 2. `drain` – the location-scoped runner

```ts
// packages/core/src/session/execution.ts:84-107
const drain = (sessionID, force, continuation?, promotable = "input") =>
  SessionRunner.Service.use((runner) =>
    runner.drain({ sessionID, force, continuation, promotable }),
  ).pipe(instances.provide(session))          // ← inject location services
```

`instances.provide(session)` is the point where the location is determined:
runner, model resolution, tools, permissions, plugins and filesystem are
**location-scoped** (`packages/core/src/session/context.ts:73-90` as
`makeLocationNode`).

The result of the drain is a `DrainResult`
(`packages/core/src/session/runner/index.ts:23-28`):

| Result | Meaning | Reaction in `execution.ts:102-106` |
|---|---|---|
| `Complete` | nothing left to do | done |
| `Moved` | location changed | drain again with continuation |
| `Reloaded` | location reloaded | drain again with `force` |

### The drain flow (`packages/core/src/session/runner/llm.ts:56-193`)

```ts
const drain = (input) => {
  let force = input.force, continuing = input.continuation !== undefined
  let step = input.continuation?.step ?? 1, entering = true
  const promotable = input.promotable ?? "input"

  if (!force && !continuing) {                                  // :63-68
    const pending = yield* SessionInbox.nextPromotable(db, sessionID, "input")
    if (!pending) return DrainResult.Complete()
    if (promotable === "steer" && pending.delivery === "queue" && !control) return Complete
  }

  yield* plugins.awaitActivation                               // :69
  yield* settleStaleCompactions(sessionID)                     // :70  → :326-354
  yield* settleStaleToolCalls(sessionID)                       // :71  → :356-379

  while (true) {
    const next = yield* advanceToStep()                        // :186
    if (next._tag !== "Ready") return next
    continuing = yield* runStep(next.context, step)            // :188
    step++; force = false; entering = false                    // :189-191
  }
}
```

> **Key point:** `while (true)` – after **every** step `advanceToStep` runs
> again. That is the "no in-memory tool loop" in code: one step is always
> its own `llm.stream`.

### `advanceToStep` – the boundary logic (`llm.ts:73-183`)

```
while (true) {
  if (lifecycle.isClosed()) {                                  // :77-80
    modelTransport.close(sessionID)
    return Reloaded({ force, continuation })
  }

  const pending = yield* SessionInbox.serialized(sessionID, …) // :82-107
      nextPromotable(scope = entering || !continuing ? "input" : "steer")
      ├─ pending.type === "compaction" → publish [InboxDelivered, Compaction.Started]  // :90-94
      └─ pending.type === "move"       → close transport, publish [InboxDelivered, Moved] // :95-104

  if (!continuing && pending?.delivery !== "steer") { entering = true; step = 1 }   // :108-111
  if (pending?.type === "move")    return Moved({ continuation })                   // :112-113
  if (pending?.type === "compaction") { … compact … ; force = false; continue }      // :114-157

  if (!force && !continuing && (!pending || (pending.delivery === "queue" && promotable === "steer")))
    return Complete()                                          // :158-159

  const ready = yield* restore(                               // :160-178
    prepareContext(sessionID)                                 // select + InstructionState.prepare
    → SessionInbox.promote(db, bus, sessionID, scope)         // delivery!
    → [title generation if promoted > 0]
    → context.load(selected)                                  // model + history
  )
  if (ready) return ready
}
```

**`promote` is the delivery** (`packages/core/src/session/inbox.ts:496-531`):

| Case | Result |
|---|---|
| Steers present, no control before | publish all steers up to the first control |
| Control at position 0 (compaction/move) | `undefined` → the runner must handle the control |
| No steer, scope `"input"` | **one** queue item (oldest by `enqueued_seq`) |
| Queue item is control | `undefined` → control first |

Each published item produces `session.inbox.delivered`; the projection
(`packages/core/src/session/projector.ts:651-682`) deletes the `session_inbox`
row **and** inserts the visible `session_message` – in the same transaction.
An item still in the inbox is **invisible** to the model.

> `SessionInbox.serialized` (`inbox.ts:62-63`) is a keyed mutex per session:
> admission, delivery and cancellation serialize against each other.

## 3. `prepareContext` → `select` (context building, part 1)

```ts
// packages/core/src/session/runner/llm.ts:195-200
const prepareContext = (sessionID) => {
  const selected = yield* context.select(sessionID)
  yield* InstructionState.prepare(db, bus, selected.instructions, sessionID)  // :198
  return selected
}
```

`SessionContext.select` (`packages/core/src/session/context.ts:126-166`):

| Step | Line | Result |
|---|---|---|
| Load session | `:127-128` | error → `Effect.die` (session does not exist) |
| Location guard | `:129-130` | session belongs to another location → `Effect.interrupt` |
| Flush MCP tools | `:132` | most recently discovered MCP tools are adopted |
| Choose agent | `:133-134` | `agents.select(session.agent)`; no agent → `AgentNotFoundError` |
| Merge permissions | `:136` | `Permission.merge(agent.permissions, session.permissions)` |
| **Parallel load** | `:137-148` | `concurrency: "unbounded"`: tools, builtins, discovery, skills, references, mcp, entries |
| Combine instructions | `:155-163` | see below |

The instruction order is deliberately cache-friendly (comment
`context.ts:152-154`):

```
CodeMode → MCP → References → Skills → Discovery(AGENTS.md) → Builtins → Session-Entries
```

`InstructionState.prepare` (`:198`) ensures that an **initial baseline**
exists before an instruction epoch begins – otherwise the first request would
not be reproducible. If the baseline is blocked (e.g. an AGENTS.md temporarily
unreadable), the admitted input stays pending (`llm.ts:197`).

## 4. `context.load` (context building, part 2)

```ts
// packages/core/src/session/context.ts:168-184
const load = (selection) => {
  const model = yield* resolveModel(selection.session)                      // :169
  const history = yield* SessionHistory.entriesForRunner(                   // :170-175
    db, selection.session.id, selection.instructions, model.ref)
  return { session, agent, model, initial: history.initial,
           messages: history.entries.map(e => e.message), tools: selection.tools }
}
```

* **Model resolution** (`packages/core/src/session/runner/model.ts:75-91`):
  no session model → global default, otherwise `ModelNotSelectedError`;
  session model not in catalog → `ModelUnavailableError`; otherwise
  `resolver.resolveModel(selected, variant)`.
* **History** (`packages/core/src/session/history.ts:193-210`): in **one**
  DB transaction the messages are read from the last completed compaction
  (`messageEntries`, `:102-141`) and the instruction baseline is determined
  (`InstructionState.initial`). Compacted messages stay in the DB – they are
  just no longer sent.

## 5. The model request

```ts
// packages/core/src/session/runner/llm.ts:220-241
const stepLimitReached = loaded.agent.info.steps !== undefined && step >= loaded.agent.info.steps
const transcript = SessionModelRequest.baseTranscript({
  agent: loaded.agent.info, model: loaded.model, tools: loaded.tools,
  initial: loaded.initial, messages: loaded.messages,
})
const prepared = yield* context.request.primary({
  session: loaded.session, agent: loaded.agent.id, model: loaded.model,
  tools: loaded.tools, system: transcript.system,
  messages: stepLimitReached
    ? [...transcript.messages, Message.assistant(MAX_STEPS_PROMPT)]
    : transcript.messages,
  toolChoice: stepLimitReached ? "none" : undefined,      // disable tools in the last step
  webSocket: "session",
  inputTokens: SessionCompaction.estimatePrompt(loaded),
})
```

`baseTranscript` (`packages/core/src/session/model-request.ts:99-119`):

```ts
system = [ agent.system ?? SessionSystemPrompt.make(toolNames), initial ]
messages = toLLMMessages(messages, model.ref, providerMetadataKey)
```

An agent with its own `system` **replaces** the harness template entirely.

### `SessionModelRequest.prepare` (`model-request.ts:236-404`)

| Step | Line | Effect |
|---|---|---|
| Remember tool definitions | `:248-250` | Map from signature → tool (for hook renaming) |
| Hook `session.context` | `:253-265` + `:406-408` | Plugins change system, messages, tools, options |
| **Output limit** | `:259-262` | only for `primary`/`compaction` |
| Resolve hook renaming | `:266-274` | object identity first, then name; invented entries drop out |
| Separate options | `:275-277` | generation vs. provider options |
| Set headers | `:281-293` | `x-opencode-session-id`, `x-session-affinity`, `x-opencode-project`, `User-Agent` … |
| Prompt cache key | `:295` | from session affinity |
| Filter media | `:297` | `unsupportedParts` + `boundImages` |
| `LLM.request(...)` | `:279-302` | builds the provider request |
| Hook `session.model.request` | `:305-320` | may override `baseURL` |
| HTTP hooks | `:321-349` | `http.request` / `http.response` |
| WebSocket binding | `:352-376` | only when `transport === "websocket"` |

**Output limit** (`model-request.ts:87-97`):

```ts
const model     = Math.min(limit.output > 0 ? limit.output : 32_000, 256_000)
const requested = kind === "compaction" ? Math.min(model, 32_000) : model
const room      = limit.context - inputTokens.measured - ceil(inputTokens.estimated * 1.15)
return Math.min(requested, Math.max(1_024, room))
```

So the catalog limit is **adjusted to the space actually available**;
`title` and `generate` keep the provider default.

### The tool snapshot

`registry.snapshot(permissions)` (`packages/core/src/tool.ts:227-288`) produces:

* `definitions` – the JSON schema definitions the provider sees (direct tools
  plus possibly the code-mode tool `execute`)
* `codeModeCatalog` – the JS catalog for code mode
* `execute` – the execution snapshot that knows **only** the tools advertised in
  this request (`tool.ts:264-285`)

Tools for which a rule `action = <name>, resource = "*"` with `deny` exists
drop out of the snapshot (`whollyDisabled`, `tool.ts:293-296`). That is
**catalog visibility, not execution authorization** – the actual check happens
in the leaf.

## 6. `runStep` – the step loop (`llm.ts:203-324`)

```ts
const runStep = (first, step) => {
  let assistantMessageID = SessionMessage.ID.create()
  const retry = yield* SessionRunnerRetry.make(bus, sessionID)
  let initial = first, recoverOverflow = true, recoverContinuation = true

  while (true) {                                       // ← attempt loop
    const loaded = initial ?? (prepareContext → context.load)
    initial = undefined

    const compacted = yield* compaction.compact({ reason: "auto", context: loaded })   // :214
    if (compacted.status === "failed") return new StepFailedError({ error: compacted.error })
    if (compacted.status === "completed") { assistantMessageID = create(); continue }   // :216-219

    const prepared = yield* context.request.primary({...})                            // :228-241
    const outcome  = yield* steps.attempt({ ..., prepared, retry, recoverOverflow, recoverContinuation })  // :242-266

    const completed = yield* SessionStep.Outcome.$match(outcome, {                    // :267-321
      Completed:  (o) => o.needsContinuation,        // → another step
      Retry:      (o) => retry.wait(...),            // backoff, same assistantMessageID
      Continue:   (o) => retry.wait(...) + Synthetic("Continue from where you left off") + new ID
      OutputLimit:(o) => … "hit the output token limit …" + new ID
      Compacted:  ()  => recoverOverflow = false, new assistantMessageID
      RecoverFull:()  => recoverContinuation = false
    })
    if (completed !== undefined) return completed
  }
}
```

* **Auto-compaction** (`:214`): happens before every step when the estimated
  request would blow the context window.
* **Step limit**: in the last allowed step tools are switched off
  (`toolChoice: "none"`, `:238`) and a `MAX_STEPS_PROMPT` is appended
  (`packages/core/src/session/runner/max-steps.ts`).
* **`needsContinuation`** (`step.ts:281`): true when a tool call is still
  unanswered – then a **new** step runs.

## 7. What happens between two steps

```
Step 1 ends (tool result durable)
   ↓
Step.Ended → cost/tokens/snapshot/files into the projection
   ↓
while(true) → advanceToStep
   ↓
prepareContext → select (agent, instructions, TOOLS re-materialized)
                → load (history re-read from DB)
   ↓
context.request.primary → new request
   ↓
steps.attempt → llm.stream  ← second model call
```

So the context is **rebuilt per step**: new tools, new instructions, new
history. That is why a tool call registered in step 1 can no longer exist in
step 2 if the registry changed meanwhile – and conversely a newly registered
tool only becomes visible in step 2.

## Checkpoints for your own changes

* New instruction source → `packages/core/src/config/plugin/instruction.ts`
  **and** the order in `context.ts:155-163` (cache-relevant!).
* New tool option → `packages/schema/src/tool.ts` + snapshot logic in
  `packages/core/src/tool.ts:227-288`.
* Change output limit → `packages/core/src/session/model-request.ts:87-97`
  (note: `title`/`generate` are unaffected).
* Change delivery boundaries → `packages/core/src/session/inbox.ts:496-531` and
  `packages/core/src/session/runner/llm.ts:82-111`.

## Next

* The model call and tool execution → [`03-stufe-3-toolcall.md`](./03-stufe-3-toolcall.md)
* Errors → [`04-stufe-4-fehler.md`](./04-stufe-4-fehler.md)