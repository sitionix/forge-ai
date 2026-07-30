#!/usr/bin/env bash

forge_pid_is_running() {
  local pid="$1"
  [[ -n "${pid}" ]] && kill -0 "${pid}" >/dev/null 2>&1
}

forge_read_pid_file() {
  local pid_file="$1"
  local line

  [[ -f "${pid_file}" ]] || return 1
  line="$(head -n 1 "${pid_file}" 2>/dev/null || true)"
  case "${line}" in
    PID=*) line="${line#PID=}" ;;
  esac
  [[ "${line}" =~ ^[0-9]+$ ]] || return 1
  printf '%s' "${line}"
}

forge_write_pid_file() {
  local pid_file="$1"
  local pid="$2"
  local pid_dir
  local tmp_file

  pid_dir="$(dirname -- "${pid_file}")"
  mkdir -p "${pid_dir}"
  tmp_file="${pid_file}.$$"
  printf '%s\n' "${pid}" > "${tmp_file}"
  mv -f "${tmp_file}" "${pid_file}"
}

forge_write_owned_pid_file() {
  local pid_file="$1"
  local pid="$2"
  local owner="$3"
  local command_label="$4"
  local pid_dir
  local tmp_file

  pid_dir="$(dirname -- "${pid_file}")"
  mkdir -p "${pid_dir}"
  tmp_file="${pid_file}.$$"
  {
    printf 'PID=%s\n' "${pid}"
    printf 'OWNER=%s\n' "${owner}"
    printf 'COMMAND=%s\n' "${command_label}"
  } > "${tmp_file}"
  mv -f "${tmp_file}" "${pid_file}"
}

forge_start_background() {
  local pid_file="$1"
  local log_file="$2"
  local work_dir="$3"
  shift 3

  mkdir -p "$(dirname -- "${pid_file}")" "$(dirname -- "${log_file}")"
  : >> "${log_file}"
  (
    cd "${work_dir}"
    set -m
    nohup "$@" </dev/null >> "${log_file}" 2>&1 &
    forge_write_pid_file "${pid_file}" "$!"
  )
}
