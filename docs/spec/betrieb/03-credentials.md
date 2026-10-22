# 03 – Credentials

> **Question:** Which secrets does the engine need, where do they live, in what
> order are they resolved – and what does the base environment actually need?
>
> **Answer:** Exactly **one** provider key. It may come from an environment
> variable or from the credential store. Everything else is optional.

Credentials are **installation-wide**, not session-wide. They are not part of
session state, appear in no session event and are not exported with a session.

## 1. Terms

| Term | Meaning | Example |
|---|---|---|
| **Integration** | an external service with ID, name and methods | `anthropic`, `github-copilot`, an MCP server |
| **Method** | **how** one authenticates | `oauth`, `key`, `command`, `external`, `env` |
| **Connection** | a concrete way to authenticate | stored credential **or** set environment variable |
| **Credential** | a **stored** secret with ID and label | `cred_…` with `{type: "key", key: "…"}` |

An integration can have **several** connections. Exactly one is active.
Evidence: `packages/schema/src/integration.ts:14-38`,
`packages/core/src/integration.ts:393-406`.

## 2. Kinds of credentials

| Kind | Fields | Where stored | Evidence |
|---|---|---|---|
| `key` | `key`, optional `metadata` | credential store | `packages/schema/src/credential.ts:42` |
| `oauth` | `methodID`, `refresh`, `access`, `expires`, optional `metadata` | credential store | `packages/schema/src/credential.ts:32` |
| `external` | `methodID`, optional `metadata` | credential store; the secret lives **outside** | `packages/schema/src/credential.ts:50` |
| `env` | name of the variable | **not** stored | `packages/core/src/integration.ts:403` |

`env` is a **connection**, not a credential. That is why there is no row in the
credential table, no label and no expiry date.

## 3. Store

The reference stores credentials in a table `credential`:

| Column | Type | Meaning | Evidence |
|---|---|---|---|
| `id` | text | `cred_…` | `packages/core/src/credential/sql.ts:6` |
| `integration_id` | text | what the secret belongs to | `:7` |
| `label` | text | display name, default `default` | `:8` |
| `value` | JSON | `key` / `oauth` / `external` | `:9` |
| `active` | boolean | exactly one active connection per integration | `:12` |
| `method_id` | text | method for OAuth | `:11` |
| `connector_id` | text | optional attachment | `:10` |
| timestamps | – | creation and change | `:13` |

The table is **global**, not location-scoped. A credential applies to all
projects of the same process.

> **Storage location is an operational decision.** The reference stores the
> value as JSON in plain text. The specification does not require that; it only
> requires that resolution is deterministic. Encryption at rest or an
> OS keychain are permissible implementations of the same rules.
>
> **Decision (2026-10-22, P-1).** The base environment uses **only** `env`
> (variant A): no credential file, no database table. Stored credentials (file
> with mode `0600` or a table) are an optional extension for
> rotation/production and are built only when needed. The resolution order above
> stays valid; without stored credentials `env` is the first (and only)
> candidate.

## 4. Resolution

The order is binding:

```
function connections(integration):
  stored = all credentials of the integration
            # sorted: active first, then newest first
  env     = all env methods whose variable is set
  return stored ++ env

function active_connection(integration):
  return connections(integration)[0]        # or: none
```

Evidence: `packages/core/src/integration.ts:393-406` (build-up),
`packages/core/src/credential.ts:93,102` (sorting),
`packages/core/src/integration.ts:718` (first connection is active).

Three hard statements follow:

1. **Stored beats environment.** If **any** stored credential exists for the
   integration, the environment variable is **not** considered – even when the
   stored credential is inactive.
2. **Active beats inactive.** Among stored credentials the active one comes
   first; among several active ones the newest.
3. **Environment is the last resort.** It is used only when **no** stored
   credential exists.

### Reading environment variables

`env` names come from two sources:

* the methods of the integration (e.g. the AWS chain),
* the provider catalog: every provider carries `env` names
  (`packages/core/src/models-dev.ts:129`), from which the binding builds `env`
  methods (`packages/core/src/plugin/models-dev.ts:49-58`).

Reading happens **on resolution**, not at startup
(`packages/core/src/integration.ts:720-722`). For the base environment the
variable is set **before** startup. A change at runtime only takes effect when
the binding is re-resolved (e.g. by reloading the location).

### OAuth renewal

An OAuth credential is renewed when it expires in **less than 5 minutes**:

```
if credential.value.expires > now + 5_minutes:
    use access
else:
    new_value = refresh(credential)
    store(new_value)
    use new_value.access
```

Evidence: `packages/core/src/integration.ts:732-737`. If renewal fails, that is an
**authentication error**, not a retry case: the user must authenticate again.

## 5. HTTP surface

### Credentials

