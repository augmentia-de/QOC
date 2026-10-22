# 01 – Types

> Algebraic data types. Everything here is **value**, no behavior.
> Behavior is in [`04-algorithms.md`](./04-algorithms.md).

## 1. Identifiers

```
type SessionID   = Opaque
type MessageID   = Opaque
type InboxID     = Opaque
type EventID     = Opaque
type CallID      = Opaque
type AgentID     = Opaque
type SkillID     = Opaque
type ToolName    = Opaque
type ModelID     = Opaque
type ProviderID  = Opaque
type PermissionRequestID = Opaque
```

**Rule:** A `MessageID` and an `InboxID` are **different types**, even
if at runtime they carry the same value. An inbox item *becomes* a
message, but it *is not* a message – the message only arises through
delivery. This type distinction prevents errors that would otherwise only
become visible through timing.

```
type Delivery  = Steer | Queue
type Effect    = Allow | Deny | Ask
type Reply     = Once | Always | Reject
```

## 2. Location and project

```
type Location =
  { directory: Path            # the active working directory
  , projectID: ProjectID       # directory identity (project)
  , workspaceID: Option<WorkspaceID> }   # reserved for later clustering

type ProjectID = Opaque
```

A project is **directory identity**, not a folder: multiple directories
can share the same project (worktrees). The identity is preferably derived
from the version-control remote, otherwise from the canonical
directory.

## 3. Session

```
type Session =
  { id: SessionID
  , projectID: ProjectID
  , location: Location
  , parentID: Option<SessionID>        # parent session (subagent/fork)
  , forkSessionID: Option<SessionID>
  , forkBoundary: Option<ForkBoundary>
  , title: Text
  , agent: AgentID
  , model: Option<ModelRef>
  , permissions: List<Rule>            # session-local rule set
  , metadata: Map
  , cost: Money
  , tokens: TokenCount
  , revert: Option<RevertState>
  , time: SessionTimes }

type SessionTimes =
  { created: Timestamp
  , updated: Timestamp
  , viewed: Option<Timestamp>
  , idle: Option<Timestamp>            # when last idle
  , idleOutcome: Option<IdleOutcome>    # result of the last run
  , suspended: Option<Timestamp>        # open execution claim
  , resumeAttempts: Int                 # durable resume attempts
  , archived: Option<Timestamp> }

type IdleOutcome = Succeeded | Failed | Interrupted
```

> **`suspended` is the execution claim.** Its presence means
> "work is running or ran in this session that was not completed".
> It is **not** deleted on failure, but only by a
> terminal event (see [`04-algorithms.md`](./04-algorithms.md), `recover`).

## 4. Messages

A message is either an **input** (from the user or the system) or an
**agent history entry**.

```
type Message =
  | UserMessage        { id: MessageID, text: Text, files: List<Attachment>
                       , agents: List<AgentID>, skills: List<SkillID>
                       , metadata: Map, time: Time }
  | SyntheticMessage   { id: MessageID, text: Text, description: Option<Text>
                       , metadata: Map, time: Time }
  | SystemMessage      { id: MessageID, text: Text, description: Option<Text>
                       , metadata: Map, time: Time }
  | SkillMessage       { id: MessageID, skill: SkillID, name: Text, text: Text
                       , metadata: Map, time: Time }
  | ShellMessage       { id: MessageID, shell: ShellInfo, time: Time }
  | AssistantMessage   { id: MessageID, agent: AgentID, model: ModelRef
                       , content: List<AssistantContent>
                       , finish: Option<FinishReason>
                       , error: Option<SessionError>
                       , retry: Option<RetryState>
                       , snapshot: Option<SnapshotRange>
                       , cost: Money, tokens: TokenCount
                       , time: AssistantTime }
  | CompactionMessage  { id: MessageID, status: Running | Completed | Failed
                       , reason: CompactionReason, summary: Text
                       , error: Option<SessionError>
                       , keep: Option<KeepReference>
                       , native: Option<NativeWindow>   # provider-side window
                       , cost: Money, tokens: TokenCount, time: Time }
  | AgentSwitched      { id: MessageID, agent: AgentID, previous: AgentID, time: Time }
  | ModelSwitched      { id: MessageID, model: ModelRef, previous: ModelRef, time: Time }
  | LocationSwitched   { id: MessageID, location: Location, previous: Location, time: Time }
  | IdleMessage        { id: MessageID, outcome: IdleOutcome
                       , error: Option<SessionError>, time: Time }
```

