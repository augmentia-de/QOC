# Manual Test Plan for QOC

> **Purpose:** Concrete, incremental test plan for the manual acceptance of the
> QOC engine – **UI-based**, via the HTTP API, as a
> **Docker-Dev container (without OIDC)** and as a **serverless deployment**
> (Cloud Run/Lambda). OIDC is tested separately on T2 (Auth0) and T6
> (Keycloak) – **either Docker-Dev without OIDC or an OIDC environment**,
> not combined. Each stage contains steps, expectations, evidence and an
> explicit list of edge cases that are **not sensibly testable manually**.
>
> **Status:** 2026-11-09
>
> **References:** [`spec/qualitaet/01-tests.md`](../spec/qualitaet/01-tests.md)
> (automated test types), [`Core.md`](./Core.md),
> [`Async-use.md`](./Async-use.md), [`security.md`](../security.md).

## 1. Scope

This plan covers the **manually verifiable** functions:

- **UI-based:** Login, sessions, chat, tool cards, permissions, questions,
  file tree, workspace, agent modal, tasks, LLM trace, logout
- Health/UI/Auth (None, Basic, OIDC)
- Sessions, prompts, streaming
- File and shell tools
- Permissions and questions
- Workspace, agents, skills
- Subagents and background tasks
- Compaction, revert, fork, move
- Persistence/restart
- Provider errors
- Security and boundaries
- **Docker-Dev without OIDC** (Compose, UI track)
- **OIDC separately:** T2 (Auth0) and T6 (Keycloak) – not combined with
  Docker-Dev
- **Serverless** (Cloud Run/Lambda): MCP endpoint, ephemeral sessions,
  agent fetch, cold start, limits
- optional integrations (MCP, A2A, email, webhooks)

**Not** part of this plan: load, fuzz, race and crash tests (see §6).

## 2. Test environments

| Environment | Purpose | Access |
|---|---|---|
| **T1 – Local (Dev)** | fast functional check | `mvn quarkus:dev`, `http://localhost:8080` |
| **T2 – GCP-VM** | production-like acceptance, Auth0-OIDC, nginx | `https://test.schippel.info` |
| **T3 – Local with stub provider** | deterministic tool/perm tests | `ENGINE_PROVIDER_SCRIPTED=true` (if configured) |
| **T4 – Docker-Dev (UI)** | hot-reload container, UI track | `docker compose --profile dev up qoc-dev`, `http://localhost:8070` |
| **T5 – Docker-Dev-Native** | native image in container, **without OIDC** | `--profile dev-native`, `http://localhost:8081` |
| **T6 – Local OIDC stack** | Keycloak + OIDC + UI (Compose, **no Dev container**) | `./deploy/start-oidc.sh`, `http://localhost:8071` |
| **T7 – Serverless local** | serverless role in container | `--profile minimal`, `http://localhost:8072` |
| **T8 – Serverless cloud** | Cloud Run / Lambda, OIDC, `/mcp` | `deploy/gcp-cloudrun-serverless/deploy.sh` or `deploy/aws-lambda-serverless/deploy.sh` |

### 2.1 Start T1

```bash
cd qoc
export OPENAI_API_KEY="sk-..."
mvn quarkus:dev
```

- UI: `http://localhost:8080/`
- Health: `http://localhost:8080/api/health/live`

### 2.2 Check T2

```bash
gcloud compute ssh torsten@quad-e2micro --zone=us-central1-a \
  --project=gen-lang-client-0960862878 --command "systemctl is-active qoc"
```

- UI: `https://test.schippel.info/`
- Logs: `sudo journalctl -u qoc -f`
- Env: `sudo cat /etc/qoc/env`

### 2.3 T4 – Docker-Dev (UI track)

```bash
cd qoc/deploy
docker compose --profile dev up -d qoc-dev
docker compose logs -f qoc-dev
```

- UI: `http://localhost:8070/`
- No login (default `qoc-dev`), `ENGINE_PERMISSION_DEFAULT=allow-all`
  → no permission dialogs (M6 tested on T1/T2/T6)
- Hot-reload: source mount `..:/app`

### 2.4 T5 – Docker-Dev-Native (without OIDC)

```bash
cd qoc/deploy
docker compose --profile dev-native up -d qoc-dev-native
```

- UI: `http://localhost:8081/`
- No login (local admin mode)
- Only serves to check the native image in the container; OIDC runs
  exclusively via T2/T6.

### 2.5 T6 – Local OIDC stack (Keycloak)

```bash
cd qoc
cp deploy/.env.example deploy/.env   # once, set OPENAI_API_KEY
SKIP_BUILD=true ./deploy/start-oidc.sh   # or without SKIP_BUILD for a fresh build
```

- Keycloak: `http://localhost:8180` (Admin `admin`/`admin`)
- QOC-UI: `http://localhost:8071`
- Test users:
  - `alice` / `alice-password` (role `qoc-user`)
  - `bob` / `bob-password` (role `qoc-admin`)
- Console: `docker logs -f qoc-local`, `docker logs -f keycloak-local`

