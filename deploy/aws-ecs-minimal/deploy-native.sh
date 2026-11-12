#!/usr/bin/env bash
# Deploy the native QOC runner (memory mode) to the existing ECS Fargate stack.
#
# Prerequisites:
#   - AWS CLI credentials for the target account
#   - Docker
#   - quarkus/target/*-runner built by deploy/gcp-native-memory/build-memory.sh
#
# The script builds and pushes a new image, registers a new task definition
# revision with the in-memory/no-DB environment and Basic Auth, and rolls the
# service. It does not need the original Terraform state.
#
# Usage:
#   ./deploy/aws-ecs-minimal/deploy-native.sh
#   AUTH_MODE=basic ./deploy/aws-ecs-minimal/deploy-native.sh
#
# OIDC/Auth0 is intentionally not enabled here: the minimal stack exposes the
# task's ephemeral public IP over plain HTTP, which cannot be a stable Auth0
# callback URL. Use the GCP VM (nginx + TLS) for OIDC, or add an ALB/domain and
# build the runner with QUARKUS_OIDC_ENABLED=true first.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

REGION="${AWS_REGION:-us-east-1}"
CLUSTER="${ECS_CLUSTER:-qoc-test-cluster}"
SERVICE="${ECS_SERVICE:-qoc-test-service}"
REPO_NAME="${ECR_REPO:-qoc-test-native}"
TASK_FAMILY="${TASK_FAMILY:-qoc-test-task}"
AUTH_MODE="${AUTH_MODE:-basic}"
IMAGE_TAG="${IMAGE_TAG:-native-memory-$(date +%Y%m%d-%H%M%S)}"

if [ "$AUTH_MODE" != "basic" ]; then
    echo "This script deploys Basic Auth only; AUTH_MODE=$AUTH_MODE is not supported." >&2
    echo "Build with OIDC and use an HTTPS endpoint/ALB for OIDC." >&2
    exit 2
fi

RUNNER="$(ls "$REPO_ROOT"/quarkus/target/*-runner 2>/dev/null | head -n 1 || true)"
if [ -z "$RUNNER" ]; then
    echo "No native runner found under quarkus/target/." >&2
    echo "Run ./deploy/gcp-native-memory/build-memory.sh first." >&2
    exit 1
fi
echo "Native runner: $RUNNER"

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
ECR_URI="$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/$REPO_NAME"

if ! aws ecr describe-repositories --repository-names "$REPO_NAME" --region "$REGION" >/dev/null 2>&1; then
    echo "Creating ECR repository $REPO_NAME ..."
    aws ecr create-repository --repository-name "$REPO_NAME" --region "$REGION" >/dev/null
fi

echo "Logging in to $ECR_URI ..."
aws ecr get-login-password --region "$REGION" \
    | docker login --username AWS --password-stdin "$ECR_URI"

echo "Building native runtime image $ECR_URI:$IMAGE_TAG ..."
docker build \
    -f "$SCRIPT_DIR/Dockerfile.native" \
    -t "$ECR_URI:$IMAGE_TAG" \
    -t "$ECR_URI:latest" \
    "$REPO_ROOT"

echo "Pushing image ..."
docker push "$ECR_URI:$IMAGE_TAG"
docker push "$ECR_URI:latest"

# --- LLM settings from deploy/.env (gitignored), with ECS defaults ----------
read_env() {
    local key="$1" fallback="$2" value=""
    if [ -f "$REPO_ROOT/deploy/.env" ]; then
        value="$(grep -E "^${key}=" "$REPO_ROOT/deploy/.env" 2>/dev/null | head -1 | cut -d= -f2- || true)"
    fi
    printf '%s' "${value:-$fallback}"
}

OPENAI_BASE_URL="$(read_env OPENAI_BASE_URL 'https://opencode.ai/zen/go/v1')"
ENGINE_PROVIDER_URL="$(read_env ENGINE_PROVIDER_URL 'https://opencode.ai/zen/go/v1/chat/completions')"
OPENAI_MODEL="$(read_env OPENAI_MODEL 'deepseek-v4.1-flash')"
ENGINE_MODEL_ID="$(read_env ENGINE_MODEL_ID "$OPENAI_MODEL")"
ENGINE_PROVIDER_SCRIPTED="$(read_env ENGINE_PROVIDER_SCRIPTED 'false')"
ENGINE_PROVIDER_TYPE="$(read_env ENGINE_PROVIDER_TYPE 'http')"
ENGINE_MODEL_PROVIDER="$(read_env ENGINE_MODEL_PROVIDER 'openai')"

