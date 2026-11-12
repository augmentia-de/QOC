#!/usr/bin/env bash
# deploy/aws-lambda-serverless/deploy.sh
#
# Deploy QOC as an AWS Lambda function with a Function URL.
# The function fetches agent configurations from a remote server
# (GCP VM) and runs prompts statelessly.
#
# Prerequisites:
#   - AWS CLI v2 configured (aws configure)
#   - Docker installed
#   - Native image built: cd qoc && mvn -Dnative package -DskipTests
#   - ECR repository created (or set CREATE_ECR=true)
#
# Usage:
#   export ENGINE_AGENT_SOURCE_URL=https://gcp-vm.example.com
#   export ENGINE_OIDC_ISSUER=https://dev-xxx.us.auth0.com/
#   export ENGINE_OIDC_AUDIENCE=qoc-engine
#   ./deploy.sh
#
# Configurable via environment variables (see below).

set -euo pipefail

AWS_REGION="${AWS_REGION:-us-east-1}"
FUNCTION_NAME="${FUNCTION_NAME:-qoc-serverless}"
ECR_REPO="${ECR_REPO:-qoc-serverless-native}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
MEMORY_MB="${MEMORY_MB:-512}"
EPHEMERAL_STORAGE_MB="${EPHEMERAL_STORAGE_MB:-512}"
TIMEOUT_SEC="${TIMEOUT_SEC:-300}"
CREATE_ECR="${CREATE_ECR:-false}"

ENGINE_AGENT_SOURCE_URL="${ENGINE_AGENT_SOURCE_URL:-}"
ENGINE_AGENT_AUTHORING_ENABLED="${ENGINE_AGENT_AUTHORING_ENABLED:-false}"

ENGINE_SECURITY_MODE="${ENGINE_SECURITY_MODE:-oidc}"
ENGINE_OIDC_ISSUER="${ENGINE_OIDC_ISSUER:-}"
ENGINE_OIDC_AUDIENCE="${ENGINE_OIDC_AUDIENCE:-qoc-engine}"

HOME_DIR="${HOME_DIR:-/tmp/home}"

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_URI="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPO}"

echo "=== QOC Lambda Serverless Deployment ==="
echo "Region:       ${AWS_REGION}"
echo "Function:     ${FUNCTION_NAME}"
echo "Memory:       ${MEMORY_MB} MB"
echo "Ephemeral:    ${EPHEMERAL_STORAGE_MB} MB"
echo "Timeout:      ${TIMEOUT_SEC} s"
echo "Agent source: ${ENGINE_AGENT_SOURCE_URL:-<not set>}"
echo ""

NATIVE_BINARY="quarkus/target/*-runner"
if ! ls $NATIVE_BINARY 1>/dev/null 2>&1; then
    echo "Native binary not found. Building..."
    cd "$(dirname "$0")/../.."
    mvn -o -pl quarkus -am clean package -Dnative -DskipTests -Dquarkus.profile=prod,native -Dquarkus.oidc.enabled=true
    cd - >/dev/null
fi

if [ "$CREATE_ECR" = "true" ]; then
    echo "Creating ECR repository ${ECR_REPO}..."
    aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION" 2>/dev/null || true
fi

echo "Logging in to ECR..."
aws ecr get-login-password --region "$AWS_REGION" | \
    docker login --username AWS --password-stdin "${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

echo "Building container image..."
docker build -f "$(dirname "$0")/Dockerfile.lambda" \
    -t "${ECR_URI}:${IMAGE_TAG}" \
    "$(dirname "$0")/../.."

echo "Pushing to ECR..."
docker push "${ECR_URI}:${IMAGE_TAG}"

ENV_VARS="ENGINE_MODE=serverless,ENGINE_SECURITY_MODE=${ENGINE_SECURITY_MODE},HOME=${HOME_DIR},ENGINE_WORKSPACE=/tmp/workspace,ENGINE_SHELL_SANDBOX_ENABLED=false,ENGINE_MAX_CONCURRENT_PROMPTS=1,ENGINE_TURN_MAX_WALL_MINUTES=10,ENGINE_AGENT_AUTHORING_ENABLED=${ENGINE_AGENT_AUTHORING_ENABLED}"

