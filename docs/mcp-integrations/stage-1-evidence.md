# MCP Integrations Stage 1 — evidence / draft review

Реалізація й тести нижче перевірені на immutable main base `a102de5c9f18ac793fa2835a9d3f0e86e45c52dc` (Remote Access Stage5). Початкова база була `edf49dbf`. Delivery branch: `feature/SITIONIX-142`, worktree `/tmp/forge-mcp-stage1`. Користувач окремо дозволив створити PR. Original checkout із його незакоміченими змінами збережено.

Під час фінальних перевірок інший workflow пересунув shared `origin/main` на `58f2854f` (Remote Access Stage6). У цьому PR немає видалень Stage6: MCP diff рахується від merge-base `a102de5c`. Є **відомий P2 integration blocker** із новою Stage6 auth: обидва Agent filters очікують різні secrets у тому самому Authorization, а Nexus Stage6 login потрапляє за MCP session/CSRF gate. Combined-mode execution — **NOT_VERIFIED**; delivery залишається draft до узгодження auth та HTTP regression tests. Merge, deployment, Stage2+ не виконуються.

[Точна карта файлів](stage-1-file-map.md) · [Machine-readable verification](stage-1-verification.json) · [Runbook](stage-1-operations.md) · [Рішення](stage-1-decisions.md) · [Наступний Stage2 — лише план](stage-2-plan.md).

## Реалізовані контракти

- Installation-owned global MCP connection, чинний `ForgeInstanceIdentityRepository`, forward-only V38, atomic metadata/credential mutation з row lock та узгодженим aggregate read.
- AES-256-GCM, random nonce, AAD installation+connection+purpose; active/old key IDs і контрольований reencrypt. KEEP/REPLACE/REMOVE, metadata-only reads. Нові connections disabled, tool allowlist порожня. ALL/SELECTED явні; empty SELECTED та невідомий/видалений project не дозволяють invocation.
- Typed Agent `/api/v1/integrations/mcp/connections` і Nexus `/api/v1/infrastructure/agents/integrations/mcp/connections`: list/get/create/update/enabled/delete/reencrypt. Окремі API/client/domain DTO та mapping. STREAMABLE_HTTP — єдиний підтримуваний transport; зовнішніх MCP calls немає.
- Protected file-only key/service/bootstrap/DB credentials. Nexus operator session, Host/Origin, CSRF, bounded TTL; окремий Agent service bearer для REST/SSE. Enabled control APIs guarded; default-off нові routes відсутні. Retained credentials або configured secret paths забороняють global downgrade до незахищеного режиму.
- Dedicated runtime UID для чинного Codex app-server і local Git, clean environment, protected control files/proc, owned systemd/cgroup cleanup. Local Compose parsing у enabled mode відхиляється до Docker CLI. Жодного нового runtime/framework/microservice.

## Фінальна автоматична перевірка

| Перевірка | Результат |
|---|---|
| Повний Agent verify | 1257 tests, failures0/errors0/skipped9, exit0/BUILD SUCCESS |
| Повний Nexus verify | 306 tests, failures0/errors0/skipped0, exit0/BUILD SUCCESS |
| Runtime helper Python | 11/11 PASS |
| Disposable fixture safety Python | 14/14 PASS |
| `git diff --check` | PASS на frozen source |

```sh
mvn -o -B -Dapi.version=1.44 -f services/forge-agent/pom.xml verify
mvn -o -B -Dapi.version=1.44 -f services/forge-nexus/pom.xml verify
python3 -m unittest discover -s scripts/runtime/tests -p 'test_*.py'
python3 -m unittest discover -s docs/mcp-integrations/probes/stage1-boundary/tests -p 'test_*.py'
git diff --check
```

Agent skips: вісім opt-in live Codex checks і один RemoteAccessLiveExecution check; їхні системні properties не задані. Це **NOT_VERIFIED live checks**, а не PASS. Точні назви/reasons та hash product diff — у verification JSON. Maven використовував disposable ForgeIT PostgreSQL/WireMock; Docker29 API pin1.44 не змінював особистий Testcontainers config. Логи виконання: `/tmp/forge-mcp-stage1-ledger/final-agent-verify.log`, `final-nexus-verify.log`.

Security/CRUD proof включає local denial із zero typed adapter calls; service bearer та credential outbound body; malformed nested credential, UUID/transport/tool approval rejection; KEEP/REPLACE/REMOVE; GET після update/enable, 404 після delete; HTTP failed reencrypt із missing/wrong old key та незмінним ciphertext. Перевірено redacted toString, inbound accidental serialization, позитивну outbound serialization, safe exception graph/stack trace та captured application/framework logs із synthetic bearer/header canaries. Довільне стороннє raw wire/body logging не вмикалося і не є покритим контрактом.

