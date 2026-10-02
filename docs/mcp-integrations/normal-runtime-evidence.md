# MCP normal runtime — evidence, updated 2026-09-29

Current contract follows the user's explicit amendment: no Forge operator login,
session or internal Nexus → Agent bearer. There is no local/remote mode or global
MCP activation switch. External provider credentials/encryption, runtime grants,
isolation, project/tool policy and explicit per-connection `enabled` remain.

## Required evidence

| Check | Result | Evidence |
| --- | --- | --- |
| NORMAL_JUST_START | PASS | Actual normal `just stop → just start`, followed by a second `just start`, exit 0 on 2026-09-29. Both main restart timestamps and effective generated configuration verified. |
| MAIN_NEXUS_HEALTH | PASS | Actual `127.0.0.1:9099/fgaisox/actuator/health`: HTTP 200, status UP after both starts. |
| MAIN_AGENT_HEALTH | PASS | Actual `127.0.0.1:7091/actuator/health`: HTTP 200, status UP after both starts. |
| SETTINGS_HTTP | PASS | Actual built main `/fgaisox/operator/settings.html`: HTTP 200, MCP integrations section present. |
| MCP_SESSION_ROUTE | PASS | Deleted Forge session route returns HTTP 404, as required by the no-auth amendment. MCP needs no session. |
| MCP_CONNECTION_LIST | PASS | Actual typed main management route: HTTP 200 with `[]`, no Authorization, Cookie or CSRF headers, after both starts. |
| EMPTY_STATE | PASS | Real Chrome on :9099 shows No integrations connected and Add integration. Existing inventory was empty; no records deleted. |
| REAL_BROWSER | PASS | Real built Console against actual main Nexus, disposable browser profile, no HTTP stub or activation override; both runs passed. |
| GLOBAL_SIDEBAR | PASS | Actual desktop browser: visible operator sidebar, active Settings and valid layout without horizontal overflow or unavailable-installation message. |
| PROJECTS_STILL_VISIBLE | PASS | Actual browser Projects navigation exists; original project records/workspaces retained. |
| NO_MCP_ENABLE_FLAG | PASS | Whole-repository audit: zero removed-switch matches; no replacement switch. |
| FULL_AGENT_VERIFY | PASS | 1,407 declared tests, 0 failures/errors, 10 skips; full final reactor completed. |
| FULL_NEXUS_VERIFY | PASS | 352 declared tests, 0 failures/errors/skips; 69 ForgeIT tests. |
| CONSOLE_TESTS | PASS | Typecheck, 622 tests across 28 files, production build; browser smoke against explicit stub passed separately. |
| RUNTIME_TESTS | PASS | 47 Python runtime tests; additional Remote Access suite: 110 tests, 2 skips. |
| CI | PASS | [Fresh Build 36442800724](https://github.com/sitionix/forge-ai/actions/runs/36442800724): all five jobs succeeded for implementation commit `70b74b49284febb8a30d050be3a8f25e157373d9`. |

## Changes and boundaries

- Removed general Forge operator/session controllers, filters and configuration,
  Agent management bearer filter, typed-client/log-stream bearer injection,
  combined MCP/RA session owner and independent operator/service file preparation.
  No compatibility shell, auth toggle, automatic login or new endpoint replaces them.
- Main Nexus uses the existing typed Agent client/executor and scoped MCP error
  boundary. Catalog remains one official Registry page, filtering URL metadata,
  five-minute bounded Caffeine cache at this acceptance snapshot; no connection
  creation or provider handshake. The current Agent cache policy is recorded in
  [stage-7-evidence.md](stage-7-evidence.md).
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

## Fresh normal runtime acceptance, 2026-09-29

Normal start and repeat start used the standard installer/configuration, without
MCP activation variables, JVM properties, manually prepared credentials or a
test-only runtime configuration. Knowledge, Jarvis, Agent and Nexus are healthy.
The persistent AES-GCM key ring and database password compared byte-identical
after the first and repeat start; no credential rotation occurred. Password
authentication happened only in the OS sudo dialog, never in chat or storage.

A separate read-only GET through the actual main Nexus
`/fgaisox/api/v1/infrastructure/agents/integrations/mcp/available?limit=5`
returned HTTP 200, five metadata entries and a nextCursor. This exercised
Nexus → Agent → official Registry without browser/operator/service credentials.
No advertised MCP endpoint, handshake or tool call was executed.

The full test/CI results in the table were executed on 2026-09-28 against the
unchanged implementation commit; they are not claimed as rerun on 2026-09-29.
Only normal provisioning/build/start, repeat, HTTP/browser acceptance, read-only
Registry smoke and diff check are fresh 2026-09-29 observations.

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

Previous implementation CI 36437708115 failed two old startup fixtures. Disposable
installation fixtures replaced their dependency on host provisioning; the fresh
Build above passed them. This evidence update changes documentation only, after
CI completed for the exact implementation commit.

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

LIVE_PROVIDER = NOT_VERIFIED. LIVE_REGISTRY = PASS (read-only actual main proxy smoke on 2026-09-29). Opt-in native and joined
Stage 4/5 tests, macOS isolation, model execution, production deployment and actual
Remote Access pairing/revoke are NOT_VERIFIED unless separately reported. An
existing-empty normal database is not a fresh-install database migration proof;
no connections are deleted to manufacture an empty state.

A fresh final read-only review found no new correctness findings. Earlier adoption
findings were fixed RED→GREEN. Earlier MCP/RA operator-owner findings were superseded
by the user's explicit deletion of MCP auth/provisioning. This does not certify CI
or mergeability. PR metadata/comments/reviews and merge state remain untouched.

The pending OS dialog was completed on 2026-09-29. Fresh results above supersede
the prior NOT_VERIFIED runtime entries. Historical OS probes and packaged RA
composition checks are still not claimed as rerun.

## Archived implementation decisions

These are chronological plan decisions preserved before private scratch cleanup.
Earlier operator/bootstrap or combined-session decisions were superseded by the
user's explicit no-auth amendment; they are not current operating instructions.
No deferred minor findings remain.

- Ruling: regular existing branch, no worktree — explicit repository/user instructions.
- Task 1/2 Ruling: start RA periodic recovery on ApplicationReadyEvent rather than a removed downgrade guard dependency — all startup verification finishes first in the main app; dedicated roots do not acquire MCP dependencies.
- Task 2/4 Ruling: configured workspace resolution is required to remove legacy workspace branches; implement this interface now while provisioning/adoption follows in Task 4. No personal-home permission changes.
- Ruling: Tasks 1–4 are coupled composition/provisioning transitions; one atomic implementation commit avoids publishing an intermediate mandatory-auth runtime without provisioning. Tasks 5/6 join that commit for exact normal-start evidence. No requirement or security guard was deferred.
- Task 4 complete: configured-root RED→GREEN, adoption tests preserve original/modified/untracked data; real four-project 6.8 GB adoption, sources retained. Ruling: preserve safe internal relative symlinks as metadata (actual old projects require them); never dereference copied links. Directory replacement RED→GREEN via no-follow descriptor traversal. Durable SQL had no old workspace-path references requiring migration.
- Ruling: one clean Agent run exposed pre-existing 90ms recovery fixture timing failure. No proven root cause; focused11 and full serial pass are reported separately, not a claim of flake elimination.
- Ruling: fresh whole-branch read-only reviewer is explicitly required by requesting-code-review skill. It reviews committed source while independent exact-commit CI runs; no implementation delegation, no PR operations.
- Final: Ruling: interrupted invalid key remains fail-closed — never replace encryption material implicitly — cost if wrong: explicit operator repair required.
- Final: Ruling: installed software from reviewed trusted checkout is authorized provisioning — root install is explicit and bounded — cost if wrong: compromised trusted source is privileged.
- Final: Ruling: unchanged RA local sessions are preserved, main non-RA bootstrap guard mandatory — no auth redesign — cost if wrong: new route regression caught by matrix.
- Final: Ruling: provider/Registry/macOS/production RA checks stay NOT_VERIFIED — no fabricated remote proof — cost if wrong: platform/provider issues remain possible.
- Final: Ruling: existing-empty normal DB proves actual empty UI, not fresh-install SQL migration — no data deletion for acceptance — cost if wrong: fresh-install-only DB behavior not independently exercised.
- Final: Ruling: historical downgrade names are explicitly obsolete docs, no production path — cost if wrong: operating instruction ambiguity.
- Final: Ruling: review does not certify in-progress CI or mergeability — exact commit Build must finish separately, user retains review/merge decision — cost if wrong: unfinished CI cannot establish regressions.
- Ruling: latest user instruction explicitly removes Forge operator sessions and internal Nexus→Agent bearer authentication, without any local/remote mode switch. This supersedes the approved earlier auth prerequisites and auth-matrix acceptance; remove these mechanisms and their provisioning, not merely disable guards. Existing Remote Access SSH/pairing transport and external MCP credential storage are independent, unchanged. Cost if wrong: unauthenticated management is the requested product contract and must not be presented as the earlier Stage 1 security model.
- Ruling: earlier reviewer RA operator-owner findings are superseded by deletion of MCP operator provisioning. Adoption mode/marker fixes remain necessary and verified. No credential rotation or removal of pre-existing RA protected material.