### Assistant content

V2 has **no separate part objects**; text, reasoning and tool calls
are elements of a `content` field inside the assistant message.

```
type AssistantContent =
  | TextContent      { text: Text, time: Time }
  | ReasoningContent { text: Text, time: Time }
  | ToolContent      { id: CallID, name: ToolName
                     , input: Json
                     , state: ToolState
                     , executed: Bool        # executed by the provider?
                     , time: ToolTime }

type ToolState =
  | Streaming { input: Text }                 # input still being streamed
  | Running   { input: Json, metadata: Map }   # execution running
  | Completed { input: Json, content: List<ToolContentItem>
              , metadata: Map }
  | Failed    { input: Json, error: SessionError
              , content: Option<List<ToolContentItem>>, metadata: Map }

type ToolContentItem = TextItem { text: Text } | FileItem { uri: Text, mime: Text, name: Option<Text> }
```

> **Important:** Tool state is a **state machine in a field**,
> not a series of part messages. The transition `Streaming → Running →
> Completed|Failed` is permitted exactly once per call.

## 5. Inbox

```
type InboxItem =
  | UserItem       { text: Text, files: List<Attachment>, agents: List<AgentID>
                   , skills: List<SkillID>, metadata: Map }
  | SyntheticItem  { text: Text, description: Option<Text>, metadata: Map }
  | CompactionItem { reason: CompactionReason }
  | MoveItem       { directory: Path }

type InboxEntry =
  { id: InboxID
  , sessionID: SessionID
  , item: InboxItem
  , delivery: Delivery
  , enqueuedSeq: Seq        # monotone sequence within the session
  , created: Timestamp }
```

**The inbox is the only place where input waits that the model has not
seen yet.** A message in the history is by definition already
delivered.

## 6. Events

Two classes, strictly separated:

```
type EventClass = Durable | Ephemeral
```

* **Durable** – in the event log, replayable, projectable. Basis for
  recovery, history and all derived read models.
* **Ephemeral** – live delivery only. Not replayable, not logged.

