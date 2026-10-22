# 02 – Evaluation

> The rule chain, the wildcard semantics, the three replies and the
> organization-bound additional rules.

## 1. The rule chain

Order decides. That is why it is fixed.

```
check(request) =
  1. RULESET
     rules = agent_rules(request.agent) ++ session_rules(request.sessionID)
     if rules empty: rules = [{action:"*", resource:"*", effect:Deny}]

  2. DENIAL PRE-CHECK
     if any resource evaluates to "deny" with the RULES:
         → DENIED   (exception: rules with the matching effect report)

  3. STORED APPROVALS
     all = rules ++ stored_approvals(project)

  4. DETERMINE EFFECT
     if any resource evaluates to "ask" with "all": → ASK
     otherwise:                                     → ALLOWED

  5. EXTENSION HOOK
     result = hook("permission.evaluate", {…})
     → the (changed) effect applies; the message is adopted
```

### Why step 2 stands **before** step 3

```
{action: "edit", resource: "*.env", effect: Deny}      ← from config
{action: "edit", resource: "*",       effect: Allow}     ← stored (“always allow")
```

Without the ordering, the stored approval would **override** the denial –
and “always allow" would be a way around every prohibition rule.
**The denial always wins.**

### Why step 4 puts “ask" over “allow"

```
{action: "read", resource: "a.txt", effect: Allow}
{action: "read", resource: "b.txt", effect: Ask}
```

A request with **both** resources is “ask". The user is asked, and their
answer applies to **both**. An “allow" for a subset must not override an
“ask" – that would be an approval without knowledge.

### The effect “deny" with several resources

```
If ONE resource evaluates to "deny" → the whole operation is blocked.
```

There is **no** “one part may, one part may not". The user can split
the operation (two tool calls) – but a single call that is partially
forbidden is **wholly** rejected. Reason: partial approval would be a
silent behavior change.

## 2. Wildcard semantics

The resource is **not a glob**, but a **pattern**:

| Character | Meaning |
|---|---|
| `*` | anything (including `/`) |
| `?` | exactly one character |
| all others | literal |
| ` *` at the end | also matches the command **without** arguments |
| `\` | becomes `/` (input **and** pattern) |
| case | case-insensitive **only** on Windows |
| matching | **anchored** to the whole value |

```
Examples:
  "git status *"      matches  "git status"  AND  "git status --short"
  "*.env"             matches  ".env", "prod.env", "a/b/.env"
  "src/**"            does NOT match "src/a/b"   (that is a glob, not a pattern)
  "read *"            matches "read x", not "reread x"
```

> **Anchoring** matters: `*.env` matches `.env` **and** `x.env` – but
> also `foo.env.bak`, because `*` stands at the beginning. For “exactly
> `.env` at the end" you need `*.env` **with** anchoring – and that is
> what exists.
>
> **Why no glob:** globs know `**`, character classes and `{a,b}`. The
> resource hierarchy is flat (action + target), and a flat pattern is
> implementable in a few lines in every language and is **predictable**.

### The special rule “ ` *` at the end"

A pattern ending in a **space** followed by `*` also matches the
command **without** arguments:

```
"git status *"  →  "git status"      ✓
                →  "git status -sb"  ✓
```

> **Why:** the stored pattern arises from the **prefix** of a command
> (`git status` → `git status *`). Without this rule you would have to
> store two patterns (`git status` and `git status *`) – and the
> approval would no longer mean “all variants of git status".

## 3. The three replies

### `Once`

Only this call is approved. **Nothing** is stored.

### `Always`

```
1. If the save pattern is empty: only like "Once" (nothing to store)
2. Otherwise: create stored_approval(project, action, save pattern)
3. Re-evaluate all other open requests
4. Those that now evaluate to "allowed" are approved automatically
```

> **Step 3 matters:** whoever has three open requests and permanently
> allows one does not want the other two to hang, even though the new
> approval covers them. That is why they are re-evaluated.
>
> **Step 3 re-evaluates, it does not overwrite.** A request that still
> evaluates to “deny" (e.g. from a denial rule) remains rejected.

### `Reject`

```
1. Reject the current request:
     without feedback → rejection without reason  (abort, not model-facing)
     with feedback    → rejection with reason      (tool error, model-facing)
2. Reject ALL other open requests of the same session likewise
   (same treatment, same feedback)
