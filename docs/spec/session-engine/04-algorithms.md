# 04 – Algorithms

> Pseudocode in the neutral notation from [`README.md`](./README.md).
> Every procedure: **precondition**, **postcondition**, **error cases**.
> Errors are passed as values (`fail(error)`), interruption is its own
> channel.

---

## Contents

- [A. Admission](#a-admission)
- [B. Wake and execution coordination](#b-wake-and-execution-coordination)
- [C. Pass (Drain)](#c-pass-drain)
- [D. Boundary logic (`reach_boundary`)](#d-boundary-logic-reach_boundary)
- [E. Delivery (`deliver`)](#e-delivery-deliver)
- [F. Selecting and loading context](#f-selecting-and-loading-context)
- [G. Building the request](#g-building-the-request)
- [H. Executing a step](#h-executing-a-step)
- [I. Attempt (one model call)](#i-attempt-one-model-call)
- [J. Tool execution](#j-tool-execution)
- [K. Permission check](#k-permission-check)
- [L. Recovery after process abort](#l-recovery-after-process-abort)
- [M. Compaction (shortened)](#m-compaction-shortened)
- [N. Ordering and concurrency guarantees](#n-ordering-and-concurrency-guarantees)

## A. Admission

**Precondition:** Session exists.
**Postcondition:** Exactly one inbox row present **or** it is recognized that the ID
was already admitted. No model call.
**Error cases:** `SessionNotFound`, `InputConflict`, `AttachmentError`,
`CapabilityNotFound`.

```
admit(request) =
  session ← load_session(request.sessionID)      # fail(SessionNotFound)

  # 1. Idempotency first: an already known ID wins.
  existing ← find_inbox(request.id)
  if existing exists:
      if existing.type ≠ request.type or existing.sessionID ≠ session.id:
          fail(InputConflict)
      return { admitted: existing, new: no }

  # 2. Preparation: hooks, attachments, capabilities.
  masking {
      recoverable {
          prepared ← prepare(session, request)   # fail(AttachmentError, CapabilityNotFound)
      }

      # 3. An open revert is committed before new work.
      if session.revert exists:
          write_revert(session)

      # 4. Admission: a durable event, from which the row arises.
      admitted ← publish(InboxEnqueued {
          sessionID: session.id, inboxID: request.id, element: prepared.element
      })
  }

  # 5. Only afterwards is work scheduled.
  if request.continue ≠ false:
      wake(session.id, scope = Input)

  return { admitted: admitted, new: yes }
```

**Notes:**

* The order *Idempotency → Preparation → Admission → Scheduling* is
  binding. In particular, an **already known** content is not prepared again
  (no hooks, no attachment resolution).
* Preparation runs **interruptibly**, the rest does not. An interruption
  leaves **no** half-admission.
* `publish` is atomic: event + inbox row arise together or not at all.

---

## B. Wake and execution coordination

**Postcondition:** If the session is inactive, an execution starts; if it is
active, the work is remembered for one more pass.

```
wake(sessionID, scope) =
    running ← active_execution(sessionID)
    if running exists:
        running.doorbell ← merge(running.doorbell, scope)   # "Input" dominates "Control"
        return
    start_execution(sessionID, scope)

start_execution(sessionID, scope) =
    execution ← new_execution(sessionID, scope)
    running_executions[sessionID] ← execution

    interruptible {
        # one tick delay: the owner is assigned before the run can fail
        wait_one_tick()

        masking {
            publish(ExecutionStarted { sessionID })   # sets the claim in the same transaction
        }

        repeat {
            result ← drain(sessionID, execution.scope, execution.continuation)
            if execution.stopping: break
            if execution.doorbell not set: break
            execution.scope ← execution.doorbell
            execution.doorbell ← not set
            execution.continuation ← not set
        } until result terminal

        # exactly one terminal event for the whole execution period
        masking {
            depending on result:
                Success   → publish(ExecutionSucceeded)
                Abort     → publish(ExecutionInterrupted { reason: User or Shutdown })
                Error e → publish(ExecutionFailed { error: e })
        }
    }

    if execution.doorbell set:     # work arrived during ending
        start_execution(sessionID, execution.doorbell)   # follow-up execution
    else:
        remove running_executions[sessionID]
```

**Notes:**

* Repeated wakes during a run are coalesced into **one** additional pass.
* The "doorbell" closes the gap between the last check in the pass and the
  idle transition – both cannot be one atomic step.
* The follow-up execution receives the work that arrived during ending.

---

## C. Pass (Drain)

**Postcondition:** Either the work is done (`Done`), or execution must
continue elsewhere (`Moved`, `Reloaded`).

```
drain(sessionID, scope, continuation) =
    wait_for_active_plugins()

    # clean up orphaned state from an earlier process
    clean_orphaned_compactions(sessionID)
    clean_orphaned_tool_calls(sessionID)

    proceed := true
    step := continuation.step or 1
    enter := true

    while proceed:
        result ← reach_boundary(sessionID, scope, step, enter, continuation)
        if result.type ≠ Ready:
            return result
        continuation ← result.continuation
        continue_flag ← run_step(result.context, step)
        step ← step + 1
        enter ← false
```

---

## D. Boundary logic (`reach_boundary`)

This is the part that decides **when** something happens: delivery,
compaction, move, or nothing at all.

```
reach_boundary(sessionID, scope, step, enter, continuation) =
    loop:
        if location_closed():
            close_transport(sessionID)
            return { type: Reloaded, continuation: continuation }

        # -- Control elements take precedence --
        serialized_for(sessionID) {
            next ← choose_deliverable(sessionID, scope = (enter or not continuation) ? Input : Control)

            if next.type = Compaction:
                publish_all([
                    InboxDelivered { inboxID: next.id },
                    CompactionStarted { reason: Manual, inputID: next.id }
                ])
                run_compaction(next)
                # the loop continues: after compaction the basis is new

            if next.type = Move:
                close_transport(sessionID)
                publish_all([
                    InboxDelivered { inboxID: next.id },
                    SessionMoved { … }
                ])
                return { type: Moved, continuation: continuation }
        }

        # -- Reset the step when delivery is not control --
        if not continuation and next.delivery_mode ≠ Control:
            enter ← true
            step ← 1

        # -- nothing to do? --
        if not forced and not continuation
           and (next missing or (next.delivery_mode = Queue and scope = Control)):
            return { type: Done }

        # -- prepare context and then deliver --
        selection ← select_context(sessionID)         # fail(AgentNotFound)
        count ← deliver(sessionID, delivery_scope = (enter and not continuation) ? scope : Control)

        if count = undefined:
            continue   # a control element took over this boundary

        if count > 0 and session.no_parent and session.unnamed:
            start_title_generation(sessionID)      # in the background, does not block

        if count > 0:
            step ← 1

        loaded ← load_context(selection)
        return { type: Ready, context: loaded }
```

**Notes:**

* **Controls** (`Control`) are delivered at the next **safe** boundary –
  with precedence over parked (`Queue`) inputs.
* **Control elements** (compaction, move) form a **boundary**: they are
  delivered before everything else, even before older controls, so their text
  lands literally and not in a summary.
* A parked input is delivered only at a **quiet boundary** – and then
  **exactly one**, after which re-evaluation happens.
* The step counter restarts when a new input is delivered.

---

## E. Delivery (`deliver`)

**Postcondition:** Delivered inputs are visible; waiting ones stay waiting.

```
deliver(sessionID, scope) =
    serialized_for(sessionID) {
        controls ← all_inbox_entries_with_mode(Control)

        if controls not empty or scope = Control:
            boundary ← index_of_first_control_element(controls)
            if boundary = 0:
                return undefined      # the caller must handle the control element
            publish_for_all(controls[0 .. boundary-1], InboxDelivered)
            return number_of_delivered

        # only at the quiet boundary: exactly one parked input
        parked ← oldest_inbox_entries_with_mode(Queue)     # 1 entry
        if parked missing:
            return 0
        if parked.type = Control:
            return undefined
        publish(InboxDelivered { inboxID: parked.id })

        # pick up newly arrived controls in the meantime
        new ← all_inbox_entries_with_mode(Control)
        boundary ← index_of_first_control_element(new)
        publish_for_all(new[0 .. boundary-1], InboxDelivered)

        return number_of_delivered
    }
```

> **Serialization:** Admission, delivery and cancellation of the same session
> must not overtake each other. They are serialized via a **per-session lock**
> – sufficient process-locally.

---

## F. Selecting and loading context

```
select_context(sessionID) =
    session ← load_session(sessionID)
    if session.location ≠ current_location:
        interrupt()                      # the execution belongs elsewhere

    refresh_mcp_tools()

    agent ← choose_agent(session.agent)     # fail(AgentNotFound)
    rights ← merge(agent.rights, session.rights)

    parallel {
        tools ← tool_snapshot(rights)
        builtin ← load_builtin_instructions()
        discovered ← load_environment_instructions()
        capabilities ← load_capability_instructions(rights)
        references ← load_reference_instructions()
        mcp ← load_mcp_instructions(rights)
        entries ← load_session_entries(sessionID)
    }

    instructions ← merge_instructions([
        code_mode, mcp, references, capabilities, discovered, builtin, entries
    ])

    return { session, agent, instructions, tools }

load_context(selection) =
    model ← resolve_model(selection.session)        # fail(ModelNotSelected / ModelNotAvailable)
    atomically {
        history ← load_history(sessionID, instructions, model)
        basis ← load_instruction_basis(sessionID, instructions)
    }
    return { …selection, model, basis, history }
```

**Notes:**

* The order of instruction sources is **cache-relevant**: first
  cross-cutting catalog contents, then project-specific, then mutable values
  (date, environment).
* The instruction basis and the history are read in **one** transaction –
  otherwise they could drift apart.

---

## G. Building the request

```
build_request(context, step) =
    system  ← [ agent.system or base_prompt(tool_names), context.basis ]
    messages ← context.history

    limit_reached ← agent.max_steps and step ≥ agent.max_steps
    if limit_reached:
        messages ← messages + [summary_prompt]
        tool_choice ← none
    else:
        tool_choice ← unspecified

    request ← {
        system, messages,
        tools: context.tools,
        model: context.model,
        output_budget: compute_output_budget(context.model, context.input_size),
        headers: [ session-, project- and affinity-related identifiers ],
        cache_key: context.affinity
    }

    # Extension hooks may change request/tools/options
    changed ← hook("context", request)
    # Tool renames are traced back via object identity:
    # a hook may move a definition; invented entries drop out
    request ← bind_tools(changed, context.tools)
    return request
```

> **The texts behind `base_prompt` and `summary_prompt`**
> are given with identifier, order and wording in
> [`13-internal-prompts.md`](./13-internal-prompts.md). That file also states
> that the step-limit prompt is inserted as an **assistant** message.

---

## H. Executing a step

```
run_step(context, step) =
    more_attempts ← true
    assistant ← new_message_id()

    while more_attempts:
        loaded ← load_context(select_context(context.session.id))

        # automatic compaction before the request blows the window
        compaction ← compact(reason = Automatic, loaded)
        if compaction failed:
            fail(StepFailed(compaction.error))
        if compaction.performed:
            assistant ← new_message_id()
            continue               # retry with new basis

        request ← build_request(loaded, step)
        result ← attempt(loaded, request, assistant)

        depending on result.type:
            Done:
                return result.continue_working
            Retry:
                wait_until(result.time)        # publishes "Retry scheduled"
                assistant stays the same
            Continuation:
                wait_until(result.time)
                publish(SyntheticRaised { text: "Continue without retry" })
                assistant ← new_message_id()
            OutputLimit:
                if output_limiter_exhausted:
                    fail(StepFailed(OutputLimit))
                publish(SyntheticRaised { text: "Split the work" })
                assistant ← new_message_id()
            Compacted:
                assistant ← new_message_id()
            FullyRebuilt:
                continue
```

---

## I. Attempt (one model call)

```
attempt(context, request, assistant) =
    start_snapshot ← capture_start_state()          # parallel to the request
    tool_runs ← []

    stream ← start_model_call(request)

    for each event in stream:
        if event = ToolCall_requested and not executed_by_provider:
            # Important: execution starts AFTER publishing "called"
            run ← start_concurrently {
                masking {
                    result ← execute_tool(event, context)
                    if result successful:
                        publish(ToolSuccess { … })
                        return truncated(result)
                    if result error:
                        publish(ToolFailed { error: classify(result.error) })
                }
            }
            tool_runs ← tool_runs + [run]

    wait_all(tool_runs)

    depending on end:
        Error_before_output:
            if classifiable_as_retryable:
                return { type: Retry, … }
        Error_after_output:
            if classifiable_as_retryable:
                return { type: Continuation, … }
        ContextOverflow_before_output:
            if compaction_possible:
                return { type: Compacted }
            # otherwise terminal
        UserRejection:
            return { type: Done, continue_working: no }
        Normal:
            publish(StepEnded { usage, cost, tokens, image, files })
            return { type: Done, continue_working: (still open tool calls) }
```

---

## J. Tool execution

```
execute_tool(call, context) =
    context ← { sessionID, agent, messageID, callID }

    # 1. Pre-hook may change the input (only hook that may fail)
    event ← hook("tool_before", call.name, call.input)
    if event rejected:
        fail(ToolError { message: event.message })

    # 2. Only the tools advertised in this request are callable
    definition ← definition_of(event.name)
    if definition missing:
        fail(ToolError { message: "Not available in this request" })
    if unknown_name:
        fail(ToolError { message: "No tool with this name" })

    # 3. Decode input — on error do NOT execute
    input ← decode(definition.input, event.input)
    if error:
        fail(ToolError { message: "Invalid arguments: … please correct and call again" })

    # 4. Leaf tool: resolution, permissions, side effect — in this order
    result ← definition.execute(input, context)

    # 5. Encode output
    if definition.output_schema:
        output ← encode(definition.output_schema, result.output)
        if error:
            fail(ToolError { message: "Invalid output value" })

    # 6. Post-hook may change the result (must never fail)
    result ← hook("tool_after", result)

    # 7. Normalize content (empty ⇒ derive from output)
    content ← normalize_content(result.content, result.output)

    return { output: result.output, content, metadata: result.metadata }
```

---

## K. Permission check

```
check_permissions(request) =
    # 1. Assemble rule set
    rules ← agent_rights(request.agent) + session_rights(request.sessionID)
    if rules empty:
        rules ← [ { action: all, resource: all, effect: Deny } ]

    # 2. Denial first — it beats any stored approval
    if any_resource_yields(rules, request.action, request.resources, Deny):
        fail(Denied { rules, action, resources })

    # 3. Add stored approvals (per project)
    all ← rules + stored_approvals(project)

    # 4. Determine effect: ask dominates allow
    effect ← if any_resource_yields(all, …, Ask) then Ask else Allow

    # 5. Extension hook may change effect and message
    result ← hook("rights_evaluate", effect)
    if result.effect = Deny:
        fail(Denied { … })
    if result.effect = Allow:
        return allow

    # 6. Ask: publish request and wait
    request_id ← request.publish(QuestionAsked)
    answer ← wait_for_answer(request_id)
    depending on answer:
        Once:   return allow
        Permanent:  store_approval(project, action, answer.suggestion); return allow
        Reject:
            if answer.feedback:
                fail(RejectionWithFeedback { feedback })   # stays typed
            else:
                interrupt_with(Rejection)                      # special abort, not an ordinary error
```

**Notes:** The order 1→6 is the **permission chain**. Each layer can only
restrict, except the extension hook, which may also extend. Stored approvals
are evaluated **after** the denial check – otherwise "allow permanently"
would be a way around "deny".

---

## L. Recovery after process abort

```
recover() =
    for each session with open claim that is not running locally:
        attempts ← count_resumption(session)     # durable, incremented BEFORE the run
        if attempts > maximum (10):
            publish(ExecutionFailed { reason: "repeatedly interrupted" })
            # releases the claim and resets the counter
            continue
        publish(SyntheticRaised { text: "The server was restarted. Continuing." })
        start_execution(session, scope = Input)

    # report running background work first, then start children
    for each background_work:
        depending on kind:
            Shell:    report_result(or Abort by restart)
            Subagent: report_result, then start child if not running locally
```

**Notes:** Recovery is **at-least-once**. It prevents concurrent runs, but not
repeated side effects after an abort. The budget is **per attempt** (not per
session), so a process that keeps dying does not create a restart loop.

---

## M. Compaction (shortened)

```
compact(reason, context) =
    publish(CompactionStarted { reason })
    window ← compute_window(context)          # remainder + summarized old load
    answer ← model_call(window, task = "summarize")
    if answer error:
        publish(CompactionFailed { error })
        return { performed: no, error }
    save_summary(answer)            # full transcript is retained
    push_epoch_start(to exact_sequence)
    publish(CompactionEnded { … })
    return { performed: yes }
```

---

## N. Ordering and concurrency guarantees

| # | Guarantee |
|---|---|
| G-01 | Admission before execution: the inbox row exists before work is scheduled. |
| G-02 | Delivery before visibility: only `InboxDelivered` makes input model-visible. |
| G-03 | `ToolCalled` before the side effect. |
| G-04 | Terminal event after the side effect. |
| G-05 | Tool calls of a step run in parallel, their **publication** stays in call order. |
| G-06 | One step = one model call; the next step reloads the history. |
| G-07 | Terminal events of the execution are published after cleanup (except on interrupt, where confirmation is immediate). |
| G-08 | Interruption ends running tool calls without discarding parked inputs. |

> **Decision (2026-10-22, L-B/L-C/L-D).** Tool calls run with at most 8
> concurrently per step (FIFO semaphore, configurable 1–32); results are
> published in call order (sequencer with buffer; on failure the sequence
> advances, no gap). Waiting inbox items have no TTL (Delivered/Cancelled =
> deleted).