| Method | Path | Purpose | Success | Error |
|---|---|---|---|---|
| `GET` | `/api/credential` | all credentials **including value** | `{data: Entry[]}` | – |
| `POST` | `/api/credential` | create | `{data: Entry}` | `409` |
| `PATCH` | `/api/credential/:id` | change label | `204` | – |
| `POST` | `/api/credential/:id/activate` | activate | `204` | – |
| `DELETE` | `/api/credential/:id` | remove | `204` | – |

Evidence: `packages/protocol/src/groups/credential.ts:8,19,33,48,61`.

Creating:

```json
{
  "integrationID": "anthropic",
  "label": "work",
  "value": { "type": "key", "key": "sk-ant-…" },
  "activate": true
}
```

`activate` is optional; without it the credential becomes **active**. Only
`activate: false` with an integration that already has a credential leaves the
current activation in place (`packages/core/src/credential.ts:126`). On
activation all other credentials of the same integration are deactivated **in
the same transaction** (`:128-133`).

### Bindings

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/integration` | integrations with methods and connection status |
| `GET` | `/api/integration/:id` | one integration |
| `POST` | `/api/integration/:id/connect/key` | create key |
| `POST` | `/api/integration/:id/connect/oauth` | start OAuth |
| `GET` | `/api/integration/:id/connect/oauth/:attemptID` | OAuth status |
| `POST` | `/api/integration/:id/connect/oauth/:attemptID/complete` | finish OAuth |
| `POST` | `/api/integration/:id/connect/external` | external sign-in |
| `POST` | `/api/integration/:id/connect/command` | run command |

Evidence: `packages/protocol/src/groups/integration.ts:16-213`.

> **`GET /api/credential` returns secrets in plain text.** That is deliberate
> (the surface must be able to show them for editing), but it means: only over
> loopback, only authenticated, never in a proxy log.

## 6. What the base environment needs

### 6.1 Required and optional

| Purpose | Required | Form | Example |
|---|---|---|---|
| model access | **yes, exactly one** | `key` via environment **or** store | `ANTHROPIC_API_KEY` |
| HTTP access | yes | Basic password of the server | user `opencode` |
| database | yes | PostgreSQL role with password | `oc_engine` |
| Copilot | no | `GITHUB_TOKEN` | – |
| AWS / GCP | no | provider credential chain | `AWS_*`, `GOOGLE_APPLICATION_CREDENTIALS` |
| MCP | no | OAuth or key per server | – |
| Git | no | system Git, not the engine | – |

**There are no further required credentials.** Tools like `read`, `write`,
`edit`, `glob`, `grep`, `shell` and `skill` need no keys of their own. They
work with the rights of the session.

### 6.2 Provider keys: the real names

The following names come from the shipped catalog
(`packages/core/src/models-dev/snapshot.txt`, 232 providers). The client need
not know them; the server reads them. Every listed name is treated as a key
(`packages/core/src/plugin/models-dev.ts:53-55`).

| Provider | Environment variable | Models in catalog |
|---|---|---|
| `anthropic` | `ANTHROPIC_API_KEY` | 17 |
| `openai` | `OPENAI_API_KEY` | 51 |
| `openrouter` | `OPENROUTER_API_KEY` | 389 |
| `google` | `GOOGLE_API_KEY`, `GOOGLE_GENERATIVE_AI_API_KEY`, `GEMINI_API_KEY` | 41 |
| `opencode` | `OPENCODE_API_KEY` | 129 |
| `github-copilot` | `GITHUB_TOKEN` | 36 |
| `amazon-bedrock` | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`, `AWS_BEARER_TOKEN_BEDROCK` | 199 |
| `google-vertex` | `GOOGLE_VERTEX_PROJECT`, `GOOGLE_VERTEX_LOCATION`, `GOOGLE_APPLICATION_CREDENTIALS` | 61 |

> **One key suffices.** The base environment chooses **one** provider and **one**
> model. A second provider is an extension, not a fallback.

### 6.3 The shortest path

**Variant A – environment (no store, no API call):**

```bash
export ANTHROPIC_API_KEY="…"
# start the server; the client creates the session with
# model = { providerID: "anthropic", id: "<model>" }.
```

**Variant B – store (survives a shell restart):**

```http
POST /api/credential
Content-Type: application/json

{ "integrationID": "anthropic",
  "value": { "type": "key", "key": "…" },
  "activate": true }
```

Both variants create the **same** connection. Variant B wins as soon as it
exists – even over a set environment variable.

### 6.4 No configuration obligation

The base environment needs **no** configuration file when the client sends
provider and model when creating the session:

```json
POST /api/session
{ "model": { "providerID": "anthropic", "id": "claude-…" } }
```

