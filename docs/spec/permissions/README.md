# Permissions – specification

> **Subject:** How it is decided whether an action is allowed, how a
> prompt is asked and answered, and where the boundary between
> “catalog visibility" and “authorization" runs.
>
> **Status:** 2026-10-22 · Reference checkout `dev` @ `3108505b2f`

## Reading order

| File | Content |
|---|---|
| [`01-types.md`](./01-types.md) | `Rule`, `Effect`, `Request`, `Reply`, `Source` – the data forms |
| [`02-evaluation.md`](./02-evaluation.md) | The rule chain, the wildcard semantics, the three replies |
| [`03-effect-matrix.md`](./03-effect-matrix.md) | Action names, resources, boundaries, catalog vs. authorization, sandbox |
| [`04-invariants.md`](./04-invariants.md) | `P-01…` – the testable rules |
| [`05-java.md`](./05-java.md) | Java 21 implementation |

## The core in four sentences

1. **A rule** is `(action, resource, effect)`. The **last** matching
   rule decides.
2. **No match** means “ask" – not “allow".
3. **Deny beats every stored approval.** That is the one ordering you
   must not confuse.
4. **Catalog visibility is not authorization.** A visible tool checks
   its permissions itself.

## Two things this spec is **not**

| Not | Why that matters |
|---|---|
| **No sandbox.** | The permission system is a usability feature, not a security boundary (`SECURITY.md:15-19`). It protects against *accidents*, not against *attacks*. |
| **No audit log.** | `permission.asked` and `permission.replied` are **ephemeral** – they do not land in the event log. Only the stored approvals exist. |

> **The absence of an audit log is a deliberate gap of the reference
> implementation** – and a point where a new implementation can be
> **better**: an audit log of approval decisions could be added with
> little effort and would answer the question “who approved what?"
> durably.

## The three levels on which permissions act

```
1. CATALOG      What is visible at all?            → tool offering
2. DECISION     What may be executed right now?    → rule chain
3. EXECUTION    What actually happens?              → leaf tool
```

The levels are **separate**, and each has its own rule:
level 1 is a coarse filter (a `deny` with `*` switches a tool off),
level 2 is the actual decision, level 3 is the final check in the tool.

## The vocabulary in this spec

| Term | Meaning |
|---|---|
| **Action** (`action`) | **free string** – no closed enum. `read`, `edit`, `shell`, `glob`, `grep`, `<server>_<tool>`, `execute` … |
| **Resource** (`resource`) | text by which the action is related to a concrete target. Path, pattern, regex, command text. |
| **Effect** (`effect`) | `allow` · `deny` · `ask` |
| **Rule** | `(action, resource, effect)` |
| **Ruleset** | ordered list of rules |
| **Request** | a concrete check: action + resources + save suggestion |
| **Reply** | `once` · `always` · `reject` |

## Flow of a check

```
Request (action, resources[])
   │
   ├─ assemble ruleset:  Agent ++ Session
   ├─ Step 1: any resource "deny"?   → DENIED
   ├─ Step 2: + stored approvals (per project)
   ├─ Step 3: any resource "ask"?     → ASK
   ├─ Step 4: otherwise              → ALLOWED
   │
   └─ Step 5: extension hook may change the effect
```

Details in [`02-evaluation.md`](./02-evaluation.md).