# Deployment – Overview of All Target Environments

> **Status:** 2026-11-04
> **Purpose:** Central overview of all supported and planned
> operating environments of QOC. Executable scripts and Terraform live in
> `deploy/`; these documents describe the architecture, configuration,
> and operating procedures.

## Target Environments

| # | Environment | Status | Document |
|---|---|---|---|
| 1 | Local (JVM, Docker) | implemented | `01-local-docker.md` |
| 2 | Local (Native, Docker) | implemented | `01-local-docker.md` |
| 3 | GCP VM (Native, systemd) | implemented | `02-gcp-native.md` |
| 4 | GCP VM (Auth0 OIDC) | partial | `03-gcp-auth0.md` |
| 5 | AWS ECS Fargate (minimal) | implemented (Test) | `04-aws-ecs-minimal.md` |
| 6 | AWS Lambda (Serverless) | spec-only | `05-aws-lambda-serverless.md` |
| 7 | Serverless Free Tier | spec-only | `07-serverless-free-tier.md` |
| 7 | Server/Serverless Modes | spec-only | `guides/deployment-modes.md` |

## Common Principles

- Engine listens only on loopback (`127.0.0.1`); public access only via nginx/TLS.
- Secrets never in the image; via `.env` (mode 0600) or Secrets Manager.
- Set `HOME` persistently so that plan files and agents survive.
- Shell sandbox (bwrap) only where user namespaces are available.
- Read-only root filesystem in containers; only `/data` and `/tmp` writable.

## Executable Artifacts

All scripts, Dockerfiles, Compose files, and Terraform live in `deploy/`.
These documents reference them but do not duplicate them.

## Security

See `06-security-hardening.md` and `docs/security.md`.
