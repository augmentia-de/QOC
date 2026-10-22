# 04 – Catalog, budget, cache and resolution

> Everything decided **before** the model call: which model, with which
> capabilities, with which output budget, under which cache key.

## 1. The catalog

A catalog is the sum of all **available** models with their properties:

```
ModelInfo =
  { id, providerID
  , variants: List<VariantID>
  , limit: { context, output }
  , cost: CostModel
  , capabilities: { input: List<Modality>, output: List<Modality> }
  , compaction: Option<Native { encrypted }>
  , transport: Option<WebSocket> }
```

| Field | Where it comes from | Where it is needed |
|---|---|---|
| `limit.context` | provider statement | budget calculation, overflow detection |
| `limit.output` | provider statement | output budget |
| `cost` | provider statement | cost accumulation |
| `capabilities.input` | provider statement | **media filter** (§4) |
| `variants` | provider/configuration | variant selection (§3) |
| `compaction` | provider statement | provider-side compaction |
| `transport` | provider statement | long-lived channel (WebSocket) |

> **The catalog is a snapshot, not a promise.** A provider can remove a
> model. The engine must be able to handle “model no longer available"
> – and as a **configuration error**, not a retry.
>
> **Decision (2026-10-22, L-7).** The base environment uses a shipped
> snapshot `models.json` (synchronous at start, no network).
> Update only explicitly (`--refresh-catalog`), atomically via temp file.
> A step resolves its model once; catalog changes apply from the next
> step. A model disappearing → typed `model.unavailable`.

## 2. Model resolution

```
resolve(session, available models):
  if session.model set:
      search (providerID, id) in the available models
      not found  → ModelUnavailableError      (no retry, no fallback)
      found      → apply variant (§3)

  otherwise:
      resolve default model
      none found  → ModelNotSelectedError      (no retry)
```

| Case | Result | Retryable |
|---|---|---|
| Valid model chosen | resolved | – |
| Chosen model disappeared | `ModelUnavailableError` | **no** |
| No model chosen and no default | `ModelNotSelectedError` | **no** |

> **There is no silent fallback.** Whoever chose a model and it
> disappears gets an error – not secretly another model. A silent switch
> would change cost, capabilities and behavior without anyone noticing.

## 3. Variants

A variant is a **named option change** on the same model
(e.g. higher thinking effort, different temperature):

```
variant = session.model.variant        # optional
resolved  = catalogResolution + variant → concrete options
```

**Rules:**

1. A variant is **not** a different model – it only changes options.
2. The variant is resolved **with** the model; if it does not exist, that
   is a configuration error.
3. The variant is part of the **model reference** (`{id, providerID, variant}`)
   and therefore reproducible.

> **Why the variant belongs in the reference:** two runs with the same
> model but different variant are not the same run. Whoever does not
> distinguish them cannot later explain why the output differs.

## 4. Capabilities and media

Before a request goes out, every medium is checked against
`capabilities.input`:

```
mimeToModality:
  image/*           → image
  audio/*           → audio
  video/*           → video
  application/pdf   → pdf

supported(modality) = capabilities.input contains modality
```

| Case | Behavior |
|---|---|
| Modality supported | medium stays in the request |
| Modality **not** supported | medium is **removed**, with a hint in the text |
| MIME not classifiable | medium is **removed** (no modality) |
| Provider rejects image type (e.g. GIF) | medium is **removed** – otherwise every later request fails |

> **The last line is the most important:** an image the provider
> rejects would make **every** subsequent request fail, because it stays
> in the history. That is why it is checked **when inserted**, not when
> sent – and removed before it becomes durable.

> **The hint text is mandatory.** A silently removed image is not
> traceable for the user; they only see that the model “does not
> understand the image".

## 5. The output budget

```
budget(limit, kind, inputTokens) =
  model    = min(limit.output > 0 ? limit.output : 32_000, 256_000)
  desired  = kind == "compaction" ? min(model, 32_000) : model

  if inputTokens missing or limit.context <= 0:
      → desired

  room = limit.context
        - inputTokens.measured
        - ceil(inputTokens.estimated * 1.15)

  → min(desired, max(1_024, room))
```

