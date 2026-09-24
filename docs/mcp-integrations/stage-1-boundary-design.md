# Stage 1 / Task 2 — мінімальна control/runtime boundary

Read-only design review бази `/tmp/forge-mcp-stage1`, `edf49dbf`; Task 1 diff не перевірявся. Це проєкт реалізації, **не PASS deployment**. Джерела: `stage-1-plan.md` Task 2, `stage-0-evidence.md` 116–146, `roadmap.md` Stage 1 та наведені нижче production classes. Жодних host/service/personal-config змін.

## Рішення

Один default-off `forge.mcp.enabled` в Agent/Nexus. Коли false: старі runtime/API шляхи зберігаються, нові management endpoints недоступні. Коли true: увесь control API автентифікований; Codex **і Git** запускаються під окремим runtime UID через один вузький root-owned helper та наявний systemd. Нового довгоживучого сервісу, IAM, runner framework чи network listener немає. За відсутності prerequisite процес startup завершується відмовою, а не переходить до старого starter.

Helper створює transient systemd **service**, не scope, для кожного Codex/Git процесу. Це необхідно для гарантованого cleanup між UID; простий `sudo -u runtime codex` недостатній. Split Task 2 на 2a (launcher/boundary/proof) і 2b (auth), після обох — REST Task 3.

## Чому guards лише нових MCP routes недостатні

- `DefaultGitCommandRunner` успадковує backend env; `GitRepositoryAdapter.inspectLocalRepositoryOnly` виконує `git status` над runtime-writable `.git/config`. `core.fsmonitor` запускає команду під backend UID. Відтворено `python3 docs/mcp-integrations/probes/git_parent_env_probe.py`: exit 0, `REPOSITORY_HOOK_OBSERVED_PARENT_SYNTHETIC_CREDENTIAL=True`. Це evidence небезпечного baseline, не PASS ізоляції. Це відбувається також під час authorized refresh, тому сам auth не лікує.
- `LocalCliDockerLogAdapter.discoverComposeServices/validate` виконує `docker compose -f <runtime YAML> config --services`; `TypedProcessExecutor` успадковує повний env. YAML interpolation/include дають доступ до control env/files під час authorized discovery. Не передавати такі файли привілейованому Docker CLI.
- Existing unauthenticated Agent repository/log/SSH/runtime APIs плюс Nexus proxies — потенційні deputies. `ForgeAgentHttpClientConfiguration` не додає caller credential. Nexus має також Jarvis command/query та Knowledge actions; відкриті sibling APIs не можна автоматично оголосити безпечними.

Enabled-mode guards: Agent **`/api/v1/**`** вимагає тільки окремий service token. Nexus **`/api/v1/**`** вимагає operator session, крім точно названих session/login endpoints. Health можна залишити лише окремим безсекретним endpoint. Guard до controller/application для всіх HTTP methods, включно з streaming/async dispatch. Нормалізація context path `/fgaisox`, encoded paths та error dispatch не має давати bypass. Narrow API prefix guard без перебору controller methods менший і надійніший за ручний список ризикових routes.

Local Compose у enabled mode: **явно відхилити local compose discovery/validate/stream** до запуску CLI; звичайні Docker container-ID операції доступні лише authenticated operator. Це найменша чесна зміна; перенесення Compose parsing під runtime з окремим output-validation можна робити пізніше. Не вимикати всі TypedProcessExecutor операції і не давати runtime Docker socket/group. Відмова має бути задокументована як enabled-mode limitation, без fallback. Перед додаванням такого обмеження перевірити його application call sites, щоб discovery повертав контрольовану unsupported помилку.

## Launcher та права

