#!/bin/bash
set -euo pipefail

DATA_DIR="${DATA_DIR:-/data}"
WORKSPACE_DIR="${WORKSPACE_DIR:-/data/workspace}"
WF_DIR="${WF_DIR:-/data/wf}"
QOC_HOME="${QOC_HOME:-/data/home}"

mkdir -p "$DATA_DIR" "$WORKSPACE_DIR" "$WF_DIR" "$QOC_HOME" "$DATA_DIR/log"
chown -R qoc:qoc "$DATA_DIR" "$WORKSPACE_DIR" "$WF_DIR" "$QOC_HOME" "$DATA_DIR/log"
chmod 750 "$DATA_DIR"
chmod 750 "$WORKSPACE_DIR"
chmod 750 "$WF_DIR"
chmod 750 "$QOC_HOME"
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

echo "Preparation done (memory mode)."
