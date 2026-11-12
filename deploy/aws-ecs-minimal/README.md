# AWS ECS Fargate – Minimaler Test-Stack (Native, Memory-Modus)

Betrieb analog zur GCP-VM im Memory-Modus: **native Binary**, **keine
Datenbank**, Workflow-Definitionen als Dateien auf EFS, Engine-Sessions im Heap.

| Eigenschaft | Wert |
|---|---|
| Region | `us-east-1` |
| Cluster | `qoc-test-cluster` |
| Service | `qoc-test-service` |
| Task-Definition | `qoc-test-task` |
| ECR | `336129193871.dkr.ecr.us-east-1.amazonaws.com/qoc-test-native` |
| EFS | `fs-007960e4d7fc061bf`, Access Point `fsap-0223840081a0b6485` |
| Workspace | `/mnt/workspace` (EFS) |
| Workflow-Dateien | `/mnt/workspace/wf` (EFS) |
| Task-Größe | 0.5 vCPU / 1 GB |
| Auth | HTTP Basic (`opencode` / `OPENCODE_SERVER_PASSWORD`) |
| Secret | `qoc-test-secrets` (Key `OPENCODE_SERVER_PASSWORD`) |

## Native Image bauen und deployen

```bash
cd qoc

# 1. Native Binary im Memory-Modus bauen (GraalVM/Mandrel erforderlich)
./deploy/gcp-native-memory/build-memory.sh

# 2. Image bauen, nach ECR pushen, Task-Definition + Service rollen
./deploy/aws-ecs-minimal/deploy-native.sh
```

Das Skript liest die LLM-Werte aus `deploy/.env` (gitignored) und behält die
Secrets `OPENAI_API_KEY` und `OPENCODE_SERVER_PASSWORD` aus der bestehenden
Task-Definition bei. Es benötigt keinen Terraform-State.

Nach dem Rollout zeigt das Skript die öffentliche Task-IP an:

```bash
curl -u "opencode:$PASS" http://<IP>:8080/api/health
curl -sS http://<IP>:8080/config.js
```

Die Security Group erlaubt Port 8080 nur aus dem konfigurierten CIDR
(`my_ip_cidr`, Default `0.0.0.0/0`). Für einen Test von einem anderen Host
dessen `/32` temporär eintragen.

## Warum Basic Auth und kein Auth0?

Der Minimal-Stack hat keinen ALB, keine Domain und kein TLS. Die öffentliche
Task-IP ist ephemer; Auth0-Callback-URLs benötigen eine stabile HTTPS-URL.
OIDC läuft deshalb auf der GCP-VM hinter nginx/TLS. Für OIDC auf ECS zuerst
einen ALB + ACM-Zertifikat (oder eine Domain mit TLS) ergänzen und die Binary
mit `QUARKUS_OIDC_ENABLED=true` bauen.

## URL, Passwort und Task-IP ermitteln

Die Task-IP ist ephemer und ändert sich bei jedem Task-Neustart. URL,
Passwort und IP daher immer frisch aus AWS lesen:

```bash
CLUSTER=qoc-test-cluster
SERVICE=qoc-test-service
REGION=us-east-1
SECRET=qoc-test-secrets

TASK=$(aws ecs list-tasks --cluster "$CLUSTER" --service-name "$SERVICE" \
  --region "$REGION" --desired-status RUNNING --query 'taskArns[0]' --output text)
ENI=$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$TASK" \
  --region "$REGION" \
  --query 'tasks[0].attachments[0].details[?name==`networkInterfaceId`].value | [0]' \
  --output text)
IP=$(aws ec2 describe-network-interfaces --network-interface-ids "$ENI" \
  --region "$REGION" --query 'NetworkInterfaces[0].Association.PublicIp' --output text)
PASS=$(aws secretsmanager get-secret-value --secret-id "$SECRET" \
  --region "$REGION" --query SecretString --output text | jq -r .OPENCODE_SERVER_PASSWORD)

echo "URL:      http://$IP:8080/"
echo "Benutzer: opencode"
echo "Passwort: $PASS"
```

Für die Passwort-Extraktion wird `jq` benötigt. Die Testbefehle im selben
Shell-Kontext ausführen:

```bash
curl -u "opencode:$PASS" "http://$IP:8080/api/health"
curl -sS "http://$IP:8080/config.js"
```

`/` und `/api/health` benötigen Basic Auth; `/config.js` ist öffentlich.
Falls `curl` timeoutet, die eigene IP in der Security Group für Port 8080
freigeben. Das Passwort ist sensibel und gehört nicht ins Repository.

## Betrieb

```bash
# Logs
aws logs tail /ecs/qoc-test --follow --region us-east-1

# Rollback auf eine frühere native Revision (z. B. :4)
aws ecs update-service --cluster qoc-test-cluster --service qoc-test-service \
  --task-definition qoc-test-task:<revision> --force-new-deployment --region us-east-1

# Hinweis: Revision :3 referenzierte :latest; dieser Tag zeigt inzwischen auf
# das Native-Image. Das alte Image ist als pre-native-memory getaggt. Für einen
# echten Rollback darauf eine Task-Definition mit diesem Tag registrieren.

# Kosten stoppen
aws ecs update-service --cluster qoc-test-cluster --service qoc-test-service \
  --desired-count 0 --region us-east-1
```

## Lokale Emulation (JVM/Postgres, optional)

```bash
cd deploy/aws-ecs-minimal
cp .env.example .env
# .env anpassen (DB, Auth, Provider)
docker compose up -d --build
curl -sS http://localhost:8080/api/health
```

Die lokale Compose-Datei nutzt weiterhin `Dockerfile.jvm` und PostgreSQL. Sie
ist unabhängig vom nativen ECS-Betrieb.

## Hinweise

- `deploy-native.sh` ist die Quelle der Wahrheit für die laufende Task-Definition.
  `main.tf` beschreibt denselben Native-Memory-Zielzustand; ohne Terraform-State
  muss der bestehende Stack importiert oder neu aufgebaut werden.
- Die native Binary wird auf dem Build-Host erzeugt. Das Runtime-Image nutzt
  `ubuntu:24.04`, damit die glibc-Version zum Build-Host passt.
- Shell-Sandbox ist auf Fargate deaktiviert (`ENGINE_SHELL_SANDBOX_ENABLED=false`).
- EFS-Workspace und Workflow-Dateien überleben Task-Neustarts; Sessions und
  Runs nicht (Memory-Modus by design).
