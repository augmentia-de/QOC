# Plan: Detect, repair, and retry tool-call leaks

**Status:** Implemented — 2026-11-17
**Reference:** `docs/spec/provider/03-classification.md`, `docs/spec/session-engine/`,
`docs/plans/strict-structured-outputs.md`
**Trigger:** DB analysis of `session_message` since `2026-11-16 00:00 UTC`

---

## 1. Finding

In the Postgres history (`qoc.session_message`) there is at least one
assistant turn in which the provider returned native tool-call markup as
**normal text** instead of delivering `tool_calls`:

- Session: `ses_825b704fbbf9859f87ae3e6499c06f6b`
- Seq: `40`
- Length: 3342 characters
- Finish: `NORMAL`
- Pattern: `<｜｜DSML｜｜ calls>` / `<｜｜DSML｜｜ invoke name="shell">` /
  `<｜｜DSML｜｜ parameter ...>` with two lost `shell` calls.

This is not valid final text. The turn was persisted as an assistant message
anyway, because `Step` only checked for the leak when tools were disabled
(`Choice.None`). In addition, the detector did not normalize double fullwidth
bars (`｜｜`) down to a single `|`, so even the existing check would have
missed the real payload.

Further edge conditions from the analysis:

- 358 assistant messages, of which 10 used no tools; only one DSML leak.
- 213 of 608 tool calls were `permission_denied` (not a leak, but
  retry-/UX-relevant).
- One orphaned session (`ses_544a19e56ba19e2a4f544a8744dbe7db`, prompt `ls`)
  ended without an assistant step.

## 2. Target state

Six measures, in this order:

1. **Harden the detector** — reliably detect real leak markers, regardless of
   tool approval.
2. **Classification** — a dedicated, typed error `TOOL_CALL_LEAK` that is
   retryable.
3. **Engine retry** — retry decision via the error matrix (not via message
   text), repair hint in the system prompt, bounded leak retries.
4. **Provider signal** — `finish_reason=tool_calls` without parsed tool calls
   detected early as an error.
5. **Salvage** — convert well-formed DSML blocks into real tool calls instead
   of losing the turn.
6. **Observability/audit** — logging, live status, and an SQL audit job.

## 3. Point 1 — Harden the detector

**File:** `engine/.../core/eval/ToolCallLeak.java`

- Normalization:
  - `U+FF5C` (`｜`), `U+2502` (`│`), `U+01C0` (`ǀ`) → `|`
  - `U+2581` (`▁`) → `_`
  - Runs of `|` reduced to a single `|`
  - case-insensitive comparison
- Markers: `<|dsml|`, `</|dsml|`, `<|tool`, `[tool_calls]`
- Signature check: markers alone are not enough (documentation quote); in
  addition `invoke`, `parameter`, `calls`, `call`, or `[tool_calls]` must
  appear.
- `find(text)` returns `Finding(startIndex, endIndex, marker)` for cleaning
  and salvage; `endIndex` covers the closing DSML block, otherwise end of
  text.

**File:** `engine/.../runtime/step/Step.java`

- Leak check no longer coupled to `toolsDisabled(prepared)`.
- Only checked when no structured tool calls are present.
- Leak → `TOOL_CALL_LEAK` instead of `UNKNOWN`.
- Attempt salvage (Point 5); if not possible → `StepResult.Failed`.
- On successful salvage: replace text events with cleaned text and execute
  the recovered tool calls like normal calls.

**Tests:** `ToolCallLeakTest` (double bar, box drawing, `▁`, split, false
positives), `StepTest` (tools active + leak, salvage).

## 4. Point 2 — Classification

**Files:**

- `engine/.../core/contract/provider/InvalidOutputClassification.java`
  → add `TOOL_CALL_LEAK`.
- `engine/.../core/eval/ProviderClassifier.java`
  → `TOOL_CALL_LEAK` is retryable.
- `docs/spec/provider/03-classification.md` is the authoritative source; the
  extension is noted there as an open spec addendum (no silent overwrite).

**Tests:** `ProviderClassifierTest` (retryable / non-retryable matrix).

## 5. Point 3 — Engine retry and repair hint

**File:** `engine/.../runtime/QocEngine.java`

- `StepResult.Failed` is decided via `ProviderClassifier.isRetryable(kind)`;
  the previous text classification (`IllegalStateException` →
  `CORRECTABLE`) no longer applies to step failures.
- `TOOL_CALL_LEAK` gets its own budget:
  `LEAK_RETRY_MAX = 1` (one repair attempt after the first occurrence).
