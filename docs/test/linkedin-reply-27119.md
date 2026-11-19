# LinkedIn reply to 27119.html – 3 versions

---

## Version 1: Direct and concise

> Thanks, you're right. Retry isn't the problem – duplicate or partial execution is. The tool calls already have an idempotency key, but the tools themselves must support it. That's exactly the point that still needs testing and analysis.

> SKIP LOCKED is mandatory with shared-DB operation, of course. These are all points the baseline (not designed for multi-instance) doesn't cover yet. I'll take a closer look.

**Strengths:** Short, clear, direct. Works as a quick comment.

---

## Version 2: Structured and technical (shorter, corrected)

> Thanks – exactly the point. Retry is simple, but duplicate or partial execution (e.g. pod crash after the first of two DB writes) is the real problem.

> The tool calls already have a deterministic idempotency key (`idem_{sessionId}_{callId}`), and the ToolResultCache returns the cached result on retry – without re-executing. But the tools themselves must be idempotent or support check-before-retry. Whether a tool is idempotent is currently unknown – there is no `idempotent` flag yet. Non-idempotent tools (email, API calls) cannot be safely retried today.

> For operations without idempotency and without a check option, the Saga pattern is the only safe option: each step declares a compensating action that is executed in reverse order on failure. This is not yet implemented in our case – an open item I'll look into more closely.

> SKIP LOCKED is mandatory with shared-DB operation – for run claiming, ToolResultCache, and session claiming. These are all points the baseline (single-instance engine) doesn't cover yet. Multi-instance is a retrofitted requirement.

> Next steps: `idempotent` flag in the tool registry, `RunStore.claimFailedRuns()` with SKIP LOCKED, `ToolResultCache` with `ON CONFLICT DO NOTHING`, and Saga compensating actions for non-idempotent steps.

**Strengths:** Technically precise, honest about open items, compact. Good for expert discussion.

---

## Version 3: Narrative and reflective

> Thanks, you're right – and exactly this insight was the most valuable part of our last test phase.

> Retry is trivial. The problem is duplicate or partial execution: if a pod dies after the first of two DB writes has been committed, the state becomes inconsistent. Per-step transactions help, but not for crashes within the transaction.

> The tool calls already have an idempotency key (`idem_{sessionId}_{callId}`), and the ToolResultCache returns the cached result on retry. But the tools must support it – and that's exactly the point that still needs testing. Not all tools are idempotent: writing a file with `O_EXCL` or `INSERT ... ON CONFLICT` is, sending an email or making API calls without an idempotency header is not. For the latter, check-before-retry is needed.

> For operations where neither idempotency nor check-before-retry is possible (e.g. "send order email"), the Saga pattern is the only safe option: each step declares a compensating action that is executed in reverse order on failure. This is not yet implemented in our case – an open item. The compensating actions themselves must also be idempotent, otherwise you have the same problem one level higher.

> SKIP LOCKED is mandatory with shared-DB operation, of course. These are all points the baseline doesn't cover yet – QOC was designed as a single-instance engine, and multi-instance is a retrofitted requirement affecting the entire concurrency architecture: session ownership, run ownership, tool execution isolation.

> I'll take a closer look. The first step will be introducing an `idempotent` flag in the tool registry so the dispatcher can decide during resume whether a tool can be retried automatically. For non-idempotent steps with external side effects, the Saga pattern with compensating actions will then follow.

**Strengths:** Tells a story (test phase → insight → next steps), reflects on the learning process. Good for thought leadership.

---

## Recommendation

- **For a quick comment:** Version 1
- **For expert discussion:** Version 2
- **For a thought-leadership post:** Version 3

---

## SAGA pattern: supplementary details

### What is SAGA in this context?

A workflow step can have an external side effect that is not idempotent and not verifiable (e.g. "send order email"). When the pod dies after this step and the run is resumed:

1. **Idempotency:** Tool has idempotency key → cache hit → return result (no re-execute). ✅ available
2. **Check-before-Retry:** Tool can check whether the side effect already happened → if yes, mark as completed. ✅ possible (if the tool supports it)
3. **SAGA (Compensating Action):** Neither 1 nor 2 possible → compensating action is defined (e.g. "send cancellation email"). On failure of the next step, all compensating actions are executed in reverse order. ⚠️ not yet implemented in QOC

### Open questions for SAGA in QOC

- Who defines the compensating action? The tool author or the workflow designer?
- Are compensating actions themselves idempotent? (They must be, otherwise you have the same problem one level higher.)
- How are compensating actions stored in the journal? (As separate steps or as metadata?)
- What happens if a compensating action itself fails? (Retry? HITL? Abort?)
