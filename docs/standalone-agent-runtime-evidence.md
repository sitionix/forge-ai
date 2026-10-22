# Standalone agent sessions — implementation and runtime evidence

Branch: `feature/SITIONIX-167`, based on `02689707`. Verification date: 2026-10-22.

## Execution ownership

`StandaloneSessionCommands` resolves a project-scoped Forge/native reference and stores
an immutable effective definition in dedicated standalone tables (V48). No Workflow,
WorkflowRun or NodeRun is created. The original workflow ownership constraints remain
unchanged. `AgentTurnExecutionPort` is the common invocation boundary; both owners use
`CodexAgentTurnExecutionAdapter` → existing `CodexClient`/app-server transport. Discovery
remains separate from execution. Workflow retains its JSON output contract and secured
cancellation lifecycle; standalone uses ordinary text and its selected root definition.

The first turn starts a durable, non-ephemeral root thread; subsequent turns resume the
persisted thread ID. Resume failure never creates a replacement. Thread/turn identities,
messages, events, revisions, idempotency receipts and fenced leases are persisted. Domain
policy owns send and completion decisions; JDBC owns atomicity, locking and constraints.

MCP grants have typed Workflow/Standalone ownership. Project eligibility, connections,
allowlists, expiration, revocation and current fencing are checked through the existing
gateway. Browser references cannot select paths, process commands or Codex configuration.

## Resource contract

Native resolution uses the existing bounded inventory reader through no-follow file
access and opaque server-derived IDs. Full execution parsing is characterized against
Python **3.14.4**, with an allowlist of name, description, developer instructions, model
and reasoning effort. Unsupported execution configuration is rejected. Forge retains
its own persisted model/effort; the global profile is only a native fallback.

Snapshots contain source fingerprints, instructions, model/effort, repository/workspace
identity, capability policy, resource hashes and executable metadata. Every turn checks
current authorization and resource compatibility. Changed resources produce an explicit
compatibility error, rather than silently changing an existing conversation.

For standalone, the existing privileged runtime launcher accepts a bounded private
`--snapshot` envelope. It copies verified bytes through no-follow ancestor/file FDs into
private temporary storage and binds project `.codex`/`.agents`, instruction files and
approved fixed global resource directories read-only. Missing resources are masked too.
Copies preserve verified executable semantics as 0555, ordinary files as 0444; write and
setuid bits are removed. Source mutation after copying cannot change the mounted copy.
Credentials, personal configuration, plugins and personal instruction files are not
copied into public project workspaces. Runtime-home instruction files are masked.

Project instruction/configuration layers include `AGENTS.md`, `AGENTS.override.md` and
intermediate selected-repository ancestors inside project scope. Ancestor instruction
layers outside that scope and unsupported project configuration are rejected by the
launcher. Standalone project config permits the existing model/reasoning base setting
allowlist only. Old launchers fail closed for `--snapshot`; installation must refresh the
launcher alongside this feature. The production launcher was **not** replaced during
these tests.

## Verified provider behavior

Installed binary: `/usr/local/lib/forge-runtime/codex/bin/codex`, **Codex 0.163.0**.

Real isolated protocol/production-adapter probes passed:

- Non-ephemeral root creation and exact same-thread resume across owned processes.
- Selected instructions/model, plain user text, no workflow envelope or parent wrapper.
- Follow-up recalling a unique fact from the first turn.
- Interrupt after actual activation, persisted interrupted-turn inspection without replay.
- Native Skill discovery/read and actual native custom-child invocation.
- Project custom agent overriding a same-name global custom agent.
- Process cleanup after completion/interruption.

A separate **managed systemd** probe used an isolated root-owned copy of the current
launcher, the installed runtime UID/binary/auth and a disposable managed workspace.
It passed root → native Skill executable helper → native custom child, then a second
turn on the same thread, with owned cleanup of both processes. The Skill marker existed
only in its executable helper; an actual normalized command event proved direct execution.
The probe made no production database writes and did not restart production services.

Subagents are enabled only for standalone on characterized 0.163.0, with concurrency 2
and depth 1. Both `agents.max_threads` and `agents.max_depth` were confirmed as recognized
installed configuration fields through differential invalid-type probes. Native v2
`subAgentActivity` and legacy `collabAgentToolCall` are accepted only under the explicit
native policy; Workflow keeps rejecting them. Unknown future item types fail closed.

