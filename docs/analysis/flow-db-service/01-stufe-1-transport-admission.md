# 01 – Level 1: Transport & Admission

> Depth: **Level 1**. Goal: the path of the prompt text up to the first durable
> fact (`session_inbox` row). From here the prompt is *admitted*; execution
> begins in `02-stufe-2-execution-runner.md`.

## The complete call path

```
TUI    packages/tui/src/component/prompt/index.tsx:1364   data.session.prompt({...})
App    packages/app/src/composer/submit.ts:545             session.data.session.prompt(admission)
  │     Solid-Client (packages/client/src/solid/) – optimistic admission
  ▼
Client packages/client/src/promise/generated/client.ts      POST /api/session/:sessionID/prompt
  │     (generated from packages/protocol/src/groups/session.ts:335)
  ▼
Middleware  Authorization → Location → Session-Location → (Form-Location)
  │         Location: packages/server/src/location.ts:71-79   (header x-opencode-directory)
  ▼
Endpoint packages/protocol/src/groups/session.ts:334-355   "session.prompt"
  ▼
Handler  packages/server/src/handlers/session.ts:278-314
  ▼
Core     packages/core/src/session/session.ts:155-188       Session.prompt
  ├─ get(sessionID)                       :158  → Session.NotFoundError
  ├─ messageID = input.id ?? create()     :159
  ├─ admission.reconcile(...)             :161-167 → inbox.ts:155-167
  ├─ SessionPrompt.prepare(...)           :169-173 → prompt.ts:29-91
  ├─ SessionRevert.commit (if staged)    :175
  ├─ admission.admit(...)                 :176-180 → inbox.ts:169-202
  │     └─ publish session.inbox.enqueued → Bus transaction
  │          ├─ INSERT event (aggregate=sessionID, seq)
  │          ├─ INSERT session_inbox      (projector.ts:683-699)
  │          └─ UPDATE event_sequence
  └─ execution.wake(sessionID)            :184  (only if resume !== false)
```

## 1. The client: optimistic admission

Both surfaces take the prompt **optimistically**: the message renders
immediately, the server confirms later.

* TUI (`packages/tui/src/component/prompt/index.tsx:1360-1378`): renders
  immediately, rolls back on rejection (`newSession.recover(error)` /
  `fail(...)`).
* App (`packages/app/src/composer/submit.ts:545`): `prompt(admission).catch(() =>
  prompt(admission))` – the client **retries** the admission once. This is safe
  because admission is idempotent (see §3).

Both pass their own `id` (`value.id` / `sessionID`), `delivery`, `metadata`
(display text, comments, attachments, chosen agent/model) and a `prepare`
callback that pins the selection (agent/model) **before** admission. A staged
revert is committed before the prompt (`packages/app/src/composer/submit.ts:516-518`).

## 2. The endpoint

```ts
// packages/protocol/src/groups/session.ts:334-355
HttpApiEndpoint.post("session.prompt", "/api/session/:sessionID/prompt", {
  params:  { sessionID: Session.ID },
  payload: Schema.Struct({
    id: SessionMessage.ID.pipe(Schema.optional),
    ...PromptInput.Prompt.fields,          // text, files, agents, skills
    metadata: SessionInbox.UserPayload.fields.metadata,
    delivery: SessionInbox.Delivery.pipe(Schema.optional),
    resume: Schema.Boolean.pipe(Schema.optional),
  }),
  success: Schema.Struct({ data: SessionInbox.User }),
  error:   [ConflictError, InvalidRequestError, SessionNotFoundError],
}).middleware(sessionLocationMiddleware)
```

The **location middleware** determines the working directory:

```ts
// packages/server/src/location.ts:71-79
const directory =
  query.get("location[directory]") ||
  (request.headers["x-opencode-directory"] ? decode(...) : process.cwd())
```

and provides the location-scoped services (`location.ts:89-99`). The server
assembles the API in `packages/server/src/routes.ts:178-189` from 31 groups
(`packages/protocol/src/api.ts`).

## 3. `Session.prompt` – the facade

```ts
// packages/core/src/session/session.ts:155-188
const prompt = Effect.fn("Session.prompt")((sessionID, input) =>
  Effect.uninterruptibleMask((restore) =>
    Effect.gen(function* () {
      const session = yield* get(sessionID)                       // :158
      const messageID = input.id ?? SessionMessage.ID.create()    // :159
      const admitted = yield* Effect.gen(function* () {
        const existing = yield* admission.reconcile({            // :161-167
          id: messageID, sessionID: session.id,
          type: "user", delivery: input.delivery ?? "steer",
        })
        if (existing) return existing                            // idempotency
        const item = yield* restore(                             // :168-173
          SessionPrompt.prepare({ session, messageID, input })
            .pipe(Effect.provideService(Instance.Service, instances),
                  Effect.provideService(FSUtil.Service, fs)),
        )
        if (session.revert) yield* SessionRevert.commit(bus, session)  // :175
        return yield* admission.admit({ id: messageID, sessionID: session.id, item }) // :176-180
      }).pipe(Effect.catchTag("SessionInbox.LifecycleConflict",
        () => new PromptConflictError({ sessionID, messageID })))   // :182
      if (input.resume !== false) yield* execution.wake(sessionID)    // :184
      return admitted
    }),
  ),
)
```

