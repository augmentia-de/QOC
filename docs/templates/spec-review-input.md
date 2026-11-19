# Spec-Review Input — Briefing for the Pre-Flight Agent

> Fill in BEFORE starting `aisdlc-spec-review`. Missing fields are queried by
> the agent — but only then, not for everything. "n/a" or "unknown"
> is a valid answer and is documented as an open point (O-<n>).

## 1. Assignment (Raw Input)

```
<Free-text assignment, unchanged>
```

## 2. Environment Manifest

| Factor | Value |
|---|---|
| JDK | (e.g. 21.0.12 Temurin) |
| Maven | (e.g. 3.9.16) |
| Git | (e.g. 2.43) |
| Docker | (e.g. 29.1.3, socket mounted) / not available |
| Browser | available (Chromium/Chrome/Firefox) / **none** |
| Node/Playwright | available / **not available** |
| Quarkus Version | (e.g. 3.40.1.1) |
| Quarkus Skills in Repo | (count + path, or "none") |
| Existing Code | Greenfield / existing (path) |
| CI | (GitHub Actions / GitLab / none) |

## 3. Repository Inventory

- Type: Greenfield / Brownfield
- Framework: (e.g. Quarkus 3.40, Spring Boot, ...)
- Available Extensions/Skills: (list or "none")
- Existing Tests: (yes/no, framework)
- Existing Configuration: (application.properties, opencode.jsonc, ...)

## 4. External Services

```yaml
external_services:
  - name: Auth0
    provided: true          # Tenant/User/role are provided
    tenant_domain: ""       # or "n/a"
    role_claim: ""          # e.g. "https://example.com/roles" or "roles"
    rp_initiated_logout: true  # Default for tenants from 14.11.2023
    test_user: "user@qoc.test"
    test_role: "user"
```

## 5. Deployment Context

```yaml
deployment:
  target: ""              # e.g. "Docker container", "Kubernetes", "bare metal"
  replicas: ""            # e.g. "≥ 2 (HA)" or "1 (single)"
  tls: ""                 # e.g. "external load balancer" or "no TLS (dev only)"
  region: ""              # e.g. "eu-central-1" or "local"
  domain: ""              # e.g. "app.example.com" or "n/a"
```

## 6. Contract Consumers

- Who uses the service? (e.g. "Browser (no third-party API consumer)", "Mobile App", "other services")
- Accessibility requirement? (e.g. "no — no public consumer scope")

## 7. Data & Persistence

- User DB? (yes/no — if no: session cookie only)
- Multi-tenancy? (yes/no)
- Session strategy for multiple instances: (Cookie / DB / Redis / "architect decides")

## 8. Previous Review State (re-runs only)

```yaml
previous_decisions:
  - "R1: BLOCKED (reason)"
  - "R2: READY_WITH_ASSUMPTIONS (decision)"
  - "R3: Question Q3-01 → Answer"
```

## 9. Known Constraints

- (e.g. "No browser in build environment", "No Node/Playwright", "No Docker")
- (e.g. "Auth0 tenant is provided but not yet configured")

---

## Filling Instructions

- Fields with "n/a" or "unknown" are valid — the agent documents them as
  open points (O-<n>) with owner "Human" or "Architect".
- If a field is missing, the agent asks — but only for that field, not for all.
- The environment facts (Section 2) can be determined with `java -version`, `mvn -v`,
  `git --version`, `docker --version`, `command -v chromium`, `command -v node`.