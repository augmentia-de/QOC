# Native Deployment (Docker, local)

Status: implemented and verified (Engine binary + Docker image + UI).
Date: 2026-11-11

## Goal

Deploy QOC locally as a **native** container:

```bash
cd qoc/deploy
docker compose --profile native up -d --build
```

| Container | URL | Role |
|---|---|---|
| `qoc-native` | http://localhost:8077 | Engine (GraalVM native binary) |
| `qoc-native-ui` | http://localhost:5174 | Workflow UI (nginx), `/api` → `qoc-native:8080` |

## Initial Problem

`deploy/Dockerfile.native` was no longer usable:

1. **Wrong builder version.** The base image
   `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21` contains GraalVM
   23.1, while Quarkus 3.40 requires 25.0.
2. **No Maven in the builder.** The Mandrel image has no `mvn` in `PATH`
   (`ENTRYPOINT` is `native-image`), so the build failed with
   `mvn: command not found`; the error was swallowed by `| tail -5`.
3. **`FileDescriptor` in the image heap.** After fixing (1) and (2),
   `native-image` failed:

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

## Root Cause

Historically, `workflow-runner` was built as a standalone server and therefore
packaged as an **Uber-JAR** using `maven-shade-plugin`. That artifact
contained:

- `de/augmentia/oc/engine/**` (751 classes, duplicated relative to the
  `engine` JAR),
- `org/slf4j/simple/**` (its own SLF4J binding), and
- further libraries (Jackson, swagger-parser, …).

Since the Quarkus integration, `workflow-runner` is a **library** and is
consumed as such by the `quarkus` module. As a result, the embedded classes
ended up in the native image. `org.slf4j.simple.SimpleLoggerConfiguration`
initializes itself while evaluating `SystemPropertyUtil` and holds
`System.err` (`PrintStream` → `FileOutputStream` → `FileDescriptor`) in a
static field. GraalVM forbids this in the image heap.

Incidentally, this also explains CSC/indexing errors such as
`Producer method return type not found in index:
de.augmentia.oc.engine.core.contract.provider.ProviderAdapter`, because
`engine` classes were present twice (JAR + Uber-JAR) on the classpath.

## Changes

### `workflow-runner/pom.xml`

- `maven-shade-plugin` **removed** → the module again delivers a normal
  library JAR (no `engine` classes, no third-party JARs).
- `slf4j-simple` → **`slf4j-api`**. The binding comes from the host
  (Quarkus/JBoss LogManager). A bundled binding is wrong in a library and
  breaks native images.
- The sources continue to use only `org.slf4j.Logger`/`LoggerFactory`.

### `deploy/Dockerfile.native`

- Builder: `quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25`
  (Mandrel 25, matching Quarkus 3.40).
- Maven 3.9 is taken over via a multi-stage copy from
  `maven:3.9-eclipse-temurin-21` (the UBI modules only provide Maven 3.5).
- The build runs in the builder image (`-Dquarkus.native.container-build=false`).
- No more hiding errors behind `| tail -5`.
- Runtime stage remains slim (Debian + git/util-linux/bubblewrap),
  `USER qoc`, `VOLUME /data`, `HOME=/data/home`.
- `deploy/Dockerfile.native.dockerignore` keeps build outputs, `data/` and
  `node_modules` out of the build context (BuildKit uses
  `<Dockerfile>.dockerignore`).

### UI integration

- `workflow-ui/vite.config.ts`: `/api` is forwarded **unchanged** to
  `VITE_API_TARGET` (default `http://localhost:8080`). The previous `rewrite`
  stripped `/api`, but the engine expects `/api/...`.
- `deploy/Dockerfile.workflow-ui`: nginx template with `API_UPSTREAM`,
  `proxy_pass` without a trailing slash (path is preserved), SSE-friendly
  (`proxy_buffering off`, long `proxy_read_timeout`).
- `deploy/docker-compose.yml`: new service `qoc-native-ui` (profile
  `native`, port 5174), `API_UPSTREAM=http://qoc-native:8080`.
- `workflow-ui/.dockerignore` prevents host `node_modules` from overwriting
  the container's `npm install`.

### Dev scripts

- `scripts/start-all.sh` starts the engine via `start.sh`
  (Quarkus Dev Mode, `/api/health`) and the UI with a matching
  `VITE_API_TARGET`.
- `scripts/stop-all.sh` stops `quarkus:dev` and Vite.

## Two Build Paths

| Path | Command | When |
|---|---|---|
| Reproducible, self-contained | `docker compose --profile native build qoc-native` | CI/fresh machine, sufficient bandwidth |
| Local, fast | `./deploy/build-native-image.sh` | Development; uses the host's `~/.m2` |

`build-native-image.sh` compiles the runner in the Mandrel-25 dev image
(`deploy/docker-dev-native`, host `~/.m2` mounted) and packages it into a slim
runtime image via `deploy/Dockerfile.native-runtime`. The self-contained build,
by contrast, re-downloads dependencies in the builder stage (several hours on
slow links).

## Verification

1. Native binary built (Mandrel 25 in the container):
   `quarkus/target/quarkus-1.0.0-SNAPSHOT-runner` (124 MB).
2. Binary started on the host against PostgreSQL (`localhost:20000`):
   - `GET /api/health` → `{"status":"ok",...}`
   - `GET /api/workflows` → existing workflows
   - `GET /api/mcp-client/servers` → `{"servers":[]}`
   - `GET /api/mcp-client/tools` → Built-in tools incl. MCP
3. `docker compose --profile native up -d` starts `qoc-native` (8077) and
   `qoc-native-ui` (5174).
4. Verified via the UI (nginx → Engine):
   - `GET :5174/api/health` → `ok`
   - `GET :5174/api/workflows` → `["test-wf2","test-wf"]` (from the
     mounted `../wf-data`)
   - Browser: `/workflows` lists both workflows, `/mcp-servers` renders
     "No MCP servers configured" (API reachable).
5. Tests: `mvn -o -pl engine,workflow-runner test` → 919 + 3 tests green.

## Open Items

- The self-contained Docker build re-downloads Maven dependencies in the
  builder stage. A BuildKit cache mount
  (`--mount=type=cache,target=/root/.m2`) or a named context on
  `~/.m2/repository` would speed up rebuilds considerably.
- `deploy/Dockerfile.workflow-runner` and `deploy/docker-compose.workflow.yml`
  still describe the old standalone runner and are no longer part of
  operations. They can be removed once nobody needs the standalone mode
  anymore.
- `workflow-runner/src/main/java/.../WorkflowRunnerMain.java` is dead code as
  a result; it can be removed together with the two files above.