1. Окремі control Agent/Nexus UIDs та runtime UID. Runtime не входить до control/docker/sudo groups. Control directories 0700; keys/DB/operator/service credential files 0600, без runtime ACL. Root-owned executable/config ancestors невritable control/runtime. Runtime home сталий, runtime-owned; жодного копіювання personal home.
2. Root-owned helper, наприклад `/usr/local/libexec/forge-runtime-launcher`, з root-owned fixed config. Якщо Python: absolute interpreter з `-I`, тільки stdlib, жодного імпорту з cwd/PYTHONPATH. Sudoers дозволяє Agent UID викликати **тільки цей helper**; не sudo systemd-run, shell, arbitrary python чи kill.
3. Helper verbs: `start codex UUID cwd`, `start git UUID argv...`, `stop UUID`, `probe`. Caller перевіряється за sudo/kernel context, UUID строга canonical форма. Unit name лише `forge-runtime-<installation>-<uuid>.service`; чужі names/PIDs/properties заборонено. Root-owned receipt в `/run/forge-runtime/` прив'язує unit до control UID/installation. Stop idempotent, bounded, підтверджує порожній cgroup.
4. Root частина лише validates fixed configuration/receipt та запускає fixed `/usr/bin/systemd-run`/`systemctl` argv. Ніякого shell eval, sourcing config, відкриття runtime-controlled cwd/config/plugin як коду root. Git arbitrary argv допускаються лише **після** fixed `/usr/bin/git` в непривілейованому ExecStart; helper не виконує Git для валідації під root. Не приймати caller-supplied environment, executable, UID, systemd property чи unit name.
5. Fixed transient service profile: User/Group runtime, empty supplementary privileged groups, `NoNewPrivileges=yes`, empty capabilities, `KillMode=control-group`, bounded stop then SIGKILL, controlled env/home/path. Root/control stores та `/proc` control environment недоступні. Cgroup migration/delegation runtime заборонено. Binding до Agent unit зупиняє units при backend exit/restart. Unit-level максимальний lifetime — останній захист від orphan під час crash між start/registration; startup reconciliation прибирає власні orphan units.
6. `systemd-run --pipe --wait --collect` (або еквівалентний verified stdio режим) зберігає stdin/stdout app-server; stderr helper diagnostics без secrets і окремо від JSON-RPC. Перевірити фактичний installed systemd у disposable fixture. Generic root-owned command executors не потрібні.
7. Trusted fixed Codex binary та dedicated CODEX_HOME. Writable provider auth/history дозволені; config/plugin startup executables можуть виконуватися лише під runtime UID. Root-owned runtime configuration, де CLI це підтримує; hostile project/home/plugin canary не може отримати control secrets чи authority. Наявність стороннього MCP inventory Stage 4 не оголошувати вирішеною Stage 1. Personal provider auth не мігрувати. Shell sandbox/network policy зберігається; launcher не вмикає shell network.
8. Shared workspace permissions: setgid/ACL тільки для managed workspace tree, щоб Java-created clone staging був writable runtime і runtime-created repositories читав control. Не chmod control home/config. Workspace symlink validation до backend filesystem writes має лишитися; runtime symlinks не повинні перетворювати cleanup/mkdir на control write. Permission/TOCTOU negative fixture обов'язковий.

`config/systemd/forge-agent.service.in` зараз `NoNewPrivileges=true`; sudo не працюватиме. Додати **окремий opt-in drop-in** enabled deployment `NoNewPrivileges=false`, не послаблювати існуючий default unit. Runtime transient services залишають `NoNewPrivileges=true`. Якщо політика не дозволяє цей вузький opt-in, конкретний launcher design BLOCKED; не обходити це альтернативним privileged каналом.

## Найменше включення lifecycle у Java

Спільні launch/lifecycle classes у **наявному infrastructure/local**, без нового Maven module. `ManagedRuntimeProcess` делегує Process streams/wait/exit до pipe process та має idempotent bounded `terminateOwnedUnit()`. `destroy`/`destroyForcibly` зупиняють unit через helper перед локальним pipe cleanup. Не вважати pipe exit доказом відсутності descendants.

- `DefaultCodexAppServerProcessStarter`: enabled branch викликає shared launcher; off branch незмінний.
- `CodexProcessTree.capture/terminateTree`: якщо ManagedRuntimeProcess, використовує owned-unit termination; інакше чинний ProcessHandle algorithm. Це покриває обидва existing callers — `CodexJsonRpcTransport` і `CodexRecoveryLifecycle`; не маскувати cross-UID проблему synthetic ProcessHandle.
- `DefaultGitCommandRunner`: enabled branch обгортає fixed git через shared launcher, зберігає stream limits/deadlines; cleanup managed branch зупиняє unit, а не `/bin/kill -PGID` (backend UID не має signal permission). Після **успішного** завершення теж перевірити/прибрати descendants unit; hostile hook може fork/setsid. Старий python/Perl setsid path лише off mode.
- `GitRepositoryAdapter`: захист option operands/remote syntax, де потрібно, але `-c core.fsmonitor=false` чи `core.hooksPath=/dev/null` не приймати як самостійну UID boundary.

## Auth без нового framework

Nexus servlet filter + session controller/service: protected bootstrap credential file, constant-time digest comparison, high-entropy opaque session ID, bounded server-side session store/TTL, fixation prevention, logout invalidation. Host-only HttpOnly SameSite=Strict cookie з явним Path; Secure для HTTPS. Exact configured Host/Origin, не довіряти forwarded headers без окремо встановленого proxy. Login вимагає правильний Origin/Host; cookie-auth mutations — Origin та session-bound unpredictable CSRF header; session GET дає CSRF лише same-origin. `Cache-Control: no-store`, generic auth errors, без body/token logging. Немає UI у Stage 1: documented HTTP bootstrap/session flow достатній.

Agent filter: окремий random service credential зі protected file, constant-time comparison, лише визначений service Authorization scheme/header; operator bootstrap/session/runtime bearer ніколи не підходять. Nexus typed RestClient **і streaming HttpClient** додають service credential до fixed Agent origin; redirects вже NEVER. Не проксувати довільний caller Authorization. API не повертає bootstrap/service/key paths/bytes.

Enable validation читає protected files без symlink та з owner/mode/ancestor checks; перевіряє configured executable/helper, distinct UID, реальний launcher probe з синтетичними canaries та runtime stop. Ніяких `verified=true`/marker-file bypass. Probe результат не має містити реальних credentials. Disposable deployment suite — додатковий acceptance доказ, не заміна runtime fail-closed config validation.

## Точні файли (від repo root; A=services/forge-agent, N=services/forge-nexus)

