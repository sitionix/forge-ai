# Stage1 — фінальний незалежний аудит frozen source

Перевірений checkout: `/tmp/forge-mcp-stage1`, `feature/SITIONIX-142`; immutable base **a102de5c** (Remote Access Stage5). Audit-only: продукт не редагував, завершені suites/root fixture не повторював, Git/PR/мережу/deployment не змінював. Застосовано skill `review` та його review-strategy. Це висновок про код і докази, не прийняття actual PR/merge.

**Нових actionable blocking findings у frozen Stage1 проти a102de5c не виявлено.** Попередні Task1/Task2a/Task2b/Task3 accepted gates узгоджуються з перевіреними міжшаровими контрактами.

## Межа щодо нового main: конкретний integration blocker

Під час аудиту `origin/main` уже вказував на **58f2854f**, Remote Access Stage6 (#147). Перший пакет помилково використовував рухомий ref і показував Stage6 deletions; root перебудував пакет проти immutable a102de5c. Explicit diff a102de5c містить145 змінених tracked файлів; це не фактичне видалення Stage6 з історії та не three-dot PR deletion delta.

**P2, integration follow-up перед прийняттям PR проти нового main:** `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/security/AgentManagementAuthenticationFilter.java:25` перевіряє єдиний `Authorization` для всіх control routes за MCP service credential. У новому main `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/remoteaccess/RemoteAccessServiceFilter.java` незалежно перевіряє цей самий header на `/api/v1/remote-access/**` за Remote Access service secret; новий `RemoteAccessHttpClientConfiguration` передає саме Remote Access token. За одночасного ввімкнення обох features та різних configured secrets правильний Remote Access request одержить401 від MCP guard. Статично підтверджено читанням коду обох refs; merged execution не запускався. Це не дефект frozen Stage5-бази, але **відомий конфлікт**, а не лише абстрактне NOT_VERIFIED. Не вирішувати простим вимкненням загального guard чи випадковим спільним secret. Потрібен окремий вузький auth-integration крок із явною моделлю довіри й combined-mode HTTP tests, збереженням negative caller/CSRF checks.

Nexus також матиме два session flows: `OperatorManagementAuthenticationFilter.java:41` звільняє від session check лише MCP login, тож новий Stage6 `/api/v1/infrastructure/agents/remote-access/operator/login` спочатку вимагатиме MCP cookie/CSRF. Потрібно явно узгодити і перевірити supported операторський flow після інтеграції; поточні green suites цього не доводять. Жодного broad auth redesign у цьому аудиті не виконано.

Отже draft PR із цим явно названим pending integration допустимий за авторизацією користувача; latest-main code acceptance/mergeability з цього аудиту не випливає.

## Перевірені міжшарові контракти

- Installation owner береться з чинного identity repository. Metadata reads не повертають ciphertext; parent/children читаються одним snapshot. Locked aggregate mutation, update-only шлях та transaction boundary зберігають credential/endpoint узгодженість і rollback.
- AES-GCM AAD включає installation/connection/purpose; active/previous key selection та write-only KEEP/REPLACE/REMOVE узгоджені з typed Agent/Nexus envelopes. Reencrypt не повертає plaintext і не зберігає частковий replacement після помилки. Обидва read API залишають лише metadata/credentialConfigured.
- ALL/SELECTED явні, empty SELECTED не стає ALL; перевіряється існування project. Stage1 не має discovery й не дозволяє caller самостійно заповнити tool allowlist. Provider calls, gateway, runtime injection, OAuth і UI не додані.
- Enabled guard охоплює sibling control routes; fixed-origin Nexus service forwarding і streaming додають service credential, redirects вимкнені. Session, Host/Origin, CSRF і safe exception boundary узгоджені між шарами. Retained-secret downgrade guard запобігає flag-only поверненню до baseline UID/безauth.
- Runtime ready gate не дозволяє fallback до same-UID Codex/Git. Helper використовує fixed executable/config, runtime UID, clean environment та owned systemd units; stop-before-start tombstone/receipt і owned-stop acknowledgement відрізняють завершення pipe від cleanup unit. Git hook code виконується runtime UID; local Compose у enabled-mode відхиляється до CLI.
- Protected workspace parents і descriptor-relative NOFOLLOW cleanup закривають backend filesystem deputy. Existing off-mode branches збережені. Opt-in NNP drop-in не змінює default service unit.
- Нові Stage5 channel/recovery bean dependencies явно ставлять `mcpDowngradeGuard` перед authority socket/startup recovery. Regression context tests з retained credentials перевіряють guard refusal до authority/server resolution; це не socket/production startup proof.

Ledger rulings (більш широкий auth, Git UID, Compose limitation, systemd helper, protected parents, reencrypt route, guard downgrade та відмова від ProcSubset=pid) мають конкретну причину в security boundary та не створюють зовнішнього MCP functionality. Зняття ProcSubset не видається за новий network-denial proof. Task3 tests-first порушення відкрито задокументовано; історію не переписано, contract negative coverage після fix перевірено.

## Докази та їхні межі

На диску перевірено final reactor BUILD SUCCESS і XML totals:

- Agent: **1257 tests, 0 failures, 0 errors, 9 opt-in live skips** (`final-agent-verify.log`, `final-agent-results.json`). Skips — 8 Codex live checks та1 RemoteAccessLiveExecution; це не пройдені live tests.
- Nexus: **306 tests, 0 failures/errors/skips** (`final-nexus-verify.log`, `final-nexus-results.json`).
- `git diff --check a102de5c`: exit0 під час цього аудиту.
- Unchanged helper11 та packaged fixture14 unprivileged tests PASS за final execution evidence; не повторювалися мною.
- Actual disposable runtime proof —18 assertions/exit0 з diagnostic helper copy, Codex0.156.1 та pinned bwrap; окремий artifact parity audit прийняв packaged fixture. Не підміняю цей факт твердженням про повторний root run final packaged fixture.

Production Java/backend deployment, installed sudoers routing, provider login, real external MCP calls та незалежний network reachability/denial canary Codex0.156.1 залишаються **NOT_VERIFIED**. Stage0 network evidence стосується0.155.1. Auth ForgeIT mock runtime verifier не є OS isolation proof; disposable helper proof не є full Java deployment. Ці межі відповідають default-off Stage1 code acceptance, але не deployment approval.

`stage-1-evidence.md` на момент читання ще містив historical IN_PROGRESS/pending і старе речення «Typed MCP CRUD … not implemented». Root має перед публікацією доповнити final totals/audit scope/latest-main caveat та позначити ці записи як історичні. Це delivery evidence cleanup, не product code finding.

Spec verdict: **ACCEPT для frozen Stage1 проти a102de5c**.

Quality/security verdict: **ACCEPT для того самого frozen scope**, з окремим невирішеним Stage6 integration blocker вище; latest-main integration не прийнята.

Код frozen scope прийнято; root може виконати явно авторизоване створення draft PR з наведеними обмеженнями. Actual PR/CI review та user final review залишаються окремими gates; merge/deploy не схвалено.

**ACCEPT**

Delivery note: historical pending/not-implemented evidence wording was replaced with final results and the concrete Stage6 integration blocker before publication. Product code unchanged after audit.
