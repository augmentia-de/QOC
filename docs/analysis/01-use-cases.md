# 01 – Use Cases & Business Logic

> **Rolle:** reference analysis des OpenCode-V2-Checkouts, not an operative
> QOC-Spec. For the implementation, see [`spec/`](../spec/README.md).

> **Workflow 1:** Use-Case & Business-Logic Extraction
> `[Code] ➔ [Structure Parsing] ➔ [LLM with domain-role focus] ➔ [Gherkin/BDD + FR document]`
>
> **As of:** 2026-10-21, Repo `dev` @ `3108505b2f`
> **Method:** The source code was **not read raw**, but prepared structurally:
> (a) Codebase-Memory graph (44,297 nodes / 213,584 edges) for symbol and
> dependency resolution, (b) AST-near inspection across the
> `schema`/`protocol`/`core` domain boundaries, (c) the binding contracts in
> `specs/v2/*.md` as scaffolding, (d) the user docs in
> `services/www/src/docs/content/` for cross-checking.
> Domain-role focus: **DDD expert + business analyst** – the rules extracted below
> are implicit business logic, validation rules and edge cases, not boilerplate
> descriptions.
>
> **Legend:** `UC-xx` Use Case · `BR-xx` Business Rule · `FR-xx` Functional
> Requirement · `NFR-xx` Non-Functional Requirement. Every rule/step has a source
> reference. References of the form `tools.mdx:49` etc. point to
> `services/www/src/docs/content/`; all other paths point to the repo root.

---

## Contents

