# LinkedIn Comment on 27119.html

**Topic:** Workflow Run as Tool Call in the Same Loop – Implications for QOC

---

## Comment (English)

This is an elegant simplification that we are implementing exactly the same way in QOC (Quarkus-based workflow engine): every workflow node – whether LLM agent, MCP tool, or REST call – goes through the same dispatch loop with a unified permission check (allow-all / least-privilege / interactive / deny-all) and a single journal (session state + audit log). This significantly reduces the attack surface and the code path compared to two separate engines.

The resume semantics are indeed the critical point. For us it looks like this:

**1. Journal granularity**
QOC writes a session snapshot after every node step (Postgres in production, in-memory in the dev/GCP-memory mode). If a pod dies, the journal knows that step N was started – but not whether the external side effect (e.g. an MCP tool call that wrote a file or made an API call) was actually executed. We have two approaches for this:

- **Idempotency Keys:** MCP tool calls and REST calls receive a deterministic key derived from `(sessionId, nodeId, attempt)`. Tools that support this can detect duplicate execution.
- **Check-before-Retry:** For tools without idempotency support, the resume loop checks, before retrying, whether the expected side effect is already visible (e.g. file exists, DB row present). If so, the step is marked as "completed" without re-executing the tool call.

**2. Run-Claiming with SKIP LOCKED**
In our setup: when a pod dies, the run remains in status `RUNNING`. Another instance can claim it via `SELECT ... FOR UPDATE SKIP LOCKED` from the `runs` table. This prevents two instances from picking up the same run simultaneously. Important: the claiming instance must load the last known journal state and check whether the last step had a side effect that is not idempotent.

**3. What we have not solved yet**
- **Non-idempotent tools without check possibility:** If a tool has an external side effect that cannot be verified (e.g. "send email"), we cannot guarantee that it is not executed twice on retry. Here we rely on `requiresHumanApproval` (HITL) as a gate before such steps.
- **Partial commits:** If a step consists of multiple DB writes and the pod dies after the first write, we get an inconsistent state. We use transactions per step, but that does not protect against process crashes within the transaction.

**Conclusion:** Simplifying to a single loop is correct and reduces complexity. But the resume semantics require that every tool explicitly declares its idempotency property. In our case this is a field in the tool registry (`idempotent: boolean`) that the dispatcher checks on resume. Tools without this flag are not automatically retried on resume but are instead moved into the HITL queue.

---

## Key Takeaways for LinkedIn

1. **One loop, one permission model, one journal** – reduces attack surface and code paths.
2. **Resume requires idempotency declaration** – every tool must state whether it is idempotent.
3. **FOR UPDATE SKIP LOCKED** for run-claiming – prevents double-pickup.
4. **Check-before-Retry** for non-idempotent tools with verifiable side effects.
5. **HITL as fallback** for tools without idempotency and without check possibility.
6. **Partial commits** remain an open problem – transactions per step help, but not against process crashes.
