#!/usr/bin/env bash
# GCP setup for the QOC shell sandbox (bwrap/bubblewrap).
# Run once on the VM as root: sudo bash deploy/gcp-vm/gcp-sandbox-setup.sh
# Does NOT deploy QOC; only prepares the OS for sandboxed shell execution.
set -euo pipefail

echo "=== install bubblewrap ==="
apt-get update
apt-get install -y --no-install-recommends bubblewrap acl

echo "=== create restricted shell user (no login, not in qoc group) ==="
if ! id qoc-shell >/dev/null 2>&1; then
    useradd --system --no-create-home --shell /usr/sbin/nologin qoc-shell
fi

echo "=== grant workspace access via ACL (qoc-shell can traverse+write /data/workspace) ==="
setfacl -m u:qoc-shell:rx /data
setfacl -m u:qoc-shell:rwx /data/workspace
# Default ACL so new files inherit
setfacl -d -m u:qoc-shell:rwx /data/workspace

echo "=== verify bwrap works ==="
if sudo -u qoc-shell bwrap -- sh -c 'echo bwrap-ok' 2>&1 | grep -q bwrap-ok; then
    echo "bwrap works as qoc-shell"
else
    # try unprivileged (as qoc user, which owns /data)
    if sudo -u qoc bwrap -- sh -c 'echo bwrap-ok' 2>&1 | grep -q bwrap-ok; then
        echo "bwrap works as qoc"
    else
        echo "WARNING: bwrap does not work; sandbox will fall back to direct execution"
    fi
fi

echo "=== done ==="
echo "To enable: set ENGINE_SHELL_SANDBOX_ENABLED=true (default) and optionally"
echo "ENGINE_SHELL_SANDBOX_UID=$(id -u qoc-shell) ENGINE_SHELL_SANDBOX_GID=$(id -g qoc-shell)"
echo "in /etc/qoc/env, then restart qoc."
