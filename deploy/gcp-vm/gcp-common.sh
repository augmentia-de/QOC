#!/usr/bin/env bash
#
# Gemeinsame Prüfhelfer für die GCP-Deployment-Skripte.
# Wird von gcp-auth0.sh, gcp-ssl.sh, qoc-prepare.sh und
# gcp-sandbox-setup.sh gesourct.

# require_cmd <cmd> <hint>
# Bricht mit klarer Fehlermeldung ab, wenn das Kommando fehlt.
require_cmd() {
    local cmd="$1" hint="${2:-}"
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "ERROR: '$cmd' nicht gefunden. ${hint}" >&2
        return 1
    fi
}

# optional_cmd <cmd> <hint>
# Gibt 0 zurück, wenn vorhanden, 1 + Warnung, wenn fehlend.
optional_cmd() {
    local cmd="$1" hint="${2:-}"
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "WARN: '$cmd' nicht gefunden. ${hint}" >&2
        return 1
    fi
    return 0
}

# require_service <name>
require_service() {
    local svc="$1"
    if ! systemctl is-active --quiet "$svc" 2>/dev/null; then
        echo "ERROR: systemd-Service '$svc' ist nicht aktiv." >&2
        return 1
    fi
}

# require_env_file <path>
require_env_file() {
    local f="${1:-/etc/qoc/env}"
    if [[ ! -r "$f" ]]; then
        echo "ERROR: $f existiert nicht oder ist nicht lesbar." >&2
        return 1
    fi
}

# require_file <path> <hint>
require_file() {
    local f="$1" hint="${2:-}"
    if [[ ! -f "$f" ]]; then
        echo "ERROR: Datei '$f' fehlt. ${hint}" >&2
        return 1
    fi
}

# require_executable <path> <hint>
require_executable() {
    local f="$1" hint="${2:-}"
    if [[ ! -x "$f" ]]; then
        echo "ERROR: '$f' ist nicht ausführbar. ${hint}" >&2
        return 1
    fi
}
