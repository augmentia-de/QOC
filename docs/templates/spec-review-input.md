# Spec-Review Input — Briefing für den Pre-Flight-Agenten

> Ausfüllen VOR dem Start von `aisdlc-spec-review`. Fehlende Felder werden vom
> Agenten nachgefragt — aber nur dann, nicht für alles. "n/a" oder "unbekannt"
> ist eine gültige Antwort und wird als offener Punkt (O-<n>) dokumentiert.

## 1. Auftrag (Raw Input)

```
<Freitext-Auftrag, unverändert>
```

## 2. Umgebungs-Manifest

| Faktor | Wert |
|---|---|
| JDK | (z.B. 21.0.12 Temurin) |
| Maven | (z.B. 3.9.16) |
| Git | (z.B. 2.43) |
| Docker | (z.B. 29.1.3, Socket gemountet) / nicht vorhanden |
| Browser | vorhanden (Chromium/Chrome/Firefox) / **keiner** |
| Node/Playwright | vorhanden / **nicht vorhanden** |
| Quarkus-Version | (z.B. 3.40.1.1) |
| Quarkus-Skills im Repo | (Anzahl + Pfad, oder "keine") |
| Bestehender Code | Greenfield / bestehend (Pfad) |
| CI | (GitHub Actions / GitLab / keine) |

## 3. Repository-Inventar

- Typ: Greenfield / Brownfield
- Framework: (z.B. Quarkus 3.40, Spring Boot, ...)
- Verfügbare Extensions/Skills: (Liste oder "keine")
- Bestehende Tests: (ja/nein, Framework)
- Bestehende Konfiguration: (application.properties, opencode.jsonc, ...)

## 4. Externe Dienste

```yaml
external_services:
  - name: Auth0
    provided: true          # Tenant/User/Rolle werden bereitgestellt
    tenant_domain: ""       # oder "n/a"
    role_claim: ""          # z.B. "https://example.com/roles" oder "roles"
    rp_initiated_logout: true  # Default für Tenants ab 14.11.2023
    test_user: "user@qoc.test"
    test_role: "user"
```

## 5. Deployment-Kontext

```yaml
deployment:
  target: ""              # z.B. "Docker-Container", "Kubernetes", "bare metal"
  replicas: ""            # z.B. "≥ 2 (HA)" oder "1 (single)"
  tls: ""                 # z.B. "externer Load-Balancer" oder "kein TLS (dev only)"
  region: ""              # z.B. "eu-central-1" oder "lokal"
  domain: ""              # z.B. "app.example.com" oder "n/a"
```

## 6. Kontrakt-Consumer

- Wer nutzt den Service? (z.B. "Browser (kein Dritt-API-Consumer)", "Mobile App", "andere Services")
- Barrierefreiheitspflicht? (z.B. "nein — kein öffentlicher Verbraucher-Scope")

## 7. Daten & Persistenz

- Nutzer-DB? (ja/nein — wenn nein: nur Session-Cookie)
- Multi-Tenancy? (ja/nein)
- Session-Strategie bei Mehr-Instanzen: (Cookie / DB / Redis / "Architekt entscheidet")

## 8. Vorheriger Review-State (nur bei Re-Runs)

```yaml
previous_decisions:
  - "R1: BLOCKED (Grund)"
  - "R2: READY_WITH_ASSUMPTIONS (Entscheidung)"
  - "R3: Frage Q3-01 → Antwort"
```

## 9. Bekannte Einschränkungen

- (z.B. "Kein Browser in Build-Umgebung", "Kein Node/Playwright", "Kein Docker")
- (z.B. "Auth0-Tenant wird bereitgestellt, ist aber noch nicht konfiguriert")

---

## Ausfüllhinweise

- Felder mit "n/a" oder "unbekannt" sind gültig — der Agent dokumentiert sie als
  offene Punkte (O-<n>) mit Owner "Mensch" oder "Architekt".
- Wenn ein Feld fehlt, fragt der Agent — aber nur für dieses Feld, nicht für alle.
- Die Umgebungs-Fakten (Abschnitt 2) können mit `java -version`, `mvn -v`,
  `git --version`, `docker --version`, `command -v chromium`, `command -v node`
  ermittelt werden.