### 2.6 T7 – Serverless local

```bash
cd qoc/deploy
docker compose --profile e2e up -d qoc-registry   # agent source, port 8074
docker compose --profile minimal up -d qoc-minimal
```

- Serverless role: `http://localhost:8072`
- `ENGINE_MODE=serverless`, `ENGINE_AGENT_SOURCE_URL=http://host.docker.internal:8074`
- OIDC active (`*oidc-env`), `/mcp` protected
- Logs: `docker logs -f qoc-minimal`

### 2.7 T8 – Serverless cloud

**GCP Cloud Run:**

```bash
cd qoc
export PROJECT_ID=gen-lang-client-0960862878
export ENGINE_AGENT_SOURCE_URL=https://test.schippel.info
export ENGINE_OIDC_ISSUER=https://dev-1kxf4iy7txwbmdks.us.auth0.com/
export ENGINE_OIDC_AUDIENCE=qoc-engine
./deploy/gcp-cloudrun-serverless/deploy.sh
```

- Service URL and `/mcp` endpoint are printed at the end.
- `ENGINE_MODE=serverless`, `ENGINE_WORKSPACE=/tmp/workspace`,
  `ENGINE_MAX_CONCURRENT_PROMPTS=1`, `ENGINE_TURN_MAX_WALL_MINUTES=10`.

**AWS Lambda:**

```bash
cd qoc
export ENGINE_AGENT_SOURCE_URL=https://test.schippel.info
export ENGINE_OIDC_ISSUER=https://dev-1kxf4iy7txwbmdks.us.auth0.com/
./deploy/aws-lambda-serverless/deploy.sh
```

- Function URL + `/mcp`; timeout 300 s, 512 MB, `/tmp` as workspace.

> **Important:** Serverless has **no** classic UI and **no** SSE. The MCP
> endpoint is tested with a bearer token. The UI runs on T2/T6 and only calls
> the serverless endpoint as a tool/agent.

## 3. Prerequisites and test data

| Prerequisite | T1 | T2 (GCP-VM) | T4/T5 (Docker-Dev) | T6 (Keycloak) | T7/T8 (Serverless) |
|---|---|---|---|---|---|
| LLM key set | `OPENAI_API_KEY` | `/etc/qoc/env` | `deploy/.env` | `deploy/.env` | Deploy env |
| Workspace | project directory | `/data/workspace` | `../data/workspace` | `../data/workspace` | `/tmp/workspace` (ephemeral) |
| Auth | local without login or Basic | Auth0-OIDC | none | Keycloak-OIDC | OIDC (Bearer) |
| Test user | – | `admin@qoc.test` / `login` | – | `alice`/`alice-password`, `bob`/`bob-password` | Auth0 test user or Keycloak |
| Scratch directory | `qoc-test-<date>` | `/data/workspace/qoc-test-<date>` | `data/workspace/qoc-test-<date>` | `data/workspace/qoc-test-<date>` | `/tmp/workspace/qoc-test-<date>` |

**Before each run:**

1. Create a new session (do not reuse an old session).
2. For T2/T6 (OIDC): `sessionStorage.clear()` in DevTools and log in again.
3. Create the scratch directory and delete it at the end.
4. Open logs/SSE before the test.
5. For serverless: check ephemeral session after the call (cleanup).

## 4. Conventions

| Field | Meaning |
|---|---|
| **ID** | `M-<stage>-<nr>`, e.g. `M4-03` |
| **Prio** | P0 = blocking, P1 = important, P2 = optional |
| **Evidence** | screenshot, `curl` output, log line or SSE recording |
| **Passed** | expectation exactly met; deviation = error with reproduction |

**Test tracks:**

| Track | Environment | Focus | Tools |
|---|---|---|---|
| **UI** | T1/T2/T4/T5/T6 | browser operation (T4/T5 without login, T2/T6 with OIDC) | Chrome/Firefox, DevTools |
| **API** | T1/T2/T6/T7 | endpoints, status codes, SSE | `curl`, `jq` |
| **Deploy** | T4/T5/T7/T8 | containers, OIDC (T2/T6), serverless | Docker, `gcloud`, `aws` |

**Test artifacts:** `evidence/M<stage>/<id>-<short>.txt|png|log`

## 5. Test stages (incremental)

```
M0 Smoke
 └─ M1 Auth
     └─ M2 Sessions
         └─ M3 Prompt & Streaming
             ├─ M4 File tools
             ├─ M5 Shell & processes
             ├─ M6 Permissions & questions
             └─ M7 Workspace & files
                 └─ M8 Agents & skills
                     └─ M9 Subagents & tasks
                         └─ M10 Context (compaction/revert/fork/move)
                             ├─ M11 Integrations (optional)
                             ├─ M12 Provider errors
                             └─ M13 Persistence & restart
                                 └─ M14 Security
                                     └─ M15 Limits & performance
                                         ├─ M16 UI track (browser, T4 without login)
                                         ├─ M17 OIDC (T2 Auth0 / T6 Keycloak)
                                         ├─ M18 Docker-Dev-Native (without OIDC)
                                         └─ M19 Serverless (Cloud Run/Lambda)
```

