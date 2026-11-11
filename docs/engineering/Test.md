# Manueller Testplan für QOC

> **Zweck:** Konkreter, aufeinander aufbauender Testplan für die manuelle
> Abnahme der QOC-Engine – **UI-basiert**, über die HTTP-API, als
> **Docker-Dev-Container (ohne OIDC)** und als **Serverless-Deployment**
> (Cloud Run/Lambda). OIDC wird getrennt davon auf T2 (Auth0) bzw. T6
> (Keycloak) getestet – **entweder Docker-Dev ohne OIDC oder OIDC-Umgebung**,
> nicht kombiniert. Enthält pro Stufe Schritte, Erwartungen, Beweise und
> eine explizite Liste der Grenzfälle, die **nicht sinnvoll manuell**
> testbar sind.
>
> **Status:** 2026-11-09
>
> **Referenzen:** [`spec/qualitaet/01-tests.md`](../spec/qualitaet/01-tests.md)
> (automatisierte Testarten), [`Core.md`](./Core.md),
> [`Async-use.md`](./Async-use.md), [`security.md`](../security.md).

## 1. Geltungsbereich

Dieser Plan deckt die **manuell prüfbaren** Funktionen ab:

- **UI-basiert:** Login, Sessions, Chat, Tool-Karten, Permissions, Fragen,
  Dateibaum, Workspace, Agent-Modal, Tasks, LLM-Trace, Logout
- Health/UI/Auth (None, Basic, OIDC)
- Sessions, Prompts, Streaming
- Datei- und Shell-Tools
- Permissions und Fragen
- Workspace, Agents, Skills
- Subagents und Background-Tasks
- Compaction, Revert, Fork, Move
- Persistenz/Neustart
- Provider-Fehler
- Sicherheit und Grenzen
- **Docker-Dev ohne OIDC** (Compose, UI-Track)
- **OIDC getrennt:** T2 (Auth0) und T6 (Keycloak) – nicht mit Docker-Dev
  kombiniert
- **Serverless** (Cloud Run/Lambda): MCP-Endpoint, ephemere Sessions,
  Agent-Fetch, Cold Start, Limits
- optionale Integrationen (MCP, A2A, E-Mail, Webhooks)

**Nicht** Teil dieses Plans: Last-, Fuzz-, Race- und Crash-Tests (siehe §6).

## 2. Testumgebungen

| Umgebung | Zweck | Zugang |
|---|---|---|
| **T1 – Lokal (Dev)** | schnelle Funktionsprüfung | `mvn quarkus:dev`, `http://localhost:8080` |
| **T2 – GCP-VM** | Produktionsnahe Abnahme, Auth0-OIDC, nginx | `https://test.schippel.info` |
| **T3 – Lokal mit Stub-Provider** | deterministische Tool-/Perm-Tests | `ENGINE_PROVIDER_SCRIPTED=true` (falls konfiguriert) |
| **T4 – Docker-Dev (UI)** | Hot-Reload-Container, UI-Track | `docker compose --profile dev up qoc-dev`, `http://localhost:8070` |
| **T5 – Docker-Dev-Native** | Native-Image im Container, **ohne OIDC** | `--profile dev-native`, `http://localhost:8081` |
| **T6 – Lokaler OIDC-Stack** | Keycloak + OIDC + UI (Compose, **kein Dev-Container**) | `./deploy/start-oidc.sh`, `http://localhost:8071` |
| **T7 – Serverless lokal** | Serverless-Rolle im Container | `--profile minimal`, `http://localhost:8072` |
| **T8 – Serverless Cloud** | Cloud Run / Lambda, OIDC, `/mcp` | `deploy/gcp-cloudrun-serverless/deploy.sh` bzw. `deploy/aws-lambda-serverless/deploy.sh` |

### 2.1 T1 starten

```bash
cd qoc
export OPENAI_API_KEY="sk-..."
mvn quarkus:dev
```

- UI: `http://localhost:8080/`
- Health: `http://localhost:8080/api/health/live`

### 2.2 T2 prüfen

```bash
gcloud compute ssh torsten@quad-e2micro --zone=us-central1-a \
  --project=gen-lang-client-0960862878 --command "systemctl is-active qoc"
```

- UI: `https://test.schippel.info/`
- Logs: `sudo journalctl -u qoc -f`
- Env: `sudo cat /etc/qoc/env`

### 2.3 T4 – Docker-Dev (UI-Track)

```bash
cd qoc/deploy
docker compose --profile dev up -d qoc-dev
docker compose logs -f qoc-dev
```

- UI: `http://localhost:8070/`
- Kein Login (Standard-`qoc-dev`), `ENGINE_PERMISSION_DEFAULT=allow-all`
  → keine Permission-Dialoge (M6 auf T1/T2/T6 testen)
- Hot-Reload: Quellcode-Mount `..:/app`

### 2.4 T5 – Docker-Dev-Native (ohne OIDC)

```bash
cd qoc/deploy
docker compose --profile dev-native up -d qoc-dev-native
```

- UI: `http://localhost:8081/`
- Kein Login (lokaler Admin-Modus)
- Dient nur dazu, das Native-Image im Container zu prüfen; OIDC läuft
  ausschließlich über T2/T6.

### 2.5 T6 – Lokaler OIDC-Stack (Keycloak)

