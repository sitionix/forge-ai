# MCP normal runtime — evidence, 2026-09-28

Current contract follows the user's explicit amendment: no Forge operator login,
session or internal Nexus → Agent bearer. There is no local/remote mode or global
MCP activation switch. External provider credentials/encryption, runtime grants,
isolation, project/tool policy and explicit per-connection `enabled` remain.

## Required evidence

| Check | Result | Evidence |
| --- | --- | --- |
| NORMAL_JUST_START | NOT_VERIFIED | Post-amendment restart pending; earlier authenticated-runtime run is historical. |
| MAIN_NEXUS_HEALTH | NOT_VERIFIED | Post-amendment actual main runtime check pending. |
| MAIN_AGENT_HEALTH | NOT_VERIFIED | Post-amendment actual main runtime check pending. |
| SETTINGS_HTTP | NOT_VERIFIED | Actual main built asset check pending. |
| MCP_SESSION_ROUTE | NOT_VERIFIED | Removed route must return 404; no session prerequisite. |
| MCP_CONNECTION_LIST | NOT_VERIFIED | Actual direct HTTP 200 list without Authorization/Cookie pending. |
| EMPTY_STATE | NOT_VERIFIED | Actual real-browser empty state pending. |
| REAL_BROWSER | NOT_VERIFIED | Actual main :9099 acceptance pending. |
| GLOBAL_SIDEBAR | NOT_VERIFIED | Actual real-browser check pending. |
| PROJECTS_STILL_VISIBLE | NOT_VERIFIED | Actual real-browser check pending. |
| NO_MCP_ENABLE_FLAG | PASS | Whole-repository audit: zero removed-switch matches; no replacement switch. |
| FULL_AGENT_VERIFY | PASS | 1,407 declared tests, 0 failures/errors, 10 skips; full final reactor completed. |
| FULL_NEXUS_VERIFY | PASS | 352 declared tests, 0 failures/errors/skips; 69 ForgeIT tests. |
| CONSOLE_TESTS | PASS | Typecheck, 622 tests across 28 files, production build; browser smoke against explicit stub passed separately. |
| RUNTIME_TESTS | PASS | 47 Python runtime tests; additional Remote Access suite: 110 tests, 2 skips. |
| CI | NOT_VERIFIED | Previous exact-commit Build 36437708115 failed two startup fixtures; these fixtures are corrected locally. Fresh amended commit CI pending. |

## Changes and boundaries

- Removed general Forge operator/session controllers, filters and configuration,
  Agent management bearer filter, typed-client/log-stream bearer injection,
  combined MCP/RA session owner and independent operator/service file preparation.
  No compatibility shell, auth toggle, automatic login or new endpoint replaces them.
- Main Nexus uses the existing typed Agent client/executor and scoped MCP error
  boundary. Catalog remains one official Registry page, filtering URL metadata,
  five-minute bounded Caffeine cache; no connection creation or provider handshake.
- ForgeIT exercises catalog and CRUD without cookie, Authorization or CSRF. Valid
  Agent errors retain their contract; malformed/unavailable responses and provider
  secret canaries remain covered. Scoped RA login/Origin/CSRF/service bearer still
  applies only to RA; combined HTTP coverage proves MCP works without an RA session.
- Normal installation prepares only the persistent encryption key/database material
  and existing isolated runtime prerequisites. Agent environment contains file paths
  and the managed workspace, no credential values. Nexus has no MCP auth environment.
  Missing material is created exclusively; invalid/conflicting material is rejected.
  Existing unused operator/service files are not read, regenerated or rotated.
- Workspace adoption preserves modified/untracked files and internal relative
  symlink metadata. Reserved adoption markers are rejected. Clone-attempt parent
  remains 2750. An actual Python adoption → production Java clone regression first
  failed on the previous mode and then passed; reserved-marker regressions also
  failed before the fix. Concurrent key writers cannot replace the winning key.
- Settings loads directly; login/logout/session state and their types/styles are
  removed. Compact MCP card, empty state, Add action, section errors and global
  navigation remain. Explicit connection Disable/Enable and disabled-only access
  editing are unchanged.

## Commands

```sh
npm --prefix services/forge-console run typecheck
npm --prefix services/forge-console test
npm --prefix services/forge-console run build
mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/boot -am verify
mvn -B -ntp -Dapi.version=1.44 -pl services/forge-nexus/boot -am verify
python3 -m unittest discover -s scripts/runtime/tests -p 'test_*.py' -v
python3 -m unittest discover -s scripts/remote-access/tests -p 'test_*.py' -v
git diff --check
just stop
just start
just start
python3 -I scripts/runtime/mcp-normal-runtime-acceptance.py --started-after <restart-marker>
```

Maven clean verification preceded final verification to eliminate stale deleted
security classes. Initial amended Nexus verify exposed an obsolete session-route
assertion; it was removed with its unused endpoint contracts. A later import
cleanup removed annotation imports accidentally; they were restored before the
successful final Nexus reactor. These failures are not counted as successful runs.

## Historical and remaining limits

The prior authenticated implementation was actually started and checked with real
Chrome, repeat material comparison, installed OS helper probe, workspace adoption
and packaged dedicated RA roots. Those observations are historical and do not
certify the current unauthenticated implementation or a rerun of OS probes.

Agent skips in the final log: CodexManagedRecoveryLifecycleTest (1),
McpGatewaySdkHttpTest (1), McpGatewayRuntimeFilterTest (1),
RemoteAccessManagementHttpIT (1), ForgeAgentPortAwareExecutionIT (6). The separate
opt-in joined/native acceptance is not exercised by default verify.

LIVE_PROVIDER = NOT_VERIFIED. LIVE_REGISTRY = NOT_VERIFIED. Opt-in native and joined
Stage 4/5 tests, macOS isolation, model execution, production deployment and actual
Remote Access pairing/revoke are NOT_VERIFIED unless separately reported. An
existing-empty normal database is not a fresh-install database migration proof;
no connections are deleted to manufacture an empty state.

A fresh final read-only review found no new correctness findings. Earlier adoption
findings were fixed RED→GREEN. Earlier MCP/RA operator-owner findings were superseded
by the user's explicit deletion of MCP auth/provisioning. This does not certify CI
or mergeability. PR metadata/comments/reviews and merge state remain untouched.
