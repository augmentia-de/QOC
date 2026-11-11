# Deployment – Übersicht aller Zielumgebungen

> **Status:** 2026-11-04
> **Zweck:** Zentrale Übersicht aller unterstützten und geplanten
> Betriebsumgebungen von QOC. Ausführbare Skripte und Terraform liegen in
> `deploy/`; diese Dokumente beschreiben die Architektur, Konfiguration
> und Betriebsabläufe.

## Zielumgebungen

| # | Umgebung | Status | Dokument |
|---|---|---|---|
| 1 | Lokal (JVM, Docker) | implemented | `01-local-docker.md` |
| 2 | Lokal (Native, Docker) | implemented | `01-local-docker.md` |
| 3 | GCP VM (Native, systemd) | implemented | `02-gcp-native.md` |
| 4 | GCP VM (Auth0 OIDC) | partial | `03-gcp-auth0.md` |
| 5 | AWS ECS Fargate (minimal) | implemented (Test) | `04-aws-ecs-minimal.md` |
| 6 | AWS Lambda (Serverless) | spec-only | `05-aws-lambda-serverless.md` |
| 7 | Serverless Free Tier | spec-only | `07-serverless-free-tier.md` |
| 7 | Server/Serverless-Modi | spec-only | `guides/deployment-modes.md` |

## Gemeinsame Prinzipien

- Engine lauscht nur auf Loopback (`127.0.0.1`); öffentlicher Zugang nur über nginx/TLS.
- Secrets nie im Image; über `.env` (mode 0600) oder Secrets Manager.
- `HOME` persistent setzen, damit Plan-Dateien und Agenten überleben.
- Shell-Sandbox (bwrap) nur wo User Namespaces verfügbar sind.
- Read-only Root-Dateisystem in Containern; nur `/data` und `/tmp` schreibbar.

## Ausführbare Artefakte

Alle Skripte, Dockerfiles, Compose-Dateien und Terraform liegen in `deploy/`.
Diese Dokumente referenzieren sie, duplizieren sie aber nicht.

## Sicherheit

Siehe `06-security-hardening.md` und `docs/security.md`.