Existing зміни:
- `A/infrastructure/codex/src/main/java/com/sitionix/forgeagent/infrastructure/codex/DefaultCodexAppServerProcessStarter.java`
- `A/infrastructure/codex/src/main/java/com/sitionix/forgeagent/infrastructure/codex/CodexProcessTree.java`
- `A/infrastructure/git/src/main/java/com/sitionix/forgeagent/infrastructure/git/DefaultGitCommandRunner.java`
- `A/infrastructure/local/src/main/java/com/sitionix/forgeagent/infrastructure/local/LocalCliDockerLogAdapter.java`
- `A/infrastructure/local/src/main/java/com/sitionix/forgeagent/infrastructure/local/LocalProjectWorkspaceAdapter.java` (лише якщо fixture виявляє needed permissions/path fix)
- `A/infrastructure/codex/pom.xml`, `A/infrastructure/git/pom.xml` (local dependency; для git чинний test scope замінити compile)
- `A/boot/src/main/resources/application.yml`, `N/boot/src/main/resources/application.yml`
- `N/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/ForgeAgentHttpClientConfiguration.java`
- `N/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/ForgeAgentLogStreamingHttpClient.java`
- `scripts/systemd/render-units.sh` (лише optional artifact), deployment docs; existing default units не послаблювати.

New production files:
- `A/infrastructure/local/src/main/java/com/sitionix/forgeagent/infrastructure/local/runtime/{RuntimeBoundaryProperties,RuntimeBoundaryVerifier,RuntimeProcessLauncher,ManagedRuntimeProcess}.java`
- `A/infrastructure/local/src/main/java/com/sitionix/forgeagent/infrastructure/local/mcp/ProtectedMcpKeySource.java` (узгодити Task 1 cipher port)
- `A/api-rest/src/main/java/com/sitionix/forgeagent/api/security/{McpManagementProperties,AgentManagementAuthenticationFilter,ProtectedCredentialFile}.java`
- `N/api-rest/src/main/java/com/sitionix/forgeai/api/security/{McpManagementProperties,OperatorSessionService,OperatorSessionController,OperatorManagementAuthenticationFilter,ProtectedCredentialFile}.java`
- `N/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/AgentServiceCredential.java` (protected file reader shared within existing appropriate module; не api-rest dependency)
- `scripts/runtime/forge-runtime-launcher.py`, `config/systemd/forge-agent-mcp-isolation.conf.in`, `config/sudoers/forge-runtime.in`, `docs/mcp-integrations/stage-1-operations.md`
- `docs/mcp-integrations/probes/stage1-boundary/` (disposable image/VM fixture, scripts, synthetic test sources; no production paths).

Tests — існуючі `DefaultGitCommandRunnerTest`, `CodexAppServerClientTest`, transport/recovery tests + sibling runtime/auth tests. HTTP integration через existing `ForgeAgentTestManager`/`NexusProxyTestManager`, endpoints/contracts і fixture resources, не паралельний ad hoc integration framework.

## Disposable acceptance та реальні blockers

1. Fixture з реальним systemd/cgroup та трьома UID; isolated temp DB, synthetic credentials, no production mounts/sockets. Якщо Docker fixture не дає systemd/cgroup, потрібна disposable VM; звичайний root shell/chroot недостатній доказ lifecycle.
2. Реальний runtime PID не читає key/DB/operator/service files, backend `/proc/PID/environ` і cwd/fd aliases, не змінює control files/helper/config/management authority; inherited env canaries відсутні. Allowed workspace write проходить.
3. Hostile git fsmonitor/hooks/ssh command/helper під час **backend-triggered status/fetch** запускаються тільки runtime UID і не читають control canaries. Verify option-looking remotes, symlink outside workspace та persisted hostile config. Authenticated Compose request fails до CLI; off mode regression лишається.
4. Codex start/JSON-RPC/normal close, timeout, blocked stdin, recovery cancellation, backend SIGKILL/restart, child double-fork+setsid та Git hook orphan: cgroups стають порожні, сусідній execution лишається живим. Wrong UUID/caller cannot stop foreign unit. Runtime не може створити privileged systemd unit чи переміститися в інший cgroup.
5. API correct operator/service проходить; missing/wrong/runtime credential, hostile Host/Origin, CSRF/expiry/logout reject з zero application/upstream calls. Перевірити existing repository/log/SSH routes та streaming route, не тільки MCP. Both feature flags mismatch must fail safely (не відкривати Agent заради compatibility).
6. Unsafe/missing key/token file/helper, sameUID, forbidden NoNewPrivileges setup, unavailable cgroup/cleanup proof — startup fail. False mode запускає existing tests unchanged.

Поточні конкретні blockers: sameUID ProcessBuilder; Git backend execution; Compose backend parsing; відсутні guards; Java cleanup не може сигналити іншому UID; sudo конфліктує з default NNP; жодного privileged disposable deployment PASS ще немає. Unit tests/mock launcher не закривають ці blockers. Якщо actual fixture недоступний — позначити NOT_VERIFIED і не заявляти Stage 1 enablement acceptance; default-off код можна перевіряти, production не змінювати.