[Official configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference)
documents `agents.max_threads` as an alias for concurrent spawned threads. The installed
0.163.0 depth field was additionally verified locally; current documentation omits it.
[Official subagent documentation](https://learn.chatgpt.com/docs/agent-configuration/subagents)
states that children inherit parent permissions/provider/MCP and that custom definitions
cannot replace those permissions.

## Recovery and interaction limits

Only one active turn is admitted per session. Cancellation, dispatch and recovery use
fenced ownership. A persistent `cleanup_pending` barrier prevents READY or recovery while
owned runtime cleanup remains unproven. Stale cleanup proof cannot clear a new fence.
If a process/service dies with cleanup unproven, the session remains in explicit
`RECOVERY_REQUIRED` for manual review; uncertain turns are never automatically replayed.
A proven terminal provider outcome can reconcile exactly once and persist its reply.

Ordinary clarification replies are normal chat turns and do not keep a provider process
alive. Native privileged approvals and in-turn user-input requests remain unsupported,
fail closed, and are disclosed in the composer. There is no new approval framework.

## Console and transport evidence

The real built Console was exercised in Chrome against a separate HTTP transport fixture:
Forge Agent launch, native Agent launch from AI Library, project/model preview, two-turn
history, polling activity, Send/Stop, search, deep links, Back/Forward, reload/reopen,
draft/focus retention and a 390×844 viewport without horizontal overflow. This fixture
uses synthetic assistant data and is **not** evidence of a browser-to-live-Codex flow.
The live provider and actual PostgreSQL/typed Agent/Nexus transport are tested separately.

Public activity keeps bounded status/tool/subagent metadata. Internal working directories,
raw tool output/instructions and arbitrary private payload fields are omitted; absolute
command paths and credential patterns are redacted. Private snapshots are never public DTOs.

## Check ledger

- Agent full unit/integration verify: PASS (exit 0), including Workflow/Dialogue/MCP regressions.
- PostgreSQL V48, protected HTTP two-turn flow, history/cursors, snapshot immutability,
  cleanup/restart/stale fencing: PASS in the final full run.
- Nexus full unit/integration verify including standalone ForgeIT: PASS.
- Console: 850 tests / 44 files PASS; TypeScript and production build PASS.
- Runtime launcher/installation/security: 71 tests, 1 environment-gated skip, PASS.
- Final combined real Codex suite: **6 tests PASS**, 0 failures/errors/skips (93.041 seconds),
  covering direct conversation/interruption probes, managed Skill executable/child/resume
  and recognized limit fields.
- Agent/Nexus `dependency:analyze`: PASS (exit 0).
- Independent review: identified resource TOCTOU/instruction coverage, cleanup fencing,
  late receipt, absent-config and executable-helper issues; fixes have regression tests.
  Final independent follow-up verdict: **ACCEPT**, no remaining blockers in reviewed changes.
- `git diff --check` and staged whitespace check: PASS.

Unverified: a deployed full-browser → Nexus → Agent → real Codex scenario, saturation
stress of provider concurrency/depth, real external MCP credentials through a native
child, and durable execution on versions other than those explicitly characterized.
No PR, remote push, merge or GitHub metadata changes were made.

## Reproduction

Normal checks:

```sh
mvn -q -f services/forge-agent/pom.xml -Dapi.version=1.44 verify
mvn -q -f services/forge-nexus/pom.xml -Dapi.version=1.44 verify
cd services/forge-console
npm test
npm run typecheck
npm run build
```

Runtime unit contracts use `python3 -m unittest discover -s scripts/runtime/tests -p 'test_*.py'`.
Native probes are opt-in through `forge.codex.standalone-smoke.binary` and, for isolated
HOME probes, `forge.codex.standalone-smoke.auth`. These are test JVM properties, never
browser/API inputs. The managed probe additionally requires an administrator-installed,
root-owned **separate** copy of the launcher and `tests/standalone_codex_fixture.py`,
then the managed driver/fixture/workspace/sudo-helper properties documented by the test.
The disposable fixture supports explicit setup/launch/stop/cleanup; it does not install
or replace the production launcher. Authentication material is never printed or copied
into the fixture workspace.
