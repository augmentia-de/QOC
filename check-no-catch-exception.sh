#!/bin/bash
# CI check: no 'catch (Exception' in main source (S-6: three error channels).
# Legitimate uses in scanner/discovery are documented exceptions.
set -euo pipefail
cd "$(dirname "$0")"
FOUND=$(grep -rn 'catch (Exception' engine/src/main/java/ || true)
if [ -n "$FOUND" ]; then
    echo "WARNING: 'catch (Exception' found in main source:"
    echo "$FOUND"
    echo ""
    echo "Review each occurrence: is it at a tool boundary (forbidden) or in"
    echo "scanner/discovery (acceptable)? Document exceptions here."
    # Exit 0 (warning only) until all are cleaned up; change to exit 1 to enforce.
    exit 0
fi
echo "OK: no 'catch (Exception' in main source"