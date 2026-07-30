#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="$(mktemp -d)"

TEST_SUMMARY=()
FAILED=0
SERVICE_PASSED=0
SERVICE_TOTAL=0
TEST_PASSED=0
TEST_TOTAL=0
LAST_TEST_COUNT="0/0"

trap 'rm -rf "${REPORT_DIR}"' EXIT

count_junit_report() {
  python3 - "$@" <<'PY'
import sys
import xml.etree.ElementTree as ET

tests = 0
failures = 0
errors = 0
skipped = 0

for report in sys.argv[1:]:
    root = ET.parse(report).getroot()
    suites = [root] if root.tag == "testsuite" else root.findall(".//testsuite")
    for suite in suites:
        tests += int(float(suite.attrib.get("tests", 0)))
        failures += int(float(suite.attrib.get("failures", 0)))
        errors += int(float(suite.attrib.get("errors", 0)))
        skipped += int(float(suite.attrib.get("skipped", 0)))

passed = max(tests - failures - errors - skipped, 0)
print(f"{passed}/{tests}")
PY
}

count_junit_reports_since() {
  local root="$1"
  local marker="$2"

  python3 - "${root}" "${marker}" <<'PY'
import os
import sys
import xml.etree.ElementTree as ET

root_dir = sys.argv[1]
marker_mtime = os.path.getmtime(sys.argv[2])
tests = 0
failures = 0
errors = 0
skipped = 0

for current_root, _, files in os.walk(root_dir):
    if not (current_root.endswith("/surefire-reports") or current_root.endswith("/failsafe-reports")):
        continue
    for file_name in files:
        if not file_name.startswith("TEST-") or not file_name.endswith(".xml"):
            continue
        path = os.path.join(current_root, file_name)
        if os.path.getmtime(path) < marker_mtime:
            continue
        xml_root = ET.parse(path).getroot()
        suites = [xml_root] if xml_root.tag == "testsuite" else xml_root.findall(".//testsuite")
        for suite in suites:
            tests += int(float(suite.attrib.get("tests", 0)))
            failures += int(float(suite.attrib.get("failures", 0)))
            errors += int(float(suite.attrib.get("errors", 0)))
            skipped += int(float(suite.attrib.get("skipped", 0)))

passed = max(tests - failures - errors - skipped, 0)
print(f"{passed}/{tests}")
PY
}

count_vitest_log() {
  python3 - "$1" <<'PY'
import re
import sys

text = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
text = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", text)
match = re.search(r"^\s*Tests\s+(.+?)\((\d+)\)", text, re.MULTILINE)
if not match:
    print("0/0")
    sys.exit(0)

summary = match.group(1)
total = int(match.group(2))
passed_match = re.search(r"(\d+)\s+passed", summary)
passed = int(passed_match.group(1)) if passed_match else 0
print(f"{passed}/{total}")
PY
}

run_test() {
  local service="$1"
  shift

  SERVICE_TOTAL=$((SERVICE_TOTAL + 1))
  LAST_TEST_COUNT="0/0"
  printf '\n==> %s\n' "${service}"
  if "$@"; then
    SERVICE_PASSED=$((SERVICE_PASSED + 1))
    TEST_SUMMARY+=("${service}: PASS ${LAST_TEST_COUNT}")
  else
    TEST_SUMMARY+=("${service}: FAIL ${LAST_TEST_COUNT}")
    FAILED=1
  fi

  local passed="${LAST_TEST_COUNT%%/*}"
  local total="${LAST_TEST_COUNT##*/}"
  TEST_PASSED=$((TEST_PASSED + passed))
  TEST_TOTAL=$((TEST_TOTAL + total))
}

run_pytest_service() {
  local service_dir="$1"
  local report="$2"
  local python="${service_dir}/.venv/bin/python3"
  local exit_code

  if [[ ! -x "${python}" ]]; then
    python="python3"
  fi

  set +e
  (
    cd "${service_dir}"
    PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 "${python}" -m pytest --junitxml="${report}"
  )
  exit_code=$?
  set -e

  if [[ -f "${report}" ]]; then
    LAST_TEST_COUNT="$(count_junit_report "${report}")"
  fi

  return "${exit_code}"
}

run_knowledge_tests() {
  run_pytest_service "${ROOT_DIR}/services/forge-knowledge" "${REPORT_DIR}/forge-knowledge.xml"
}

run_jarvis_tests() {
  run_pytest_service "${ROOT_DIR}/services/forge-jarvis" "${REPORT_DIR}/forge-jarvis.xml"
}

run_console_tests() {
  local log="${REPORT_DIR}/forge-console.log"
  local exit_code

  set +e
  (
    cd "${ROOT_DIR}/services/forge-console"
    npm ci
    npm run typecheck
    npm test 2>&1 | tee "${log}"
    test_exit="${PIPESTATUS[0]}"
    if [[ "${test_exit}" -ne 0 ]]; then
      exit "${test_exit}"
    fi
    npm run build
  )
  exit_code=$?
  set -e

  if [[ -f "${log}" ]]; then
    LAST_TEST_COUNT="$(count_vitest_log "${log}")"
  fi

  return "${exit_code}"
}

run_nexus_tests() {
  local marker="${REPORT_DIR}/forge-nexus-marker"
  local exit_code

  touch "${marker}"
  set +e
  (
    cd "${ROOT_DIR}/services/forge-nexus"
    mvn -q verify
  )
  exit_code=$?
  set -e

  LAST_TEST_COUNT="$(count_junit_reports_since "${ROOT_DIR}/services/forge-nexus" "${marker}")"
  return "${exit_code}"
}

run_portable_startup_tests() {
  local log="${REPORT_DIR}/portable-startup.log"
  local exit_code
  local summary

  set +e
  "${ROOT_DIR}/scripts/test-portable-startup.sh" 2>&1 | tee "${log}"
  exit_code="${PIPESTATUS[0]}"
  set -e

  summary="$(sed -n 's/^Portable startup shell tests: PASS \([0-9][0-9]*\/[0-9][0-9]*\)$/\1/p' "${log}" | tail -n 1)"
  LAST_TEST_COUNT="${summary:-0/0}"
  return "${exit_code}"
}

run_test "portable-startup-shell" run_portable_startup_tests
run_test "forge-knowledge" run_knowledge_tests
run_test "forge-jarvis" run_jarvis_tests
run_test "forge-console" run_console_tests
run_test "forge-nexus" run_nexus_tests

if [[ "${FAILED}" -eq 0 ]]; then
  printf '\nTest summary: PASS services %s/%s, tests %s/%s\n' "${SERVICE_PASSED}" "${SERVICE_TOTAL}" "${TEST_PASSED}" "${TEST_TOTAL}"
else
  printf '\nTest summary: FAIL services %s/%s, tests %s/%s\n' "${SERVICE_PASSED}" "${SERVICE_TOTAL}" "${TEST_PASSED}" "${TEST_TOTAL}"
fi
for result in "${TEST_SUMMARY[@]}"; do
  printf '  %s\n' "${result}"
done

exit "${FAILED}"
