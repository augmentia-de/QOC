# QOC – Deployments

Dieses Verzeichnis bündelt alle Deployment-Varianten der QOC-Engine:
lokale Docker-Container (JVM und native) sowie VM-/Cloud-Deployments
(GCP, AWS). Jede Variante ist eigenständig und hat ein eigenes
`docker-compose.yml`, eigene Ports und eine eigene `README.md`.

> Die fachliche Quelle der Wahrheit ist die Spezifikation unter `qoc/docs/`.
> Dieses Dokument beschreibt die **Betriebs**-Perspektive.

---

## Übersicht

| Variante | Verzeichnis | Container / Dienst | Host-Port | Laufzeit | Storage | Auth | Zweck |
|---|---|---|---|---|---|---|---|
| JVM Dev | `docker-dev/` | `qoc-dev` | **8070** (UI), **5006** (JDWP) | JVM, Quarkus Dev Mode (Hot Reload) | Postgres aus `.env` + Workflow-Dateien | aus (lokal `allow-all`) | Entwicklung, Debugging |
| Native Dev | `docker-dev-native/` | `qoc-dev-native` | **8082** | Native (Mandrel 25) | Postgres aus `.env` | **aus** (`none`, `allow-all`) | nativer Test ohne Login |
| Native OIDC | `docker-oidc-native/` | `qoc-oidc-native` | **8081** | Native (Mandrel 25) | Postgres aus `.env` | **OIDC** (lokales Keycloak) | Login-/Token-Tests |
| Keycloak | `keycloak/` | `keycloak-local` | **8180** | Keycloak 26.0 | Volume `qoc-keycloak-data` | – | lokaler Identity-Provider |
| GCP VM (DB) | `gcp-vm/` | systemd `qoc.service` | intern **8081**, extern **443** (nginx) | Native | Neon PostgreSQL | Auth0/OIDC (oder Basic) | Test/Prod auf GCP-VM |
| GCP VM (Memory) | `gcp-native-memory/` | systemd `qoc-memory.service` | intern **8081** | Native | Heap + `/data/wf` (Dateien) | Auth0/OIDC | schlanke VM ohne Datenbank |
| AWS ECS | `aws-ecs-minimal/` | ECS Fargate `qoc-test-service` | **8080** (Task-IP) | Native (Memory) | EFS `/mnt/workspace` | HTTP Basic | minimaler Cloud-Teststack |
| Deprecated | `_deprecated/` | – | – | – | – | – | nicht mehr verwenden |

### Ports auf dem Host

| Port | Variante |
|---|---|
| 8070 | `qoc-dev` (JVM Dev Mode) |
| 8081 | `qoc-oidc-native` (Native + Keycloak) |
| 8082 | `qoc-dev-native` (Native, ohne OIDC/Security) |
| 5006 | JDWP-Debug von `qoc-dev` |
| 8180 | `keycloak-local` |

