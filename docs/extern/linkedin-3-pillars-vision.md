# LinkedIn Post: 3-Pillar Model for Efficient AI Workflows (Vision)

> Location: `docs/linkedin-3-pillars-vision.md` (outside the Git repo)
> Language: English
> Project name: **QOC**

---

## Post (copy-paste for LinkedIn)

Why a giant agent that keeps everything in context fails in practice – and how QOC solves it.

Most AI workflows fail at two extremes:

- Too rigid: pure scripts without intelligence.
- Too chaotic and expensive: a giant agent that keeps everything in context, gets stuck, and blows up the context window.

The solution is a precise division of labor inside the workflow graph across three clearly defined node types.

🔹 1. Deterministic Nodes (The Guardrails)

What they do: Execute fixed rules, data transformations, API calls (MCP), branching, or human approvals – completely without an LLM.

Context requirement: Minimal (only the exact input variables needed).

Why we need them:
- 0 € token cost and maximum speed: what a script solves in 5 milliseconds must not be computed by an expensive language model.
- 100 % reliability: same input guarantees same output. They form the stable skeleton of the process and guarantee auditability and replayability.

🔹 2. Subagent Nodes with Bounded Context (The Specialists)

What they do: Encapsulate a self-contained, adaptive agent loop (ReAct) for a clearly scoped subtask – e.g. "Research source X" or "Extract data from PDF Y".

Context requirement: Isolated (sandbox principle). The subagent only sees its specific assignment and the raw data needed for it. After the work is done, all internal tool calls, failed attempts, and intermediate steps are discarded – only the clean, structured end result (JSON Schema) flows back into the main workflow.

Why we need them:
- Prevents context pollution: when an agent needs 15 tool calls to find one piece of information, that process does not clutter the rest of the workflow.
- High quality and focus: an LLM that is not distracted by irrelevant history hallucinates significantly less and works more precisely.

🔹 3. Context Agents with Overall Overview (The Strategists)

What they do: Stand at critical junctions (planning, synthesis, final approval) where the big picture is needed to make decisions or merge results.

Context requirement: Curated and condensed. They receive the global system prompt, the original objective, and the compressed end results of the previous subagents and deterministic steps.

Why we need them:
- Maximum context caching: because the global system prompt and base documents stand stably at the beginning and the dynamic history stays extremely compact thanks to subagent isolation, the LLM providers' prefix caching works optimally. This cuts the cost of large contexts by up to 75–90 % and massively speeds up response time.
- Real intelligence in the right place: the model uses its full capacity for connecting relationships instead of searching for details.

---

The Comparison at a Glance

| Criterion | Deterministic Nodes | Subagent Nodes (Specialists) | Context Agents (Strategists) |
|---|---|---|---|
| Task | Rules, routing, transformation | Isolated detail work & tool usage | Synthesis, evaluation, overall strategy |
| LLM usage | No (0 tokens) | Yes (focused, own child session) | Yes (overarching overview) |
| Context scope | Point-in-time variables | Tight sandbox (cleaned up afterwards) | Curated overall history + base knowledge |
| Main advantage | Speed, determinism, 0 € cost | No context pollution, high precision | Optimal context caching, well-founded decisions |

---

The Conclusion for the Team

"We let deterministic nodes do the work that needs no AI. We let subagents absorb the noise of tool execution in isolated rooms. And we give the context agents exactly the condensed overview they need to decide quickly, cheaply, and accurately – supported by context caching."

🚀 What is still missing on your path from agent demo to agent service?

📌 Repository: https://github.com/augmentia-de/QOC

#AIAgents #Harness #MCP #LangChain4j #Quarkus #Java #OIDC #RBAC #SpecDrivenDevelopment #WorkflowOrchestration #ContextCaching #LLMOps

---

## Publication Notes

- This post is formulated as a standalone vision (not a direct follow-up).
- Can be used as a standalone post or as a thread reply to the predecessor (31.10.2026).
- Facts checked against the code:
  - Deterministic Nodes: `decision`, `fork`, `join`, `loop`, `mcp_call`, `hitl`, `start`, `end` (no LLM).
  - Subagent isolation: `DefaultNodeDispatcher.runAgent` → Child-Session; only `NodeResult` returned.
  - Structured Output: `responseSchema` + `StructuredOutputConverter`.
  - Context Caching: `CacheAffinity` (Headers), `cacheKey` field present (provider integration pending).
  - Compaction: `CompactionService` for long histories.
- Hashtags: 12 — reduce to 5–8 for LinkedIn (recommendation: #AIAgents #MCP #Quarkus #Java #WorkflowOrchestration #ContextCaching).
- Image suggestion: Architecture diagram from `docs/diagrams/` or the 3-pillar diagram in this file.
- The "75–90 % savings" is an estimate based on prompt-caching discounts (OpenAI: 50 %, Anthropic: up to 90 % on cached prefixes). Validate with real measurements before publishing.