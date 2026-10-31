# Security Policy

## Supported versions

QOC is an early-stage project. Security fixes are applied to the current
`main` branch.

## Reporting a vulnerability

Please do **not** open a public issue for security vulnerabilities.

Send a short report to:

**torsten@schippel.info**

Include:

- a description of the issue
- affected component or endpoint
- steps to reproduce
- potential impact
- any suggested fix or mitigation

You will receive an acknowledgement as soon as possible. Please allow time for
a fix before disclosing the issue publicly.

## Scope

This policy covers the QOC runtime, HTTP API, permission model, tool execution,
and deployment assets in this repository.

## Security model

QOC uses a three-layer approach:

1. **OS hardening** — run the process with the least privileges required.
2. **Engine policy** — deny by default, explicit allow rules, bounded tool
   execution.
3. **Input validation** — shell scanner and structured tool boundaries.

See [`docs/Security.md`](./docs/Security.md) and
[`docs/guides/OS-based-security.md`](./docs/guides/OS-based-security.md) for
details.
