# Invariant Register

> **Status:** 2026-10-25  
> **Purpose:** Central entry point into the invariants of the specification.  
> **Important:** This document is a **register**, not a second source.
> The named spec file is always authoritative.

## Prefixes

| Prefix | Area | Source | Count / scope |
|---|---|---|---|
| `I-*` | Session engine | [`session-engine/07-invariants.md`](./session-engine/07-invariants.md) | 111 invariants, checked in [`09-invariants-audit.md`](./session-engine/09-invariants-audit.md) |
| `E-*` | Event log and projections | [`session-engine/02-events.md`](./session-engine/02-events.md) | E-01 … E-10 |
| `F-*` | Error channels | [`session-engine/06-errors.md`](./session-engine/06-errors.md) | F-01 … F-10 |
| `W-*` | Tools | [`tools/05-contract-invariants.md`](./tools/05-contract-invariants.md) | Tool execution chain, bounding, error channels |
| `S-*` | Subagent | [`tools/07-subagent.md`](./tools/07-subagent.md) | S-01 … S-06 |
| `R-*` | Remote Shell | [`tools/08-remote-shell.md`](./tools/08-remote-shell.md) | R-01 … R-23 |
| `P-*` | Permissions | [`permissions/04-invariants.md`](./permissions/04-invariants.md) | Rule chain, wildcards, effects |
| `V-*` | Provider | [`provider/05-invariants.md`](./provider/05-invariants.md) | Model call, stream, classification, budget, cache |

## Verification methods

| Method | Meaning |
|---|---|
| Unit test | Isolated value or decision logic |
| Integration test | Interaction with ports, adapters or database |
| Property test | Invariant over many inputs |
| ArchUnit | Layer and dependency rule |
| Security test | Secrets, paths, rights, escapes |
| Spec review | Conscious rule not automatically verifiable |

## Mapping to quality gates

| Gate | Document | Invariants |
|---|---|---|
| Tests | [`qualitaet/01-tests.md`](./qualitaet/01-tests.md) | I, W, P, V, R |
| Architecture | [`qualitaet/02-architecture-pipeline.md`](./qualitaet/02-architecture-pipeline.md) | Layers, dependencies, cycles |
| Implementability | [`qualitaet/03-implementability.md`](./qualitaet/03-implementability.md) | Spec gaps and corrections |
| Remote Shell | [`tools/08-remote-shell.md`](./tools/08-remote-shell.md) | R-01 … R-23 |

## Maintenance

New invariants:

1. Assign an ID in the matching prefix range.
2. Formulate the rule in the source spec as IF/THEN or verifiable statement.
3. Add verification method and test location.
4. Update this register only when a new prefix or a new source arises.
5. Run `node docs/tools/check-docs.mjs`.