if [ -n "$ENGINE_AGENT_SOURCE_URL" ]; then
    ENV_VARS="${ENV_VARS},ENGINE_AGENT_SOURCE_URL=${ENGINE_AGENT_SOURCE_URL}"
fi
if [ -n "$ENGINE_OIDC_ISSUER" ]; then
    ENV_VARS="${ENV_VARS},QUARKUS_OIDC_AUTH_SERVER_URL=${ENGINE_OIDC_ISSUER},QUARKUS_OIDC_TOKEN_ISSUER=${ENGINE_OIDC_ISSUER},QUARKUS_OIDC_TOKEN_AUDIENCE=${ENGINE_OIDC_AUDIENCE}"
fi

FUNCTION_EXISTS=$(aws lambda get-function --function-name "$FUNCTION_NAME" --region "$AWS_REGION" 2>/dev/null && echo "yes" || echo "no")

if [ "$FUNCTION_EXISTS" = "yes" ]; then
    echo "Updating existing function..."
    aws lambda update-function-code \
        --function-name "$FUNCTION_NAME" \
        --image-uri "${ECR_URI}:${IMAGE_TAG}" \
        --region "$AWS_REGION"
    aws lambda wait function-updated --function-name "$FUNCTION_NAME" --region "$AWS_REGION"

    aws lambda update-function-configuration \
        --function-name "$FUNCTION_NAME" \
        --memory-size "$MEMORY_MB" \
        --timeout "$TIMEOUT_SEC" \
        --ephemeral-storage "Size=${EPHEMERAL_STORAGE_MB}" \
        --environment "Variables={${ENV_VARS}}" \
        --region "$AWS_REGION"
else
    echo "Creating new function..."
    ROLE_ARN="${LAMBDA_ROLE_ARN:-arn:aws:iam::${ACCOUNT_ID}:role/lambda-execution-role}"
    aws lambda create-function \
        --function-name "$FUNCTION_NAME" \
        --package-type Image \
        --code "ImageUri=${ECR_URI}:${IMAGE_TAG}" \
        --role "$ROLE_ARN" \
        --memory-size "$MEMORY_MB" \
        --timeout "$TIMEOUT_SEC" \
        --ephemeral-storage "Size=${EPHEMERAL_STORAGE_MB}" \
        --environment "Variables={${ENV_VARS}}" \
        --region "$AWS_REGION"
    aws lambda wait function-active --function-name "$FUNCTION_NAME" --region "$AWS_REGION"
fi

URL_CONFIG=$(aws lambda get-function-url-config --function-name "$FUNCTION_NAME" --region "$AWS_REGION" 2>/dev/null || echo "none")
if [ "$URL_CONFIG" = "none" ]; then
    echo "Creating Function URL..."
    aws lambda create-function-url-config \
        --function-name "$FUNCTION_NAME" \
        --auth-type NONE \
        --region "$AWS_REGION"
    aws lambda add-permission \
        --function-name "$FUNCTION_NAME" \
        --statement-id FunctionURLAllowPublicAccess \
        --action lambda:InvokeFunctionUrl \
        --principal "*" \
        --function-url-auth-type NONE \
        --region "$AWS_REGION" 2>/dev/null || true
fi

FUNCTION_URL=$(aws lambda get-function-url-config --function-name "$FUNCTION_NAME" --region "$AWS_REGION" --query FunctionUrl --output text)
echo ""
echo "=== Deployment complete ==="
echo "Function URL: ${FUNCTION_URL}"
echo "MCP endpoint: ${FUNCTION_URL}mcp"
echo ""
echo "Free Tier note: Lambda free tier = 1M requests/month + 400K GB-seconds/month."
echo "At ${MEMORY_MB}MB RAM: ~$((400000 / MEMORY_MB)) seconds/month free."
