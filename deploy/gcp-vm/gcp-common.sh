#!/usr/bin/env bash
#
# Shared check helpers for the GCP deployment scripts.
# Wird von gcp-auth0.sh, gcp-ssl.sh, qoc-prepare.sh und
# gcp-sandbox-setup.sh gesourct.

# require_cmd <cmd> <hint>
# Bricht mit klarer Fehlermeldung ab, wenn das Kommando fehlt.
require_cmd() {
    local cmd="$1" hint="${2:-}"
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "ERROR: '$cmd' not found. ${hint}" >&2
        return 1
    fi
}

# optional_cmd <cmd> <hint>
# Returns 0 if present, 1 + warning if missing.
optional_cmd() {
    local cmd="$1" hint="${2:-}"
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "WARN: '$cmd' not found. ${hint}" >&2
        return 1
    fi
    return 0
}

# require_service <name>
require_service() {
    local svc="$1"
    if ! systemctl is-active --quiet "$svc" 2>/dev/null; then
        echo "ERROR: systemd service '$svc' is not active." >&2
        return 1
    fi
}

# require_env_file <path>
require_env_file() {
    local f="${1:-/etc/qoc/env}"
    if [[ ! -r "$f" ]]; then
        echo "ERROR: $f does not exist or is not readable." >&2
        return 1
    fi
}

# require_file <path> <hint>
require_file() {
    local f="$1" hint="${2:-}"
    if [[ ! -f "$f" ]]; then
        echo "ERROR: file '$f' missing. ${hint}" >&2
        return 1
    fi
}

# require_executable <path> <hint>
require_executable() {
    local f="$1" hint="${2:-}"
    if [[ ! -x "$f" ]]; then
        echo "ERROR: '$f' is not executable. ${hint}" >&2
        return 1
    fi
}
