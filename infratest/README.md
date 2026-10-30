# infratest

Standalone Quarkus module demonstrating database access and REST CRUD for a
single table with an `id` primary key.

## Stack

- Java 21, Maven, Quarkus `3.40.1.1`
- Quarkus REST + Jackson
- Hibernate ORM with Panache
- H2 database (file based in dev/prod-like runs, in-memory in tests)
- Flyway for schema migrations

## Table

`infratest_entry`

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGINT` | generated identity, primary key |
| `name` | `VARCHAR(255)` | required, not blank |
| `content` | `VARCHAR(1024)` | optional |

The migration lives in
`src/main/resources/db/migration/V1__create_infratest_entry.sql`.

## REST API

| Method | Path | Behaviour |
|---|---|---|
| `GET` | `/infratest` | list all entries ordered by `id` |
| `GET` | `/infratest/{id}` | read one entry; `404` when missing |
| `POST` | `/infratest` | create; returns `201` with `Location` |
| `PUT` | `/infratest/{id}` | replace `name` and `content`; `404` when missing |
| `DELETE` | `/infratest/{id}` | delete; `204` or `404` |

Request and response bodies:

```json
{
  "id": 1,
  "name": "example",
  "content": "arbitrary text"
}
```

`id` is ignored on input; it is generated on `POST` and taken from the path on
`PUT`/`DELETE`.

## Run

```bash
cd qoc
mvn -pl infratest quarkus:dev
```

The service listens on `http://127.0.0.1:8090`; the H2 database file is
created under `data/infratest.mv.db`.

## Test

```bash
cd qoc
mvn -pl infratest test
```

Tests use an isolated in-memory H2 database and exercise the full CRUD
lifecycle, validation errors and unknown identifiers.

## Cloud Run (free tier)

Prerequisites: authenticated `gcloud`, Docker, Java 21 and Maven.

```bash
cd qoc/infratest
./deploy-cloudrun.sh
```

The script builds the fast-jar, pushes the image to Artifact Registry
(`us-central1-docker.pkg.dev/<project>/infratest/infratest:<timestamp>`) and
deploys the Cloud Run service `infratest` in `us-central1`.

Deployment settings:

| Setting | Value |
|---|---|
| Region | `us-central1` (Tier 1 / free-tier pricing) |
| CPU / memory | 1 vCPU / 512 MiB |
| Min / max instances | `0` / `1` |
| Concurrency | 10 |
| Authentication | public, unauthenticated |
| Database | in-memory H2 via the `cloudrun` profile |

### Important: ephemeral data

Cloud Run is stateless. The H2 in-memory database is recreated on every cold
start and on every new revision; **data does not survive**. This is intentional
for the infrastructure test. For durable data, use Cloud SQL/PostgreSQL or the
existing e2-micro VM.

### Free-tier checks

Cloud Run and Compute Engine have separate always-free allowances:

- **Cloud Run** (request-based, `us-central1`): 2 million requests,
  180,000 vCPU-seconds and 360,000 GiB-seconds per month.
- **Compute Engine**: one non-preemptible `e2-micro` VM plus 30 GB standard
  disk in `us-west1`, `us-central1` or `us-east1`. The existing
  `quad-e2micro` already consumes this allowance, so a second e2-micro is
  **not** free.

Artifact Registry adds 0.5 GB of free storage per month; the image is well
below that limit.

### Current deployment

- URL: <https://infratest-6npdzlxhhq-uc.a.run.app>
- Example: `curl https://infratest-6npdzlxhhq-uc.a.run.app/infratest`

### CRUD smoke test

`smoke-test.sh` creates an entry, reads it by id, updates it, reads it again,
deletes it and verifies the resulting `404`. It needs `curl` and `jq` and
cleans up the created entry if a step fails.

```bash
cd qoc/infratest
./smoke-test.sh                                  # deployed service
BASE_URL=http://127.0.0.1:8090 ./smoke-test.sh   # local service
./smoke-test.sh https://my-service.run.app       # explicit URL
```