## Фактичний runtime proof

**PASS18 assertions, exit0, TASK2A_BOUNDARY_PASS, CLEANUP_PASS**: [raw result із hashes](probes/stage1-boundary/result.txt). Reviewed diagnostic helper copy запустив справжній Codex0.156.1 із pinned штатним bwrap. Native command виконав permitted workspace write у workspaceWrite з explicit `networkAccess=false`.

Підтверджено: runtime UID34 не читає чотири synthetic control files і control `/proc` aliases; clean env; malformed/wrong caller/runtime helper invocation/symlink cwd rejection; Git fsmonitor під runtime UID; cleanup double-fork/setsid descendant; blocked-stdin cleanup; sibling unit залишається живим; Agent SIGKILL зупиняє owned runtime. Fixture створювала тільки random `/run/forge-stage1-UUID` і owned transient units, без permanent users/groups/services/sudoers, production secrets чи personal Codex home.

Proof використовує logging-instrumented helper copy. Independent parity review підтвердив еквівалентність packaged fixture, крім reviewed logging/source selection; packaged fixture повторно з root не запускався. Поточний product helper SHA256 `b86094c047153eb0fff0a9267afa9e72b2820ef190113a31a205ece3a13f5530`, fixture `e330d2d98145a1400116849de5578b5c23b138ede2ab3a790faccc73df9f048f`.

Попередні невдалі attempts не враховуються як PASS: зайнятий nobody UID; symlink `/usr/bin/env`; відсутній dedicated `.codex`; відсутній bundled bwrap; `ProcSubset=pid` приховував необхідний `/proc/sys/kernel/overflowuid`. Після reviewed corrections збережено ProtectProc=invisible та всі UID/cgroup/capability controls і повторено negatives. Cleanup підтверджено. Backup UID34 використано лише для disposable workload; це не рекомендація production account.

Stage0 перевіряв Codex0.155.1; його protocol/fresh/resume evidence збережене окремо у [Stage0 evidence](stage-0-evidence.md). Stage1 proof не переносить автоматично ту matrix на0.156.1. Окремий socket/reachability canary на0.156.1 — **NOT_VERIFIED**; sandbox network не відкривався.

## Review та виправлені дефекти

Task1 ACCEPT після row-lock/update-only/snapshot fixes. Task2a ACCEPT після transport/recovery owned-cleanup fixes, фактичного disposable proof і artifact parity. Task2b ACCEPT після mixed-case HTTPS Secure-cookie regression. Task3 ACCEPT після чотирьох corrections: credential envelope parity, safe client exceptions без raw upstream body/header/cause, log/serialization proof, повна CRUD/negative-rotation vertical. Focused final Task3: Agent3unit+9ForgeIT, Nexus7unit+12ForgeIT, scheduler1, усе green.

При першому full Agent run старий isolated scheduler fixture не мав нового guard bean. Fixture виправлено без послаблення production guard; фінальний повний run вище пройшов. Після rebase додано guard-before-channel/recovery ordering для Stage5 early startup; actual context RED4/1failure → GREEN guard4+scheduler1. Final whole-change audit: **ACCEPT для frozen a102de5c scope**, без нових блокерів у ньому; latest-main Stage6 integration не прийнята. Див. [review](stage-1-review.md).

Task3 частково написано до тестів; strict test-first sequencing не виконано. Actual defect RED/GREEN зафіксовано чесно; tests, які одразу були green, не названі RED за filename.

## Мінімальні operational prerequisites та NOT_VERIFIED

Повний порядок у runbook: окремі control/runtime accounts; trusted root-owned helper/config/executables та bundled bwrap; вузький sudo route; dedicated runtime HOME/.codex; shared workspace ownership/modes; protected key/service/DB та Nexus bootstrap/service files; exact operator origin і HTTPS або explicit loopback HTTP. Enabled startup виконує реальний runtime verifier для фактичних protected paths; `verified=true` bypass немає.

AES key source перечитує protected key file на кожну cipher operation; bootstrap/service/DB loaded credentials потребують restart для застосування. Після provisioning рекомендовано controlled restart для повторного startup proof. Pause — connection.enabled=false зі global flag=true. Deprovision/rollback потребує усунення retained credentials і protected paths; unknown orphan files/інша DB/старий binary guard не знаходить.

**NOT_VERIFIED:** installed sudoers caller routing; full production Java deployment; TLS rollout; provider login/history migration; reboot/power-loss recovery; окремий0.156.1 network-denial canary; співіснування з concurrently merged Stage6 auth. Жоден із цих пунктів не позначений PASS. Stage2 outbound policy/discovery, gateway, OAuth, UI та каталог не реалізовувалися.
