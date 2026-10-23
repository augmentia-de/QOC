#!/bin/bash
set -euo pipefail

DATA_DIR="${DATA_DIR:-/var/lib/qoc}"
WORKSPACE_DIR="${WORKSPACE_DIR:-/var/lib/qoc/workspaces}"

# Create directories and set ownership
mkdir -p "$DATA_DIR"/{data,workspaces,log}
chown -R qoc:qoc "$DATA_DIR"
chmod 750 "$DATA_DIR"
chmod 700 "$DATA_DIR/data"
chmod 750 "$DATA_DIR/workspaces"
chmod 750 "$DATA_DIR/log"

# Dynamically set project directories from env
if [ -n "${WORKSPACE_DIRS:-}" ]; then
    IFS=':' read -ra DIRS <<< "$WORKSPACE_DIRS"
    for d in "${DIRS[@]}"; do
        if [ -d "$d" ]; then
            chown -R qoc:qoc "$d" 2>/dev/null || true
            chmod -R u+rwX,go-rwx "$d"
        fi
    done
fi

echo "Preparation done."