```bash
cd qoc
cp deploy/.env.example deploy/.env   # einmalig, OPENAI_API_KEY setzen
SKIP_BUILD=true ./deploy/start-oidc.sh   # oder ohne SKIP_BUILD für Neu-Build
```

- Keycloak: `http://localhost:8180` (Admin `admin`/`admin`)
- QOC-UI: `http://localhost:8071`
- Test-User:
  - `alice` / `alice-password` (Rolle `qoc-user`)
  - `bob` / `bob-password` (Rolle `qoc-admin`)
- Konsole: `docker logs -f qoc-local`, `docker logs -f keycloak-local`

### 2.6 T7 – Serverless lokal

```bash
cd qoc/deploy
docker compose --profile e2e up -d qoc-registry   # Agent-Quelle, Port 8074
docker compose --profile minimal up -d qoc-minimal
```

- Serverless-Rolle: `http://localhost:8072`
- `ENGINE_MODE=serverless`, `ENGINE_AGENT_SOURCE_URL=http://host.docker.internal:8074`
- OIDC aktiv (`*oidc-env`), `/mcp` geschützt
- Logs: `docker logs -f qoc-minimal`

### 2.7 T8 – Serverless Cloud

**GCP Cloud Run:**

```bash
cd qoc
export PROJECT_ID=gen-lang-client-0960862878
export ENGINE_AGENT_SOURCE_URL=https://test.schippel.info
export ENGINE_OIDC_ISSUER=https://dev-1kxf4iy7txwbmdks.us.auth0.com/
export ENGINE_OIDC_AUDIENCE=qoc-engine
./deploy/gcp-cloudrun-serverless/deploy.sh
```

- Service-URL und `/mcp`-Endpoint werden am Ende ausgegeben.
- `ENGINE_MODE=serverless`, `ENGINE_WORKSPACE=/tmp/workspace`,
  `ENGINE_MAX_CONCURRENT_PROMPTS=1`, `ENGINE_TURN_MAX_WALL_MINUTES=10`.

**AWS Lambda:**

```bash
cd qoc
export ENGINE_AGENT_SOURCE_URL=https://test.schippel.info
export ENGINE_OIDC_ISSUER=https://dev-1kxf4iy7txwbmdks.us.auth0.com/
./deploy/aws-lambda-serverless/deploy.sh
```

- Function-URL + `/mcp`; Timeout 300 s, 512 MB, `/tmp` als Workspace.

> **Wichtig:** Serverless hat **keine** UI im klassischen Sinn und **kein**
> SSE. Getestet wird der MCP-Endpoint mit Bearer-Token. Die UI läuft auf
> T2/T6 und ruft den Serverless-Endpoint nur als Tool/Agent auf.

## 3. Voraussetzungen und Testdaten

| Voraussetzung | T1 | T2 (GCP-VM) | T4/T5 (Docker-Dev) | T6 (Keycloak) | T7/T8 (Serverless) |
|---|---|---|---|---|---|
| LLM-Key gesetzt | `OPENAI_API_KEY` | `/etc/qoc/env` | `deploy/.env` | `deploy/.env` | Deploy-Env |
| Workspace | Projektverzeichnis | `/data/workspace` | `../data/workspace` | `../data/workspace` | `/tmp/workspace` (ephemer) |
| Auth | lokal ohne Login oder Basic | Auth0-OIDC | keins | Keycloak-OIDC | OIDC (Bearer) |
| Test-User | – | `admin@qoc.test` / `login` | – | `alice`/`alice-password`, `bob`/`bob-password` | Auth0-Testuser oder Keycloak |
| Scratch-Verzeichnis | `qoc-test-<datum>` | `/data/workspace/qoc-test-<datum>` | `data/workspace/qoc-test-<datum>` | `data/workspace/qoc-test-<datum>` | `/tmp/workspace/qoc-test-<datum>` |

**Vor jedem Durchlauf:**

1. Neue Session anlegen (keine Alt-Session wiederverwenden).
2. Bei T2/T6 (OIDC): in DevTools `sessionStorage.clear()` und neu einloggen.
3. Scratch-Verzeichnis anlegen und am Ende löschen.
4. Logs/SSE vor dem Test öffnen.
5. Bei Serverless: ephemere Session nach dem Call prüfen (Cleanup).

## 4. Konventionen

| Feld | Bedeutung |
|---|---|
| **ID** | `M-<stufe>-<nr>`, z. B. `M4-03` |
| **Prio** | P0 = blockierend, P1 = wichtig, P2 = optional |
| **Beweis** | Screenshot, `curl`-Ausgabe, Log-Zeile oder SSE-Mitschnitt |
| **Bestanden** | Erwartung exakt erfüllt; Abweichung = Fehler mit Reproduktion |

**Test-Tracks:**

| Track | Umgebung | Fokus | Werkzeuge |
|---|---|---|---|
| **UI** | T1/T2/T4/T5/T6 | Bedienung im Browser (T4/T5 ohne Login, T2/T6 mit OIDC) | Chrome/Firefox, DevTools |
| **API** | T1/T2/T6/T7 | Endpunkte, Statuscodes, SSE | `curl`, `jq` |
| **Deploy** | T4/T5/T7/T8 | Container, OIDC (T2/T6), Serverless | Docker, `gcloud`, `aws` |

**Testartefakte:** `evidence/M<stufe>/<id>-<kurz>.txt|png|log`

## 5. Teststufen (aufeinander aufbauend)