3. Remove all requests from the queue
```

> **Step 2 is the batch.** The user rejects – not just this one action.
> The others are rejected with the same reason.

## 4. Channel separation (the most important point)

```
Rejection WITHOUT feedback  →  ABORT
   · the step ends
   · the model sees NOTHING
   · execution is terminated (or paused, with HITL)

Rejection WITH feedback     →  TOOL ERROR
   · the model sees the feedback text
   · the model may retry
```

> **Why the difference?** “No" is an answer to a question, not an error
> report. If the model gets “you declined that" as a tool result, it
> retries the same thing – typically in a loop until the step limit is
> reached.
>
> **The implementation:** the reject-without-feedback path throws an
> **abort** that sits below the error handling. In the reference
> checkout this is an `Effect.die` (`permission.ts:257`), which is only
> caught again at the tool boundary (`model-request.ts:388-402`). In
> Java it is a separate unchecked exception (`Aborted`) that is **not**
> caught by `catch (ToolFailure)`.

## 5. Organization-wide additional rules (Policy)

Beyond configuration, rules can come from a **connected organization**.
They are **binary** (only `allow`/`deny`) and have **final authority**.

```
type PolicyStatement =
  { effect: Allow | Deny
  , action: "provider.use" | "tool.use" | "integration.use"
  , resource: Text }
```

| Action | Resource | Meaning |
|---|---|---|
| `provider.use` | provider ID | this provider is (not) allowed |
| `tool.use` | `<permissionaction>:<resource>` | e.g. `shell:sudo *`, `edit:*.env` |
| `integration.use` | `mcp:<server>`, `plugin:<package>`, `hosted:<integration>`, `skill:<skill>` | this extension is (not) allowed |

**Two properties that surprise:**

| # | Property | Consequence |
|---|---|---|
| 1 | A `tool.use` with `allow` **never grants** access. | It only lifts a **broader denial**. The actual decision between “allowed" and “ask" remains with the normal rules. |
| 2 | A `tool.use` with `deny` **overrides** even stored “always allow" approvals. | The organization is stronger than the user. |

> **Property 1 matters for implementation:** a naive implementation
> (“Policy-`allow` → allowed") would override the whole rule chain. The
> correct meaning is: “this broader denial no longer applies" – after
> that the normal chain decides.

### Evaluation order of the policy

```
1. Provider with provider.use-deny   → remove from catalog
2. MCP server with integration.use-deny → remove (do not connect)
3. Skills with integration.use-deny    → remove
4. tool.use-deny against every permission check → effect to "deny" + message
```

> **The policy is applied **last** – after all other catalog
> transformations. Otherwise a later reload could bring back a filtered
> provider.

### Precedence of sources (6 levels)

| # | Source |
|---|---|
| 1 (highest) | connected organization console |
| 2 | managed system defaults (macOS) |
| 3 | system configuration (`/etc/opencode` or similar) |
| 4 | global configuration (`~/.config/opencode`) |
| 5 | direct project configuration – **outer beats inner** |
| 6 (lowest) | `.opencode/` configuration – **outer beats inner** |

> **Within a file** the written order applies. **Within a document**
> several sources are merged
> (`[...generated, ...experimental, ...native]`) – later ones win.

### Behavior on errors

| Situation | Behavior |
|---|---|
| Fetching the organization rules fails | the **last** known configuration stays active (“fail open") |
| Connection dropped / workspace changed / 404 | the rules are **replaced** or emptied |
| Rules of different connections | are **never** merged |

> **“Fail open" is a deliberate decision** – with a rationale: a failed
> connection should not destroy the **personal** credentials. The price
> is that the organization restriction does not apply in that moment.
> This belongs in the operations documentation.

## 6. Verified semantics (reference checkout)

| Concept | Location |
|---|---|
| `evaluate` via `findLast` | `packages/core/src/permission.ts:87-97` |
| Denial pre-check, stored approvals, ask-dominates-allow | `packages/core/src/permission.ts:165-178` |
| Extension hook | `packages/core/src/permission.ts:179-188` |
| `assert` with BlockedError, Deferred, defect tunnel | `packages/core/src/permission.ts:235-267` |
| `reply` (once/always/reject, batch, re-evaluation) | `packages/core/src/permission.ts:269-329` |
| Wildcard semantics | `packages/core/src/util/wildcard.ts:3-13` |
| Policy plugin (four intervention points) | `packages/core/src/config/plugin/policy.ts:28-69` |
| Organization rules (delivery, fail open) | `packages/core/src/managed-policy.ts`, `specs/v2/provider-policy.md:202-248` |
