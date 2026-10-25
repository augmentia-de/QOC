# Spec Conventions

> **Status:** 2026-10-25  
> **Applies to:** all documents under `docs/spec/`.  
> **Goal:** keep new specs consistent, testable and indexable.

## 1. File names

- Numbering: `NN-topic.md`, continuing within an area.
- Do not renumber after deletions; deleted numbers remain documented or
  are explained in the README.
- New tool families receive the next free number under `tools/`.
- One document covers exactly one contract or family.

## 2. Header

Every spec begins with:

```markdown
# NN – Title

> **Purpose:** one sentence.
> **Status:** YYYY-MM-DD · `spec-only` | `implemented` | `partial`
> **Reference:** if present, reference code or implementation package
```

The status is a documentation statement. Build and tests remain authoritative.

## 3. Section order

For contract specs, this order applies:

1. Purpose
2. Scope / out of scope
3. Tool or component contract
4. Input / output / types
5. Resolution and configuration
6. Permissions and policy
7. Algorithm
8. Error handling
9. Security
10. Bounding and audit
11. Limits and concurrency
12. Invariants
13. Adapter and runtime
14. Best practices
15. References

Deviations are allowed when the subject requires them; the order should
make comparison between specs easier.

## 4. Invariants

- Every invariant has a stable ID:
  - `I-*` Session engine
  - `W-*` Tools
  - `S-*` Subagent
  - `P-*` Permissions
  - `V-*` Provider
  - `R-*` Remote Shell
  - `F-*` Error channels
  - `E-*` Events
- Format: `| ID | Rule | Verification method |`
- Every invariant is formulated as IF/THEN or as a verifiable statement.
- The central register is [`INVARIANTS.md`](./INVARIANTS.md); the source
  remains the respective spec.

## 5. References

- Relative Markdown links for documents with file and optional anchor,
  e.g. `file.md#anchor` in the link target.
- Code references: `path/to/file:line`.
- External references only with full URL.
- No links to non-existent files; the docs check verifies them.
- Anchors are lowercased, without punctuation, spaces as `-`.

## 6. Tables and diagrams

- Tables have a header row and a separator row.
- Every row has the same number of columns as the header.
- Mermaid diagrams are in ```mermaid blocks.
- Diagrams carry a legend when colors or shapes carry meaning.

## 7. Language and terms

- Specs under `docs/spec/` may be German or English, but consistent per
  file.
- Technical terms are explained at first use.
- "MUST", "SHALL", "MAY" are used normatively.
- Implementation details belong in the spec only when they are part of the
  contract or the invariants.

## 8. Changes

When changing any spec:

1. Check affected README indices.
2. Update [`INVARIANTS.md`](./INVARIANTS.md) when new IDs arise.
3. Update [`../STATUS.md`](../STATUS.md) when the implementation status
   changes.
4. Run `node docs/tools/check-docs.mjs`.
5. For code references, additionally run `mvn test` in `qoc/`.