**Rule:** A stage counts as passed only when all P0 cases are green.
P1/P2 may remain documented as open.

---

## M0 – Smoke (P0)

| ID | Step | Expectation | Evidence |
|---|---|---|---|
| M0-01 | open UI | page loads, login button visible | screenshot |
| M0-02 | `/api/health/live` with token | `200 {"status":"UP"}` | curl |
| M0-03 | `/api/health/ready` with token | `200` if ready, otherwise `503` | curl |
| M0-04 | `/api/health/live` without token (OIDC) | `401` + `WWW-Authenticate: Bearer` | curl |
| M0-05 | SSE `/api/event` with token | connection stays open, events arrive | `curl -N` |
| M0-06 | `/config.js` | contains `issuer`, `clientId`, `audience` | curl |
| M0-07 | browser console | no unhandled errors | screenshot |

```bash
curl -s -H "Authorization: Bearer $TOKEN" https://test.schippel.info/api/health/live
```

---

## M1 – Authentication (P0)

| ID | Step | Expectation |
|---|---|---|
| M1-01 | click login → Auth0 | redirect to Auth0, back to app after login |
| M1-02 | after login | "Logout" button, API calls 200 |
| M1-03 | logout | token deleted, API calls 401 |
| M1-04 | wrong password | Auth0 error, no token |
| M1-05 | T1 Basic mode: wrong password | 401, `WWW-Authenticate: Basic` |
| M1-06 | T1 Basic mode: correct password | 200 |
| M1-07 | `/api/metrics` without `admin` role | 403 |
| M1-08 | `/api/audit` without `admin` role | 403 |
| M1-09 | foreign user's session ID | 403/404, no data leak |
| M1-10 | token expired (manipulate via DevTools) | 401, re-login required |

**Note:** OIDC token is stored in `sessionStorage` (`qoc.accessToken`).

---

## M2 – Sessions (P0)

| ID | Step | Expectation |
|---|---|---|
| M2-01 | create new session | `id` in response, session in list |
| M2-02 | load session list | new session appears |
| M2-03 | open session | empty message list, status "ready" |
| M2-04 | switch agent (idle) | 200, agent name updated |
| M2-05 | switch agent (running) | 409 `SessionRunningError` |
| M2-06 | invalid agent | 400/403 |
| M2-07 | unknown session | 404 |
| M2-08 | delete session | 200, removed from list |
| M2-09 | double delete | 404/200 idempotent (document) |

```bash
curl -s -X POST https://test.schippel.info/api/session \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}'
```

---

## M3 – Prompt and streaming (P0)

| ID | Step | Expectation |
|---|---|---|
| M3-01 | simple prompt ("Reply with OK") | answer appears, status back to "ready" |
| M3-02 | observe SSE | `message`, `text`, `finish` events in order |
| M3-03 | trigger tool call ("Read README.md") | tool card + result, then answer |
| M3-04 | reload page | history remains |
| M3-05 | send second prompt | new answer, history grows |
| M3-06 | send empty prompt | 400 |
| M3-07 | prompt during running turn | 409 `SessionBusyError` |
| M3-08 | interrupt during turn | status "interrupted", no hanging display |
| M3-09 | very long prompt | limit is respected (error or truncation) |
| M3-10 | SSE reconnect after network drop | events from `after` sequence, no duplicates |

---

## M4 – File tools (P0)

Preparation: scratch file `qoc-test-<date>/a.txt` with known content.

| ID | Step | Expectation |
|---|---|---|
| M4-01 | `read` on `a.txt` | content exactly |
| M4-02 | `write` new file | file exists with content |
| M4-03 | `edit` (replace) | only target location changed |
| M4-04 | `patch` (multi-hunk) | all hunks correct |
| M4-05 | `glob`/`grep` | correct matches, no foreign files |
| M4-06 | `read` non-existent file | typed error to the model |
| M4-07 | path `../../etc/passwd` | denied/no content outside workspace |
| M4-08 | symlink to outside | denied (no escape) |
| M4-09 | binary file | clean error or Base64, no crash |
| M4-10 | large file (> 1 MB) | limit applies, clear message |

---

## M5 – Shell and processes (P0)

| ID | Step | Expectation |
|---|---|---|
| M5-01 | `echo hello` | output `hello`, exit 0 |
| M5-02 | start `sleep 60` | process runs, UI stays usable |
| M5-03 | process list `/processes` | process visible |
| M5-04 | kill process | process terminated, status updated |
| M5-05 | command with exit ≠ 0 | exit code and stderr visible |
| M5-06 | timeout command | timeout error, no hanging step |
| M5-07 | write attempt outside workspace (sandbox `workspace-write`) | denied |
| M5-08 | interrupt during shell | process terminated/aborted |

---

## M6 – Permissions and questions (P0)

