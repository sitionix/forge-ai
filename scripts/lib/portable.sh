#!/usr/bin/env bash

forge_sha256() {
  local path="$1"
  local output

  if command -v sha256sum >/dev/null 2>&1; then
    if output="$(sha256sum "${path}" 2>/dev/null)"; then
      printf '%s\n' "${output}" | awk '{print $1}'
      return 0
    fi
  fi
  if command -v shasum >/dev/null 2>&1; then
    if output="$(shasum -a 256 "${path}" 2>/dev/null)"; then
      printf '%s\n' "${output}" | awk '{print $1}'
      return 0
    fi
  fi
  return 1
}

forge_file_stamp() {
  local path="$1"

  [[ -f "${path}" ]] || return 1
  if stat -c '%y' "${path}" >/dev/null 2>&1; then
    stat -c '%y' "${path}" 2>/dev/null | cut -d'.' -f1 && return 0
  fi
  if stat -f '%Sm' -t '%Y-%m-%d %H:%M:%S' "${path}" >/dev/null 2>&1; then
    stat -f '%Sm' -t '%Y-%m-%d %H:%M:%S' "${path}" 2>/dev/null && return 0
  fi
  return 1
}

forge_file_size() {
  local path="$1"

  [[ -f "${path}" ]] || return 1
  wc -c < "${path}" | tr -d ' '
}

forge_java_command() {
  if [[ -n "${FORGE_JAVA_COMMAND:-}" ]]; then
    printf '%s\n' "${FORGE_JAVA_COMMAND}"
    return
  fi
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    printf '%s\n' "${JAVA_HOME}/bin/java"
    return
  fi
  if [[ -x "${HOME:?HOME is required}/.sdkman/candidates/java/current/bin/java" ]]; then
    printf '%s\n' "${HOME}/.sdkman/candidates/java/current/bin/java"
    return
  fi
  command -v java 2>/dev/null || {
    echo "Java is required to run Forge Agent and Nexus." >&2
    return 1
  }
}
