# 04 – Workflows and multi-agent handoff

## 1. Scope

Workflows are optional. The default mode is a single active agent per session.
Workflows allow sequential or conditional handoff between agents.

## 2. Workflow definition

```json
{
  "workflows": [
    {
      "name": "plan-build-review",
      "steps": [
        { "agent": "plan", "input": "user", "output": "plan" },
        { "agent": "build", "input": "plan", "output": "changes" },
        { "agent": "review", "input": "changes", "output": "final" }
      ]
    }
  ]
}
```

## 3. Handoff rules

- A handoff happens only after a step finishes (`FinishReason.NORMAL`).
- The next agent receives the previous output as part of the conversation.
- Each agent uses its own tool set and permissions.
- The session's active agent is updated on handoff.

## 4. Events

- `AgentSwitched(session, previous, next)`
- `WorkflowStarted(session, workflow)`
- `WorkflowStepCompleted(session, step, agent)`

## 5. Invariants

- **WF-01:** Handoff only after a completed step.
- **WF-02:** The receiving agent uses its own tool snapshot.
- **WF-03:** Workflow state is part of the session event log.
- **WF-04:** A workflow can be aborted like a normal prompt.
EOF