ENV_JSON="$(jq -n \
    --arg openai_base "$OPENAI_BASE_URL" \
    --arg provider_url "$ENGINE_PROVIDER_URL" \
    --arg openai_model "$OPENAI_MODEL" \
    --arg model_id "$ENGINE_MODEL_ID" \
    --arg scripted "$ENGINE_PROVIDER_SCRIPTED" \
    --arg provider_type "$ENGINE_PROVIDER_TYPE" \
    --arg model_provider "$ENGINE_MODEL_PROVIDER" \
    '[
      {"name":"QUARKUS_HTTP_HOST","value":"0.0.0.0"},
      {"name":"QUARKUS_HTTP_PORT","value":"8080"},
      {"name":"QUARKUS_OTEL_ENABLED","value":"false"},
      {"name":"QUARKUS_LOG_FILE_ENABLED","value":"true"},
      {"name":"QUARKUS_LOG_FILE_PATH","value":"/tmp/qoc.log"},
      {"name":"QUARKUS_FLYWAY_ENABLED","value":"false"},
      {"name":"QUARKUS_DATASOURCE_JDBC_URL","value":""},
      {"name":"ENGINE_WORKSPACE","value":"/mnt/workspace"},
      {"name":"HOME","value":"/mnt/workspace/.home"},
      {"name":"LOG_DIR","value":"/tmp/log"},
      {"name":"ENGINE_STORAGE","value":"memory"},
      {"name":"ENGINE_RECOVERY_ENABLED","value":"false"},
      {"name":"ENGINE_WORKFLOW_STORAGE","value":"file"},
      {"name":"ENGINE_WORKFLOW_STORAGE_DIR","value":"/mnt/workspace/wf"},
      {"name":"ENGINE_WORKFLOW_RECOVERY_ENABLED","value":"false"},
      {"name":"ENGINE_WORKFLOW_MAX_STEPS","value":"100"},
      {"name":"ENGINE_WORKFLOW_MAX_RESUME_ATTEMPTS","value":"10"},
      {"name":"ENGINE_MEMORY_MAX_BYTES","value":"536870912"},
      {"name":"ENGINE_SECURITY_MODE","value":"basic"},
      {"name":"ENGINE_SHELL_SANDBOX_ENABLED","value":"false"},
      {"name":"ENGINE_REVERT_SNAPSHOT","value":"false"},
      {"name":"ENGINE_MAX_CONCURRENT_PROMPTS","value":"4"},
      {"name":"ENGINE_TURN_MAX_WALL_MINUTES","value":"10"},
      {"name":"ENGINE_PROVIDER_SCRIPTED","value":$scripted},
      {"name":"ENGINE_PROVIDER_TYPE","value":$provider_type},
      {"name":"ENGINE_MODEL_PROVIDER","value":$model_provider},
      {"name":"OPENAI_BASE_URL","value":$openai_base},
      {"name":"ENGINE_PROVIDER_URL","value":$provider_url},
      {"name":"OPENAI_MODEL","value":$openai_model},
      {"name":"ENGINE_MODEL_ID","value":$model_id}
    ]')"

echo "Reading current task definition $TASK_FAMILY ..."
CURRENT="$(aws ecs describe-task-definition --task-definition "$TASK_FAMILY" --region "$REGION" --query 'taskDefinition' --output json)"
SECRETS="$(echo "$CURRENT" | jq '[.containerDefinitions[0].secrets[] | select(.name == "OPENAI_API_KEY" or .name == "OPENCODE_SERVER_PASSWORD")]')"
if [ "$(echo "$SECRETS" | jq 'length')" -lt 2 ]; then
    echo "Expected OPENAI_API_KEY and OPENCODE_SERVER_PASSWORD in the current task definition." >&2
    echo "Current secrets: $(echo "$CURRENT" | jq -c '[.containerDefinitions[0].secrets[].name]')" >&2
    exit 1
fi

NEW_TASKDEF="$(echo "$CURRENT" | jq \
    --arg image "$ECR_URI:$IMAGE_TAG" \
    --argjson env "$ENV_JSON" \
    --argjson secrets "$SECRETS" \
    'del(.taskDefinitionArn,.revision,.status,.requiresAttributes,.compatibilities,.registeredAt,.registeredBy,.deregisteredAt)
     | .containerDefinitions[0].image = $image
     | .containerDefinitions[0].environment = $env
     | .containerDefinitions[0].secrets = $secrets
     | .containerDefinitions[0].readonlyRootFilesystem = true')"

TASKDEF_FILE="/tmp/qoc-taskdef-native.json"
echo "$NEW_TASKDEF" > "$TASKDEF_FILE"
echo "Registering task definition ..."
NEW_ARN="$(aws ecs register-task-definition \
    --cli-input-json "file://$TASKDEF_FILE" \
    --region "$REGION" \
    --query 'taskDefinition.taskDefinitionArn' \
    --output text)"
echo "New task definition: $NEW_ARN"

echo "Updating service $SERVICE ..."
aws ecs update-service \
    --cluster "$CLUSTER" \
    --service "$SERVICE" \
    --task-definition "$NEW_ARN" \
    --force-new-deployment \
    --region "$REGION" >/dev/null

echo "Waiting for the service to stabilize ..."
if ! aws ecs wait services-stable --cluster "$CLUSTER" --services "$SERVICE" --region "$REGION"; then
    echo "Service did not stabilize. Recent events:" >&2
    aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" --region "$REGION" \
        --query 'services[0].events[0:10].message' --output text >&2 || true
    exit 1
fi

TASK="$(aws ecs list-tasks --cluster "$CLUSTER" --service-name "$SERVICE" --region "$REGION" --desired-status RUNNING --query 'taskArns[0]' --output text)"
ENI="$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$TASK" --region "$REGION" \
    --query 'tasks[0].attachments[0].details[?name==`networkInterfaceId`].value | [0]' --output text)"
IP="$(aws ec2 describe-network-interfaces --network-interface-ids "$ENI" --region "$REGION" \
    --query 'NetworkInterfaces[0].Association.PublicIp' --output text)"

echo
echo "=== Native ECS deployment complete ==="
echo "Image:    $ECR_URI:$IMAGE_TAG"
echo "Task:     $NEW_ARN"
echo "URL:      http://$IP:8080/"
echo "User:     opencode (password from secret qoc-test-secrets/OPENCODE_SERVER_PASSWORD)"
echo
echo "The security group only allows 8080 from the configured CIDR. To verify from"
echo "another host, add its /32 to the ECS security group."