> **Environment:** T1/T2/T6 with `ENGINE_PERMISSION_DEFAULT=least-privilege`.
> T4 (`qoc-dev`) runs with `allow-all` and triggers no permission dialogs.

| ID | Step | Expectation |
|---|---|---|
| M6-01 | action with Ask rule (e.g. `shell`) | permission dialog appears |
| M6-02 | answer `ONCE` | action runs once |
| M6-03 | answer `ALWAYS` | action runs, approval stored |
| M6-04 | reuse stored approval | no repeated prompt |
| M6-05 | `REJECT` without feedback | action rejected, model informed |
| M6-06 | `REJECT` with feedback | feedback reaches the model |
| M6-07 | Deny rule + stored Allow | **Deny wins** |
| M6-08 | `question` tool | question panel, answer `selected`/`text` |
| M6-09 | question timeout/headless | configured behavior (deny/retry) |
| M6-10 | child-session permission (`includeChildren=true`) | child request visible |
| M6-11 | reply to unknown request ID | 404 |

```bash
curl -s -X POST .../api/session/$SID/permission/$REQ/reply \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"reply":"ONCE"}'
```

---

## M7 – Workspace and files (P1)

| ID | Step | Expectation |
|---|---|---|
| M7-01 | load file tree | correct structure |
| M7-02 | view file | content, UTF-8 correct |
| M7-03 | download file | bytes identical |
| M7-04 | download directory | ZIP/archive complete |
| M7-05 | switch workspace (new) | directory created, UI reloads |
| M7-06 | switch workspace (invalid) | 400, current workspace remains |
| M7-07 | create/list/remove worktree (if enabled) | consistent list, no leftovers |

---

## M8 – Agents and skills (P1)

| ID | Step | Expectation |
|---|---|---|
| M8-01 | agent list | default + configured agents |
| M8-02 | create agent (UI/API) | appears in list |
| M8-03 | modify agent | change effective |
| M8-04 | delete agent | removed from list |
| M8-05 | switch session to agent | agent is used |
| M8-06 | skill list | visible/allowed skills |
| M8-07 | load skill via prompt | content in context, no errors |
| M8-08 | invalid skill frontmatter | skill ignored, no crash |
| M8-09 | config reload | changes visible without restart |

---

## M9 – Subagents and background tasks (P1)

| ID | Step | Expectation |
|---|---|---|
| M9-01 | synchronous subagent | result as tool output |
| M9-02 | `subagent(background=true)` | immediate `taskId`, parent continues |
| M9-03 | task list (`/tasks`) | task with status `RUNNING` |
| M9-04 | `task wait` (short) | result or timeout |
| M9-05 | `task wait` (long) | result after completion |
| M9-06 | `task cancel` | status `CANCELLED` |
| M9-07 | 5th concurrent subagent | 5th waits/rejected (limit 4) |
| M9-08 | depth > 2 | rejected |
| M9-09 | subagent error | task `FAILED`, error visible |
| M9-10 | delete parent session | child tasks terminated |
| M9-11 | SSE `SubagentStarted/Finished` | events appear in order |

---

## M10 – Context: compaction, revert, fork, move (P1)

| ID | Step | Expectation |
|---|---|---|
| M10-01 | long conversation | auto-compaction, summary |
| M10-02 | manual compaction | summary replaces old context |
| M10-03 | revert | messages from point removed, files reset (if configured) |
| M10-04 | fork | new session with copied history |
| M10-05 | move | session switches workspace/location |
| M10-06 | title generation | title is set |
| M10-07 | todo list | entries appear/update |
| M10-08 | compaction error | step fails typed, no data loss |

---

## M11 – Integrations (P2, only if enabled)

| ID | Step | Expectation |
|---|---|---|
| M11-01 | `/api/mcp` | configured servers |
| M11-02 | MCP status | `connected`/`error` correct |
| M11-03 | MCP authorize/callback | token taken over |
| M11-04 | `/api/a2a` | remote agents listed |
| M11-05 | A2A call | result or typed error |
| M11-06 | read/send email (if enabled) | correct limits/recipients |
| M11-07 | webhook trigger (if enabled) | session woken, answer visible |

---

## M12 – Provider errors and resilience (P1)

| ID | Step | Expectation |
|---|---|---|
| M12-01 | invalid API key | 502/503, clear error message |
| M12-02 | simulate rate limit | retry with backoff, then error |
| M12-03 | provider timeout | typed error, session remains usable |
| M12-04 | disconnect network (DevTools offline) | retry/error, no hanging UI |
| M12-05 | switch model | next prompt uses new model |
| M12-06 | preflight error (DB/key) | start/health reports problem |

---

## M13 – Persistence and restart (P0 for T2)

| ID | Step | Expectation |
|---|---|---|
| M13-01 | reload page | session + history remain |
| M13-02 | `systemctl restart qoc` | service `active` again |
| M13-03 | open session after restart | history complete |
| M13-04 | prompt during restart | claim recovery: session continues or fails typed |
| M13-05 | stored approvals after restart | still effective |
| M13-06 | orphaned background tasks after restart | marked ended/lost, no zombie |
| M13-07 | audit/journal | events consistent, no gaps |

