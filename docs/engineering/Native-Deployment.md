# Native Deployment (Docker, lokal)

Status: umgesetzt und verifiziert (Engine-Binary + Docker-Image + UI).
Datum: 2026-11-11

## Ziel

QOC lokal als **native** Container deployen:

```bash
cd qoc/deploy
docker compose --profile native up -d --build
```

| Container | URL | Rolle |
|---|---|---|
| `qoc-native` | http://localhost:8077 | Engine (GraalVM-Native-Binary) |
| `qoc-native-ui` | http://localhost:5174 | Workflow-UI (nginx), `/api` → `qoc-native:8080` |

## Ausgangsproblem

`deploy/Dockerfile.native` war nicht mehr benutzbar:

1. **Falsche Builder-Version.** Die Basis
   `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21` enthält GraalVM
   23.1, Quarkus 3.40 verlangt 25.0.
2. **Kein Maven im Builder.** Das Mandrel-Image hat keinen `mvn` im `PATH`
   (`ENTRYPOINT` ist `native-image`), der Build scheiterte mit
   `mvn: command not found`; der Fehler wurde durch `| tail -5` verschluckt.
3. **`FileDescriptor` im Image-Heap.** Nach Korrektur von (1) und (2) brach
   `native-image` ab:

   ```
   Detected a FileDescriptor in the image heap.
     reading field java.io.FileOutputStream.fd of constant FileOutputStream
     reading field java.io.FilterOutputStream.out of constant PrintStream
     reading field org.slf4j.simple.OutputChoice.targetPrintStream
     reading field org.slf4j.simple.SimpleLoggerConfiguration.outputChoice
   scanning root SimpleLoggerConfiguration embedded in
     SimpleLogger.innerHandleNormalizedLoggingCall(SimpleLogger.java:385)
   at io.netty.util.internal.SystemPropertyUtil.getInt(...)
   at io.netty.buffer.PooledByteBufAllocator.<clinit>(...)
   ```

## Ursache

`workflow-runner` wurde historisch als eigenständiger Server gebaut und
deshalb mit `maven-shade-plugin` als **Uber-JAR** gepackt. Das Artefakt
enthielt:

- `de/augmentia/oc/engine/**` (751 Klassen, doppelt zum `engine`-JAR),
- `org/slf4j/simple/**` (eigenes SLF4J-Binding) und
- weitere Bibliotheken (Jackson, swagger-parser, …).

Seit der Quarkus-Integration ist `workflow-runner` eine **Library** und wird
als solches vom `quarkus`-Modul konsumiert. Dadurch landeten die eingebetteten
Klassen im Native-Image. `org.slf4j.simple.SimpleLoggerConfiguration`
initialisiert sich beim Auswerten von `SystemPropertyUtil` und hält dabei
`System.err` (`PrintStream` → `FileOutputStream` → `FileDescriptor`) in einem
statischen Feld. GraalVM verbietet das im Image-Heap.

Nebenbei erklärt das auch CSC-/Indexierungsfehler wie
`Producer method return type not found in index:
de.augmentia.oc.engine.core.contract.provider.ProviderAdapter`, weil
`engine`-Klassen doppelt (JAR + Uber-JAR) im Klassenpfad lagen.

## Änderungen

### `workflow-runner/pom.xml`

- `maven-shade-plugin` **entfernt** → das Modul liefert wieder ein normales
  Library-JAR (keine `engine`-Klassen, keine Fremd-JARs).
- `slf4j-simple` → **`slf4j-api`**. Das Binding kommt vom Host
  (Quarkus/JBoss LogManager). Ein gebündeltes Binding ist in einer Library
  falsch und bricht Native-Images.
- Die Quellen nutzen weiterhin nur `org.slf4j.Logger`/`LoggerFactory`.

### `deploy/Dockerfile.native`

- Builder: `quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25`
  (Mandrel 25, passend zu Quarkus 3.40).
- Maven 3.9 wird per Multi-Stage-Copy aus `maven:3.9-eclipse-temurin-21`
  übernommen (die UBI-Module liefern nur Maven 3.5).
- Build läuft im Builder-Image (`-Dquarkus.native.container-build=false`).
- Keine Fehler mehr hinter `| tail -5` verstecken.
- Runtime-Stage unverändert schlank (Debian + git/util-linux/bubblewrap),
  `USER qoc`, `VOLUME /data`, `HOME=/data/home`.
