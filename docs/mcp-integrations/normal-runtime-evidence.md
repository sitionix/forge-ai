# MCP normal runtime — evidence, 2026-09-28

Main Agent/Nexus always compose MCP. There is no global activation switch or same-UID runtime fallback. Per-connection `enabled` remains unchanged. Dedicated Remote Access uses narrow Spring application roots in the existing boot artifacts; it does not select general Forge availability.

## Required evidence

| Check | Result | Evidence |
| --- | --- | --- |
| NORMAL_JUST_START | PASS | Actual `just stop` followed by `just start`, exit 0; all four normal services healthy. Restart timestamps checked against the captured start marker. |
| MAIN_NEXUS_HEALTH | PASS | Actual `127.0.0.1:9099/fgaisox/actuator/health`, HTTP 200. |
| MAIN_AGENT_HEALTH | PASS | Actual `127.0.0.1:7091/actuator/health`, HTTP 200. |
| SETTINGS_HTTP | PASS | Actual built `/fgaisox/operator/settings.html`, HTTP 200, integrations section present. |
| MCP_SESSION_ROUTE | PASS | Unauthenticated session read returns 401; operator login succeeds using generated protected file path. |
| MCP_CONNECTION_LIST | PASS | Actual authenticated typed management route returns HTTP 200, `[]`. |
| EMPTY_STATE | PASS | Real desktop Chrome shows `No integrations connected` and `Add integration`. No connections were deleted to obtain this state. |
| REAL_BROWSER | PASS | Built assets, actual local Nexus at port 9099, no HTTP stub or activation override. |
| GLOBAL_SIDEBAR | PASS | Visible `.operator-sidebar`, active Settings, correct page layout, no horizontal overflow or unavailable-installation message. |
| PROJECTS_STILL_VISIBLE | PASS | Real browser Projects navigation remains visible. Existing project records and source workspaces are retained. |
| NO_MCP_ENABLE_FLAG | PASS | Whole-repository removed-switch audit has zero matches; conditional audit finds no global MCP activation branch. |
| FULL_AGENT_VERIFY | PASS | Final serial reactor verify: 1,419 declared tests, 0 failures/errors, 10 skips. |
| FULL_NEXUS_VERIFY | PASS | Clean reactor verify: 380 declared tests, 0 failures/errors/skips; includes 87 ForgeIT tests. |
| CONSOLE_TESTS | PASS | Typecheck, 625 tests across 28 files, production build. |
| RUNTIME_TESTS | PASS | 45 Python runtime tests. |
| CI | NOT_VERIFIED | Fresh exact-commit Build must be recorded after publication; local results are not CI. |

Private command logs and material comparisons are kept in the ignored `.superpowers/sdd/2026-09-28-mcp-normal-runtime/` directory. Credential contents, hashes and privileged fixture material are not committed or printed.

## Changes and tested boundaries

- Existing systemd install prepares protected material before main startup. Agent/Nexus env files contain paths, explicit loopback binds/origin, and no secret values. Files are regular, single-link, protected 0600 with validated owners/ancestors. Missing files are exclusively created; conflicting/invalid existing material fails closed.
- Existing database password is retained, not rotated. The AES-GCM key ring, Agent service audience and distinct operator authority persist across repeat setup. Existing Remote Access operator ownership is reused by path when applicable.
- Mandatory isolated launcher is installed through the existing sudoers/systemd path. Native installed software is copied without personal Codex configuration/auth/history. Main Agent has only the reviewed helper route; its transient children retain their existing containment.
- Actual installed helper probe: runtime UID 992, control UID 1000; protected-path access denied, process aliases denied, environment clean, owned cleanup confirmed. This is a fresh local privileged probe, separate from mocked Java fixture wiring.
- Repeat actual `just start` succeeded; all seven protected material/env files compared byte-identical. No key/credential regeneration. Real runtime/browser acceptance passed again against new service start timestamps.
- Configured managed workspace is `/srv/forge/workspaces/forge-projects`. Existing four managed projects (approximately 6.8 GB) were adopted without changing/deleting their original source. Modified/untracked files and internal relative symlink metadata are preserved. Unsupported external links/special files/conflicting destinations fail closed; directory traversal is descriptor-based and no-follow.
- First startup attempt refused legitimate internal relative symlinks. A failing regression was added, then metadata-preserving copying implemented; the subsequent complete startup succeeded. Symlink targets are never fetched/copied, and links escaping the project are rejected.
- Dedicated Agent/Nexus executable archives were actually started against disposable local PostgreSQL and synthetic protected files using their configured Spring Boot archive launcher; both health endpoints returned 200 without main MCP prerequisites. Fixtures were removed. This proves packaged composition, not a production Remote Access pairing session.
- Main/combined/Remote-Access-only management tests preserve authentication, Origin, CSRF, alternative-route denial and zero upstream calls on local denials. Ordinary positive ForgeIT fixtures now authenticate through actual guards; no global activation overrides.
- Settings errors/login remain scoped to the integrations card. A 401 requests sign-in; upstream errors are not converted into empty success. Existing explicit connection Enable/Disable and permission forms are preserved.

## Commands and actual results

```bash
npm --prefix services/forge-console run typecheck
npm --prefix services/forge-console test
npm --prefix services/forge-console run build
mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/boot -am verify
mvn -B -ntp -Dapi.version=1.44 -pl services/forge-nexus/boot -am clean verify
python3 -m unittest discover -s scripts/runtime/tests -p 'test_*.py' -v
git diff --check
just stop
just start
# Repeat start, checking persistent material and new service timestamps:
just start
python3 -I scripts/runtime/mcp-normal-runtime-acceptance.py --started-after <captured-monotonic-microseconds>
```

All final commands above succeeded. Focused regressions preceded full checks: dedicated compositions; unconditional MCP registration/auth matrix; protected preparation/partial repeat/conflict rejection; managed workspace adoption and directory replacement; mandatory Git/Codex lifecycle; Settings hierarchy/empty/error state; normal acceptance rejecting stale restart/stub/activation overrides.

One earlier clean Agent reactor attempt failed `CodexRecoveryInspectorTest.initializeTimeoutUsesRemainingDeadlineAndTerminatesProcess` under its existing 90 ms fixture budget. Its root cause was NOT_VERIFIED; no production recovery code or timeout was changed. The focused recovery suite subsequently passed (11 tests), followed by the full serial Agent verify above. Both packaged archives were inspected: obsolete downgrade classes are absent. A green retry is not proof that the earlier timing failure cannot recur.

## Explicit limits

- The 10 Agent skips include opt-in native/provider/recovery and joined Stage 4/5 scenarios. Default verify does not establish their execution. XML files from older runs are not used as fresh evidence; counts above come from the named command logs.
- Real local acceptance used the normal existing database, whose MCP connection inventory was already empty; it did not erase data or substitute a fake activation configuration.
- `LIVE_PROVIDER = NOT_VERIFIED`; no external provider call was required. `LIVE_REGISTRY = NOT_VERIFIED`; browser acceptance does not certify live Registry availability.
- macOS privileged isolation, full workflow/model execution, actual production Remote Access pairing/revoke and remote production deployment are NOT_VERIFIED by these checks.
- Historical Stage 1/4/5 OS and joined probes are not claimed as rerun. The installed-helper and actual normal-start/browser checks above are the named fresh boundaries.
- No PR metadata/comments/reviews or merge state were changed.
