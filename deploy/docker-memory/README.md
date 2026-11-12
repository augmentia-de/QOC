# In-Memory Runtime (Port 8082)

Vollständige Engine ohne Datenbank: alle Storage- und Koordinations-Ports
liegen im Heap des Containers. Die Definition liegt in
`deploy/docker-compose.yml` (Profil `memory`).

## Start

```bash
cd qoc
./deploy/build-image.sh          # baut qoc-engine:local
cd deploy
docker compose --profile memory up -d qoc-memory
```

- UI: http://localhost:8082/
- Kein PostgreSQL, keine Flyway-Migration, kein Restart-Recovery.

## Eigenschaften

- `ENGINE_STORAGE=memory`
- `ENGINE_RECOVERY_ENABLED=false` – Recovery ist im Memory-Modus nicht verfügbar
- `ENGINE_REVERT_SNAPSHOT=true` – Snapshots liegen ebenfalls im Heap
- `ENGINE_MEMORY_MAX_BYTES=314572800` (300 MiB)
- `ENGINE_MODE=server`, Agent-Authoring aktiv (kein Serverless-Limit)
- `ENGINE_SECURITY_MODE=none` – nur für lokale/vertrauenswürdige Nutzung
- `/data/workspace`, `/data/home`, `/data/log` und `/tmp` sind tmpfs; nach
  einem Neustart ist alles weg
- `QUARKUS_DATASOURCE_JDBC_URL` wird bewusst auf einen leeren Wert gesetzt.
  Dadurch bleibt die DataSource inaktiv, auch wenn `deploy/.env` weiterhin
  PostgreSQL-Werte enthält.

## Grenzen

| Nicht verfügbar | Grund |
|---|---|
| Restart/Recovery | Zustand liegt nur im Heap |
| Multi-Node / HA | Kein geteilter Store |
| Audit über Prozessgrenzen | Keine persistierten Events |
| Asynchrone, durable Approvals | Benötigen die Datenbank |

Approvals und Fragen funktionieren innerhalb des Prozesses über die Web-UI.
Die asynchrone Approval-Variante (`ENGINE_APPROVAL_ASYNC_ENABLED=true`) bleibt
im Memory-Modus fail-closed.

## Warum nicht `qoc-minimal`?

`qoc-minimal` ist eine Serverless-Rolle mit tmpfs-Workspace, nutzt aber
weiterhin PostgreSQL aus `deploy/.env` und deaktiviert Agent-Authoring.
`qoc-memory` ist dagegen die vollständige Engine mit ausschließlich
In-Memory-Storage.