```
M0 Smoke
 └─ M1 Auth
     └─ M2 Sessions
         └─ M3 Prompt & Streaming
             ├─ M4 Datei-Tools
             ├─ M5 Shell & Prozesse
             ├─ M6 Permissions & Fragen
             └─ M7 Workspace & Dateien
                 └─ M8 Agents & Skills
                     └─ M9 Subagents & Tasks
                         └─ M10 Kontext (Compaction/Revert/Fork/Move)
                             ├─ M11 Integrationen (optional)
                             ├─ M12 Provider-Fehler
                             └─ M13 Persistenz & Neustart
                                 └─ M14 Sicherheit
                                     └─ M15 Grenzen & Performance
                                         ├─ M16 UI-Track (Browser, T4 ohne Login)
                                         ├─ M17 OIDC (T2 Auth0 / T6 Keycloak)
                                         ├─ M18 Docker-Dev-Native (ohne OIDC)
                                         └─ M19 Serverless (Cloud Run/Lambda)
```

**Regel:** Eine Stufe gilt erst als bestanden, wenn alle P0-Fälle grün sind.
P1/P2 dürfen dokumentiert offen bleiben.

---

## M0 – Smoke (P0)

| ID | Schritt | Erwartung | Beweis |
|---|---|---|---|
| M0-01 | UI öffnen | Seite lädt, Login-Button sichtbar | Screenshot |
| M0-02 | `/api/health/live` mit Token | `200 {"status":"UP"}` | curl |
| M0-03 | `/api/health/ready` mit Token | `200` wenn bereit, sonst `503` | curl |
| M0-04 | `/api/health/live` ohne Token (OIDC) | `401` + `WWW-Authenticate: Bearer` | curl |
| M0-05 | SSE `/api/event` mit Token | Verbindung bleibt offen, Events kommen | `curl -N` |
| M0-06 | `/config.js` | enthält `issuer`, `clientId`, `audience` | curl |
| M0-07 | Browser-Konsole | keine unbehandelten Fehler | Screenshot |

```bash
curl -s -H "Authorization: Bearer $TOKEN" https://test.schippel.info/api/health/live
```

---

## M1 – Authentifizierung (P0)

| ID | Schritt | Erwartung |
|---|---|---|
| M1-01 | Login klicken → Auth0 | Redirect zu Auth0, nach Login zurück zur App |
| M1-02 | Nach Login | Button „Logout", API-Calls 200 |
| M1-03 | Logout | Token gelöscht, API-Calls 401 |
| M1-04 | Falsches Passwort | Auth0-Fehler, kein Token |
| M1-05 | T1 Basic-Modus: falsches Passwort | 401, `WWW-Authenticate: Basic` |
| M1-06 | T1 Basic-Modus: richtiges Passwort | 200 |
| M1-07 | `/api/metrics` ohne `admin`-Rolle | 403 |
| M1-08 | `/api/audit` ohne `admin`-Rolle | 403 |
| M1-09 | Andere Session-ID als Fremd-User | 403/404, kein Datenleck |
| M1-10 | Token abgelaufen (DevTools manipulieren) | 401, erneuter Login nötig |

**Hinweis:** OIDC-Token liegt in `sessionStorage` (`qoc.accessToken`).

---

## M2 – Sessions (P0)

| ID | Schritt | Erwartung |
|---|---|---|
| M2-01 | Neue Session anlegen | `id` in Antwort, Session in Liste |
| M2-02 | Session-Liste laden | neue Session erscheint |
| M2-03 | Session öffnen | leere Message-Liste, Status „bereit" |
| M2-04 | Agent wechseln (idle) | 200, Agent-Name aktualisiert |
| M2-05 | Agent wechseln (running) | 409 `SessionRunningError` |
| M2-06 | Ungültiger Agent | 400/403 |
| M2-07 | Unbekannte Session | 404 |
| M2-08 | Session löschen | 200, aus Liste entfernt |
| M2-09 | Doppeltes Löschen | 404/200 idempotent (dokumentieren) |

```bash
curl -s -X POST https://test.schippel.info/api/session \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}'
```

---

## M3 – Prompt und Streaming (P0)

