# AWS ECS Fargate – Minimal Test Stack

> **Status:** implemented (Test)
> **Source:** `deploy/aws-ecs-minimal/*.tf`, `deploy/AWS-OAUTH.md`

## Infrastructure

| Component | Value |
|---|---|
| Account | `336129193871` |
| Region | `us-east-1` |
| Cluster | `qoc-test-cluster` |
| Service | `qoc-test-service` |
| ECR | `qoc-test-native` |
| Secret | `qoc-test-secrets` |
| EFS | `fs-007960e4d7fc061bf` |
| Access Point | `fsap-0223840081a0b6485` |
| Workspace | `/mnt/workspace` |
| Task size | 0.5 vCPU / 1 GB |

## Operations

```bash
cd deploy/aws-ecs-minimal
eval "$(aws configure export-credentials --format env)"

# Determine task IP
TASK=$(aws ecs list-tasks --cluster qoc-test-cluster --service-name qoc-test-service --region us-east-1 --desired-status RUNNING --query 'taskArns[0]' --output text)
ENI=$(aws ecs describe-tasks --cluster qoc-test-cluster --tasks "$TASK" --region us-east-1 --query 'tasks[0].attachments[0].details[?name==`networkInterfaceId`].value | [0]' --output text)
aws ec2 describe-network-interfaces --network-interface-ids "$ENI" --region us-east-1 --query 'NetworkInterfaces[0].Association.PublicIp' --output text

# Health check
curl -sS -u "opencode:$PASS" http://<IP>:8080/api/health

# Stop costs
terraform destroy -auto-approve
```

## Cost (approx.)

~$0.03/h, ~$0.75/day, ~$22-23/month (Fargate + Public IP + EFS + Secrets).

## Security

See `06-security-hardening.md`. Additionally:
- No TLS/HTTPS (HTTP + Basic Auth)
- No ALB/WAF
- Task has a public IP (no private subnets)
- Shell sandbox disabled

## Auth0 OIDC Plan

See `spec/betrieb/04-oidc-auth0.md` and `deploy/AWS-OAUTH.md`.

## New: Neon + Auth0 + JVM (Minimal Stack)

- **Database**: Neon Free-Tier Postgres (`db_jdbc_url` set to the Neon JDBC URL).
- **Auth**: Auth0 OIDC (`ENGINE_SECURITY_MODE=oidc`, `QUARKUS_OIDC_*`).
- **Image**: JVM image (`Dockerfile.jvm`) for fast iteration; native for production.
- **Local emulation**: `deploy/aws-ecs-minimal/docker-compose.yml` + `.env.example`.
- **Roles**: `build`/`plan` = `*`, all others = `admin` (default) or explicit roles.

Operational commands: see `deploy/aws-ecs-minimal/README.md`.
```

Wait code blocks preserved. Need no extra trailing? Fine.<|end|><|start|>","+xml<|message|><rfl:parameter name="path">
/home/torsten/dev/my-projects/2oc/qoc/docs/deployment/04-aws-ecs-minimal.md