| Constant | Value | Meaning |
|---|---|---|
| Fallback output limit | **32 000** | when the provider reports `output = 0` |
| Output ceiling | **256 000** | hard cap regardless of provider |
| Compaction output | **32 000** | summaries never need more |
| Estimation error | **15 %** | safety margin on estimated prompt size |
| Minimum output | **1 024** | below this the request is pointless |

### Why two prompt sizes

```
measured  – measured by the provider (exact)
estimated – estimated locally (inexact)
```

* If `measured` is present, the calculation is **exact**.
* If only `estimated` is present, the **estimation error** is added.

> **Why estimate at all:** before the first call there is no measurement.
> After the first call the provider delivers the real value, and the
> estimate is only needed for the **increment**.
>
> **Why 15 %:** an estimate via tokenization deviates systematically –
> especially with code and non-Latin script. The surcharge is the
> insurance against an overflow that would otherwise cost every step.

## 6. Cache affinity

Providers cache prompts **per route**. For the cache to hit, related
requests must carry the same key:

```
affinity(session) = parentID ?? fork.sessionID ?? id
```

| Case | Affinity |
|---|---|
| Normal session | own ID |
| **Child** (subagent) | that of the **parent** |
| **Fork** | that of the **source session** |

**What follows:**

1. A subagent hits the same cache as its parent – the shared context
   prefix is identical.
2. A fork hits the cache of the source session – the forked history is
   identical.
3. A **new** session has a **new** key – no cache hit.

### The headers

```
x-session-affinity: <affinity>
X-Session-Id:       <affinity>
x-opencode-session: <affinity>
```

> **Three names for the same value** – because different providers and
> gateways evaluate different headers. The value is **always** the same.
>
> **The key contains no message and no step.** It is session-stable; the
> cache is built by the provider from the **prefix** of the prompt.
> Anyone who changes the key per request never gets a hit.

### Provider-specific cache key

```
promptCacheKey = (affinity without the prefix "ses_")   — only if the form matches
```

> **The transformation is deliberately narrow:** it applies only when the
> affinity is exactly in the form of a session ID. Otherwise the value is
> passed through unchanged. A wrongly shaped key would **shatter** the
> provider cache, not improve it.

## 7. Catalog transformations

The catalog the user sees is **filtered**:

```
Raw catalog (provider)
  → authentication removes unusable entries
  → configuration removes disabled providers
  → organization rules remove forbidden providers     (P-57)
  → location plugins may add/filter
  → display
```

**Two rules for transformations:**

| # | Rule |
|---|---|
| 1 | A transformation may replace the catalog **only as a whole** – no partial merge that produces half entries. |
| 2 | Organization rules run **last** (P-59), so that no later reload brings back a filtered provider. |

> **Transformation order is part of the semantics.** A provider blocked
> by an organization rule must not appear even if a plugin adds it –
> that is why the policy stands at the end.

## 8. No route to the provider

| Class | Meaning | What the user can do |
|---|---|---|
| `NoRoute` | no transport path (configuration, network) | check configuration |
| `UnknownProvider` | provider ID unknown | install/configure provider |
| `ModelUnavailable` | model not (any longer) in the catalog | choose another model |
| `ModelNotSelected` | no model chosen | choose a model |

> **All four are configuration errors.** They are **not** retried and
> they produce **no** model request. They are therefore not “provider
> errors" in the strict sense – they occur **before** the call.

## 9. Verified semantics (reference checkout)

| Concept | Location |
|---|---|
| Model resolution, `ModelUnavailable`/`ModelNotSelected` | `packages/core/src/session/runner/model.ts:70-96` |
| Output budget formula and constants | `packages/core/src/session/model-request.ts:48-56`, `:87-97` |
| Capability filter, xAI image list | `packages/core/src/session/model-request.ts:110-140` |
| Cache affinity (child/fork) | `packages/core/src/session/affinity.ts:7-9` |
| Affinity headers, `promptCacheKey` | `packages/core/src/session/model-request.ts:278-296` |
| Catalog transformation, provider policy | `packages/core/src/config/plugin/policy.ts:28-69` |