```bash
sudo systemctl restart qoc && sleep 6 && systemctl is-active qoc
```

---

## M14 – Security (P0)

| ID | Step | Expectation |
|---|---|---|
| M14-01 | API without token | 401 |
| M14-02 | guess foreign session ID | 403/404 |
| M14-03 | path traversal (`../`, URL-encoded) | denied |
| M14-04 | symlink escape | denied |
| M14-05 | shell injection attempt (e.g. `; rm -rf`) | treated as argument/literal only, no unintended effect |
| M14-06 | XSS in prompt/filename (`<script>`) | displayed escaped |
| M14-07 | secrets in UI/log | no API keys/tokens in plaintext |
| M14-08 | credential list | secrets masked |
| M14-09 | admin endpoints as non-admin | 403 |
| M14-10 | error messages | no stack traces/internals to client |

---

## M15 – Limits and performance (P2, manual)

| ID | Step | Expectation |
|---|---|---|
| M15-01 | create 20 sessions | list responds smoothly |
| M15-02 | display file > 1 MB | limit/error, no UI freeze |
| M15-03 | tool output > limit | truncation with marker/file |
| M15-04 | SSE open 10 minutes | connection stable, heartbeat/reconnect |
| M15-05 | 4 parallel subagents | no exceedance |
| M15-06 | very long prompt (100k chars) | 400/limit, no OOM |
| M15-07 | many messages in one session | scroll/history stays usable |

---

## M16 – UI track (browser, no curl) (P0)

Prerequisite: T1, T2 or T6 (T4 `qoc-dev` runs with
`ENGINE_PERMISSION_DEFAULT=allow-all` and is unsuitable for M16-05…07). All
steps **only** via the UI.

| ID | Step | Expectation |
|---|---|---|
| M16-01 | click login (OIDC) | Auth0/Keycloak login, return to app |
| M16-02 | session dropdown → new session | new session appears, chat empty |
| M16-03 | enter + send prompt (Ctrl+Enter) | answer streams, status "ready" |
| M16-04 | trigger tool call ("Read README.md") | tool card with result |
| M16-05 | permission modal: `ONCE` | action runs once |
| M16-06 | permission modal: `ALWAYS` | action runs, no repeated prompt |
| M16-07 | permission modal: `REJECT` + feedback | action rejected, feedback visible |
| M16-08 | trigger `question` tool | question panel, send answer |
| M16-09 | open file tree, click file | content in viewer |
| M16-10 | download file | download identical |
| M16-11 | workspace menu → switch | UI reloads, workspace correct |
| M16-12 | agent modal: create/modify/delete | list updated |
| M16-13 | task list/status/cancel (if UI available) | states correct |
| M16-14 | open LLM trace panel | request/response visible |
| M16-15 | disconnect SSE (DevTools offline) | banner "SSE disconnected", reconnect |
| M16-16 | logout | token gone, API calls 401, login button |
| M16-17 | mobile layout (narrow window) | resizer/sidebar work |
| M16-18 | browser console | no unhandled errors |

## M17 – OIDC (T2 Auth0 / T6 Keycloak) (P0)

**Separate from Docker-Dev.** Either T2 (Auth0 on the GCP VM) or T6 (local
Keycloak stack) – not the dev container.

| ID | Step | Expectation |
|---|---|---|
| M17-01 | T6: run `start-oidc.sh` | Keycloak + QOC start, health 200 |
| M17-02 | T6: Keycloak admin `http://localhost:8180/admin` | login `admin`/`admin` |
| M17-03 | open QOC UI (T2 `https://test.schippel.info`, T6 `http://localhost:8071`) | loads, login button |
| M17-04 | login `alice`/`alice-password` (T6) or Auth0 test user (T2) | return, API calls 200 |
| M17-05 | check token in DevTools | `iss` correct, `aud` contains `qoc-engine` |
| M17-06 | `/api/health` with token | 200 |
| M17-07 | `/api/metrics` as non-admin | 403 |
| M17-08 | login `bob`/`bob-password` (T6) or admin (T2) | `/api/metrics` 200 |
| M17-09 | SSE in DevTools | `fetch` request with Bearer, status 200 |
| M17-10 | wrong audience (manipulate token) | 401 |
| M17-11 | T6: CLI token (`qoc-cli`, password grant) | API call 200 |
| M17-12 | T6: container restart (`docker restart qoc-local`) | session/history remain (SQLite volume) |
| M17-13 | static UI without token | `/` loads, `/api/*` 401 |
| M17-14 | logout | token deleted, re-login required |

**Check split-horizon (T6):**

- Browser issuer: `http://localhost:8180/realms/qoc`
- Container JWKS: `http://host.docker.internal:8180/realms/qoc/...`
- `QUARKUS_OIDC_TOKEN_ISSUER` must be exactly the browser issuer.

**T2-specific:** Auth0 issuer with trailing slash, `aud=qoc-engine`,
JWKS reachable from the VM, nginx passes `Authorization` through.

