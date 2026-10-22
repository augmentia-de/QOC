# 05 – Provider interface

> The neutral interface between the Session Engine and a
> language model. Goal: **one** provider adapter, arbitrarily many models; and
> **no** provider details in the engine.
>
> Principle from the package boundary: *authentication, permissions, plugins and
> orchestration belong to the engine; the provider knows exactly one
> model call.*

## 1. Role distribution

```
        ┌──────────────────────── Engine ────────────────────────┐
        │  Tools · Permissions · History · Compaction · Events   │
        └───────────────────────────┬──────────────────────────────┘
                                    │  neutral provider protocol
        ┌───────────────────────────┴──────────────────────────────┐
        │  Provider adapter: translation ⇄ request/response        │
        └──────────────────────────────────────────────────────────┘
```

The adapter may **only** translate. It may not check permissions, produce
events, or reclassify errors.

## 2. Request

```
type ModelRequest =
  { model: ModelRef
  , system: List<Text>
  , messages: List<ModelMessage>
  , tools: List<ToolDefinition>
  , toolChoice: Option<Choice>        # free | none | one | required
  , generation: GenerationOptions     # provider-neutral options
  , providerOptions: Map              # provider-specific, passed through unchanged
  , headers: Map<Text,Text>           # e.g. session/project identifiers, affinity
  , cacheKey: Option<Text> }

type ModelMessage =
  | Nutzer       { inhalt: List<Content> }
  | Assistent    { inhalt: List<Content> }
  | Werkzeugergebnis { aufrufID: CallID, name: ToolName, ergebnis: OutcomePayload }

type Content =
  | Text  { text: Text }
  | Medium { medienart: Bild | Audio | Video | PDF, daten: Bytes, name: Option<Text> }
  | Werkzeugaufruf { aufrufID: CallID, name: ToolName, eingabe: Json }
```

**Rules:**

* **P-01** The message list contains **only** delivered history – no
  waiting inputs.
* **P-02** The system part consists of at most two blocks: the
  provider-/agent-specific basis block and the instruction block.
  An agent with its own system block **replaces** the basis block entirely.
  Precedence (Agent → Provider → Basis) and texts:
  [`13-internal-prompts.md`](./13-internal-prompts.md) §3.1.
* **P-03** The output budget is computed by the engine and passed through; the
  provider may not raise it on its own.

### Output budget

```
budget = min( kataloglimit, 256_000 )
budget = min( budget, max( 1_024, kontextfenster - gemessene_eingangsgröße - puffer ) )
```

* For compaction assignments an additional smaller upper bound (32,000) applies.
* For title and helper tasks **no** budget is set – their reasoning effort is
  hard to plan.
* The input-size estimate has a safety margin; catalogs occasionally
  overestimate the window.

## 3. Event stream

A call returns a **stream** of events. The model can mix
text, reasoning and tool calls; a tool call can still be incomplete
(streaming of input).

```
type ProviderEvent =
  | TextBegonnen        { blockID }
  | TextFortschritt     { blockID, text }
  | TextBeendet         { blockID, text }

  | ReasoningBegonnen   { blockID }
  | ReasoningFortschritt{ blockID, text }
  | ReasoningBeendet    { blockID, text }

  | WerkzeugEingabeBegonnen { aufrufID, name }
  | WerkzeugEingabeFortschritt { aufrufID, text }
  | WerkzeugEingabeBeendet   { aufrufID, text }

  | WerkzeugAufgerufen  { aufrufID, name, eingabe: Json }
  | WerkzeugErgebnis    { aufrufID, ergebnis: OutcomePayload }   # executed by the provider
  | WerkzeugFehler      { aufrufID, meldung }

  | Beendet             { grund: FinishReason, verbrauch: TokenCount, roh: Json }

  | AnbieterFehler      { meldung, einstufung: FailureClass }
```

| Field | Meaning |
|---|---|
| `blockID` | identifies a text/reasoning block within the response |
| `aufrufID` | identifies a tool call **within** a step |
| `FinishReason` | `normal` · `laengenlimit` · `inhaltsfilter` · `unbekannt` · `abbruch` |
| `OutcomePayload` | either content or an error text – never both |
| `verbrauch` | token count, if the provider reports it |

**Rules:**

* **V-01** The stream is **read completely**, not only up to the
  completion event. Reason: the response should remain reusable for the next request.
* **V-02** The order of events is significant. A block is begun,
  continued and ended – exactly once.
* **V-03** A tool call is reported **before** its result. A result
  without a preceding call is a protocol error.
* **V-04** Exactly **one** result per call ID. A second result is a
  protocol error (exception: a late error result after an abort cleanup is
  harmless and is ignored).
* **V-05** Provider-side errors are reported as an **event**
  (`AnbieterFehler`), not by aborting the stream – so the engine can
  decide whether an attempt is retried.
* **V-06** An abort of the stream is an abort, not an error.

## 4. Tool definitions

```
type ToolDefinition =
  { name: Text
  , description: Text
  , inputSchema: JsonSchema
  , outputSchema: Option<JsonSchema> }
```

**Rules:**

* **W-01** The name is unique for the provider and contains no
  provider-specific characters.
* **W-02** The input schema is **standard JSON Schema** (no
  provider-specific extensions). Unsupported schema constructs are simplified
  without changing validation.
