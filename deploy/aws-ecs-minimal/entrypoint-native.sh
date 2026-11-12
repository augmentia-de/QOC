#!/bin/sh
# Runtime entrypoint for the native QOC image on ECS Fargate.
# The container runs as 1000:1000; EFS is mounted at /mnt/workspace.
set -e

mkdir -p "$ENGINE_WORKFLOW_STORAGE_DIR" \
         "$ENGINE_WORKSPACE/plans" \
         "$HOME/.opencode/agents" \
         "$LOG_DIR"

# Match the GCP VM deployment: install the native-specific workspace rules.
if [ -f /app/AGENTS.native.md ]; then
    cp /app/AGENTS.native.md "$ENGINE_WORKSPACE/AGENTS.md"
fi

exec /app/qoc-runner
