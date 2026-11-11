# QOC CLI

Command-line client for the QOC engine (REST/SSE). Java 21, Picocli 4.7.7.

## Build

```bash
cd qoc
mvn -pl cli -am package -DskipTests
```

Erzeugt `cli/target/qoc-cli-1.0.0-SNAPSHOT.jar` (shaded, ~3.7 MB) mit Main-Class `de.augmentia.qoc.cli.QocCli`.

## Start

```bash
java -jar cli/target/qoc-cli-1.0.0-SNAPSHOT.jar --help
```

## Globale Optionen

Die Optionen gehören zum jeweiligen Subcommand (nicht zum Root):

```bash
java -jar qoc-cli-1.0.0-SNAPSHOT.jar health -u http://localhost:8081
```

| Option | Env | Beschreibung |
|---|---|---|
| `-u, --url` | `QOC_URL` | QOC-Server-Base-URL (Default: `http://localhost:8080`) |
| `-t, --token` | `QOC_TOKEN` | Bearer-Token für OIDC-Modus |

## Befehle

### health

```bash
qoc health -u http://localhost:8081
```

Prüft `GET /api/health`.

### session

```bash
qoc session list -u http://localhost:8081
qoc session create -a build -u http://localhost:8081
qoc session show <id> -u http://localhost:8081
qoc session delete <id> -u http://localhost:8081
```

Session-ID wird bei `create` in `~/.qoc/session` gespeichert und von `run`/`chat` wiederverwendet.

### run

```bash
qoc run -u http://localhost:8081 -a build "Erkläre kurz, was QOC ist."
```

Einmaliger Prompt; Ausgabe auf stdout, Metadaten auf stderr.

### chat

```bash
qoc chat -u http://localhost:8081 -a build
```

Interaktive REPL (JLine). Eingabe leer oder `exit`/`quit` beendet.

### agent

```bash
qoc agent list -u http://localhost:8081
```

### model

```bash
qoc model list -u http://localhost:8081
```

Gibt das rohe JSON der `/api/model`-Antwort aus.

## Hinweise

- Der CLI-Default-Port ist `8080`; Deployments laufen typischerweise auf `8070`/`8071`/`8081`. Bitte `-u` setzen.
- `run` und `chat` streamen Live-Events über `GET /api/event` (SSE): Text-Deltas werden sofort ausgegeben, Tool-/Subagent-Events auf stderr.
- Es gibt keine Tests im Modul `cli` (Stand 2026-11-09).
- TLS/Custom-CA und Retry-Logik sind nicht implementiert.

## Verwandtes

- `deploy/README.md` – Deployment-Varianten und Ports
- `docs/settings.md` – Konfiguration des Servers