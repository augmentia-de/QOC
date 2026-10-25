# Ideas

## Wrapper REST method: prompt in an ephemeral session

### Goal

A single REST method should:

1. create a new session,
2. execute the given prompt,
3. return the result,
4. automatically delete the session afterwards.

This allows a client to perform a task with a single call, without
session-ID management.

### Proposed endpoint

```http
POST /api/run
Content-Type: application/json

{
  "agent": "build",
  "text": "Name the capital of France in one word.",
  "deleteOnComplete": true
}
```

Possible response:

```json
{
  "finish": "NORMAL",
  "inputTokens": 123,
  "outputTokens": 2,
  "text": "Paris",
  "sessionId": null,
  "durationMs": 456
}
```

### Behavior

- If `agent` is missing, the primary agent is used.
- If `deleteOnComplete=true`, the session is deleted after execution.
- If deletion fails, the `sessionId` is returned in the response.
- Running or failed sessions can optionally be retained (`retainOnError`).
- Internally the method should use `createSession`, `prompt`, and
  `deleteSession`.

### Open questions

- Should the response always return the `sessionId` or only on errors?
- Should the method support agent definitions by name or inline definition?
- Should it use the same request body as the later task gateway?
- Should it provide SSE events for the run or only return JSON?
- How should timeout be handled?
- Should the session be deleted automatically on error?

### Further ideas

- Batch execution of multiple prompts in one session
- Agent switch before execution
- Isolate a workspace per call
- Idempotency key for repeated calls
- Audit log for ephemeral runs
- Optional persistent mode instead of automatic deletion
- Compatibility with the later task/execution gateway