| ID | Schritt | Erwartung |
|---|---|---|
| M3-01 | Einfacher Prompt („Antworte mit OK") | Antwort erscheint, Status zurück auf „bereit" |
| M3-02 | SSE beobachten | `message`, `text`, `finish`-Events in Reihenfolge |
| M3-03 | Tool-Call auslösen („Lies README.md") | Tool-Karte + Ergebnis, danach Antwort |
| M3-04 | Seite neu laden | History bleibt erhalten |
| M3-05 | Zweiten Prompt senden | neue Antwort, History wächst |
| M3-06 | Leeren Prompt senden | 400 |
| M3-07 | Prompt während laufendem Turn | 409 `SessionBusyError` |
| M3-08 | Interrupt während Turn | Status „interrupted", keine hängende Anzeige |
| M3-09 | Sehr langer Prompt | Grenze wird respektiert (Fehler oder Truncation) |
| M3-10 | SSE-Reconnect nach Netzwerkabbruch | Events ab `after`-Sequenz, keine Duplikate |

---

## M4 – Datei-Tools (P0)

Vorbereitung: Scratch-Datei `qoc-test-<datum>/a.txt` mit bekanntem Inhalt.

| ID | Schritt | Erwartung |
|---|---|---|
| M4-01 | `read` auf `a.txt` | Inhalt exakt |
| M4-02 | `write` auf neue Datei | Datei existiert mit Inhalt |
| M4-03 | `edit` (ersetzen) | nur Zielstelle geändert |
| M4-04 | `patch` (Multi-Hunk) | alle Hunks korrekt |
| M4-05 | `glob`/`grep` | korrekte Treffer, keine Fremddateien |
| M4-06 | `read` auf nicht existierende Datei | typisierter Fehler an das Modell |
| M4-07 | Pfad `../../etc/passwd` | verweigert/kein Inhalt außerhalb Workspace |
| M4-08 | Symlink nach außen | verweigert (kein Escape) |
| M4-09 | Binärdatei | sauberer Fehler oder Base64, kein Absturz |
| M4-10 | Große Datei (> 1 MB) | Limit greift, klare Meldung |

---

## M5 – Shell und Prozesse (P0)

| ID | Schritt | Erwartung |
|---|---|---|
| M5-01 | `echo hello` | Ausgabe `hello`, Exit 0 |
| M5-02 | `sleep 60` starten | Prozess läuft, UI bleibt bedienbar |
| M5-03 | Prozessliste `/processes` | Prozess sichtbar |
| M5-04 | Prozess killen | Prozess beendet, Status aktualisiert |
| M5-05 | Kommando mit Exit ≠ 0 | Exit-Code und stderr sichtbar |
| M5-06 | Timeout-Kommando | Timeout-Fehler, kein hängender Step |
| M5-07 | Schreibversuch außerhalb Workspace (Sandbox `workspace-write`) | verweigert |
| M5-08 | Interrupt während Shell | Prozess wird beendet/abgebrochen |

---

## M6 – Permissions und Fragen (P0)

> **Umgebung:** T1/T2/T6 mit `ENGINE_PERMISSION_DEFAULT=least-privilege`.
> T4 (`qoc-dev`) läuft mit `allow-all` und löst keine Permission-Dialoge aus.

| ID | Schritt | Erwartung |
|---|---|---|
| M6-01 | Aktion mit Ask-Regel (z. B. `shell`) | Permission-Dialog erscheint |
| M6-02 | `ONCE` antworten | Aktion läuft einmal |
| M6-03 | `ALWAYS` antworten | Aktion läuft, Approval gespeichert |
| M6-04 | Gespeichertes Approval erneut nutzen | keine erneute Nachfrage |
| M6-05 | `REJECT` ohne Feedback | Aktion abgelehnt, Modell informiert |
| M6-06 | `REJECT` mit Feedback | Feedback kommt beim Modell an |
| M6-07 | Deny-Regel + gespeichertes Allow | **Deny gewinnt** |
| M6-08 | `question`-Tool | Frage-Panel, Antwort `selected`/`text` |
| M6-09 | Frage-Timeout/headless | konfiguriertes Verhalten (deny/retry) |
| M6-10 | Kind-Session-Permission (`includeChildren=true`) | Kind-Anfrage sichtbar |
| M6-11 | Antwort auf unbekannte Request-ID | 404 |

```bash
curl -s -X POST .../api/session/$SID/permission/$REQ/reply \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"reply":"ONCE"}'
```

---

## M7 – Workspace und Dateien (P1)

| ID | Schritt | Erwartung |
|---|---|---|
| M7-01 | Dateibaum laden | korrekte Struktur |
| M7-02 | Datei anzeigen | Inhalt, UTF-8 korrekt |
| M7-03 | Datei herunterladen | Bytes identisch |
| M7-04 | Verzeichnis herunterladen | ZIP/Archiv vollständig |
| M7-05 | Workspace wechseln (neu) | Verzeichnis wird angelegt, UI lädt neu |
| M7-06 | Workspace wechseln (ungültig) | 400, aktueller Workspace bleibt |
| M7-07 | Worktree anlegen/listen/entfernen (falls aktiviert) | konsistente Liste, keine Reste |

---

## M8 – Agents und Skills (P1)

| ID | Schritt | Erwartung |
|---|---|---|
| M8-01 | Agent-Liste | Default + konfigurierte Agents |
| M8-02 | Agent anlegen (UI/API) | erscheint in Liste |
| M8-03 | Agent ändern | Änderung wirksam |
| M8-04 | Agent löschen | aus Liste entfernt |
| M8-05 | Session auf Agent umschalten | Agent wird verwendet |
| M8-06 | Skill-Liste | sichtbare/erlaubte Skills |
| M8-07 | Skill per Prompt laden | Inhalt im Kontext, keine Fehler |
| M8-08 | Ungültiges Skill-Frontmatter | Skill wird ignoriert, kein Crash |
| M8-09 | Config-Reload | Änderungen ohne Neustart sichtbar |

---

## M9 – Subagents und Background-Tasks (P1)

| ID | Schritt | Erwartung |
|---|---|---|
| M9-01 | Synchroner Subagent | Ergebnis als Tool-Output |
| M9-02 | `subagent(background=true)` | sofort `taskId`, Parent läuft weiter |
| M9-03 | Task-Liste (`/tasks`) | Task mit Status `RUNNING` |
| M9-04 | `task wait` (kurz) | Ergebnis oder Timeout |
| M9-05 | `task wait` (lang) | Ergebnis nach Abschluss |
| M9-06 | `task cancel` | Status `CANCELLED` |
| M9-07 | 5. gleichzeitiger Subagent | 5. wartet/abgelehnt (Limit 4) |
| M9-08 | Depth > 2 | abgelehnt |
| M9-09 | Subagent-Fehler | Task `FAILED`, Fehler sichtbar |
| M9-10 | Parent-Session löschen | Kind-Tasks werden beendet |
| M9-11 | SSE `SubagentStarted/Finished` | Events erscheinen in Reihenfolge |

---

## M10 – Kontext: Compaction, Revert, Fork, Move (P1)

| ID | Schritt | Erwartung |
|---|---|---|
| M10-01 | Lange Konversation | Auto-Compaction, Zusammenfassung |
| M10-02 | Manuelle Compaction | Summary ersetzt alten Kontext |
| M10-03 | Revert | Nachrichten ab Punkt entfernt, Dateien zurückgesetzt (falls konfiguriert) |
| M10-04 | Fork | neue Session mit kopierter History |
| M10-05 | Move | Session wechselt Workspace/Location |
| M10-06 | Titel-Generierung | Titel wird gesetzt |
| M10-07 | Todo-Liste | Einträge erscheinen/aktualisieren |
| M10-08 | Compaction-Fehler | Step schlägt typisiert fehl, kein Datenverlust |

---

## M11 – Integrationen (P2, nur wenn aktiviert)

| ID | Schritt | Erwartung |
|---|---|---|
| M11-01 | `/api/mcp` | konfigurierte Server |
| M11-02 | MCP-Status | `connected`/`error` korrekt |
| M11-03 | MCP-Authorize/Callback | Token wird übernommen |
| M11-04 | `/api/a2a` | Remote-Agents gelistet |
| M11-05 | A2A-Aufruf | Ergebnis oder typisierter Fehler |
| M11-06 | E-Mail lesen/senden (falls aktiviert) | korrekte Limits/Empfänger |
| M11-07 | Webhook-Trigger (falls aktiviert) | Session wird geweckt, Antwort sichtbar |

---

## M12 – Provider-Fehler und Resilienz (P1)

| ID | Schritt | Erwartung |
|---|---|---|
| M12-01 | Ungültiger API-Key | 502/503, klare Fehlermeldung |
| M12-02 | Rate-Limit simulieren | Retry mit Backoff, danach Fehler |
| M12-03 | Provider-Timeout | typisierter Fehler, Session bleibt nutzbar |
| M12-04 | Netzwerk trennen (DevTools offline) | Retry/Fehler, keine hängende UI |
| M12-05 | Modell wechseln | nächster Prompt nutzt neues Modell |
| M12-06 | Preflight-Fehler (DB/Key) | Start/Health meldet Problem |

---

## M13 – Persistenz und Neustart (P0 für T2)

| ID | Schritt | Erwartung |
|---|---|---|
| M13-01 | Seite neu laden | Session + History bleiben |
| M13-02 | `systemctl restart qoc` | Service wieder `active` |
| M13-03 | Nach Neustart Session öffnen | History vollständig |
| M13-04 | Prompt während Neustart | Claim-Recovery: Session wird fortgesetzt oder typisiert fehlgeschlagen |
| M13-05 | Gespeicherte Approvals nach Neustart | weiterhin wirksam |
| M13-06 | Verwaiste Background-Tasks nach Neustart | als beendet/verloren markiert, kein Zombie |
| M13-07 | Audit/Journal | Events konsistent, keine Lücken |

```bash
sudo systemctl restart qoc && sleep 6 && systemctl is-active qoc
```

---

## M14 – Sicherheit (P0)

| ID | Schritt | Erwartung |
|---|---|---|
| M14-01 | API ohne Token | 401 |
| M14-02 | Fremde Session-ID raten | 403/404 |
| M14-03 | Pfad-Traversal (`../`, URL-encoded) | verweigert |
| M14-04 | Symlink-Escape | verweigert |
| M14-05 | Shell-Injection-Versuch (z. B. `; rm -rf`) | nur als Argument/Literal, kein ungewollter Effekt |
| M14-06 | XSS in Prompt/Dateiname (`<script>`) | wird escaped dargestellt |
| M14-07 | Secrets im UI/Log | keine API-Keys/Token im Klartext |
| M14-08 | Credential-Liste | Secrets maskiert |
| M14-09 | Admin-Endpunkte als Nicht-Admin | 403 |
| M14-10 | Fehlermeldungen | keine Stacktraces/Interna an den Client |

---

## M15 – Grenzen und Performance (P2, manuell)

| ID | Schritt | Erwartung |
|---|---|---|
| M15-01 | 20 Sessions anlegen | Liste reagiert flüssig |
| M15-02 | Datei > 1 MB anzeigen | Limit/Fehler, kein UI-Freeze |
| M15-03 | Tool-Output > Limit | Truncation mit Marker/Datei |
| M15-04 | SSE 10 Minuten offen | Verbindung stabil, Heartbeat/Reconnect |
| M15-05 | 4 parallele Subagents | keine Überschreitung |
| M15-06 | Sehr langer Prompt (100k Zeichen) | 400/Limit, kein OOM |
| M15-07 | Viele Nachrichten in einer Session | Scroll/History bleibt bedienbar |

---

## M16 – UI-Track (Browser, ohne curl) (P0)

Voraussetzung: T1, T2 oder T6 (T4 `qoc-dev` läuft mit `ENGINE_PERMISSION_DEFAULT=allow-all` und ist für M16-05…07 nicht geeignet). Alle Schritte **nur** über die Oberfläche.

| ID | Schritt | Erwartung |
|---|---|---|
| M16-01 | Login-Button klicken (OIDC) | Auth0/Keycloak-Login, Rückkehr zur App |
| M16-02 | Session-Dropdown → neue Session | neue Session erscheint, Chat leer |
| M16-03 | Prompt eingeben + senden (Ctrl+Enter) | Antwort streamt, Status „bereit" |
| M16-04 | Tool-Call auslösen („Lies README.md") | Tool-Karte mit Ergebnis |
| M16-05 | Permission-Modal: `ONCE` | Aktion läuft einmal |
| M16-06 | Permission-Modal: `ALWAYS` | Aktion läuft, keine erneute Nachfrage |
| M16-07 | Permission-Modal: `REJECT` + Feedback | Aktion abgelehnt, Feedback sichtbar |
| M16-08 | `question`-Tool auslösen | Frage-Panel, Antwort senden |
| M16-09 | Dateibaum öffnen, Datei anklicken | Inhalt im Viewer |
| M16-10 | Datei herunterladen | Download identisch |
| M16-11 | Workspace-Menü → wechseln | UI lädt neu, Workspace korrekt |
| M16-12 | Agent-Modal: Agent anlegen/ändern/löschen | Liste aktualisiert |
| M16-13 | Task-Liste/Status/Cancel (falls UI vorhanden) | Zustände korrekt |
| M16-14 | LLM-Trace-Panel öffnen | Request/Response sichtbar |
| M16-15 | SSE-Verbindung trennen (DevTools offline) | Banner „SSE getrennt", Reconnect |
| M16-16 | Logout | Token weg, API-Calls 401, Login-Button |
| M16-17 | Mobile Layout (Fenster schmal) | Resizer/Sidebar funktionieren |
| M16-18 | Browser-Konsole | keine unbehandelten Fehler |

## M17 – OIDC (T2 Auth0 / T6 Keycloak) (P0)

**Getrennt von Docker-Dev.** Entweder T2 (Auth0 auf der GCP-VM) oder T6
(lokaler Keycloak-Stack) – nicht der Dev-Container.

| ID | Schritt | Erwartung |
|---|---|---|
| M17-01 | T6: `start-oidc.sh` ausführen | Keycloak + QOC starten, Health 200 |
| M17-02 | T6: Keycloak-Admin `http://localhost:8180/admin` | Login `admin`/`admin` |
| M17-03 | QOC-UI öffnen (T2 `https://test.schippel.info`, T6 `http://localhost:8071`) | lädt, Login-Button |
| M17-04 | Login `alice`/`alice-password` (T6) bzw. Auth0-Testuser (T2) | Rückkehr, API-Calls 200 |
| M17-05 | Token in DevTools prüfen | `iss` korrekt, `aud` enthält `qoc-engine` |
| M17-06 | `/api/health` mit Token | 200 |
| M17-07 | `/api/metrics` als Nicht-Admin | 403 |
| M17-08 | Login `bob`/`bob-password` (T6) bzw. Admin (T2) | `/api/metrics` 200 |
| M17-09 | SSE in DevTools | `fetch`-Request mit Bearer, Status 200 |
| M17-10 | Falsche Audience (Token manipulieren) | 401 |
| M17-11 | T6: CLI-Token (`qoc-cli`, password grant) | API-Call 200 |
| M17-12 | T6: Container-Neustart (`docker restart qoc-local`) | Session/History bleiben (SQLite-Volume) |
| M17-13 | Statische UI ohne Token | `/` lädt, `/api/*` 401 |
| M17-14 | Logout | Token gelöscht, erneuter Login nötig |

**Split-Horizon prüfen (T6):**

- Browser-Issuer: `http://localhost:8180/realms/qoc`
- Container-JWKS: `http://host.docker.internal:8180/realms/qoc/...`
- `QUARKUS_OIDC_TOKEN_ISSUER` muss exakt der Browser-Issuer sein.

**T2-spezifisch:** Auth0-Issuer mit Trailing Slash, `aud=qoc-engine`,
JWKS von der VM erreichbar, nginx reicht `Authorization` durch.

## M18 – Docker-Dev-Native ohne OIDC (T5) (P1)

| ID | Schritt | Erwartung |
|---|---|---|
| M18-01 | `docker compose --profile dev-native up -d` | Native-Build startet |
| M18-02 | UI `http://localhost:8081` | lädt ohne Login |
| M18-03 | API-Call ohne Token | 200 (lokaler Admin-Modus) |
| M18-04 | Native-Startzeit im Log | dokumentiert (Vergleich JVM) |
| M18-05 | Reflection/Config | keine `ClassNotFound`/Config-Fehler |
| M18-06 | Container-Neustart | Session/History bleiben |
| M18-07 | Gleiche UI-Fälle wie M16 (Stichprobe) | identisches Verhalten |
| M18-08 | Kein OIDC im Container | kein Login/Redirect, kein Auth-Zwang |

## M19 – Serverless (Cloud Run / Lambda) (P0/P1)

Voraussetzung: T7 (lokal) oder T8 (Cloud). Serverless hat **keine UI**;
getestet wird der `/mcp`-Endpoint mit Bearer-Token.

| ID | Schritt | Erwartung |
|---|---|---|
| M19-01 | Deployment-Skript ausführen | Service/Function aktiv, URL ausgegeben |
| M19-02 | `/mcp` ohne Token | 401 |
| M19-03 | `/mcp` mit gültigem OIDC-Token (Auth0/Keycloak) | MCP-Handshake erfolgreich |
| M19-04 | `qoc_run_agent` mit `build`/`plan` | Ergebnis + `_meta` |
| M19-05 | `qoc_run_agent` mit unbekanntem Agent | Fetch von `ENGINE_AGENT_SOURCE_URL` |
| M19-06 | `qoc_run_agent_with_config` (Inline-Markdown) | transienter Agent, danach entfernt |
| M19-07 | Nach dem Call Session prüfen | ephemere Session gelöscht |
| M19-08 | Ergebnis-Metadaten | Tokens, Finish, Warnungen, `agentCreated`/`agentDeleted` |
| M19-09 | Cold Start (Cloud Run `min-instances=0`) | erster Call innerhalb Timeout |
| M19-10 | Parallele Calls (mehrere Instanzen/Concurrency) | korrekte Ergebnisse, keine geteilten Sessions |
| M19-11 | Turn-Limit (10 min) / Lambda-Timeout (300 s) | typisierter Fehler, kein Hänger |
| M19-12 | Großer Prompt / 512 MB Memory | Limit/Fehler, kein OOM |
| M19-13 | Workspace `/tmp/workspace` | nur Scratch, nach Call bereinigt |
| M19-14 | Agent-Quelle nicht erreichbar | `agent not available`, klarer Fehler |
| M19-15 | Unerlaubte Tools (Leaf-Profil) | abgelehnt/gar nicht verfügbar |
| M19-16 | Skills/Subagents im Serverless-Modus | nicht verfügbar (MVP) |
| M19-17 | Lambda-Variante: Function-URL + `/mcp` | gleiche Fälle wie Cloud Run |
| M19-18 | Logs (Cloud Run/Lambda) | Request-ID, keine Secrets |

## 6. Grenzfälle: manuell vs. automatisiert

Diese Fälle sind **nicht sinnvoll manuell** testbar – zu fein, zu selten, zu
timingabhängig oder zu zerstörerisch. Sie gehören in automatisierte Tests.

| Grenzfall | Warum nicht manuell | Automatisierung |
|---|---|---|
| Zwei gleichzeitige Prompts derselben Session | Race, ms-Fenster | Unit/Integration: `ConcurrentPromptTest` |
| Claim-Kollision / doppelte Execution | ms-Fenster, nicht reproduzierbar | `CoordinatorTest` (existiert) |
| Doorbell-Coalescing | Timing | `CoordinatorTest` |
| Admission/Delivery-Reihenfolge | Transaktionsrace | Integration mit DB |
| Crash mitten im Step (SIGKILL) | zerstört Umgebung | Recovery-Test mit Prozess-Kill |
| Stromausfall / JVM-Crash | nicht kontrollierbar | Chaos-/Recovery-Test |
| OOM / Memory-Druck | instabil | Lasttest mit Heap-Limit |
| Virtual-Thread-Pinning | nur unter Last sichtbar | `-Djdk.tracePinnedThreads=full` |
| Clock-Skew / Token-Ablauf exakt an der Grenze | Zeitmanipulation | `FixedClock` + Contract-Test |
| Netzwerkpartition / halbe SSE-Frames | nicht deterministisch | Proxy/Fault-Injection |
| Provider-Stream bricht mitten im Token ab | nicht reproduzierbar | Stub-Provider mit Fehlerinjektion |
| Doppelte Tool-Ausführung bei Retry | Race | Effect-Ledger-Test |
| DB-Deadlock / Serialisierungsfehler | DB-spezifisch | Integration mit Parallelität |
| Multi-Node-Claims/Leases | mehrere Instanzen | Cluster-Test |
| Symlink-TOCTOU | Race | Property-/Fuzz-Test |
| Path-Traversal-Varianten (Encoding) | zu viele Varianten | Fuzz-Test |
| SSRF über Web-Tools | Umgebung | Security-Test mit Mock-Server |
| Command-Injection-Payloads | zu viele Varianten | Fuzz-Test |
| XSS-Payload-Korpus | zu viele Varianten | UI-Test (Playwright) |
| JSON-Schema-Fuzzing für Tool-Inputs | Kombinatorik | Property-based Test |
| Migration/Schema-Evolution | DB-Zustände | Migrations-Test |
| 1.000 wartende Sessions | Last | Load-Test |
| Eviction unter Speicherdruck | Timing | Lasttest mit Budget |
| Token-Refresh-Race | ms-Fenster | Integrationstest |

**Regel:** Wenn ein Grenzfall manuell nur mit Tricks (Debugger, künstliche
Verzögerung, Kill -9) erreichbar ist, gehört er **nicht** in diesen Plan,
sondern in die automatisierte Suite.

## 7. Testmatrix / Checkliste

| Stufe | P0 | P1 | P2 | Bestanden am | Tester |
|---|---|---|---|---|---|
| M0 Smoke | 7 | – | – | | |
| M1 Auth | 10 | – | – | | |
| M2 Sessions | 9 | – | – | | |
| M3 Prompt/SSE | 10 | – | – | | |
| M4 Datei-Tools | 10 | – | – | | |
| M5 Shell | 8 | – | – | | |
| M6 Permissions | 11 | – | – | | |
| M7 Workspace | – | 7 | – | | |
| M8 Agents/Skills | – | 9 | – | | |
| M9 Subagents | – | 11 | – | | |
| M10 Kontext | – | 8 | – | | |
| M11 Integrationen | – | – | 7 | | |
| M12 Provider | – | 6 | – | | |
| M13 Persistenz | 7 | – | – | | |
| M14 Sicherheit | 10 | – | – | | |
| M15 Grenzen | – | – | 7 | | |
| M16 UI-Track | 18 | – | – | | |
| M17 OIDC | 14 | – | – | | |
| M18 Docker-Dev-Native | – | 8 | – | | |
| M19 Serverless | 12 | 6 | – | | |

## 8. Umgebungsbefehle

### 8.1 Lokal

```bash
cd qoc
export OPENAI_API_KEY="sk-..."
mvn quarkus:dev
curl -s http://localhost:8080/api/health/live
```

### 8.2 GCP-VM

```bash
# Status
gcloud compute ssh torsten@quad-e2micro --zone=us-central1-a \
  --project=gen-lang-client-0960862878 --command "systemctl is-active qoc"

# Logs
gcloud compute ssh torsten@quad-e2micro --zone=us-central1-a \
  --project=gen-lang-client-0960862878 --command "sudo journalctl -u qoc -n 100 --no-pager"

# Neustart
gcloud compute ssh torsten@quad-e2micro --zone=us-central1-a \
  --project=gen-lang-client-0960862878 --command "sudo systemctl restart qoc"

# Authentifizierter Aufruf
TOKEN="<access token aus sessionStorage>"
curl -s -H "Authorization: Bearer $TOKEN" https://test.schippel.info/api/health/live
```

### 8.3 Token aus dem Browser holen (nur Test)

DevTools-Konsole:

```js
sessionStorage.getItem('qoc.accessToken')
```

## 9. Bekannte Stolperfallen

| Problem | Ursache | Abhilfe |
|---|---|---|
| Alle API-Calls 401 nach Login | Token fehlt/abgelaufen; PKCE-Race | `sessionStorage.clear()`, neu einloggen |
| „Login-State ungültig" | API-Calls während Login haben PKCE-Keys gelöscht | UI-Fix; im Test langsam klicken |
| SSE hängt | `EventSource` kann keinen Bearer senden | UI nutzt Fetch-SSE; curl mit Header |
| Login-Redirect fehlerhaft | falsche `redirect_uri`/Auth0-Config | `/config.js` prüfen |
| 403 trotz Admin | Rolle fehlt im Token | Auth0-Rollen prüfen |
| Session „busy" | vorheriger Turn läuft | warten oder `/interrupt` |
| Permissions hängen | `ENGINE_PERMISSION_ASK_MODE=deny` | Konfiguration prüfen |
| Workspace-Wechsel schlägt fehl | Pfad fehlt/Rechte | `create=true` oder Pfad prüfen |
| T6: Login-Redirect auf Container-Host | Split-Horizon falsch | `QUARKUS_OIDC_TOKEN_ISSUER` = Browser-Issuer (`localhost:8180`) |
| T6: `401` trotz Login | Audience/Issuer weichen ab | Token-Payload mit `jq` prüfen |
| Serverless: erster Call langsam | Cold Start (`min-instances=0`) | warm halten oder Timeout erhöhen |
| Serverless: Datei fehlt nach Call | ephemerer Workspace (`/tmp`) | kein persistenter Workspace im Serverless-Modus |
| Serverless: Agent nicht gefunden | `ENGINE_AGENT_SOURCE_URL` falsch/Token fehlt | Quelle und Bearer-Token prüfen |

## 10. Exit-Kriterien

Ein Durchlauf gilt als bestanden, wenn:

1. Alle **P0**-Fälle in T1 **und** T2 grün sind.
2. Der **UI-Track (M16)** auf T4 (ohne Login) und T2/T6 (mit OIDC) grün ist.
3. Der **OIDC-Track (M17)** auf T2 oder T6 grün ist.
4. Der **Serverless-Track (M19)** auf T8 (Cloud Run oder Lambda) grün ist.
5. Alle **P1**-Fälle grün oder dokumentiert offen sind.
6. Keine unbehandelten Fehler in Browser-Konsole/Server-Log.
7. Testartefakte unter `evidence/` abgelegt sind.
8. Die Testmatrix (§7) vollständig ausgefüllt ist.

**Sign-off:**

| Rolle | Name | Datum | Ergebnis |
|---|---|---|---|
| Tester | | | |
| Reviewer | | | |

## 11. Referenzen

| Thema | Dokument |
|---|---|
| Automatisierte Testarten | [`spec/qualitaet/01-tests.md`](../spec/qualitaet/01-tests.md) |
| Architektur/Core | [`Core.md`](./Core.md) |
| Async/Heartbeat | [`Async-use.md`](./Async-use.md) |
| Storage | [`Storage-alternative.md`](./Storage-alternative.md) |
| In-Memory-Runtime | [`InMemory.md`](./InMemory.md) |
| Sicherheit | [`security.md`](../security.md) |
| Subagents | [`guides/Subagent-Delegation.md`](../guides/Subagent-Delegation.md) |
| A2A | [`guides/a2a.md`](../guides/a2a.md) |
| Agent-Registry | [`guides/Agent-Registry.md`](../guides/Agent-Registry.md) |
| Skills | [`skill-loading.md`](../skill-loading.md) |
| Betrieb/HTTP/SSE | [`spec/betrieb/01-http-streaming.md`](../spec/betrieb/01-http-streaming.md) |