If the model is omitted, the installation must know a default. That is the
subject of the configuration schema (L-2) and not part of this chapter.

## 7. Start without a credential

A missing credential is **not a startup error**:

1. The server starts. `GET /api/integration` lists the integration **without**
   an active connection; `GET /api/provider` does not list it, because only
   available providers appear (`packages/server/src/handlers/provider.ts:17`).
2. The integration's `connections` list is empty; the client shows "no provider
   connected".
3. `GET /api/model` shows no models for this provider, because providers
   without a connection are not considered available
   (`packages/core/src/provider.ts:452-465`).
4. The first prompt fails with a `provider.*` class – with missing
   authentication `provider.auth`.
5. There is **no** switch to another provider.

Evidence for the error class:
[`../session-engine/06-errors.md`](../session-engine/06-errors.md).

## 8. Security

| Rule | Why |
|---|---|
| Bind to `127.0.0.1` | the credential API returns secrets |
| Basic authentication on | without a password every local process is authorized |
| TLS before the process | the engine terminates no TLS |
| no secrets in events | events are distributed to every client |
| no secrets in prompts | the prompt goes to the provider |
| no secrets in error messages | errors are shown and logged |
| rotation by activation | a new credential deactivates the old one atomically |
| removal is final | `DELETE` deletes the row, not just the flag |

**What the reference does not do:** encrypt values, put them in a keychain or
mask them when displayed. The base environment may add that as long as the
resolution order stays the same.

## 9. Rules

| # | Rule | Check |
|---|---|---|
| **CR-01** | Credentials are installation-wide. They are not session state and appear in no session event. | State |
| **CR-02** | There are three stored kinds (`key`, `oauth`, `external`) and one non-stored connection (`env`). | State |
| **CR-03** | Resolution is: active stored credential → further stored (newest first) → `env` → no connection. | State |
| **CR-04** | If **one** stored credential exists for the integration, its `env` variables are not considered. | State |
| **CR-05** | Secrets never appear in events, prompts, tool results, error messages or logs. | Effect |
| **CR-06** | `GET /api/credential` returns values in plain text and is therefore reachable only authenticated and only over loopback. | Effect |
| **CR-07** | Activating a credential deactivates all others of the same integration atomically. | State |
| **CR-08** | OAuth is renewed when it expires in less than 5 minutes; a failed renewal is an authentication error. | Flow |
| **CR-09** | A credential change takes effect before the next request; the provider cache is recomputed (`packages/core/src/provider.ts:491`). | Flow |
| **CR-10** | Without a credential the server starts; the prompt fails with a `provider.*` class (with missing authentication `provider.auth`); there is no provider fallback. | State |
| **CR-11** | The base environment has exactly **one active** provider connection. Further credentials are inactive or an extension. | State |
| **CR-12** | Rotation happens by creating and activating a new credential; the old one is removed afterwards. | Flow |

## 10. Evidence

| Rule | Test | Kind |
|---|---|---|
| CR-01 | Create credential, export session: no secret in the export. | pure |
| CR-02 | `key`, `oauth`, `external` are stored; `env` creates no row. | pure |
| CR-03 | stored + env set: stored wins; without stored: env. | pure |
| CR-04 | inactive stored credential + env: env is **not** used. | pure |
| CR-05 | provoke error: message contains no key. | Integration |
| CR-06 | request without auth to `/api/credential`: `401`. | Contract |
| CR-07 | create two credentials and activate: exactly one row `active`. | pure |
| CR-08 | clock at `expires - 4 min`: refresh is called; error → `provider.auth`. | pure |
| CR-09 | change credential, next request uses new value. | Integration |
| CR-10 | start without credential, send prompt: `provider.auth`, no other provider. | Integration |
| CR-11 | base environment with two providers: start refused or second inactive. | Integration |
| CR-12 | activate new credential, delete old: exactly one remains. | pure |

## 11. Open points

| Point | Question | Impact |
|---|---|---|
| **CR-A** | Encryption at rest or keychain? | security vs. portability |
| **CR-B** | May credentials apply per project? | tenant separation |
| **CR-C** | Should `GET /api/credential` mask values and reveal them only on edit? | prevents accidental copying |
| **CR-D** | How is the user notified of expiring OAuth credentials? | operations |
| **CR-E** | Are MCP credentials the same credentials or a separate layer? | boundary to [`../tools/04-mcp.md`](../tools/04-mcp.md) §9 |

> **Decision (2026-10-22, CR-A…CR-E).** File mode `0600` + OS keychain (no
> own crypto); credentials per project only with tenants (project_id nullable);
> `GET /api/credential` masks (hasValue+last4, value via `/value` with audit);
> expiry warning for OAuth (refresh 5 min beforehand); MCP credentials in the
> same store (`mcp:<server>`).