## M18 – Docker-Dev-Native without OIDC (T5) (P1)

| ID | Step | Expectation |
|---|---|---|
| M18-01 | `docker compose --profile dev-native up -d` | native build starts |
| M18-02 | UI `http://localhost:8081` | loads without login |
| M18-03 | API call without token | 200 (local admin mode) |
| M18-04 | native start time in log | documented (compare to JVM) |
| M18-05 | reflection/config | no `ClassNotFound`/config errors |
| M18-06 | container restart | session/history remain |
| M18-07 | same UI cases as M16 (spot check) | identical behavior |
| M18-08 | no OIDC in container | no login/redirect, no auth requirement |

## M19 – Serverless (Cloud Run / Lambda) (P0/P1)

Prerequisite: T7 (local) or T8 (cloud). Serverless has **no UI**; the
`/mcp` endpoint is tested with a bearer token.

| ID | Step | Expectation |
|---|---|---|
| M19-01 | run deployment script | service/function active, URL printed |
| M19-02 | `/mcp` without token | 401 |
| M19-03 | `/mcp` with valid OIDC token (Auth0/Keycloak) | MCP handshake successful |
| M19-04 | `qoc_run_agent` with `build`/`plan` | result + `_meta` |
| M19-05 | `qoc_run_agent` with unknown agent | fetch from `ENGINE_AGENT_SOURCE_URL` |
| M19-06 | `qoc_run_agent_with_config` (inline markdown) | transient agent, removed afterwards |
| M19-07 | check session after the call | ephemeral session deleted |
| M19-08 | result metadata | tokens, finish, warnings, `agentCreated`/`agentDeleted` |
| M19-09 | cold start (Cloud Run `min-instances=0`) | first call within timeout |
| M19-10 | parallel calls (multiple instances/concurrency) | correct results, no shared sessions |
| M19-11 | turn limit (10 min) / Lambda timeout (300 s) | typed error, no hang |
| M19-12 | large prompt / 512 MB memory | limit/error, no OOM |
| M19-13 | workspace `/tmp/workspace` | scratch only, cleaned after call |
| M19-14 | agent source unreachable | `agent not available`, clear error |
| M19-15 | forbidden tools (leaf profile) | rejected/not available at all |
| M19-16 | skills/subagents in serverless mode | not available (MVP) |
| M19-17 | Lambda variant: function URL + `/mcp` | same cases as Cloud Run |
| M19-18 | logs (Cloud Run/Lambda) | request ID, no secrets |

## 6. Edge cases: manual vs. automated

These cases are **not sensibly testable manually** – too fine-grained, too
rare, too timing-dependent or too destructive. They belong in automated
tests.

| Edge case | Why not manual | Automation |
|---|---|---|
| Two concurrent prompts on the same session | race, ms window | Unit/Integration: `ConcurrentPromptTest` |
| Claim collision / double execution | ms window, not reproducible | `CoordinatorTest` (exists) |
| Doorbell coalescing | timing | `CoordinatorTest` |
| Admission/delivery ordering | transaction race | integration with DB |
| Crash mid-step (SIGKILL) | destroys environment | recovery test with process kill |
| Power loss / JVM crash | not controllable | chaos/recovery test |
| OOM / memory pressure | unstable | load test with heap limit |
| Virtual thread pinning | only visible under load | `-Djdk.tracePinnedThreads=full` |
| Clock skew / token expiry exactly at boundary | time manipulation | `FixedClock` + contract test |
| Network partition / half SSE frames | non-deterministic | proxy/fault injection |
| Provider stream breaks mid-token | not reproducible | stub provider with error injection |
| Duplicate tool execution on retry | race | effect ledger test |
| DB deadlock / serialization errors | DB-specific | integration with concurrency |
| Multi-node claims/leases | multiple instances | cluster test |
| Symlink TOCTOU | race | property/fuzz test |
| Path traversal variants (encoding) | too many variants | fuzz test |
| SSRF via web tools | environment | security test with mock server |
| Command injection payloads | too many variants | fuzz test |
| XSS payload corpus | too many variants | UI test (Playwright) |
| JSON schema fuzzing for tool inputs | combinatorics | property-based test |
| Migration/schema evolution | DB states | migration test |
| 1,000 waiting sessions | load | load test |
| Eviction under memory pressure | timing | load test with budget |
| Token refresh race | ms window | integration test |

**Rule:** If an edge case is only reachable manually with tricks (debugger,
artificial delay, kill -9), it does **not** belong in this plan but in the
automated suite.

## 7. Test matrix / checklist

