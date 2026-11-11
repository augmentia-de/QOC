# QOC – Entwicklungsumgebung (Port 8070)

Hot-Reload-Entwicklung mit Quarkus Dev Mode. Die Definition liegt in der
konsolidierten Datei `deploy/docker-compose.yml` (Profil `dev`); dieser Ordner
enthält nur Image und Entrypoint.

## Start

```bash
cd deploy
cp .env.example .env      # OPENAI_API_KEY eintragen
docker compose up -d --build qoc-dev
```

- UI: http://localhost:8070/
- Debug: `localhost:5006` (JDWP)
- Logs: `docker compose logs -f qoc-dev`

Änderungen an `deploy/.env` werden erst nach `docker compose up -d --force-recreate qoc-dev`
wirksam.

## LLM

Das Dev-Profil setzt `ENGINE_MODEL_ID=glm-5.3-flash` und
`ENGINE_PERMISSION_DEFAULT=allow-all`. Alles andere kommt aus `deploy/.env`.

- `ENGINE_PROVIDER_SCRIPTED=false` schaltet den Mock-Provider ab.
- Fehlt ein gültiger Key, startet der Container trotzdem; der erste Prompt
  schlägt dann mit einem Provider-Fehler fehl.

## JVM-Mode (schneller Start, kein Hot-Reload)

```bash
cd deploy
docker compose -f docker-compose.yml -f docker-compose.dev-jvm.yml up -d --build qoc-dev
```

## OIDC (Keycloak)

```bash
cd deploy
docker compose -f docker-compose.yml -f docker-compose.oidc.yml up -d qoc-dev
```

Keycloak: http://localhost:8180 (admin/admin), Realm `qoc`, Client `qoc-ui`
(public, Redirect `http://localhost:8070/*`).

## Debugging

- Debug-Port: `5006` (JDWP), gemappt auf `localhost:5006`.
- VS Code: `deploy/docker-dev/.devcontainer/devcontainer.json` → "Reopen in Container".
- IntelliJ: Remote Debug auf `localhost:5006`.

## Tests

```bash
docker compose exec qoc-dev mvn test
```

## Shell & Berechtigungen

- Die bwrap-Sandbox ist deaktiviert (`ENGINE_SHELL_SANDBOX_ENABLED=false`):
  Unprivilegierte Docker-Container können hier keine Namespaces erzeugen.
- `ENGINE_PERMISSION_DEFAULT=allow-all` gilt auch für den eingebauten
  `build`-Agent. Shell-Kommandos wie `mvn package` oder `java -jar …` laufen
  damit ohne Rückfrage. (Vorher überschrieb der Least-Privilege-Satz des
  Build-Agents das globale Allow-All und löste für jedes Nicht-Lese-Kommando
  einen Freigabe-Dialog aus.)
- `ENGINE_PERMISSION_ASK_MODE=interactive` greift nur noch, wenn eine Regel
  tatsächlich `ask` liefert — z. B. beim Lesen von `.env`-Dateien oder bei
  expliziten Agent-/Workspace-Regeln.
- Strengeres Verhalten: `ENGINE_PERMISSION_DEFAULT=least-privilege` setzen;
  dann fragt der Build-Agent wieder für alle Kommandos außer
  `ls`, `cat`, `git status/log/diff`.

## Testcontainers

```bash
cd deploy
docker compose -f docker-compose.yml -f docker-compose.docker-socket.yml up -d qoc-dev
```

## Hinweise

- `data/`, `log/` und `.env` sind gitignored.
- Der Container läuft als `UID:GID` (Default `1000:1000`).
- `DOCKER_GID` ist die GID der Docker-Gruppe (`stat -c '%g' /var/run/docker.sock`).
- Das Host-`~/.m2` wird nach `/home/ubuntu/.m2` gemountet.
- `docker-dev/data/home` enthält Agent-Definitionen und Plan-Dateien.
- Der Workspace ist `qoc/data/workspace` (Host) → `/data/workspace`.
