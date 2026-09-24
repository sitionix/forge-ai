# Stage 1 — рішення під час виконання

Усі Ruling entries з execution ledger. Delivery — draft PR; це не дозвіл на merge/deployment.

Ruling: use delivered Stage0 architecture/Stage1 plan as approved intent when user said continue; current local plan refines execution and current-main references, no duplicate approval request.

Ruling: owner reuses newly merged ForgeInstanceIdentityRepository (Stage0 predates it), migration starts at V38 only after verifying no conflict.

Ruling: broaden enabled-only auth to existing control API prefix and runtime isolation to Git; local Compose parsing fails closed in enabled mode — concrete existing deputy paths invalidate MCP-route-only protection. Cost if wrong: enabled deployments require operator session for existing Console APIs and cannot use local Compose flows; default-off unchanged.

Ruling: use owned transient systemd services through narrow root-owned helper, not plain sudo-to-runtime — current ProcessHandle cleanup cannot signal cross-UID descendants; cgroup ownership/lifetime must be tested. Cost if wrong: implementation/deployment complexity; no production enablement claimed without actual disposable fixture. Task2 split into 2a runtime and 2b auth.

Ruling: Task2a disposable privileged fixture may use random /run/forge-stage1-UUID and real transient systemd units with existing nobody UID, no RootDirectory — closer to proposed UID-only production filesystem profile and avoids masking isolation with a different mount boundary. Only synthetic control/runtime files and root-copied actual Codex; no permanent accounts/config/services or host secret reads. Cost if wrong: fixture cleanup/containment risk, mitigated owned UUID allowlist and review before run. Direct root driver+SUDO_UID context is explicitly not installed sudoers routing proof; that remains NOT_VERIFIED.

Ruling: add typed POST connections/{id}/reencrypt (204) to Task3 — roadmap explicitly requires controlled operator re-encryption, existing Java-only service method is not an operator entrypoint. Cost if wrong: one additional small guarded management route; no bulk job/secret read/framework.

Ruling: enabled-only workspace preparation uses control-owned managed parents with runtime group traversal and group-writable checkout/staging contents; Git safe.directory limited to validated managed root — runtime UID otherwise cannot use existing checkout. No host group provisioning. Cost if wrong: permissions regression; require focused tests and real fixture before enablement.

Ruling: enabled-only workspace deletion uses SecureDirectoryStream relative NOFOLLOW traversal and fails closed if unsupported — existing absolute Files.walk/delete permits nested symlink substitution into control paths. Default-off unchanged. Cost if wrong: cleanup compatibility, covered with synthetic race/escape tests.

Ruling: execute reviewed disposable helper fixture while independent Java lifecycle fix proceeds — fixture exercises unchanged helper directly, not Java transport; full helper source audit and fixture safety gates passed. Cost if wrong: cannot infer Java acceptance from helper proof; full Task2a gate remains blocked until lifecycle fixes/re-review.

Ruling: revise synthetic fixture identity to existing unused backup account with identical live-process preflight and isolated HOME; no host account/group changes or reads of account data — nobody cannot be safely reused while busy. Cost if wrong: account concurrency/permissions risk; require scoped fixture review and runtime filesystem restrictions before rerun. Helper remains frozen.

Ruling: start default-off Task2b implementation after accepted Task2a code while reviewed OS fixture retry is pending — public interfaces stable; no deployment or overall enablement accepted without actual proof. Cost if wrong: later integration rework; no bypass/verifiedflag allowed.

Ruling: reuse completed stage1_core implementer seat for Task2b because platform rejects fresh agent thread; provide new self-contained brief and explicit Task1 freeze. Cost if wrong: context carryover; independent Task2b review remains mandatory. Task2b implementation now active, task2b-brief.md.

Reviewed pinnedbwrap diagnostic run: initialOSboundary+CodexhandshakePASS; sandboxcommand exit1 `bwrap: Cant read /proc/sys/kernel/overflowuid: No such file or directory`, cleanupPASS. Exactfailure caused by helper ProcSubset=pid hiding required kernel metadata. Ruling: remove only ProcSubset=pid; retain ProtectProc=invisible, UID/capabilities/NNP/env/cgroup/workspace controls and networkfalse — nativeCodexsandbox requires readonlykernelmetadata, not exposure of controlproc/secrets. Cost if wrong: broader nonsensitive/proc metadata visibility; realnegativeproof must rerun. Soleproductimplementer assignednarrowfix; no helperedit bydiagnosticworker.

Ruling: off-mode must failstartup if any retainedMCPcipher/credentialflag or configuredMCPprotectedpaths remain — confirmed downgrade P1 otherwise returns sameUIDruntime and removesauth. Pauseconnections throughenabled=false instead. Cost if wrong: stricter intentionaldeprovisioning; preserve emptylegacydefaultoff, document deliberateconfigremoval/unknownorphanfiles limitation, no scans or newframework. Guardmust precede launch/background including earlyRemoteAccessPairingReconciliation; reportdisabled-retained-secret-review.md. SoleTask2bimplementer assigned guard +globalmetadataquery/tests.

Ruling: Task3 exposes explicit typed STREAMABLE_HTTP invariant (derived metadata) without adding unnecessary transport persistence for a single supported option — roadmap requires transport metadata, not multiple transports. Cost if wrong: future additionaltransport requires explicit schema/model evolution; reject unsupportedcallertransport today.

Task3 progress: Agent/Nexus typed vertical compiled; focused tests actual RED found new Nexus ProjectAccess null-check defect, fix pending validation. Implementer disclosed code preceded tests, violating planned strict test-first sequencing. Ruling: retain reversible implementation and require honest defect RED/GREEN + complete contract tests and independent review, rather than delete/recreate code to simulate test-first — cost if wrong: less evidence of test sensitivity; final audit must inspect behavior/negative coverage. No claim strict TDD followed for Task3.

Task3 frozen119product/test/configfiles; scoped40filedelta packaged. Independent gpt-6-astra stage1_crud_review dispatched. Ruling: run final full Agent/Nexus verification in parallel with read-only Task3 review on frozen source — no dependent implementation starts; cost if review requires fixes is targeted rerun. Report lacks persisted focused logs; requested provenance, no invented evidence. Log-canary capture gap explicitly sent reviewer.

Final whole-change audit ACCEPT frozen Stage1/a102de5c,0newfrozenblockers; actual latest-main P2 auth integration unresolved (distinct MCP and Stage6 tokens on same Authorization, Nexus dual-login gate). Ruling: publish requested PR as draft with explicit known integration blocker rather than silently redesign newly merged Stage6 auth — user requested PR, not merge; cost if wrong: follow-up combined-mode auth work before acceptance. Final Agent1257/0fail/0error/9live skips, Nexus306/0/0/0. No further product edits after audited/tested freeze.
