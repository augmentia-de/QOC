# QOC – minimal local/Docker deployment

Build and run locally:

```bash
cd qoc
cp deploy/.env.example deploy/.env
# edit deploy/.env
./deploy/build-image.sh
cd deploy
docker compose up -d
```

`build-image.sh` builds the fast-jar with the SQLite database kind and driver
(both are build-time settings in Quarkus), then builds the image. A plain
`mvn package` produces a PostgreSQL build and the container will fail at
startup with `Driver does not support the provided URL: jdbc:sqlite:...`.
Tests also leave a test-profile build in `quarkus/target/quarkus-app`, so run
`build-image.sh` after `mvn verify` before recreating the container.

Open: http://127.0.0.1:${QOC_PORT:-8333}/

Data (SQLite DB, workspace, logs) is stored in the `qoc-data` Docker volume.
Plan mode files are written to `HOME` (`/data/home`), i.e.
`/data/home/.opencode/plan` inside the volume. The compose file sets `HOME`
explicitly because the container user has no `/etc/passwd` entry, which would
otherwise make Java fall back to `/` and break plan writes.

## Native image

```bash
cd qoc
mvn -DskipTests package -Dnative     # requires GraalVM
cd deploy
INSTALL_GIT=true docker compose -f docker-compose.native.yml up -d --build
```

`INSTALL_GIT` (default `false`) installs git for the worktree tools. On the
Debian native base it adds about **88 MiB** (git plus the Perl stack): the
image grows from ~263 MiB to ~351 MiB. Without git the worktree tools return a
clear "git is not available" failure; everything else works. The JVM image
uses Alpine git (~9 MiB) and always includes it.
