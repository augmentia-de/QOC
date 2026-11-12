# AWS ECS Fargate – Minimaler Test-Stack (Neon + Auth0/JVM)

## Lokale Emulation

```bash
cd deploy/aws-ecs-minimal
cp .env.example .env
# .env anpassen (DB, Auth, Provider)
docker compose up -d --build
curl -sS http://localhost:8080/api/health
```

## AWS-Deployment (Terraform)

```bash
cd deploy/aws-ecs-minimal
eval "$(aws configure export-credentials --format env)"

# terraform.tfvars befüllen (db_jdbc_url=Neon, auth-mode, secrets)
terraform plan -out tfplan
terraform apply tfplan

# Task-IP ermitteln (siehe ../AWS-OAUTH.md)
TASK=$(aws ecs list-tasks --cluster qoc-test-cluster --service-name qoc-test-service --region us-east-1 --desired-status RUNNING --query 'taskArns[0]' --output text)
ENI=$(aws ecs describe-tasks --cluster qoc-test-cluster --tasks "$TASK" --region us-east-1 --query 'tasks[0].attachments[0].details[?name==`networkInterfaceId`].value | [0]' --output text)
aws ec2 describe-network-interfaces --network-interface-ids "$ENI" --region us-east-1 --query 'NetworkInterfaces[0].Association.PublicIp' --output text
```

## Auth-Modi

- **Basic (intern)**: `ENGINE_SECURITY_MODE=basic`, `OPENCODE_SERVER_PASSWORD` setzen.
- **Auth0 (OIDC)**: `ENGINE_SECURITY_MODE=oidc`, Auth0-Variablen setzen (siehe `.env.example`).

## Datenbank

- **Lokal**: Postgres-Container (`postgres:16`).
- **Neon**: `QUARKUS_DATASOURCE_JDBC_URL` auf Neon-JDBC-URL setzen; IP-Whitelist beachten.

## Kosten stoppen

```bash
terraform destroy -auto-approve
```

## Hinweise

- Native Image für Fargate: `deploy/aws-ecs-minimal/Dockerfile` (GraalVM).
- JVM-Image für schnelle Iteration: `Dockerfile.jvm`.
- Auth0 Callback-URLs müssen die Task-IP oder Domain enthalten.
- Agenten mit `rolesAllowed` sind standardmäßig `admin`-gebunden (außer `build`/`plan`).
