# Research Agent – Asynchronous and Parallel Research

> **Status:** Design · 2026-11-04
> **Purpose:** Architecture and usage of a Research Agent that performs web search,
> fetch, analysis, summarization, and verification, and returns results to the
> caller. Supports asynchronous and parallel execution.

## 1. Research Agent as Agent Definition

The Research Agent is a subagent with specific tools:

```markdown
---
name: research-agent
mode: subagent
hidden: true
steps: 20
toolAllow: [websearch, webfetch, read, write, glob, grep]
toolDeny: [shell, edit, patch]
permissions:
  websearch: allow
  webfetch: allow
  read: allow
  write: ask
---
You are a Research Agent. Your task:
1. Search the web for relevant information
2. Fetch the most important sources (webfetch)
3. Analyze and compare the results
4. Create a structured summary
5. Verify sources for consistency and currency
6. Return the result as a structured report

Always respond with: Summary, Sources, Confidence Score.
```

## 2. Three Execution Modes

### Mode A: Synchronous (one topic, blocking)

The caller waits for the result. Suitable for short research (< 2 min.).

### Mode B: Asynchronous (one topic, non-blocking)

`background=true` starts a daemon thread. The caller immediately receives a
session ID and can retrieve the result via polling (`GET /api/session/{id}/messages`)
or SSE (`GET /api/event`).

### Mode C: Parallel (two topics simultaneously)

An orchestrator agent spawns multiple research subagents in parallel. If the
LLM provider supports parallel tool calls, the subagents are started in a single
step. The engine executes them in parallel (max. 4 at a time, semaphore in
SubagentRunner).

## 3. Orchestrator Agent

```markdown
---
name: research-orchestrator
mode: primary
steps: 10
toolAllow: [subagent]
---
You are a Research Orchestrator. When the user mentions multiple topics:
1. Start a research subagent for each topic (in parallel)
2. Wait for all results
3. Combine and compare the results
4. Return an overall summary
```

## 4. Constraints

| Constraint | Impact |
|---|---|
| Depth limit (2) | Orchestrator → Subagent, but no Subagent → Subagent |
| Semaphore (4) | Max. 4 parallel subagents per engine instance |
| No persistent result | Results are lost when the session is deleted |
| No callback/webhook | No push on async completion; polling or SSE required |

## 5. Recommended Implementation

1. Create agent definitions (research-agent, research-orchestrator)
2. Test with an LLM provider (parallel tool calls)
3. Optional: callback/webhook for async completion
4. Optional: persist results in a DB

## 6. What Is Already Implemented

- SubagentTool (parallel, depth 2, semaphore 4)
- Background subagent (`background=true`)
- Session polling (`GET /api/session/{id}/messages`)
- SSE stream (`GET /api/event`)
- MCP tools `qoc_run_agent` and `qoc_run_agent_with_config`

## 7. What Is Missing

- Callback/webhook on completion (2-3 days)
- Result persistence beyond the session (1-2 days)
- Remote subagent via MCP (SubagentTool is local)