* **W-03** An optional output schema describes the **machine** value
  (for code mode). The model still sees the content.
* **W-04** Names with a namespace become `<namespace>_<tool>`.

## 5. Error classification

The provider reports errors in its **own**, provider-neutral taxonomy.
The engine classifies them; the adapter only translates.

```
type FailureClass =
  | Ratebegrenzt        { wartezeit: Option<Duration> }
  | AnbieterIntern      { wartezeit: Option<Duration> }
  | Transport           { zustellung: Ungesendet | Angenommen | Abgelehnt
                        , operation: Senden | Lesen
                        , code: Text }
  | UngueltigeAusgabe   { einstufung: UnvollstaendigerStrom | Unbekannt }
  | UngueltigeAnfrage   { einstufung: Kontextueberlauf | Sonstiges }
  | Authentifizierung   { hinweis: Option<Text> }
  | Kontingent          { hinweis: Option<Text> }
  | InhaltsrichtlinieVerletzt
  | NichtUnterstuetzt   { operation: Text }
  | KeineRoute
  | UnbekannterAnbieter
  | Zeitueberschreitung
```

### Retryability

```
wiederholbar(fehler) =
    if the provider supplies an explicit value: use it
    je nach klasse:
        Ratebegrenzt, AnbieterIntern           → ja
        Transport:
            zustellung = Abgelehnt             → nein  (the request was rejected)
            zustellung = Angenommen             → ja, only if operation = Lesen
            zustellung = Ungesendet            → ja
        UngueltigeAusgabe: UnvollstaendigerStrom → ja
        UnbekannterAnbieter                    → ja
        else                                   → nein
```

> **Principle:** *Unknown* errors are retried. The classification records only
> **proven** evidence; transient errors arrive exactly in forms no classifier
> foresees.

### Context overflow

```
kontextueberlauf(fehler) =
    fehler.typ = UngueltigeAnfrage und fehler.einstufung = Kontextueberlauf
```

**Important:** Text patterns like "too many tokens" are **not** a context
overflow – they mean an output limit, which compaction does not fix. Likewise
"rate limit", "service unavailable" and "throttling error".

### Retry effort limits

```
wait_time = min( provider_desired_wait_time, at most 15 minutes )
wartezeit = max( wartezeit, eigene_backoff_stufe )
own_backoff_step = exponential from 2 s, capped at 10 s per gap, with jitter
maximale_wiederholungen = 10
maximale_wiederholungen_bei_zeitueberschreitung = 3
```

> **Deviation from the present specification** (`specs/v2/session.md`):
> four retries are named there, the code allows **ten**. This specification
> follows the **code** (ten) and marks the deviation so it can be decided.
> See [`08-porting.md`](./08-porting.md).

## 6. Transport requirements

Requirements on the **binding**, not on a specific protocol:

| # | Requirement |
|---|---|
| T-01 | **Streaming.** The call returns a stream; the engine reads it completely. |
| T-02 | **Abort.** An abort by the engine ends the request; the provider should close the connection. |
| T-03 | **Error classification at the edge.** The adapter recognizes error classes (429, 5xx, network errors, …) and reports them classified. |
| T-04 | **Wait-time directives** are respected and never exceeded. |
| T-05 | **Reusability.** Responses may be cached for the next request (provider side). |
| T-06 | **Optional state channel.** Some providers offer a long-lived channel per session; the engine may use it but must work without it. |
| T-07 | **Headers** (session, project, affinity, cache key) are passed through unchanged. |
| T-08 | **Keys/tokens** come from the engine's credential layer, never from the provider adapter. |

## 7. What the provider does **not** know

| The provider does not know | Why |
|---|---|
| Sessions, messages, history | it is stateless; the engine holds state |
| Permissions and approvals | authorization belongs to the engine |
| Plugins and extensions | the engine materializes tools |
| Tool execution | the provider **requests**, the engine **executes** |
| Compaction | the engine decides what fits in context |
| Cost and token accumulation | the engine projects them |

Exception: **provider-executed tools** (server-side tool execution
of the provider). The provider reports these as a result; the engine does not
execute them. The event then carries a "executed by the provider" marker,
because otherwise the tool-call state machine would see a call without a
`ToolCalled` event.

## 8. Provider catalog

```
type ProviderEntry =
  { id: ProviderID
  , models: List<ModelInfo> }

type ModelInfo =
  { id: ModelID
  , providerID: ProviderID
  , varianten: List<VariantID>
  , grenzen: { kontext: Int, ausgabe: Int }
  , kosten: KostenModell
  , faehigkeiten: { eingabe: List<Medienart>, ausgabe: List<Medienart> }
  , verdichtung: Option<VerdichtungsModell>   # provider-side
  , transport: Option<Zustandskanal> }
```

**Rules:**

* **K-01** The catalog is the source for limits and capabilities.
  **Capabilities are checked before sending**: a medium the model cannot
  read is replaced by an explanatory text before the request – not rejected
  by the provider first.
* **K-02** Images are shrunk so that a storage budget is met; older images
  are removed first so that the unchanged prefix (and thus the cache) stays
  stable.
* **K-03** Variants affect reasoning effort and cost. The default is the
  one chosen by the user; there is **no** automatic rotation on errors.
* **K-04** A model not in the catalog is unavailable – it is not "guessed".