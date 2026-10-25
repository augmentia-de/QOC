# 04 – Session Lifecycle and Execution Control

## Purpose

This diagram shows the lifecycle of a session and the deterministic control
of a run: claim, doorbell, exactly one active run per session and a terminal
event.

> **Current state:** The states are a reading of the existing events,
> projections and building blocks. There is no separate `session_state` table.
> `Coordinator`, `SessionExecution` and `Recovery` exist and are tested,
> but are not yet used by the HTTP path. `Engine` currently works with its
> own `running` map and a direct prompt loop.

## Session states

```mermaid
stateDiagram-v2
  [*] --> Created: SessionCreated
  Created --> Idle
  Idle --> Queued: InboxEnqueued
  Queued --> Delivered: InboxDelivered
  Delivered --> Running: Engine.prompt / runPrompt
  Running --> Running: Step + Tool Calls
  Running --> Compacting: Context Overflow
  Compacting --> Running: CompactionEnded
  Running --> Completed: no tool call / maxSteps
  Running --> Interrupted: User Interrupt
  Running --> Failed: Provider/Tool Error
  Running --> Suspended: Shutdown
  Suspended --> Running: Recovery
  Completed --> Idle
  Interrupted --> Idle: Claim free
  Failed --> Idle: Claim free
  Idle --> Deleted: SessionDeleted / Retention
  Created --> Deleted: DELETE /api/session/{id}

  note right of Running
    Deterministic framework:
    claim, one active run per session,
    doorbell, one terminal event.
    The LLM decides only about text
    and tool calls within a step.
  end note
```

## Claim, doorbell and recovery

```mermaid
flowchart TD
  Wake["wake(session)"]:::det --> Active{"run active?"}:::det
  Active -->|"yes"| Ring["Doorbell ring<br/>merge scopes"]:::det
  Ring --> Coalesce["no second run<br/>wake is coalesced"]:::det
  Active -->|"no"| Acquire["ClaimStore.acquire(session)"]:::det
  Acquire -->|"empty"| Busy["already claimed<br/>no run"]:::det
  Acquire -->|"ok"| Started["ExecutionStarted"]:::det
  Started --> Run["ExecutionRunner.run"]:::mixed
  Run --> Terminal{"terminal?"}:::det
  Terminal -->|"success"| Success["ExecutionSucceeded<br/>Claim release"]:::det
  Terminal -->|"failed"| Failed["ExecutionFailed<br/>Claim release"]:::det
  Terminal -->|"interrupted (user)"| Interrupted["ExecutionInterrupted<br/>Claim release"]:::det
  Terminal -->|"interrupted (shutdown)"| Keep["claim retained<br/>recovery"]:::det
  Success --> Follow{"Doorbell?"}:::det
  Failed --> Follow
  Interrupted --> Follow
  Follow -->|"yes"| Wake
  Follow -->|"no"| Idle["idle"]:::det
  Keep --> Recovery["recovery on restart"]:::det
  Recovery --> Run

  classDef det fill:#d9f7d9,stroke:#2e7d32,stroke-width:1px,color:#111;
  classDef mixed fill:#fff9c4,stroke:#f9a825,stroke-width:1px,color:#111;
```

## Rules

| Rule | Meaning |
|---|---|
| **One active run per session** | `Coordinator` holds a `SessionExecution` per session; parallel wakes are coalesced via the doorbell. |
| **Claim survives the process** | A shutdown does not release the claim; on restart `Recovery` takes over. |
| **Exactly one terminal event** | `ExecutionSucceeded`, `ExecutionFailed` or `ExecutionInterrupted`. |
| **Doorbell is coalescing** | Multiple wakes during a run lead to exactly one follow-up run. |
| **Interrupt is idempotent** | An interrupt on an inactive session is a no-op. |
| **User interrupt releases the claim** | Shutdown retains it for recovery. |

## Where does the LLM fit in?

The LLM share lies **inside** `ExecutionRunner.run` / `Step.run`:
text and tool calls. The state transitions, claims, doorbell and terminal
events are deterministic.

## Sources

- `engine/src/main/java/de/augmentia/oc/engine/runtime/execution/Coordinator.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/execution/SessionExecution.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/execution/ExecutionRunner.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/execution/Recovery.java`
- `engine/src/main/java/de/augmentia/oc/engine/core/model/SessionEvent.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java`