```
type Event =
  | SessionCreated      { sessionID }
  | SessionRenamed      { sessionID, title }
  | SessionArchived     { sessionID }
  | SessionUnarchived   { sessionID }
  | SessionMetadataUpdated { sessionID, metadata }
  | SessionPermissions  { sessionID, permissions: List<Rule> }
  | SessionAgentSelected{ sessionID, agent: AgentID, previous: Option<AgentID> }
  | SessionModelSelected{ sessionID, model: ModelRef, previous: Option<ModelRef> }
  | SessionViewed       { sessionID, idle: Timestamp }
  | SessionMoved        { sessionID, location: Location, projectID, subpath }
  | SessionForked       { sessionID, parentID, boundary: ForkBoundary }
  | SessionDeleted      { sessionID }

  | InboxEnqueued       { sessionID, inboxID: InboxID, item: InboxItem }
  | InboxDelivered      { sessionID, inboxID: InboxID }
  | InboxCancelled      { sessionID, inboxID: InboxID }
  | InboxDeliveryChanged{ sessionID, inboxID: InboxID, delivery: Delivery }

  | InstructionsUpdated { sessionID, delta: Map<Key, Option<Hash>>, text: Option<Text> }

  | SyntheticRaised     { sessionID, text, description, metadata }
  | SkillActivated      { sessionID, skill: SkillID, name, text }
  | ShellStarted        { sessionID, shell: ShellInfo }
  | ShellEnded          { sessionID, shell: ShellInfo, output: Text }

  | StepStarted         { sessionID, assistantMessageID, agent, model, started }
  | StepStreamed        { sessionID, assistantMessageID }
  | StepEnded           { sessionID, assistantMessageID, finish, rawFinish, native
                        , cost, tokens, snapshot, files }
  | StepFailed          { sessionID, assistantMessageID, error, cost, tokens }

  | TextStarted         { sessionID, assistantMessageID }
  | TextEnded           { sessionID, assistantMessageID, text }
  | ReasoningStarted    { sessionID, assistantMessageID }
  | ReasoningEnded      { sessionID, assistantMessageID, text }
  | ToolInputStarted    { sessionID, assistantMessageID, id: CallID, name }
  | ToolInputEnded      { sessionID, assistantMessageID, id: CallID, text }
  | ToolCalled          { sessionID, assistantMessageID, id: CallID, name
                        , input: Json, executed: Bool }
  | ToolSuccess         { sessionID, assistantMessageID, id: CallID
                        , content: List<ToolContentItem>, metadata: Map, executed: Bool }
  | ToolFailed          { sessionID, assistantMessageID, id: CallID
                        , error: SessionError, metadata: Map, executed: Bool }
  | ToolProgress        { sessionID, assistantMessageID, id: CallID, metadata }  # ephem

  | CompactionStarted   { sessionID, reason, inputID: Option<InboxID> }
  | CompactionEnded     { sessionID, reason, text, model, native, cost, tokens }
  | CompactionFailed    { sessionID, reason, error, inputID: Option<InboxID> }

  | RevertStaged        { sessionID, messageID }
  | RevertCleared       { sessionID }
  | RevertCommitted     { sessionID }

  | ExecutionStarted    { sessionID }
  | ExecutionSucceeded  { sessionID }
  | ExecutionFailed     { sessionID, error: SessionError }
  | ExecutionInterrupted{ sessionID, reason: User | Shutdown }

  | RetryScheduled      { sessionID, assistantMessageID, attempt: Int
                        , at: Timestamp, error: SessionError }
  | UsageRecorded       { sessionID, cost, tokens }
```

Ephemeral (live only, never logged):

```
type LiveEvent =
  | TextDelta        { sessionID, assistantMessageID, text }
  | ReasoningDelta   { sessionID, assistantMessageID, text }
  | ToolInputDelta   { sessionID, assistantMessageID, id: CallID, text }
  | ToolProgressLive { sessionID, assistantMessageID, id: CallID, metadata }
  | CompactionDelta  { sessionID, text }
  | UsageUpdated     { sessionID, cost, tokens }   # generated, not received
  | PermissionAsked  { sessionID, request: PermissionRequest }
  | PermissionReplied{ sessionID, requestID, reply: Reply }
```

> **Asymmetry you must know:** `UsageUpdated` is **not** an input
> event. It is **generated** from the projected cost line and is
> ephemeral. Costs are a projection, not a truth about inputs.

## 7. Agent and model

```
type AgentInfo =
  { id: AgentID
  , name: Text
  , model: Option<ModelRef>
  , system: Option<Text>        # fully replaces the harness template
  , description: Option<Text>
  , mode: Primary | Subagent | All
  , hidden: Bool                # visibility, not security
  , steps: Option<Int>          # maximum model steps
  , permissions: List<Rule>
  , color: Option<Hex> }

type ModelRef = { providerID: ProviderID, id: ModelID, variant: Option<VariantID> }
```

Model resolution (order is binding):

1. Session has a model → must exist in the catalog, otherwise `ModellNichtVerfügbar`.
2. Session has no model → global default; if none exists → `ModellNichtGewählt`.

## 8. Tools

