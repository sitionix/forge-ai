#!/usr/bin/env bash
set -euo pipefail

# Stable callback transport wrapper:
# - always use system curl
# - force IPv4 loopback path behavior
# - bypass proxy configuration inherited from parent shells
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY all_proxy
export NO_PROXY="*"
export no_proxy="*"

exec /usr/bin/curl -4 --noproxy '*' "$@"
