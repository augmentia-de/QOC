# QOC – minimal local/Docker deployment

Build and run locally:

```bash
cd qoc
cp deploy/.env.example deploy/.env
# edit deploy/.env
mvn -q -pl quarkus package -DskipTests
cd deploy
docker compose up -d --build
```

Open: http://127.0.0.1:${QOC_PORT:-8333}/

Data (SQLite DB, workspace, logs) is stored in the `qoc-data` Docker volume.