| Stage | P0 | P1 | P2 | Passed on | Tester |
|---|---|---|---|---|---|
| M0 Smoke | 7 | – | – | | |
| M1 Auth | 10 | – | – | | |
| M2 Sessions | 9 | – | – | | |
| M3 Prompt/SSE | 10 | – | – | | |
| M4 File tools | 10 | – | – | | |
| M5 Shell | 8 | – | – | | |
| M6 Permissions | 11 | – | – | | |
| M7 Workspace | – | 7 | – | | |
| M8 Agents/skills | – | 9 | – | | |
| M9 Subagents | – | 11 | – | | |
| M10 Context | – | 8 | – | | |
| M11 Integrations | – | – | 7 | | |
| M12 Provider | – | 6 | – | | |
| M13 Persistence | 7 | – | – | | |
| M14 Security | 10 | – | – | | |
| M15 Limits | – | – | 7 | | |
| M16 UI track | 18 | – | – | | |
| M17 OIDC | 14 | – | – | | |
| M18 Docker-Dev-Native | – | 8 | – | | |
| M19 Serverless | 12 | 6 | – | | |

## 8. Environment commands

### 8.1 Local

```bash
cd qoc
export OPENAI_API_KEY="sk-..."
mvn quarkus:dev
curl -s http://localhost:8080/api/health/live
```

### 8.2 GCP-VM

```bash
# Status
gcloud compute ssh torsten@quad-e2micro --zone=us-central1-a \
  --project=gen-lang-client-0960862878 --command "systemctl is-active qoc"

# Logs
gcloud compute ssh torsten@quad-e2micro --zone=us-central1-a \
  --project=gen-lang-client-0960862878 --command "sudo journalctl -u qoc -n 100 --no-pager"

# Restart
gcloud compute ssh torsten@quad-e2micro --zone=us-central1-a \
  --project=gen-lang-client-0960862878 --command "sudo systemctl restart qoc"

# Authenticated call
TOKEN="<access token from sessionStorage>"
curl -s -H "Authorization: Bearer $TOKEN" https://test.schippel.info/api/health/live
```

### 8.3 Get token from the browser (test only)

DevTools console:

```js
sessionStorage.getItem('qoc.accessToken')
```

## 9. Known pitfalls

| Problem | Cause | Remedy |
|---|---|---|
| All API calls 401 after login | token missing/expired; PKCE race | `sessionStorage.clear()`, log in again |
| "Login state invalid" | API calls during login cleared PKCE keys | UI fix; click slowly in the test |
| SSE hangs | `EventSource` cannot send a Bearer | UI uses fetch-SSE; curl with header |
| Login redirect wrong | wrong `redirect_uri`/Auth0 config | check `/config.js` |
| 403 despite admin | role missing in token | check Auth0 roles |
| Session "busy" | previous turn still running | wait or `/interrupt` |
| Permissions hang | `ENGINE_PERMISSION_ASK_MODE=deny` | check configuration |
| Workspace switch fails | path missing/permissions | `create=true` or check path |
| T6: login redirect to container host | split-horizon wrong | `QUARKUS_OIDC_TOKEN_ISSUER` = browser issuer (`localhost:8180`) |
| T6: `401` despite login | audience/issuer differ | check token payload with `jq` |
| Serverless: first call slow | cold start (`min-instances=0`) | keep warm or raise timeout |
| Serverless: file missing after call | ephemeral workspace (`/tmp`) | no persistent workspace in serverless mode |
| Serverless: agent not found | `ENGINE_AGENT_SOURCE_URL` wrong/token missing | check source and bearer token |

## 10. Exit criteria

A run counts as passed when:

1. All **P0** cases in T1 **and** T2 are green.
2. The **UI track (M16)** is green on T4 (without login) and T2/T6 (with OIDC).
3. The **OIDC track (M17)** is green on T2 or T6.
4. The **serverless track (M19)** is green on T8 (Cloud Run or Lambda).
5. All **P1** cases are green or documented as open.
6. No unhandled errors in browser console/server log.
7. Test artifacts stored under `evidence/`.
8. The test matrix (§7) is fully filled in.

**Sign-off:**

| Role | Name | Date | Result |
|---|---|---|---|
| Tester | | | |
| Reviewer | | | |

## 11. References

| Topic | Document |
|---|---|
| Automated test types | [`spec/qualitaet/01-tests.md`](../spec/qualitaet/01-tests.md) |
| Architecture/core | [`Core.md`](./Core.md) |
| Async/heartbeat | [`Async-use.md`](./Async-use.md) |
| Storage | [`Storage-alternative.md`](./Storage-alternative.md) |
| In-memory runtime | [`InMemory.md`](./InMemory.md) |
| Security | [`security.md`](../security.md) |
| Subagents | [`guides/Subagent-Delegation.md`](../guides/Subagent-Delegation.md) |
| A2A | [`guides/a2a.md`](../guides/a2a.md) |
| Agent registry | [`guides/Agent-Registry.md`](../guides/Agent-Registry.md) |
| Skills | [`skill-loading.md`](../skill-loading.md) |
| Operation/HTTP/SSE | [`spec/betrieb/01-http-streaming.md`](../spec/betrieb/01-http-streaming.md) |

---

## 12. Updates (2026-11)

This section extends the manual test plan with the features added since
2026-11. Sections 1–11 remain valid unchanged.

### 12.1 Workflow UI (GraphEditor, agents, runs, testbench, MCP servers)

