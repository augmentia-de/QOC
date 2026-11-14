#!/usr/bin/env bash
#
# Quick check of the build and deploy toolchain for GCP.
#
# Usage:
#   ./deploy/gcp-vm/check-prereqs.sh          # lokal (Build + Deploy)
#   ./deploy/gcp-vm/check-prereqs.sh --remote  # auf der VM (Laufzeit)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/gcp-common.sh"

REMOTE=false
if [[ "${1:-}" == "--remote" ]]; then
    REMOTE=true
fi

fail=0

check() {
    local cmd="$1" hint="$2"
    if command -v "$cmd" >/dev/null 2>&1; then
        echo "  OK   $cmd ($(command -v "$cmd"))"
    else
        echo "  MISSING $cmd -- $hint"
        fail=1
    fi
}

if [[ "$REMOTE" == "false" ]]; then
    echo "=== Build toolchain (local) ==="
    check java "Installiere Java 21 (GraalVM empfohlen)."
    check mvn "Installiere Maven 3.9+."
    check native-image "Installiere GraalVM native-image."
    check gcloud "Installiere gcloud CLI."
    check curl "Installiere curl."
    check dig "Installiere dnsutils/bind-utils."
    check python3 "Install python3 (for certbot validation)."

    echo ""
    echo "=== Versionen ==="
    java -version 2>&1 | head -1 || true
    mvn -version 2>&1 | head -1 || true
    native-image --version 2>&1 | head -1 || true
    gcloud --version 2>&1 | head -1 || true
else
    echo "=== Runtime (VM) ==="
    check systemctl "systemd erforderlich."
    check curl "curl erforderlich."
    check nginx "nginx erforderlich (TLS/Proxy)."
    require_service qoc || fail=1
    require_env_file /etc/qoc/env || fail=1
    require_file /opt/qoc/qoc-runner "Native Binary fehlt. Siehe vm.md." || fail=1
    require_executable /opt/qoc/qoc-runner "Binary not executable." || fail=1

    echo ""
    echo "=== Optionale Tools ==="
    optional_cmd certbot "TLS setup not possible (certbot missing)."
    optional_cmd python3 "Certificate validation limited."
    optional_cmd git "Worktree-Tools deaktiviert."
    optional_cmd bwrap "Shell sandbox disabled (fallback to direct executiong)."
fi

echo ""
if [[ "$fail" -ne 0 ]]; then
    echo "ERROR: required tools missing. See hints above."
    exit 1
fi
echo "OK: all required tools present."
