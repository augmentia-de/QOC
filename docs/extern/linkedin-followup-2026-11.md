# LinkedIn Post: QOC – from Demo to Production-Ready Agent Service (Follow-up)

> Location: `docs/linkedin-followup-2026-11.md` (outside the Git repo, not committed)
> Language: English (like the predecessor post)
> Project name in the text: **QOC**

---

## Post (copy-paste for LinkedIn)

Why I ported OpenCode to Java – Part 2: What makes QOC production-ready.

The first part covered the foundation: a spec-driven Java port of OpenCode, with a session engine, tools, MCP, A2A, asynchronous approvals, and an idempotent runtime. The question that followed: "And how do I actually use this now, without the agent being allowed to do everything?"

That is exactly where QOC comes in. Three topics made the biggest difference.

🔹 1. Security: from allow-all to least-privilege

At the beginning, like with many agent demos, there was a generous allow-all: the agent could read, write, and execute. That is enough for a prototype – not for production. QOC rebuilt this in stages:

- The permission default is now least-privilege (deny-by-default). Only what is explicitly granted is allowed.
- The shell sandbox (bubblewrap) limits write access to the workspace; in containers the boundary comes from the mounts.
- Ask mode controls what happens when permission is missing: deny, allow, or interactive.
- Short-lived, origin-bearing tool tokens (60 s) for downstream calls.

💡 The value: the agent gets exactly the rights it needs – and no more. Least privilege as the default, not as a retrofit.

🔹 2. OIDC instead of "local admin"

For the first developer mode, a login-free loopback with a local admin was enough. Not for production. QOC now speaks OIDC (Keycloak in the dev profile, Auth0 in prod) and resolves roles from the JWT (realm_access/roles, permissions, groups). Plus trusted-proxy auth (nginx/LB) and HTTP Basic as a fallback. The role-claim path is configurable per profile; missing claims fail closed to 403.

💡 The value: the same code runs locally without login and in production with real identity and roles – no "if (dev)" code path.

🔹 3. The same engine as deterministic workflow orchestration

The LLM loop (prompt → context → tool calls → result) is proven and verifiable. QOC now also uses it for workflow orchestration: workflows are topologically sorted, nodes (agents, decisions, forks/joins, loops, MCP calls) run in the same engine. State lives in the journal (event sourcing, in the DB or in SQLite/in-memory) – every step is traceable, every run repeatable and resumable.

💡 The value: no second, untested orchestration stack. Deterministic because the loop is the same one already running in production. Auditable because everything lands in the journal.

🔹 4. In-memory variant for helper tasks

Not every task needs a database. QOC has an in-memory mode: sessions, conversations, journal, claims, and tool outputs in the heap, no persistence, no restart recovery. Ideal for embedded helpers, ephemeral sessions, tests, and single-process deployments. The footprint stays small (default budget 300 MB, LRU-capped tool outputs).

💡 The value: the same engine, two operating modes – durable for production, ephemeral for helpers and tests. No second framework.

AI delivers the intelligence. QOC delivers production readiness – and now also the security, identity, and orchestration that turn a demo into a product.

🚀 What is still missing on your path from agent demo to agent service?

📌 Repository: https://github.com/augmentia-de/QOC

#AIAgents #Harness #MCP #LangChain4j #Quarkus #Java #OIDC #RBAC #SpecDrivenDevelopment #WorkflowOrchestration

---

## Publication notes

- The post is the follow-up to "Why I ported OpenCode to Java" (31.10.2026).
- Facts verified against the code:
  - `engine.permission.default=least-privilege` (default), `allow-all` as alternative (`application.properties`).
  - Shell sandbox: `engine.shell.sandbox.mode=workspace-write`, bwrap; disabled in Docker (unprivileged) (`06-security-hardening.md`).
  - Ask mode: `deny | allow | interactive` (`engine.permission.ask-mode`).
  - Tool token: `engine.security.tool-token.ttl-seconds=60` (default).
  - OIDC: `quarkus.oidc.enabled`, Keycloak (`%dev`) / Auth0 (`%prod`), roles from `realm_access.roles`/`permissions`/`groups` (`OidcPrincipalProducer`, `SecurityConfig`).
  - Trusted proxy: `TrustedProxyFilter`, `X-Forwarded-*`.
  - Workflow orchestration: `DefaultNodeDispatcher`, `TopologicalSort`, `WorkflowExecutor`, journal events (`WorkflowRunStarted`, `WorkflowNodeCompleted`), DB/SQLite/in-memory (`Workflow.md`, `WorkflowStore`).
  - In-memory: `engine.storage=memory`, `RuntimeState`, no-op adapters for optional stores, `engine.memory.max-bytes=314572800` (300 MiB), LRU-capped tool outputs (max 256).
  - Lightweight: keep the e2-micro mention from the predecessor post.
- Adjust the hashtags at the end (LinkedIn allows max. ~5–10 hashtags for best reach; currently 11).
- Image suggestion: screenshot of the workflow UI (GraphEditor) or the security diagram from `docs/diagrams/`.