- **GraphEditor** (`/workflows/:id`):
  - Create nodes (agent, decision, fork, join, loop, HITL, MCP call,
    subworkflow, start, end), connect edges (click-to-connect).
  - Property panel per type: agent ref (dropdown), decision branches (JSON),
    loop (max iterations, exit condition), fork/join, HITL (assignee),
    MCP call (server, tool, args), subworkflow (ref).
  - Save/load, validation (GraphCompiler), auto-layout (elkjs).
- **Agents** (`/agents`):
  - CRUD, model, temperature, max tokens, top P, system prompt, tools,
    guardrails, hooks, JSON output, active.
  - Response schema (JSON schema textarea).
  - Import via `/api/schema/from-class`, `/from-openapi`, `/validate`.
- **Runs** (`/runs`): start workflow (input), run status, result,
  live SSE streaming (text deltas, tool calls/results).
- **Testbench** (`/testbench`): run workflow with test input, check result.
- **McpServers** (`/mcp-servers`): add/remove servers, show tools, reload.

### 12.2 Structured output (responseSchema)

- Create agent with `responseSchema` (agents page).
- Run workflow with agent node + `responseSchema` (runs/testbench).
- Result: structured JSON (not just text).
- Invalid schema → error message (server: `JsonSchema.parse` → `Result.Err`).
- Test converters: `JavaRecordToJsonSchema`, `OpenApiToJsonSchema`
  (`POST /api/schema/from-class`, `/from-openapi`, `/validate`).

### 12.3 RBAC / ownership (proxy mode)

- Create workflow/agent as user A → as user B → 403 on
  get/save/delete/run.
- Admin sees all workflows/agents.
- Shared workflows (ownerId=null) readable by everyone.
- System token: `curl -H "X-System-Token: <token>"` → `Principal.system()`.
- OIDC: login via Keycloak/Auth0, roles from JWT.

### 12.4 In-memory mode

- Set `ENGINE_STORAGE=memory`.
- No DB, no restart recovery.
- Sessions/workflows ephemeral (gone after restart).
- Memory budget (300 MiB) on snapshots.
- No-op adapters for snapshots, approvals, instructions, credentials,
  tool result cache.

### 12.5 Native image / Docker

- `docker compose --profile native up -d` (port 8077).
- Health check: `GET /api/health` → `{"status":"ok"}`.
- Workflow UI via nginx (port 5174, `/api` → engine).
- SQLite alternative: `build-image-sqlite.sh`.

### 12.6 Context caching (provider level)

- Measure prompt-caching discount (OpenAI/Anthropic dashboard).
- Stable system prompt prefixes (no timestamps).
- Subagent isolation (no context pollution).

### 12.7 Workflow resume

- `POST /api/workflows/runs/{runId}/resume`.
- Checkpoint replay after crash.
- `max-resume-attempts` (default 10).

### 12.8 Revert snapshots

- `ENGINE_REVERT_SNAPSHOT=true` (dev) / `false` (prod).
- `POST /api/session/{id}/revert` (API).
- Performance: snapshots write all workspace files into the DB.

### 12.9 Updates to existing sections

- **Section 1 (scope):** add workflow UI, RBAC, structured output,
  in-memory, native image.
- **Section 2 (stages):** new stage "workflow UI" and "RBAC/proxy".
- **Section 3 (environments):** add `qoc-memory` (8082), `qoc-native` (8077),
  `qoc-native-ui` (5174).
- **Section 4 (health/UI/auth):** add proxy mode with trusted proxies.
- **Section 5 (sessions/prompts/streaming):** live SSE, tool calls/results.
- **Section 6 (file/shell tools):** sandbox modes (workspace-write/full-access),
  seccomp.
- **Section 7 (permissions/questions):** least-privilege as default, ask mode.
- **Section 8 (workspace/agents/skills):** agent store (global + user-scoped),
  response schema.
- **Section 9 (subagents/background):** recursion depth (max 2), semaphore,
  background tasks.
- **Section 10 (compaction/revert/fork/move):** compaction triggers,
  revert snapshot performance.
- **Section 11 (edge cases):** in-memory (no recovery), native image
  (no dev services), OIDC (fail-closed).

### 12.10 Test matrix (automated vs. manual)

| Area | Automated (unit/integration) | Manual (UI/API) |
|---|---|---|
| Agent loop | `QocEngineTest`, `ContextBuilderTest` | chat, tool calls |
| Workflow loop | `WorkflowRunJournalTest`, `WorkflowStoreContractTest` | GraphEditor, runs |
| Security | `AuthTest`, `AgentVisibilityTest`, `WorkflowOwnershipTest` | proxy/OIDC login |
| Storage | `InMemoryStoresTest`, `SqlAdaptersTest` | — |
| Tools | `ToolRuntimeTest`, `ReadTool`, `GrepTool` | files/shell/web |
| Schema/converter | `JavaRecordToJsonSchemaTest`, `SchemaParseCompatTest` | agents UI, `/api/schema/*` |