- [1. Domain Model (derived from the code)](#1-domain-model-derived-from-the-code)
- [2. Use Cases](#2-use-cases)
- [3. Business Rules (implicit domain logic, extracted)](#3-business-rules-implicit-domain-logic-extracted)
- [4. Validation rules and edge cases (excerpt)](#4-validation-rules-and-edge-cases-excerpt)
- [5. Aggregation – Functional Requirements](#5-aggregation-functional-requirements)
- [6. Still-open domain questions](#6-still-open-domain-questions)
- [7. Next step](#7-next-step)

## 1. Domain Model (derived from the code)

The domain language of the system as it appears in the code:

| Term | Definition | Reference |
|---|---|---|
| **Location** | A directory context (project root + current working directory) in which services live | `packages/core/src/location.ts`, `packages/core/src/location-services.ts` |
| **Project** | Directory identity (from Git remote hash, otherwise `Hash("directory:<dir>")`) | `packages/core/src/project.ts:354-397` |
| **Workspace** | Optional, reserved cluster/workspace identity | `packages/core/src/location.ts:19-32` |
| **Session** | A conversation with durable history, costs, tokens, permission ruleset | `packages/core/src/session/session.ts`, `session/sql.ts:22-77` |
| **Inbox Item** | Not-yet-delivered input (user, synthetic, compaction, move) | `packages/core/src/session/inbox.ts` |
| **Step** | A logical model call (`llm.stream`) | `specs/v2/session.md:70-80` |
| **Physical Attempt** | A concrete attempt within a step (retry, continuation) | `specs/v2/session.md:72` |
| **Agent** | Named assistant profile: system prompt, model, permissions, steps, mode | `packages/schema/src/agent.ts:22-36` |
| **Tool** | An executable value with `input`/`output`/`execute` | `specs/v2/tools.md:7-27` |
| **Tool Call** | A concrete execution, durable before the side effect | `specs/v2/session.md:74` |
| **Permission** | Decision `allow`/`deny`/`ask` for action × resource | `packages/schema/src/permission.ts` |
| **Policy** | Binary, organisation-capable lock over providers/tools/integrations | `packages/schema/src/config/policy.ts` |
| **Instruction** | Value source (hash) in the system-prompt context (AGENTS.md, skills, …) | `packages/core/src/instructions/index.ts` |
| **Compaction** | Replaces older conversation with summary + recent context | `packages/core/src/session/compaction.ts` |
| **Revert** | Undoing a message change via snapshots | `packages/core/src/session/revert.ts` |
| **Worktree** | Additional checkout of a project | `packages/core/src/worktree.ts` |
| **Form** | Structured follow-up form to the user | `packages/schema/src/form.ts` |

---

## 2. Use Cases

### UC-01 – Interactive coding session in the terminal

**Actor:** Developer · **System:** TUI + Core
**Source:** `packages/tui/src/app.tsx`, `packages/core/src/session/session.ts:155-188`

```gherkin
Scenario: User starts a session and gives a coding task
  Given a Location for the directory "acme/packages/web"
  And the agent "build" is selected
  And no provider is locked
  When the user enters "find the timeout configuration and set it to 30 seconds"
  Then exactly one inbox item with delivery="steer" is admitted
  And the item is logged as "enqueued" before execution
  And the session is woken
  And the model receives the system prompt, instructions and tool definitions
  And every tool result is logged durably before the side effect
  And the response appears live in the TUI
```

```gherkin
Scenario: User asks a second question while execution is running
  Given a session is currently running
  When the user enters "and adjust the tests too"
  Then the item is admitted as "steer"
  And it is delivered only at the next safe step boundary
  And the already running execution is not aborted
```

```gherkin
Scenario: User aborts execution
  Given a session is running
  When the user chooses abort
  Then the locally owned execution is stopped
  And pending inbox inputs remain
  And an unknown session ID yields SessionNotFoundError
  And for a known but idle session the abort is a no-op
```
Reference: `specs/v2/session.md:16-19, 57-64`; `packages/core/src/session/execution.ts:150-167`

---

### UC-02 – Switching agents (build ↔ plan)

**Actor:** Developer · **Source:** `packages/core/src/agent.ts:74-105`, `plugin/plan.ts`

```gherkin
Scenario: User switches to plan mode
  Given a session with agent "build"
  When the user switches the agent to "plan"
  Then "session.agent.selected" is published
  And the plan-mode reminder ("<system-reminder> You are in Plan mode …") is injected as a synthetic message
  And file changes outside ~/.opencode/plan are blocked
  And the blockage is reported to the model as a ToolFailure with a clear reason
```

```gherkin
Scenario: User switches back to "build"
  Given a session with agent "plan"
  When the user switches the agent to "build"
  Then the counter-reminder is injected ("You are NO LONGER in Plan mode")
  And the previous plan restriction no longer applies
```
Reference: `packages/core/src/plugin/plan.ts:14-25, 44-53, 75-96`

---

### UC-03 – Permission request (ask → once / always / reject)

**Actor:** Developer · **Source:** `packages/core/src/permission.ts:87-329`

```gherkin
Scenario: Tool needs a permission that has not been granted
  Given an agent whose rules for "shell" with resource "git push *" end in "ask"
  When the model executes a shell command "git push origin main"
  Then the permission is evaluated
  And a permission request is published ("permission.asked")
  And the tool call waits blocking for the answer
```

```gherkin
Scenario: User allows once
  When the user chooses "once"
  Then exactly this request is granted
  And nothing is persisted permanently
```

```gherkin
Scenario: User allows permanently for the project
  Given the tool proposes saving "git status *"
  When the user chooses "always"
  Then a row is stored in the "permission" table for the project
  And all further pending requests that now resolve to "allow" are granted automatically
```

```gherkin
Scenario: User rejects
  When the user chooses "reject"
  Then all parallel open requests of the same session are rejected
  And the rejection aborts the current step
  And if no text feedback is given, Permission.DeclinedError arises
  And if text feedback is given, Permission.CorrectedError arises
  And the model can continue working with the feedback
```
Reference: `packages/core/src/permission.ts:227-233, 269-329`; `packages/core/src/session/to-session-error.ts:68-69`

---

### UC-04 – Organisation policy blocks an action

**Actor:** Organisation administrator · **Source:** `packages/core/src/config/plugin/policy.ts:54-69`

```gherkin
Scenario: Console policy denies a shell call
  Given the connected console delivers the statement "tool.use / shell:sudo * = deny"
  When the model runs "sudo apt install"
  Then the permission is evaluated after all agent and session rules and after all saved approvals
  And the policy statement is evaluated last
  And the result is "deny"
  And the error message reads "Blocked by <Organisation>'s policy"
```

```gherkin
Scenario: A user rule cannot override an organisation policy
  Given the global config allows "provider.use / openai"
  And the console denies "provider.use / openai"
  Then openai remains blocked
  And the provider disappears from catalog and model selection
```
Reference: `specs/v2/provider-policy.md:202-248`; `services/www/src/docs/content/policies.mdx:178-207`

---

### UC-05 – Starting a subagent

**Actor:** Main agent · **Source:** `packages/core/src/tool/plugin/subagent.ts`

```gherkin
Scenario: Main agent delegates a search
  Given the agent "explore" is available as a subagent
  When the main agent calls the subagent tool with agent ID "explore"
  Then a child session is created
  And the child session uses the agent's model if configured, otherwise the parent's
  And the child session starts with fresh context
  And the child session's permissions are those of the agent, not the parent's
```

```gherkin
Scenario: Nesting beyond the default limit
  Given "experimental.subagent_depth" is not increased (default 1)
  When a subagent tries to start another subagent
  Then the call is rejected
```
Reference: `packages/core/src/tool/plugin/subagent.ts:29-62, 129-137`; `services/www/src/docs/content/agents.mdx:640-641`

---

### UC-06 – Compaction (automatic and manual)

**Actor:** System / Developer · **Source:** `packages/core/src/session/compaction.ts`, `specs/v2/session.md:98-104`

```gherkin
Scenario: Context limit is reached
  Given the model-visible request approaches the context window
  And compaction is enabled
  When the runner estimates the request before the next step
  Then a structured rolling summary plus bounded recent context is stored
  And the full transcript remains durably retained
  And provider-native continuation state does not cross the compaction boundary
```

```gherkin
Scenario: Provider reports overflow before durable output
  Given the provider rejects the request as "too long"
  And no durable assistant output has been produced yet
  Then exactly one overflow compaction may occur
  And the same logical step is rebuilt
  But a second overflow is terminal
  And an overflow after durable output is terminal
```

```gherkin
Scenario: Developer triggers compaction manually
  Given "compaction.auto" is false
  When the developer requests a manual compaction
  Then it is admitted as an inbox control item
  And it runs at the next safe point, before waiting prompts
  And repeated requests are coalesced into the running compaction
  And an explicit "id" makes the call idempotent
```
Reference: `services/www/src/docs/content/compaction.mdx:42-66, 83-89`; `specs/v2/session.md:100-104`

---

### UC-07 – Instruction update during a session

**Actor:** Developer · **Source:** `packages/core/src/config/plugin/instruction.ts`, `specs/v2/session.md:90-96`

```gherkin
Scenario: AGENTS.md is changed during a session
  Given a running session already has an instruction epoch
  When the developer adds a line to AGENTS.md
  Then OpenCode detects the change before the next model request
  And a delta with the new SHA-256 hash of the source is admitted
  And the rendered text is projected as a chronological system message
  And the client sees the changed keys, but not their privileged content
```

```gherkin
Scenario: All AGENTS.md files are removed
  When no ambient AGENTS.md files exist anymore
  Then the session reports that the previous ambient instructions no longer apply
```

```gherkin
Scenario: File is temporarily unreadable
  When reading the instruction file fails
  Then the last known instructions remain
  And the source is not treated as deleted
```
Reference: `services/www/src/docs/content/instructions.mdx:78-99`; `specs/v2/session.md:92-94`

---

### UC-08 – Recovery after process crash

**Actor:** System · **Source:** `packages/core/src/session/execution/restart.ts`

```gherkin
Scenario: Server starts after an unclean stop
  Given a session has an open write-ahead claim
  When the server starts
  Then inherited tool calls still projected as "streaming" or "running" are failed
  And the session continues from the durably projected history
  And the recovery-attempt counter is incremented
  And at most 10 attempts per claim take place
  And exactly-once execution of provider or tool effects is not guaranteed
```
Reference: `specs/v2/session.md:68, 114-116`; `packages/core/src/session/execution/restart.ts:33, 65-232`

---

### UC-09 – Sharing a session

**Actor:** Developer · **Source:** `packages/schema/src/config.ts:43-44`, API `share`

```gherkin
Scenario: Session is shared
  Given "share" is "manual" or "auto"
  When the developer shares the session
  Then a publicly accessible link is created
  And access runs through "/api/share/:shareID"
```
Reference: `packages/schema/src/config.ts:41-44`; `packages/protocol/src/groups/session.ts`

---

### UC-10 – Creating a worktree and moving a session there

**Actor:** Developer · **Source:** `packages/core/src/worktree.ts`, `packages/core/src/session/move.ts`

```gherkin
Scenario: Developer works in a dedicated worktree
  When the developer calls "worktree_create" with name "login"
  Then a worktree is created below the configured target directory
  And the target path is resolved relative to the project's stored canonical checkout
  And the worktree is linked in the "worktree" table
```

```gherkin
Scenario: Session is moved into the worktree
  When the developer calls "session_move"
  Then the session is moved at the next safe boundary
  And the instruction state is retained so that target instructions appear as an update
  And worktree_remove uses the "edit" permission with the worktree directory as resource
```
Reference: `packages/core/src/tool/plugin/opencode.ts:156-199`; `specs/v2/session.md:96`; `services/www/src/docs/content/tools.mdx:239-256`

---

### UC-11 – Using an MCP server

**Actor:** Developer · **Source:** `packages/core/src/mcp/`, `packages/core/src/tool/mcp.ts`

```gherkin
Scenario: MCP tool is offered
  Given an MCP server is connected
  When the server reports tools
  Then the tools are registered in the registry under "<server>_<tool>"
  And the permission action is "<server>_<tool>" with resource "*"
  And the MCP instructions appear as a "<mcp_instructions>" block in the prompt
```

```gherkin
Scenario: MCP server is blocked by policy
  Given a policy "integration.use / mcp:github = deny"
  When the policy is applied
  Then the server is removed from the catalog
  And no connection is opened
  And its tools, instructions, prompts and resources disappear
```
Reference: `packages/core/src/tool/mcp.ts:20-21, 46-119`; `specs/v2/provider-policy.md:373-379`

---

### UC-12 – Loading a skill

**Actor:** Main agent · **Source:** `packages/core/src/skill.ts`

```gherkin
Scenario: Agent loads a skill
  Given a skill with ID "effect" is available in the catalog
  When the agent calls the skill tool with this ID
  Then instructions and bundled resources are loaded into the context as a "<skill_content>" block
  And the block names the skill's base directory and a sampled file list
  And the permission action is "skill" with the skill ID as resource
```

```gherkin
Scenario: Skill is blocked by policy
  Given a policy "integration.use / skill:* = deny"
  Then the skill is neither visible in the catalog nor in model guidance
  And it cannot be loaded via the skill tool nor via a prompt mention
```
Reference: `packages/core/src/skill.ts:33-53`; `specs/v2/provider-policy.md:267-282`

---

### UC-13 – Structured follow-up form

**Actor:** Developer · **Source:** `packages/core/src/tool/plugin/question.ts`, `packages/schema/src/form.ts`

```gherkin
Scenario: Agent needs a decision
  Given the model calls the "question" tool with a question and options
  Then execution is paused
  And the client shows an interactive form
  And "Type your own answer" is always available
  And with "multiple: true" multiple selections are possible
  And the permission action is "question" with resource "*"
```

```gherkin
Scenario: Developer dismisses the form
  When the developer closes the dialog
  Then the call is aborted with QuestionTool.CancelledError
```

```gherkin
Scenario: Conditional fields
  Given a form with a field "target" and a follow-up field "url"
  And the follow-up field has "when: {key: target, op: eq, value: web}"
  When "target" is set to "web"
  Then the follow-up field is active
  But when "target" is something else
  Then the follow-up field is neither required nor answerable
```
Reference: `packages/core/src/tool/plugin/question.ts:12-36`; `packages/schema/src/form.ts:34-42`

---

### UC-14 – Non-interactive run (`opencode run`)

**Actor:** CI / Script · **Source:** `packages/cli/src/run/noninteractive.ts:158-186`

```gherkin
Scenario: Headless run with permission prompt
  Given a permission resolves to "ask"
  And the client is not interactive
  Then "reject" is answered automatically
  And a feedback text is sent along so the model can continue working
  But with "--auto"
  Then "once" is answered automatically
  And configured "deny" rules remain enforced in every case
```
Reference: `services/www/src/docs/content/permissions.mdx:294-295`; `packages/cli/src/run/noninteractive.ts:158-186`

---

### UC-15 – Coupling the web UI to the local server

**Actor:** Developer · **Source:** `services/www/src/docs/content/index.mdx:51-61`

```gherkin
Scenario: Developer opens the web UI
  When the developer runs "opencode pair"
  Then a one-time link is created
  And the link expires after 5 minutes
  And it connects the browser session to the local server
```
Reference: `services/www/src/docs/content/index.mdx:53-61`; `packages/server/src/pairing.ts`

---

### UC-16 – Blocking a provider in the catalog

**Actor:** Administrator · **Source:** `packages/core/src/config/plugin/policy.ts:28-41`

```gherkin
Scenario: Provider is blocked by policy
  Given a policy "provider.use / openai = deny"
  When the catalog is built
  Then provider and model entries are first fully materialised
  And only then is the policy applied
  And the provider is removed from the catalog
  And it disappears from selection even if it has valid credentials
```
Reference: `specs/v2/provider-policy.md:315-327`; `services/www/src/docs/content/policies.mdx:83-110`

---

### UC-17 – Forking a session

**Actor:** Developer · **Source:** `packages/core/src/session/transfer.ts`, `packages/schema/src/session-fork.ts`

```gherkin
Scenario: Developer forks a session
  When a fork is created at a chosen boundary
  Then only messages up to the boundary are copied
  And the parent's instruction values are adopted as baseline
  And the fork sets session and events as its own aggregate sequence
```
Reference: `specs/v2/session.md:96`; `packages/core/src/session/projector.ts:115` (`projectFork`)

---

### UC-18 – Revert (undo) of a change

**Actor:** Developer · **Source:** `packages/core/src/session/revert.ts`, `packages/core/src/snapshot.ts`

```gherkin
Scenario: Developer undoes a change
  Given snapshots are enabled
  When a file is changed
  Then a snapshot is created in a dedicated Git repository under
    "~/.local/share/opencode/snapshot/<projectID>/<hash>"
  When the developer reverts
  Then "session.revert.staged", "session.revert.committed" or "session.revert.cleared" is published
  And on a committed revert the instruction state is deleted
```
Reference: `packages/core/src/snapshot.ts:92-103`; `specs/v2/session.md:96`

---

### UC-19 – Event feed overloads a connection

**Actor:** Client · **Source:** `packages/server/src/event-feed.ts`, `specs/v2/event-stream-architecture.md`

```gherkin
Scenario: Slow client overflows its queue
  Given the client has 4,096 public frames in the queue
  When another frame is offered
  Then the queue is immediately removed from the registry
  And it is terminated with SubscriberOverflowError
  And the frame is still offered to all other active queues
  And publication never blocks for other connections
```

```gherkin
Scenario: Public event is not encodable
  When schema encoding of an event fails
  Then all currently connected queues are terminated with EncodingError
  And the faulty event is skipped
  And the feed remains available for later connections
```
Reference: `specs/v2/event-stream-architecture.md:47-64, 157-166`

---

## 3. Business Rules (implicit domain logic, extracted)

### 3.1 Session & Execution

| ID | Rule | Reference |
|---|---|---|
| BR-S-01 | WHEN a session ID is reused, THEN the system adopts the existing session | `specs/v2/session.md:9` |
| BR-S-02 | WHEN the same item ID is submitted again for the same session and type, THEN the **first** admission wins; payload/metadata/delivery of the retry are ignored | `specs/v2/session.md:9` |
| BR-S-03 | WHEN the same item ID is used for a different session or type, THEN the operation fails | `specs/v2/session.md:9` |
| BR-S-04 | WHEN an inbox item is admitted, THEN it sits **outside** the model-visible history until `session.inbox.delivered` consumes it | `specs/v2/session.md:7` |
| BR-S-05 | WHEN `resume` is missing or `true`, THEN admission **and** `SessionExecution.wake` are triggered; with `false` only admission occurs | `specs/v2/session.md:11-14` |
| BR-S-06 | WHEN several steers are pending, THEN they are delivered in admission order; delivery stops before a compaction or move control item | `specs/v2/session.md:18`, `:23` |
| BR-S-07 | WHEN an item is parked (`queue`) and the session becomes idle, THEN steers still have priority; otherwise exactly one queue item is delivered, then re-evaluated | `specs/v2/session.md:19` |
| BR-S-08 | WHEN new user input is promoted, THEN the selected agent's step allowance is reset; a batch of steers resets it **once** | `specs/v2/session.md:21` |
| BR-S-09 | WHEN a step begins, THEN the runner reloads history, resolves agent/model, prepares instructions and materialises tools | `specs/v2/session.md:70-72` |
| BR-S-10 | WHEN a local tool call completes, THEN a **new** step begins; there is **no** in-memory tool loop | `specs/v2/session.md:80` |
| BR-S-11 | WHEN a local tool call starts, THEN it is **durable** before the side effect begins | `specs/v2/session.md:74` |
| BR-S-12 | WHEN a tool call is published durably, THEN it additionally carries `assistantMessageID`, because `callID` is only unique **within** a step | `specs/v2/session.md:76` |
| BR-S-13 | WHEN orphaned tool calls are still projected as streaming/running at drain start, THEN they are failed before further model work starts | `specs/v2/session.md:78` |
| BR-S-14 | WHEN a provider request fails generically, THEN at most **4 retries** with jittered exponential backoff are allowed; before durable output no further step allowance is consumed | `specs/v2/session.md:84-86` |
| BR-S-15 | WHEN a provider returns a content-filter finish, THEN the step fails; partially streamed content remains visible | `specs/v2/session.md:88` |
| BR-S-16 | WHEN a process starts, THEN sessions with open claims are resumed (max. 10 attempts); success/failure/user interrupt release the claim, shutdown interrupt and unclean death do not | `specs/v2/session.md:68`, `session/execution/restart.ts:33` |
| BR-S-17 | WHEN a session is idle, settled or not locally owned, THEN an interrupt is a no-op | `specs/v2/session.md:64` |
| BR-S-18 | WHEN `sessions.active()` is called, THEN it returns only the busy periods **owned in this process**; durable claims are history, not proof | `specs/v2/session.md:66` |
| BR-S-19 | WHEN an instruction epoch ends through a completed compaction, THEN the epoch start is set to its exact sequence | `specs/v2/session.md:96` |
| BR-S-20 | WHEN a session is moved, THEN the instruction state is retained so that target instructions appear as an update | `specs/v2/session.md:96` |

### 3.2 Permissions

| ID | Rule | Reference |
|---|---|---|
| BR-P-01 | WHEN several rules match, THEN the **last** wins (evaluation via `findLast`) | `packages/core/src/permission.ts:87-97` |
| BR-P-02 | WHEN no rule matches, THEN `ask` applies | `packages/core/src/permission.ts:93-95` |
| BR-P-03 | WHEN any resource resolves to `deny`, THEN the operation is blocked – even across a saved approval | `packages/core/src/permission.ts:165-167, 175` |
| BR-P-04 | WHEN several resources are checked, THEN `ask` beats `allow` (except on `deny`) | `packages/core/src/permission.ts:177-178` |
| BR-P-05 | WHEN a plugin uses the `permission.evaluate` hook, THEN it may override effect and message | `packages/core/src/permission.ts:179-188` |
| BR-P-06 | WHEN the answer is `always`, THEN the `save` pattern proposed by the **tool** is stored **per project** | `packages/core/src/permission.ts:303-309` |
| BR-P-07 | WHEN the answer is `reject`, THEN all parallel open requests of the same session are rejected | `packages/core/src/permission.ts:286-299` |
| BR-P-08 | WHEN the user rejects without feedback, THEN the error is tunnelled as a defect and **not** converted into tool output | `packages/core/src/permission.ts:252-257` |
| BR-P-09 | WHEN the user rejects with feedback, THEN the error stays typed and becomes `ToolFailure`, so the model can continue | `packages/core/src/tool/AGENTS.md` (section "Construction"), `packages/core/src/permission.ts:66-68` |
| BR-P-10 | WHEN the agent is missing, THEN `deny` applies to everything | `packages/core/src/permission.ts:19` |
| BR-P-11 | WHEN the base rules apply, THEN `*.env` and `*.env.*` are `ask`, `*.env.example` is `allow` | `packages/schema/src/agent.ts:46-51` |
| BR-P-12 | WHEN `external_directory` is not explicitly regulated, THEN `allow` applies – external access produces **no** prompt | `services/www/src/docs/content/permissions.mdx:142-145` |
| BR-P-13 | WHEN a path lies outside the location and the project worktree, THEN `external_directory` is checked **first**, then `read`/`edit` | `packages/core/src/file-access.ts:152-159` |
| BR-P-14 | WHEN a policy matches `tool.use`, THEN the result is `deny` – even across a saved "allow always" approval | `services/www/src/docs/content/policies.mdx:128-129` |
| BR-P-15 | WHEN a policy says `allow` for `tool.use`, THEN it **never** grants access; it only lifts an earlier broader `deny` | `services/www/src/docs/content/policies.mdx:140-141` |
| BR-P-16 | WHEN a filter removes a tool from the catalog, THEN that is **catalog visibility**, not execution authorisation | `packages/core/src/tool/AGENTS.md` |

### 3.3 Policies & Providers

| ID | Rule | Reference |
|---|---|---|
| BR-C-01 | WHEN no policy applies, THEN the action is allowed (default `allow`) | `specs/v2/provider-policy.md:108`, `packages/core/src/managed-policy.ts:72-74` |
| BR-C-02 | WHEN `action` is not in the closed set, THEN the statement is dropped during normalisation with a diagnostic | `specs/v2/provider-policy.md:70` |
| BR-C-03 | WHEN authored config documents are read, THEN they are evaluated in **reverse** order, so user-global overrides the repository policy | `specs/v2/provider-policy.md:164-172` |
| BR-C-04 | WHEN organisation statements exist, THEN they stand **behind** all authored statements and have final authority | `specs/v2/provider-policy.md:204-208` |
| BR-C-05 | WHEN the console config fetch fails, THEN the last config remains active (deliberately "fail open" for policy) | `specs/v2/provider-policy.md:236` |
| BR-C-06 | WHEN the connection is dropped, the workspace is switched or the console returns 404, THEN the statements are replaced or cleared | `specs/v2/provider-policy.md:231` |
| BR-C-07 | WHEN a plugin wants to change policy statements, THEN it is blocked; `remove` ignores `opencode.config.policy` and `opencode.provider.opencode` | `specs/v2/provider-policy.md:244-246` |
| BR-C-08 | WHEN provider and model entries are built, THEN the policy is applied **afterwards**, so later reloads cannot bring back a filtered provider | `specs/v2/provider-policy.md:315-326` |
| BR-C-09 | WHEN a provider is blocked, THEN it is **not** usable via the normal provider/model path – but plugin code is **not** sandboxed by this | `specs/v2/provider-policy.md:248` |

### 3.4 Tools

| ID | Rule | Reference |
|---|---|---|
| BR-T-01 | WHEN a tool declares `output`, THEN that is the ephemeral machine value for Code Mode; `content` is the durable model value; `metadata` is UI JSON | `specs/v2/tools.md:19` |
| BR-T-02 | WHEN a tool has no `output`, THEN it MUST deliver non-empty model content | `specs/v2/tools.md:142` |
| BR-T-03 | WHEN invalid input is decoded, THEN the tool is **never** executed | `specs/v2/tools.md:140` |
| BR-T-04 | WHEN invalid output is decoded, THEN there is **no** successful run | `specs/v2/tools.md:140` |
| BR-T-05 | WHEN the model content is too large, THEN it is truncated head+tail and stored in full in managed storage | `specs/v2/tools.md:152` |
| BR-T-06 | WHEN full retention fails, THEN the run fails **operationally** – never a lossy success | `specs/v2/tools.md:152` |
| BR-T-07 | WHEN a tool triggers `execute.after` hooks, THEN the result is normalised and bounded again | `specs/v2/tools.md:154` |
| BR-T-08 | WHEN the last step begins, THEN the request keeps the tool definitions with `toolChoice: "none"`, so the prompt prefix stays warm | `specs/v2/tools.md:144` |
| BR-T-09 | WHEN a tool registration is closed, THEN **only** that registration disappears and the previous one becomes visible again | `specs/v2/tools.md:77-83` |
| BR-T-10 | WHEN the model request advertises the tools, THEN exactly the tool version captured there is executed | `specs/v2/tools.md:176` |
| BR-T-11 | WHEN `execute` is deny with `*`, THEN Code Mode and all tools reachable only through it disappear; nested tools keep their own rules | `services/www/src/docs/content/tools.mdx:218-221` |
| BR-T-12 | WHEN a tool experiences an interruption, THEN it is **not** translated into `ToolFailure` | `specs/v2/tools.md:47` |

### 3.5 Instructions & Provider responses

| ID | Rule | Reference |
|---|---|---|
| BR-I-01 | WHEN an instruction source changes, THEN a SHA-256 hash per changed key is stored; absence is marked as `"removed"` | `specs/v2/session.md:92` |
| BR-I-02 | WHEN instruction values are stored, THEN they remain privileged – clients see **which** keys changed, not the content | `specs/v2/session.md:94` |
| BR-I-03 | WHEN a source is unavailable at the first delta, THEN that blocks **only** this initial delta; the stored value remains | `specs/v2/session.md:94` |
| BR-I-04 | WHEN V2 recognises an instruction file, THEN it is exclusively `AGENTS.md` – `CLAUDE.md` is not a fallback | `services/www/src/docs/content/instructions.mdx:41-43` |
| BR-I-05 | WHEN a model request arises, THEN all sources are read and hashed **in parallel, exactly once** | `specs/v2/session.md:94` |
| BR-I-06 | WHEN a provider receives a requested header or body overlay, THEN it is **not yet sent** (V2 gap) | `services/www/src/docs/content/agents.mdx:337-340` |

### 3.6 Event feed

| ID | Rule | Reference |
|---|---|---|
| BR-E-01 | WHEN the feed runs, THEN there is exactly **one** global core subscription and **one** encoding per event | `specs/v2/event-stream-architecture.md:5-23` |
| BR-E-02 | WHEN no HTTP subscribers exist, THEN the observer returns **before** wire encoding | `specs/v2/event-stream-architecture.md:134` |
| BR-E-03 | WHEN internal events, `server.connected` or heartbeats occur, THEN they consume **no** queue capacity | `specs/v2/event-stream-architecture.md:64` |
| BR-E-04 | WHEN the client exceeds queue capacity, THEN **only this** connection dies | `specs/v2/event-stream-architecture.md:47-51` |
| BR-E-05 | WHEN a public event is not encodable, THEN all current queues are terminated and the feed remains usable | `specs/v2/event-stream-architecture.md:157-166` |
| BR-E-06 | WHEN a client connects, THEN `server.connected` is sent first, then the live frames run | `specs/v2/event-stream-architecture.md:145-153` |

---

## 4. Validation rules and edge cases (excerpt)

| Context | Rule / edge case | Reference |
|---|---|---|
| Permission wildcards | `*` = any (incl. `/`), `?` = one character, rest literal; **anchored** to the whole value; case-insensitive only on Windows; backslashes → `/` | `packages/core/src/util/wildcard.ts:3-13` |
| Shell pattern | `git status *` also matches `git status` without arguments | `packages/core/src/util/wildcard.ts:11` |
| Home expansion | `~`, `~/`, `$HOME`, `$HOME/` are resolved only for `external_directory`, `read`, `edit` – **not** for shell resources | `packages/core/src/config/plugin/agent.ts:141-162` |
| `read` | 2,000 lines, 50 KiB per page; lines > 2,000 characters truncated; image/PDF up to 20 MiB; other binary files fail | `services/www/src/docs/content/tools.mdx:29-35` |
| `glob` | Default 100 files, timeout 30 s | `tools.mdx:49` |
| `grep` | Default 100 matching lines, timeout 30 s; resource is the **regex**, not the path | `tools.mdx:62-64` |
| `shell` | Default 2 min (foreground); `background: true` = no timeout, notification on completion | `tools.mdx:116-125` |
| `webfetch` | Timeout up to 120 s; only HTTP/HTTPS, no binary downloads | `tools.mdx:136-142` |
| `patch` | only for supported GPT models; otherwise `edit` + `write` | `tools.mdx:101-102` |
| Code Mode | no filesystem access, no imports, no timers; only `fetch` | `tools.mdx:216-217` |
| Browser | upload paths server-side, 5 MiB per transfer; contents are **not trusted** | `tools.mdx:270-276` |
| Compaction | `keep.tokens` default 15,000, `buffer` = 15 % (min. 16k); cannot help when the request consists almost only of instructions/tool schemas | `compaction.mdx:83-89, 154-166` |
| Native compaction | encrypted checkpoint is **bound to provider and model**; on model change the session continues with the original | `compaction.mdx:128` |
| Warming | real provider requests (costs, rate limits); admits nothing, dispatches no tools, response is discarded | `warming.mdx:92-127` |
| Subagent | `subagent_depth` default 1; `primary` agents are invalid as subagents | `agents.mdx:640-641`, `tool/plugin/subagent.ts:136-137` |
| `todowrite` | only registered when `experimental.todo === true` (default `false`) | `packages/core/src/tool/plugin/todo.ts:50-55` |
| Shell scanner | `portable_shell_scanner` **replaces** the tree-sitter scanner (no fallback); commands that cannot be analysed produce a scanner error, **not** a permission rejection | `permissions.mdx:205-207` |
| Agent selection | default must exist, be visible and primary-capable; otherwise `build`, then the first matching agent | `agents.mdx:96` |
| Agent merging | later scalars replace, request maps merge by key, permission rules are **appended** | `agents.mdx:160-177` |
| Locales | `location_idle_timeout` default 60 min; running sessions and background jobs keep the location loaded | `config.mdx:650-653` |
| Updates | `update` default `notify`; auto-installation does **not** restart the server | `config.mdx:168-170` |
| Config variables | `{env:VAR}` / `{file:path}`; missing values → empty string | `config.mdx:582-606` |
| V1→V2 | `write\|patch → edit`, `task → subagent`, `bash → shell`; `enabled_providers`/`disabled_providers` → `provider.use` statements | `packages/core/src/v1/config/migrate.ts:28-33`, `config/normalize.ts:409-427` |
| V1 fields (agent) | `temperature`, `top_p`, `prompt`, `permission`, `tools`, `disable`, `maxSteps` no longer used | `agents.mdx:342` |
| Event capacity | 4,096 frames per connection; **no** byte budget; capacity was deliberately not increased | `specs/v2/event-stream-architecture.md:53, 101-122` |

---

## 5. Aggregation – Functional Requirements

The aggregation summary document: the 19 use cases are condensed into **13
functional requirements**.

| ID | Requirement | Covered use cases |
|---|---|---|
| FR-01 | The system must durably admit user input, manage it idempotently and deliver it to the model only at safe boundaries. | UC-01 |
| FR-02 | The system must manage agents as named profiles (prompt, model, permissions, steps, mode) and accompany mode switches with context. | UC-02, UC-05 |
| FR-03 | The system must authorise tool calls before execution and synchronously involve the user on `ask` without losing state. | UC-03, UC-14 |
| FR-04 | The system must enforce organisation rules beyond its own configuration and prevent plugins from circumventing those rules. | UC-04, UC-16 |
| FR-05 | The system must reconstruct sessions across process crashes without claiming exactly-once. | UC-08 |
| FR-06 | The system must compact long sessions at the context limit without data loss (durable full transcript + summary). | UC-06 |
| FR-07 | The system must manage instructions as versioned, hash-based deltas and reload them continuously. | UC-07 |
| FR-08 | The system must integrate extensions (MCP, skills, plugins) into the tool catalog without bypassing their permissions. | UC-11, UC-12 |
| FR-09 | The system must conduct follow-up questions as structured forms, including conditional fields and cancellation. | UC-13 |
| FR-10 | The system must be able to create, list, remove workspaces (worktrees) and move sessions into them. | UC-10 |
| FR-11 | The system must be able to share, fork, rename, revert and archive sessions. | UC-09, UC-17, UC-18 |
| FR-12 | The system must distribute events through a single encoded feed that isolates and punishes slow connections without blocking others. | UC-19 |
| FR-13 | The system must serve multiple surfaces (TUI, web, desktop, CLI, SDK, ACP) through **one** HTTP contract. | UC-01, UC-15 |

### 5.1 Non-functional requirements (from the contracts)

| ID | Requirement | Reference |
|---|---|---|
| NFR-01 | One step = exactly one `llm.stream`; no in-memory tool loop | `AGENTS.md`, `specs/v2/session.md:80` |
| NFR-02 | Execution is process-local; clustering is not implemented (deliberate boundary) | `specs/v2/session.md:53-55, 118` |
| NFR-03 | Schema → Core/Protocol → Server; client never Core/Server | Root `AGENTS.md` |
| NFR-04 | The transcript remains fully durable, even after compaction | `specs/v2/session.md:102` |
| NFR-05 | The permission system is **not** a sandbox – it is a UX feature | `SECURITY.md:15-19` |

---

## 6. Still-open domain questions

1. Which **implicit** rules of provider configuration are undocumented?
   (Candidates: `packages/core/src/plugin/provider/*`, `packages/core/src/variant.ts` – 29 KB)
2. How do the **console harnesses** (Claude Code/Codex) behave domain-wise?
   A specification of the session/permission model of these integrations is missing.
3. Are there **cost/budget warning** rules that live not in core but only in the console?
4. Which business rules live in `packages/core/src/variant.ts` (model variants, overlays)?
5. `packages/core/src/session/stats.ts` (14 KB) – which metrics count as domain-relevant?

---

## 7. Next step

From the open questions a **rules-catalog artifact** can be derived next: a
machine-readable list of all `BR-*` with source, so that later changes can be
checked against these rules. A `packages/core`-adjacent test that checks the
core BRs as regression guardrails would be sensible.