- On leak:
  - `LEAK_REPAIR_HINT` is added to the next `Prepared` via `withSystem(...)`
    (once).
  - Delay = 0 (no backoff, no rate limit).
  - Live status: `[Retry] Tool-call markup detected ...`.
  - Log with session, step, and action.
- The forced final step (tools disabled) is also checked against
  `StepResult.Failed` and, on a leak, retried exactly once with the hint;
  afterward a typed engine error.

**Tests:** `QocEngine` retry is unit-testable via the matrix; an integration
test with a fake provider verifies: first step leak → second step sees hint →
turn becomes `COMPLETED`, no leak text persisted.

## 6. Point 4 — Provider signal

**File:** `engine/.../runtime/provider/client/OpenAiEventTranslator.java`

- Keep the raw `finish_reason`.
- In `finish()`: `finish_reason=tool_calls` **without** parsed tool calls →
  `ProviderFailed(InvalidOutput(TOOL_CALL_LEAK))` instead of `Finished`.
- Text leaks with `finish_reason=stop` remain the responsibility of `Step`
  (salvage possible there).

**Tests:** `OpenAiEventTranslatorTest` (mismatch → `ProviderFailed`, normal
tool call remains `Finished`).

## 7. Point 5 — Salvage parser

**File:** `engine/.../core/eval/ToolCallLeakParser.java` (new)

- Parses DSML `invoke`/`parameter` blocks (fullwidth bars, `▁`, whitespace).
- `SalvagedCall(String name, JsonValue input)`.
- `string="true"` → String; otherwise parse JSON, fallback to String.
- Resolve XML entities (`&quot;`, `&amp;`, `&lt;`, `&gt;`).
- Limits: max. 8 calls, max. 100k characters per parameter.
- `Step` accepts salvage only if all names are present in `prepared.tools()`
  and tools are not disabled. Otherwise retry path.
- Recovered calls are published live as `ToolInputStarted` / `ToolInputEnded` /
  `ToolCallRequested`.

**Tests:** `ToolCallLeakParserTest` with the real 2-call payload, malformed
input, string/JSON parameters.

## 8. Point 6 — Observability and audit

- `Step`: `WARNING` log on detection/salvage.
- `QocEngine`: `WARNING` log on retry/final error, live status line.
- `scripts/find-tool-call-leaks.sql`: finds assistant messages without a
  tool card whose text contains DSML/tool markup (fullwidth bars tolerated),
  including session, seq, time, length, preview.
- Optional operational note: daily run against `qoc.session_message`.

## 9. Acceptance criteria

- `mvn test` in `qoc/` green.
- The real 3342-character payload is detected and split into two `shell`
  calls.
- A leak with enabled tools produces `TOOL_CALL_LEAK` (no final text).
- A leak leads to exactly one repair retry; afterward a typed error.
- `finish_reason=tool_calls` without calls produces `TOOL_CALL_LEAK`.
- Audit SQL finds the historical leak.
- No change to `docs/` spec without a separate assignment (exception: note
  about the open spec addendum).

## 10. Verification

- `mvn -pl engine test` → **BUILD SUCCESS** (1140+ tests, including new
  leak, parser, step, translator, and engine-retry tests).
- `mvn -pl engine clean test` → **BUILD SUCCESS**.
- `mvn -DskipTests compile` (entire reactor) → **BUILD SUCCESS**.
- `mvn test` → **BUILD SUCCESS** (all modules: engine, workflow-runner, quarkus, cli).
- Audit SQL `scripts/find-tool-call-leaks.sql` finds the production leak
  `ses_825b704fbbf9859f87ae3e6499c06f6b`, Seq `40`, 3342 characters.
- New tests:
  - `ToolCallLeakTest` — double bar, box drawing, `▁`, production payload,
    false positive.
  - `ToolCallLeakParserTest` — 2-call production payload, JSON/string
    parameters, entities, malformed, cap.
  - `StepTest` — leak with active tools, unknown tool, successful salvage.
  - `OpenAiEventTranslatorTest` — `tool_calls` without structured call.
  - `QocEngineLeakRetryTest` — repair hint, no leak text in result,
    retry budget.

## 11. Risks and non-goals

- **False-positive risk:** Text that merely quotes DSML could trigger a retry.
  Mitigation: signature check (marker + `invoke`/`parameter`/`calls`).
- **Salvage risk:** A wrongly parsed call could trigger an unwanted tool
  invocation. Mitigation: name check against `prepared.tools()`, permission
  gate remains active, max. 8 calls.
- **Non-goal:** Supporting arbitrary provider markup (e.g. Llama-specific
  forms) for salvage. Only the observed DSML and tool markers.
- **Non-goal:** Strict Structured Outputs (Phase 3); see
  `docs/plans/strict-structured-outputs.md`.