Four properties you need to know:

1. **`uninterruptibleMask`** – the admission path is not interruptible. Only
   `SessionPrompt.prepare` runs via `restore` and is therefore interruptible
   (attachments can take a long time).
2. **Reconcile before prepare** – an already admitted item skips hooks and
   attachment resolution entirely (`specs/v2/session.md:43`).
3. **Revert before admission** – a staged revert is committed *before* new work
   is admitted (`session.ts:174-175`).
4. **Wake after admission** – only once the row exists is execution scheduled
   (`session.ts:184`).

### `SessionPrompt.prepare` (`packages/core/src/session/prompt.ts:29-91`)

| Step | Line | Result |
|---|---|---|
| `Plugin.awaitActivation` | `:38` | Plugins must be active before hooks run |
| Hook `session.prompt` | `:40-51` | Plugins may change text/files/agents/skills/delivery |
| Materialize attachments | `:53-55` | Concurrency 8; data URL or `file:` URL; limit 20 MiB (`prompt.ts:203`) |
| Resolve skills | `:56-76` | Skill content is loaded into the payload up front |
| Build payload | `:77-89` | `{ type: "user", payload: UserPayload, delivery }` |

Errors at this level: `AttachmentError` (`:108-111`, `:193-199`), `SkillNotFoundError`
(`:66`). Both are translated to `InvalidRequestError` in the handler
(`packages/server/src/handlers/session.ts:304-309`).

### `admission.admit` (`packages/core/src/session/inbox.ts:169-202`)

```ts
const existing = yield* reconcile({ ...request, type: request.item.type, delivery: request.item.delivery })
if (existing !== undefined) return existing          // idempotency
const admitted = yield* bus.publish(SessionEvent.InboxEnqueued, {
  inboxID: request.id, sessionID: request.sessionID, item: request.item,
})
```

The publish runs through `Bus.commitDurableEvent`
(`packages/core/src/bus.ts:289-419`):

```
db.transaction(() => {
  seq = (event_sequence.seq ?? -1) + 1          // :332, :377
  encoded = Schema.encode(definition.data)(event.data)   // :333
  for (projector of list) projector(committed)  // :406-408   ← all projections
  commit?.(seq)                                // :409        ← claim/store hook
  upsert event_sequence                        // :410-419
})
```

The projection for `session.inbox.enqueued`
(`packages/core/src/session/projector.ts:683-699`):

```ts
yield* SessionInbox.projectAdmitted(db, { enqueuedSeq: event.durable.seq, ... })  // :685
yield* db.update(SessionTable).set({ time_updated: event.created })                // :692-697
```

`projectAdmitted` (`packages/core/src/session/inbox.ts:277-317`) does the
INSERT into `session_inbox` with `onConflictDoNothing`; if the message ID is
already present as a `session_message` (e.g. after delivery), the projection
dies with `LifecycleConflict` – **no** silent drop.

> **Transaction guarantee:** Event row + `session_inbox` row + `event_sequence`
> are created **atomically**. A crash between publish and projection is
> impossible.

## 4. What happens after wake (preview)

`execution.wake` (`packages/core/src/session/session.ts:184` →
`packages/core/src/session/execution.ts:169` → `coordinator.wake`) is
asynchronous: the HTTP request responds to the **admission**, not to the model
result. The client tracks progress through the SSE feed
`GET /api/event`.

## Errors at this level

| Error | Origin | HTTP | What the user sees |
|---|---|---|---|
| `Session.NotFoundError` | `session.ts:158` | 404 | "Session not found" |
| `Session.PromptConflictError` | `session.ts:182` | 409 | "Prompt message ID conflicts with an existing durable record" (`handlers/session.ts:296-303`) |
| `Session.AttachmentError` | `prompt.ts:108` | 400 | "Attachment exceeds … byte limit" (`handlers/session.ts:304-306`) |
| `Session.SkillNotFoundError` | `prompt.ts:66` | 400 | "Skill not found: …" (`handlers/session.ts:307-309`) |
| `SessionInbox.LifecycleConflict` | `inbox.ts:65-67` | 409 | via `PromptConflictError` |
| Location error | `location.ts:96` | 400 | "Directory does not exist / is not a directory" (`handlers/session.ts:310`) |

**Important:** An error at this level means: **nothing** was admitted. There is
no half-admission. If the client retries (App `submit.ts:545`), that is safe.

## Checkpoints for your own changes

* New fields in the prompt payload → `packages/protocol/src/groups/session.ts:337`
  **and** `packages/schema/src/prompt-input.ts`; then run `bun run generate` in
  `packages/protocol` and `packages/client`.
* New error kind → map it in the handler to one of the three declared errors
  in `packages/server/src/handlers/session.ts:294-311` (the endpoint may only
  return the declared errors, `groups/session.ts:345`).
* Admission logic → `packages/core/src/session/inbox.ts:155-202`. Note:
  `reconcile` runs **before** `prepare`; a change there alters the
  idempotency semantics.
* Transport & admission detail → [`02-stufe-2-execution-runner.md`](./02-stufe-2-execution-runner.md)