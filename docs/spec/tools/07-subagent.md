# 07 – Tool family: Subagent (task delegation)

> Tool: `subagent` (V1 name: `task`). Delegates a task to a specialized
> agent in a child session.

## 1. Purpose

An agent can delegate a subtask to a specialized subagent. The subagent runs
in its **own session** (with `parentID`), with its own system prompt, its
own rights, its own model and its own step budget. The result is returned
to the calling agent as tool output.

## 2. Agent modes

| Mode | Meaning | Visible in `GET /api/agent` |
|---|---|---|
| `primary` | Selectable by the user | yes |
| `subagent` | Only invoked (not selectable) | no |
| `all` | both | yes |

Built-in subagents:
| Agent | Mode | Rights | Purpose |
|---|---|---|---|
| `general` | subagent | `question` + `subagent` denied | Multi-step/parallel work |
| `explore` | subagent | `*` denied, whitelist: `shell, grep, glob, webfetch, websearch, read` | File search |

## 3. Tool contract

```
Input:  { agent: AgentID, prompt: String, description?: String }
Output:  Text (result of the subagent) + Metadata { sessionId, agent, steps, cost, tokens }
```

### 3.1 Execution

```
subagent(agent, prompt, context):
    1. Check recursion depth (max. 2 levels). If depth > limit → error.
    2. Load agent profile (system prompt, rights, model, steps).
    3. Check rights: agent must have been allowed `subagent` (Permission.assert).
    4. Create child session (parentID = current session).
    5. Enqueue prompt in child session (SessionService.enqueue).
    6. Run child session (Coordinator.wake) → blocks until idle.
    7. Read assistant answer from child session (Conversation.messages).
    8. Bounding: shorten result if > limit (OutputBounding).
    9. Return result as tool output.
```

### 3.2 Recursion protection

- Max. depth: 2 (Parent → Child → Grandchild, no deeper).
- `general` has `subagent` denied → cannot delegate further.
- `explore` has `subagent` denied → cannot delegate further.
- Depth is checked via the `parentID` chain.

### 3.3 Error handling

| Error | Effect |
|---|---|
| Unknown agent | `Tool.Error` (visible to the model) |
| No permission | `PermissionDenied` (visible to the model) |
| Recursion limit | `Tool.Error` (visible to the model) |
| Subagent fails | `Tool.Error` with subagent error (visible to the model) |
| Timeout | `Tool.Error` (visible to the model) |

### 3.4 Cost and tokens

- Subagent costs are added to the parent session.
- Subagent tokens are tracked separately (metadata in tool output).

## 4. Agent scoping

Each subagent receives:

- Its own system prompt (from the agent definition).
- Its own rights (from the agent definition or defaults).
- Its own tool snapshot (`registry.snapshot(agentPermissions)`).
- Its own optional step budget (`steps`); absent means no step limit.
- Its own model (or parent model as fallback).

## 5. Agent definition (extended)

File `<project>/agent/<id>.md` or `~/.config/opencode/agent/<id>.md`:

```markdown
---
description: Reviews changes without editing them.
mode: subagent           # primary | subagent | all
model: anthropic/claude-sonnet-4-5
steps: 16
hidden: false
permissions:
  - { action: "*",      resource: "*", effect: allow }
  - { action: "edit",   resource: "*", effect: deny }
  - { action: "subagent", resource: "*", effect: deny }
---
You are a reviewer. Read the change before judging.
```

## 6. Invariants

| # | Invariant | Verification method |
|---|---|---|
| S-01 | A subagent runs in its own session with `parentID`. | Integration test |
| S-02 | The subagent cannot delegate deeper than 2 levels. | Unit test |
| S-03 | A subagent with `subagent` = deny cannot delegate further. | Unit test |
| S-04 | The subagent result is returned to the parent as tool output. | Integration test |
| S-05 | Subagent costs are added to the parent session. | Integration test |
| S-06 | A failed subagent produces `Tool.Error`, not a crash. | Integration test |

## 7. Best practices

1. **Isolation:** No shared mutable state between parent and child.
2. **Timeout:** Subagent runs have a timeout (default: 5 min).
3. **Result truncation:** Large results are shortened (OutputBounding).
4. **No parallel subagents:** Sequential execution (simpler, less resource
   contention).
5. **Context handover:** Parent passes relevant files/questions in the prompt.
6. **Tool restriction:** Subagent receives only tools its permissions allow.
7. **Recursion protection:** Depth limit + `subagent`-deny for leaf agents.
8. **Cost tracking:** Separate tracking, but attribution to the parent session.

## 8. References

- Spec: `spec/permissions/03-effect-matrix.md` (`subagent` action, target agent ID)
- Spec: `spec/session-engine/01-types.md` (`parentID`, `forkSessionID`, `forkBoundary`)
- Spec: `spec/session-engine/04-algorithms.md` (Execution, Claim, resumption)
- Spec: `spec/betrieb/README.md` §3 (Agent modes, baseline: one agent)
- Spec: `spec/tools/01-types.md` (Tool contract)
- OpenCode reference: `oc/packages/core/src/session/revert.ts` (Session hierarchy)
- OpenCode reference: `oc/packages/core/src/tool/plugin/task.ts` (V1 task tool)