- `deploy/Dockerfile.native.dockerignore` hält Build-Outputs, `data/` und
  `node_modules` aus dem Build-Context (BuildKit nutzt
  `<Dockerfile>.dockerignore`).

### UI-Anbindung

- `workflow-ui/vite.config.ts`: `/api` wird **unverändert** an
  `VITE_API_TARGET` (Default `http://localhost:8080`) weitergeleitet. Der
  bisherige `rewrite` entfernte `/api`, die Engine erwartet aber `/api/...`.
- `deploy/Dockerfile.workflow-ui`: nginx-Template mit `API_UPSTREAM`,
  `proxy_pass` ohne Trailing-Slash (Pfad bleibt erhalten), SSE-freundlich
  (`proxy_buffering off`, langer `proxy_read_timeout`).
- `deploy/docker-compose.yml`: neuer Service `qoc-native-ui` (Profil
  `native`, Port 5174), `API_UPSTREAM=http://qoc-native:8080`.
- `workflow-ui/.dockerignore` verhindert, dass Host-`node_modules` das
  Container-`npm install` überschreiben.

### Dev-Skripte

- `scripts/start-all.sh` startet die Engine über `start.sh`
  (Quarkus Dev Mode, `/api/health`) und die UI mit passendem
  `VITE_API_TARGET`.
- `scripts/stop-all.sh` beendet `quarkus:dev` und Vite.

## Zwei Build-Wege

| Weg | Kommando | Wann |
|---|---|---|
| Reproduzierbar, self-contained | `docker compose --profile native build qoc-native` | CI/frische Maschine, genug Bandbreite |
| Lokal, schnell | `./deploy/build-native-image.sh` | Entwicklung; nutzt den Host-`~/.m2` |

`build-native-image.sh` kompiliert den Runner im Mandrel-25-Dev-Image
(`deploy/docker-dev-native`, Host-`~/.m2` gemountet) und packt ihn über
`deploy/Dockerfile.native-runtime` in ein schlankes Runtime-Image. Der
self-contained Build lädt die Abhängigkeiten dagegen in der Builder-Stage
neu (auf langsamen Leitungen mehrere Stunden).

## Verifikation

1. Native Binary gebaut (Mandrel 25 im Container):
   `quarkus/target/quarkus-1.0.0-SNAPSHOT-runner` (124 MB).
2. Binary auf dem Host gegen PostgreSQL (`localhost:20000`) gestartet:
   - `GET /api/health` → `{"status":"ok",...}`
   - `GET /api/workflows` → vorhandene Workflows
   - `GET /api/mcp-client/servers` → `{"servers":[]}`
   - `GET /api/mcp-client/tools` → Built-in-Tools inkl. MCP
3. `docker compose --profile native up -d` startet `qoc-native` (8077) und
   `qoc-native-ui` (5174).
4. Über die UI (nginx → Engine) geprüft:
   - `GET :5174/api/health` → `ok`
   - `GET :5174/api/workflows` → `["test-wf2","test-wf"]` (aus dem
     gemounteten `../wf-data`)
   - Browser: `/workflows` listet beide Workflows, `/mcp-servers` rendert
     „No MCP servers configured“ (API erreichbar).
5. Tests: `mvn -o -pl engine,workflow-runner test` → 919 + 3 Tests grün.

## Offene Punkte

- Der self-contained Docker-Build lädt Maven-Abhängigkeiten in der
  Builder-Stage erneut. Eine BuildKit-Cache-Mount
  (`--mount=type=cache,target=/root/.m2`) oder ein Named Context auf
  `~/.m2/repository` würde Rebuilds deutlich beschleunigen.
- `deploy/Dockerfile.workflow-runner` und `deploy/docker-compose.workflow.yml`
  beschreiben noch den alten Standalone-Runner und sind nicht mehr Teil des
  Betriebs. Sie können entfernt werden, sobald niemand mehr den Standalone-
  Modus braucht.
- `workflow-runner/src/main/java/.../WorkflowRunnerMain.java` ist damit toter
  Code; er kann zusammen mit den beiden Dateien oben entfernt werden.