```
type ToolInfo =
  { name: ToolName
  , description: Text
  , inputSchema: JsonSchema
  , outputSchema: Option<JsonSchema>
  , execute: (input: Json, context: ToolContext) -> Outcome
  , options: Option<ToolOptions> }

type ToolOptions =
  { namespace: Option<Text>      # effective name: <namespace>_<tool>
  , permission: Option<Action>   # for catalog visibility
  , codemode: Bool               # default: part of code mode
  , pinned: Bool }

type ToolContext = { sessionID, agent: AgentID, messageID: MessageID, callID: CallID }

type Outcome = Ok(ToolResult) | Fehler(ToolError)

type ToolResult =
  { output: Option<Json>         # ephemeral machine value
  , content: List<ToolContentItem>  # durable model value
  , metadata: Option<Map> }      # compact, UI-only
```

**The three-way split `output` / `content` / `metadata` is essential:**

* `output` – for code mode; never stored durably.
* `content` – what the model sees; stored durably.
* `metadata` – compact UI information; never model-side.

A tool without `output` **must** deliver non-empty `content`.

## 9. Permissions

```
type Rule = { action: Action, resource: Pattern, effect: Effect }
type Action  = Text          # free string, not a closed enum
type Pattern = Text          # wildcard pattern, not a glob

type PermissionRequest =
  { id: PermissionRequestID
  , sessionID: SessionID
  , action: Action
  , resources: List<Text>
  , save: Option<List<Text>>     # storage suggested by the tool
  , metadata: Option<Map>
  , source: Option<InvocationSource> }
```

Wildcard semantics (`*`, `?`, bracketing, case sensitivity only on
Windows) are defined in [`07-invariants.md`](./07-invariants.md).

## 10. Instructions

```
type InstructionSource =
  | GlobalFile | ProjectFile { path: Path } | Configured { path: Path } | Remote { url }

type InstructionDelta = Map<Key, Option<Hash>>   # None = source removed

type InstructionState =
  { epochStart: Seq
  , throughSeq: Seq
  , initialValues: Map<Key, Value>   # baseline
  , currentValues: Map<Key, Value> }
```

An instruction epoch covers completed compactions. At the end of a
compaction the epoch start moves to its exact sequence.

## 11. Errors

```
type SessionError =
  { type: Text                  # free string, classified
  , message: Text
  , status: Option<Int>
  , response: Option<{ body: Text }>
  , retryAfterMs: Option<Duration> }
```

The classification (`type`) is defined in [`06-errors.md`](./06-errors.md).
The taxonomy is **open**: new error kinds may arise without touching a
central enum.

## 12. Tokens and cost

```
type TokenCount =
  { input: Int, output: Int, reasoning: Int
  , cacheRead: Int, cacheWrite: Int }

type Money = { amount: Decimal, currency: Currency }
```

## Type flow rules (portable)

| Rule | Why |
|---|---|
| Identifiers are opaque and not convertible | prevents confusion of message and inbox item |
| `Option<T>` is its own type, not a null pointer | makes "not present" explicit |
| Events are **values**, not objects with identity | allows replay without object graph |
| Tool `execute` takes `Json` and returns `Outcome` | boundary between trusted and untrusted |
| Errors are values, not exceptions | allows partial matching and serializable representation |<|end|><|start|>write to=assistant<|channel|>analysis<|message|>I kept some German identifiers in code? "ModellNichtVerfügbar" and "ModellNichtGewählt" are error type strings? They appear as `ModellNichtVerfügbar`. The rule says keep identifiers. These are identifiers/error type names in German in original. Should I translate? They are identifiers (error type strings). The rule says keep code/paths/identifiers/numbers/invariant IDs. So keep. But they read German. Hmm. "ModellNichtVerfügbar" is an identifier-like error name. To be safe keep original. Also `# ephem` kept. `# erzeugt, nicht empfangen` translated? I translated comment to "# generated, not received". That's prose in comment. Fine. I translated "# vom Anbieter ausgeführt?" etc. Good.

Note I translated "Taxonomie" → taxonomy. Good.

Now 02-events.md.<|end|><|start|> fileList? Let me read.<|end|><|start|><|start|>assistant to=read<|channel|>analysis <|constrain|>xml<|message|><rfl:parameter name="path">
/home/torsten/dev/my-projects/2oc/qoc/docs/spec/session-engine/02-events.md