Alle Docker-Varianten können parallel laufen, weil sie unterschiedliche
Host-Ports und getrennte Native-Runner-Dateien nutzen (siehe
[Native-Builds](#native-builds)).

---

## Gemeinsame Grundlagen

### `deploy/.env`

Die Docker-Varianten lesen ihre Laufzeit-Konfiguration aus `deploy/.env`
(gitignored, empfohlene Rechte `0600`). Diese Datei enthält u. a.:

- LLM-Provider: `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `ENGINE_MODEL_ID`, …
- Datenbank: `ENGINE_DB_KIND`, `ENGINE_DB_DRIVER`, `QUARKUS_DATASOURCE_*`,
  `ENGINE_FLYWAY_LOCATIONS`
- Security: `ENGINE_SECURITY_MODE`, `OPENCODE_SERVER_PASSWORD`
- Permissioning: `ENGINE_PERMISSION_DEFAULT`, `ENGINE_PERMISSION_ASK_MODE`
- Workspace/Logging: `ENGINE_WORKSPACE`, `LOG_DIR`

Eine historische Vorlage liegt unter `deploy/_deprecated/.env.example`;
die dort genannten Werte vor der Übernahme prüfen. **Secrets niemals
committen.**

### Host-Benutzer, Gruppen, Mounts

- Container laufen als Host-`UID:GID` (Default `1000:1000`).
- `DOCKER_GID` ist die GID der Docker-Gruppe
  (`stat -c '%g' /var/run/docker.sock`) und wird von Compose gesetzt, falls ein
  Docker-Socket für Testcontainers eingebunden wird.
- Das Host-`~/.m2` wird als Maven-Repository eingebunden.
- Workspace, `data/home` und `log/` sind Bind-Mounts; `data/`, `log/` und
  `.env` sind in den Varianten gitignored.

### Native-Builds

Der Host hat eine neuere glibc als das Runtime-Image. Native-Binaries werden
deshalb **im Container** gebaut (`quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25`).

- Speicher-Cap setzen, sonst belegt GraalVM ~80 % des RAMs und der Host-OOM-
  Killer beendet den Build mit Exit 137:
  `-Dquarkus.native.native-image-xmx=8g`
- UI-Änderungen zuerst bauen: `(cd reactflow && npm run build:qoc)`
- Quarkus 3.40 benötigt Mandrel/JDK 25; die JDK-21-Mandrel-Images scheitern an
  der Versionsprüfung.

Die beiden nativen Dev-Varianten teilen sich **nicht** dasselbe Binary:

| Variante | Runner-Datei | OIDC im Binary |
|---|---|---|
| `docker-dev-native` | `quarkus/target/runner-dev` | nein |
| `docker-oidc-native` | `quarkus/target/runner-oidc` | ja |

Der Entrypoint liest `RUNNER_FILE` und kopiert nach einem Rebuild das frisch
erzeugte `*-runner` auf die jeweilige Datei. So überschreiben sich die
Container beim Start nicht gegenseitig. Die Runner-Dateien liegen unter
`quarkus/target/` (gitignored) und werden beim ersten Start bzw. Rebuild
erzeugt.

Beispiel für einen manuellen OIDC-Build (verkürzt; Details in
`docker-oidc-native/README.md`):

```bash
cd /home/torsten/dev/my-projects/2oc/qoc
docker run --rm \
  -v "$PWD":/app -v "$HOME/.m2:/home/qoc/.m2" \
  -e HOME=/tmp -e MAVEN_CONFIG=/home/qoc/.m2 \
  -e ENGINE_DB_KIND=postgresql -e ENGINE_DB_DRIVER=org.postgresql.Driver \
  -e ENGINE_FLYWAY_LOCATIONS=classpath:db/migration \
  -e QUARKUS_OIDC_ENABLED=true -u 1000:1000 \
  --entrypoint /bin/bash qoc-engine:dev-native \
  -lc 'cd /app && mvn -o -B -pl quarkus -am package -DskipTests -Djacoco.skip=true \
        -Dmaven.repo.local=/home/qoc/.m2/repository \
        -Dquarkus.package.type=native -Dquarkus.native.container-build=false \
        -Dquarkus.native.native-image-xmx=8g \
        -Dquarkus.arc.exclude-types=org.a2aproject.sdk.**'
cp quarkus/target/quarkus-*-runner quarkus/target/runner-oidc
```

Für die Dev-Variante denselben Build mit `QUARKUS_OIDC_ENABLED=false` und
Ziel `runner-dev` ausführen. Alternativ den Rebuild des Containers nutzen
(`DEV_NATIVE_REBUILD=always`, siehe unten).

---

## `docker-dev` – JVM-Entwicklung (Hot Reload)

Vollwertige Entwicklungsumgebung mit Quarkus Dev Mode, JDWP-Debug und
Docker-CLI/bwrap im Container.

| Eigenschaft | Wert |
|---|---|
| Image | `qoc-engine:dev` (`maven:3.9-eclipse-temurin-21` + Git, bwrap, Docker-CLI) |
| Container | `qoc-dev` |
| UI | <http://localhost:8070> |
| Debug | `localhost:5006` (JDWP) |
| Entrypoint | `mvn quarkus:dev` im Modul `quarkus/` |

Start:

```bash
./deploy/docker-dev/deploy.sh up      # baut Image und startet
./deploy/docker-dev/deploy.sh logs
./deploy/docker-dev/deploy.sh down
```

Wichtig:

- Änderungen an `deploy/.env` werden erst nach einem Recreate wirksam:
  `cd deploy/docker-dev && docker compose up -d --force-recreate qoc-dev`
- Das Compose setzt `ENGINE_PERMISSION_DEFAULT=allow-all`,
  `ENGINE_PERMISSION_ASK_MODE=interactive`, `ENGINE_MODEL_ID=glm-5.3-flash`
  und `ENGINE_WORKFLOW_STORAGE=file`.
- Die bwrap-Sandbox ist im Container deaktiviert
  (`ENGINE_SHELL_SANDBOX_ENABLED=false`), weil unprivilegierte Container keine
  Namespaces erzeugen können.
- Tests: `docker compose exec qoc-dev mvn test`

Details: `docker-dev/README.md`.

---

## `docker-dev-native` – Native ohne OIDC/Security

Schneller nativer Container **ohne Login und ohne Authentifizierung**. Gedacht
für lokale Tests, bei denen Keycloak/Auth0 keine Rolle spielen.

| Eigenschaft | Wert |
|---|---|
| Container | `qoc-dev-native` |
| UI/API | <http://localhost:8082> |
| Runner | `quarkus/target/runner-dev` |
| OIDC | aus (`DEV_NATIVE_OIDC=false`, `QUARKUS_OIDC_ENABLED=false`) |
| Security | `ENGINE_SECURITY_MODE=none` |
| Permissions | `ENGINE_PERMISSION_DEFAULT=allow-all` |
| Rebuild | `DEV_NATIVE_REBUILD=auto` (nur wenn Quellen neuer sind) |

Start:

```bash
./deploy/docker-dev-native/deploy.sh up
./deploy/docker-dev-native/deploy.sh rebuild   # erzwingt Native-Rebuild
./deploy/docker-dev-native/deploy.sh down
```

Der Login-Button erscheint **nicht**, weil `/config.js`
`oidcEnabled:false` liefert. API-Aufrufe sind ohne Token erlaubt.

Schnelltest:

```bash
curl -sS http://localhost:8082/api/health                  # -> 200
curl -sS http://localhost:8082/config.js                   # -> oidcEnabled:false
```

---

## `docker-oidc-native` – Native mit lokalem Keycloak

Native Engine mit OIDC-Validierung gegen ein lokales Keycloak
(Authorization Code + PKCE). Der Login-Button erscheint automatisch
(`/config.js` → `oidcEnabled:true`).

| Eigenschaft | Wert |
|---|---|
| Container | `qoc-oidc-native` |
| UI/API | <http://localhost:8081> |
| Runner | `quarkus/target/runner-oidc` |
| OIDC | an (`QUARKUS_OIDC_ENABLED=true`) |
| Security | `ENGINE_SECURITY_MODE=oidc` |
| Rebuild | `DEV_NATIVE_REBUILD=never` (schnelle Restarts) |
| Netzwerk | zusätzlich `qoc-keycloak_default` (Keycloak) |

Reihenfolge beim ersten Start:

```bash
# 1. Keycloak
cd deploy/keycloak && docker compose up -d

# 2. Realm, Clients und Test-User anlegen (idempotent)
cd /home/torsten/dev/my-projects/2oc/qoc
KEYCLOAK_URL=http://localhost:8180 ./deploy/keycloak/setup-keycloak.sh

# 3. Native Engine mit OIDC (Runner ggf. einmalig bauen, s.o.)
./deploy/docker-oidc-native/deploy.sh up
./deploy/docker-oidc-native/deploy.sh rebuild   # DEV_NATIVE_REBUILD=always
```

Aufruf: <http://localhost:8081> → „Login“ → `alice` / `alice-password`
(alternativ `bob` / `bob-password`, Rolle `admin`).

Wichtige Konfiguration:

- Browser-Issuer: `http://localhost:8180/realms/qoc`
- JWKS wird intern über `http://keycloak:8080/...` geladen, weil Keycloaks
  Discovery `localhost:8180` liefert und `localhost` im Container auf den
  Container selbst zeigt.
- Audience: `qoc-engine`

Schnelltest ohne Browser:

```bash
curl -s -X POST http://localhost:8180/realms/qoc/protocol/openid-connect/token \
  -d grant_type=password -d client_id=qoc-cli \
  -d username=alice -d password=alice-password \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])'
# Token als Bearer gegen http://localhost:8081/api/health -> 200
```

Ohne Token liefert `/api/*` **401** (erwartet).

Details: `docker-oidc-native/README.md`.

---

## `keycloak` – lokaler Identity-Provider

Keycloak 26.0 für lokale OIDC-Tests.

| Eigenschaft | Wert |
|---|---|
| Container | `keycloak-local` |
| URL | <http://localhost:8180> |
| Admin | `admin` / `admin` |
| Realm | `qoc` |
| Clients | `qoc-engine` (confidential), `qoc-ui` (public PKCE), `qoc-cli` (Direct Grant) |
| User | `alice` / `alice-password`, `bob` / `bob-password` (admin) |
| Netzwerk | `qoc-keycloak_default` (extern für `qoc-oidc-native`) |
| Volume | `qoc-keycloak-data` |

```bash
cd deploy/keycloak
docker compose up -d
# Realm/Clients/User:
cd /home/torsten/dev/my-projects/2oc/qoc
KEYCLOAK_URL=http://localhost:8180 ./deploy/keycloak/setup-keycloak.sh

# Aufräumen inkl. Realm-Daten:
cd deploy/keycloak && docker compose down -v
```

`KC_HOSTNAME=http://localhost:8180` ist bewusst gesetzt, damit Browser und
Backend denselben Issuer sehen. Die JWKS lädt das Backend intern über
`keycloak:8080`.

---

## `gcp-vm` – GCP-VM mit Datenbank (Produktion/Test)

Runbook und Skripte für den Betrieb der **nativen** Binary auf einer GCP-VM
(`quad-e2micro`, `us-central1-a`, Projekt `gen-lang-client-0960862878`) mit
**Neon PostgreSQL**, systemd und nginx/TLS.

| Eigenschaft | Wert |
|---|---|
| Dienst | systemd `qoc.service` |
| Binary | `/opt/qoc/qoc-runner` |
| Env | `/etc/qoc/env` |
| Datenbank | Neon PostgreSQL (`QUARKUS_DATASOURCE_JDBC_URL`) |
| Extern | nginx Reverse Proxy, TLS via Certbot |
| Intern | `127.0.0.1:8081` |
| Auth | Auth0/OIDC (empfohlen) oder HTTP Basic |
| Runbook | `gcp-vm/vm.md` |

Wichtige Dateien:

| Datei | Zweck |
|---|---|
| `vm.md` | vollständiges Runbook (Build, systemd, nginx, Backup, Rollback) |
| `gcp-auth0.sh` | schreibt OIDC-Werte nach `/etc/qoc/env` und `/etc/qoc/ui-config.js`, ruft `gcp-ssl.sh` |
| `gcp-auth0.env.example` | Vorlage für `gcp-auth0.env` (gitignored) |
| `gcp-ssl.sh` | DNS-/TLS-/nginx-Setup |
| `nginx.conf` | Beispiel-Reverse-Proxy (`X-QOC-User`) |
| `qoc-native.service` | systemd-Unit (native, DB-Modus) |
| `qoc.service` | systemd-Unit für JVM-Fat-Jar (Legacy) |
| `qoc-prepare.sh` | legt `/data/*` an und setzt Ownership |
| `gcp-sandbox-setup.sh` | einmaliges bwrap-Setup auf der VM |
| `check-prereqs.sh` | Voraussetzungen prüfen |

Ablauf (Kurzform):

```bash
cd qoc
# 1. OIDC/Auth0 konfigurieren
cp deploy/gcp-vm/gcp-auth0.env.example deploy/gcp-vm/gcp-auth0.env
# Werte anpassen, dann:
set -a; . deploy/gcp-vm/gcp-auth0.env; set +a
./deploy/gcp-vm/gcp-auth0.sh check
./deploy/gcp-vm/gcp-auth0.sh apply

# 2. Binary + systemd + nginx: siehe deploy/gcp-vm/vm.md
```

Die Engine ist ein reiner OIDC-Resource-Server (Bearer-Token-Validierung über
Auth0 Discovery/JWKS); es liegt **kein** Auth0-Client-Secret auf der VM. Die
UI bleibt öffentlich, damit der Browser den PKCE-Flow starten kann.

Hinweis: Im OIDC-Modus liefert `/api/health/live` ohne Token `401` – das ist
erwartet und kein Deployment-Fehler.

---

## `gcp-native-memory` – GCP-VM ohne Datenbank

Schlankes Deployment derselben VM-Infrastruktur, aber **komplett in-memory**:
keine Datenbank, keine Migration, kein Recovery. Workflow-**Definitionen**
liegen als Dateien unter `/data/wf`; Sessions und Runs leben im Heap.

| Aspekt | Wert |
|---|---|
| Dienst | systemd `qoc-memory.service` |
| Engine-Storage | `memory` |
| Workflow-Storage | `file` (`/data/wf`) |
| Recovery | deaktiviert |
| Datenbank | keine (keine JDBC-URL, kein Flyway) |
| Auth | Auth0/OIDC |
| Memory-Budget | `ENGINE_MEMORY_MAX_BYTES` (Default 1 GiB) |

Dateien:

| Datei | Zweck |
|---|---|
| `env.memory.example` | Vorlage für `/etc/qoc/env` |
| `qoc-memory.service` | systemd-Unit (native, memory) |
| `qoc-prepare-memory.sh` | legt `/data`, `/data/wf`, `/data/workspace`, `/data/home` an |
| `build-memory.sh` | nativer Build im Memory-Modus |
| `deploy.sh` | Provisioniert Binary, Env, Unit und Verzeichnisse auf der VM |

```bash
cd qoc
./deploy/gcp-native-memory/build-memory.sh
export GCP_PROJECT=gen-lang-client-0960862878
export GCP_ZONE=us-central1-a
export GCP_VM=quad-e2micro
./deploy/gcp-native-memory/deploy.sh
```

Auth0/OIDC anschließend wie bei `gcp-vm` über
`deploy/gcp-vm/gcp-auth0.sh` aktivieren. Nicht für Multi-Node oder
persistente Sessions geeignet – dafür `gcp-vm` (DB-Modus) nutzen.

Details: `gcp-native-memory/README.md`.

---

## `aws-ecs-minimal` – AWS ECS Fargate (Minimal-Teststack)

Minimaler Cloud-Teststack analog zum GCP-Memory-Modus: **native Binary**,
**keine Datenbank**, Workflow-Definitionen als Dateien auf EFS, Sessions im
Heap.

| Eigenschaft | Wert |
|---|---|
| Region | `us-east-1` |
| Cluster / Service | `qoc-test-cluster` / `qoc-test-service` |
| Task-Definition | `qoc-test-task` |
| ECR | `336129193871.dkr.ecr.us-east-1.amazonaws.com/qoc-test-native` |
| EFS | `fs-007960e4d7fc061bf`, Access Point `fsap-0223840081a0b6485` |
| Workspace | `/mnt/workspace` (EFS) |
| Workflow-Dateien | `/mnt/workspace/wf` (EFS) |
| Auth | HTTP Basic (`opencode` / `OPENCODE_SERVER_PASSWORD`) |
| Secret | `qoc-test-secrets` (Key `OPENCODE_SERVER_PASSWORD`) |

Deployment:

```bash
cd qoc
./deploy/gcp-native-memory/build-memory.sh        # Native Binary (Memory)
./deploy/aws-ecs-minimal/deploy-native.sh         # Image -> ECR, Task/Service
```

Die Task-IP ist ephemer; URL, Passwort und IP immer frisch aus AWS lesen
(siehe `aws-ecs-minimal/README.md`). `/api/health` und `/` benötigen Basic
Auth, `/config.js` ist öffentlich.

**Warum Basic Auth und kein OIDC?** Der Stack hat keinen ALB, keine stabile
Domain und kein TLS; Auth0-Callbacks brauchen aber eine stabile HTTPS-URL.
Für OIDC auf ECS zuerst ALB + ACM-Zertifikat ergänzen und die Binary mit
`QUARKUS_OIDC_ENABLED=true` bauen.

Lokale JVM/Postgres-Emulation (unabhängig vom ECS-Betrieb):

```bash
cd deploy/aws-ecs-minimal
cp .env.example .env    # Werte anpassen
docker compose up -d --build
curl -sS http://localhost:8080/api/health
```

Details: `aws-ecs-minimal/README.md`.

---

## `_deprecated` – nicht mehr verwenden

Enthält die frühere konsolidierte Compose-Datei mit vielen Profilen
(`qoc-local`, `qoc-memory`, `qoc-minimal`, `qoc-secure`, `qoc-native`, …)
sowie alte Bilder/Skripte. Diese Varianten werden nicht mehr gepflegt und
sind durch die eigenständigen Ordner oben ersetzt.

Die dortige `README.md` beschreibt noch den alten Stand
(`deploy/docker-compose.yml`); maßgeblich sind die READMEs der aktiven
Varianten.

---

## Schnellprüfung (laufende lokale Container)

```bash
# Welche Varianten laufen?
docker ps --format '{{.Names}}\t{{.Ports}}' | grep -E 'qoc-dev|qoc-oidc|keycloak'

# Native Dev (ohne Auth): 200
curl -sS http://localhost:8082/api/health
curl -sS http://localhost:8082/config.js        # oidcEnabled:false

# Native OIDC (mit Auth): 401 ohne Token
curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/health
curl -sS http://localhost:8081/config.js        # oidcEnabled:true

# JVM Dev: 200
curl -sS http://localhost:8070/api/health
```

Runner prüfen (welche Binary läuft wirklich?):

```bash
docker exec qoc-dev-native  readlink /proc/1/exe   # -> .../runner-dev
docker exec qoc-oidc-native readlink /proc/1/exe   # -> .../runner-oidc
```

Logs:

```bash
docker logs -f qoc-dev
docker logs -f qoc-dev-native
docker logs -f qoc-oidc-native
docker logs -f keycloak-local
```

---

## Weiterführende Dokumentation

- `docker-dev/README.md` – JVM-Entwicklung, Debugging, Testcontainers
- `docker-oidc-native/README.md` – Keycloak-Setup, OIDC-Details, Native-Build
- `keycloak/setup-keycloak.sh` – Realm/Clients/User (idempotent)
- `gcp-vm/README.md`, `gcp-vm/vm.md` – GCP-VM-Runbook (DB-Modus, Auth0, TLS)
- `gcp-native-memory/README.md` – GCP-VM ohne Datenbank
- `aws-ecs-minimal/README.md` – AWS ECS Fargate Minimal-Stack
- `../docs/` – Spezifikation, Architektur, Betriebs- und Qualitätsregeln
