# MCP Integrations — Stage 0 evidence

Дата: 2026-09-23. **Stage 0 audit/probes виконано; production readiness — BLOCKED.**
Stage 1 та наступні стейджі не реалізовано. PASS нижче стосується лише явно названого probe, а не всієї інтеграції.

## Scope та джерела

Прочитано повністю `/home/vlad/Downloads/FORGE_AI_MCP_INTEGRATIONS_ROADMAP_2026-09-23.md` (851 рядок). Погоджені Settings → Integrations, Console → typed Nexus → Agent, глобальні connections, Forge-owned credentials/OAuth, execution-scoped gateway та native Codex tools збережено.

Перевірено checkout `3dcc43c2547793a2a939e50d072c7e39ef720f27`, звичайна гілка `feature/SITIONIX-139`. Нової гілки/worktree, commit, PR, metadata, comments чи merge не створювали. На початку вже були зміни `application.yml`, `CodexAppServerClient.java`, `CodexAppServerProperties.java`, `CodexAppServerTurnClientTest.java` та untracked `docs/superpowers/plans/2026-09-21-global-node-working-repositories.md`. Їх не редагували; висновки про config враховують саме робочий код.

Прочитано кореневий `AGENTS.md`, надані користувачем правила, `Codex-Task-Lifecycle-&-PR-Review-Strategy.txt`, чинні файли/тести та roadmap Remote Access. Окремий `Forge-AI-Task-and-PR-Review-Rules(1).md` не знайдено у перевірених repo/Downloads/Documents/skill locations: **NOT_VERIFIED**; не заявляємо, що прочитано відсутній документ. Typed Nexus та ForgeIT перевірено безпосередньо в коді.

Найближчі прийняті в історії референси: `c62d0bd9` / #140 для working repositories і Nexus contracts; `da8107c2` / #113 для resource/SSH management vertical. `docs/remote-access/evidence.md` — окремий audit/probe, **не production security implementation**. `docs/codex-durable-session-protocol-audit.md` — історичний референс; його твердження повторно перевірено проти нового binary. Зовнішнє прийняття поточного незакоміченого коду тут не встановлюється.

## Підсумок перевірок

| Перевірка | Результат | Межа доказу |
| --- | --- | --- |
| Installed Codex та generated protocol | PASS: `codex-cli 0.155.1`, звичайна й experimental JSON schemas | Це встановлений CLI, не припущення з online latest |
| Native MCP call fresh | PASS: synthetic model → native `mcp__forge_probe.echo` → HTTP fixture | Справжній Codex runtime; Responses provider є локальним deterministic fixture |
| Resume у новому PID | PASS: той самий thread ID, roots і provider; grant A замінено B; native call працює | Після завершеного turn, зі сталим disposable CODEX_HOME |
| Home/project/plugin merge | PASS як діагностика: всі три sentinel MCP стали callable | Додавання одного Forge entry НЕ замінює інвентар |
| Explicit suppression | PASS для fixture: fresh/resume мають лише Forge tool; disabled direct call відхилено | Динамічні шкідливі зміни project config/нові plugins — NOT_VERIFIED |
| Shell network | PASS: socket до відомого живого loopback fixture відхилено, MCP працює | `workspaceWrite`, `networkAccess=false`; загальний мережевий pentest не проводився |
| Secret files та inherited environment | **FAIL boundary**: synthetic key/DB/management files читаються; parent env canary успадковується | Файли 0600 поза workspace, той самий UID; очистка env не закриває файли |
| `/proc` parent access | Host PID не видимий у sandbox: ENOENT | **NOT_VERIFIED** для всіх sibling/parent шляхів і production deployment; не називати це повною ізоляцією |
| Production management auth | **BLOCKER**: захист operator/service identity у перевірених Agent/Nexus шарах відсутній | Статичний audit; live management requests не виконували |
| SDK на поточній платформі | PASS: Java 21 / Boot 3.3.4 / MCP 0.18.3, HTTP discovery/call для 3 revisions | Disposable embedded Tomcat; не повний Forge gateway |
| OAuth library | PASS: Spring Security OAuth2 Client 6.3.3, генерація PKCE S256 | Реальні grants, callback/refresh/issuer/resource binding — NOT_VERIFIED |
| Forge UI / DB / actual hosted-provider E2E | **NOT_VERIFIED** | Production не запускали/не змінювали; mock provider не дорівнює live OpenAI account |

## Точна карта чинного коду

Шляхи нижче від кореня репозиторію. Позначення префіксів:

- `A` = `services/forge-agent`; Java package root = `src/main/java/com/sitionix/forgeagent`.
- `N` = `services/forge-nexus`; Java package root = `src/main/java/com/sitionix/forgeai`.
- `C` = `services/forge-console`.

Це існуючі файли; запропоновані нові файли наведено окремо у плані.

| Відповідальність | Існуючий файл / точка розширення |
| --- | --- |
| Один sidebar, майбутній Settings | `C/src/operator/operator-bootstrap.js`, `initSidebar()` на рядку 56: Projects/Jarvis/Knowledge; Settings зараз немає. Узгодити з Remote Access перед Stage 5 |
| Console HTTP через Nexus | `C/src/operator/infrastructure-http-client.js`, `createInfrastructureHttpClient()`; context path + `/api/v1/infrastructure`; `C/src/operator/agent-projects-api.js` |
| Console contracts/tests | `C/tests/http-client.test.ts`, `agent-projects-api.test.ts`, `operator-modular-ownership.test.ts`, `resource-workspace.test.ts`, `working-repositories.test.ts`; Vitest/jsdom у `C/package.json` |
| Nexus typed REST | `N/api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiProjectAssetsController.java` → `api/agentproxy/AgentProxyApiMapper.java`; typed request/response, `@Valid`, UUID |
| Nexus application/domain | `N/application/src/main/java/com/sitionix/forgeai/application/agentproxy/AgentProjectAssetsUseCase.java` → `N/domain/src/main/java/com/sitionix/forgeai/domain/usecase/ManageAgentProjectAssets.java` та `domain/port/ForgeAgentClient.java` |
| Nexus upstream adapter | `N/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/ForgeAgentClientAdapter.java`, `ForgeAgentHttpClient.java`, `dto/ProjectAssetRequest.java`, `dto/ProjectAssetResponse.java` |
| Nexus ForgeIT | `N/boot/src/test/java/com/sitionix/forgeproxyit/NexusAgentProxyIT.java`; `infra/NexusProxyTestManager.java`, `NexusAgentMockMvcEndpoints.java`, `ForgeAgentWireMockEndpoints.java`; JSON fixtures у `N/boot/src/test/resources` |
| Agent REST reference | `A/api-rest/src/main/java/com/sitionix/forgeagent/api/ProjectAssetsController.java`, `api/dto/ProjectAssetResponse.java` |
| Agent application transactions | `A/application/src/main/java/com/sitionix/forgeagent/application/usecase/ProjectAssetUseCases.java`: `@Transactional`, read-only reads, project existence/ownership checks; для MCP не копіювати його eager network probe у Stage 1 |
| Installation ownership | `A/domain/src/main/java/com/sitionix/forgeagent/domain/model/Project.java`: UUID/name/normalizedName/timestamps, без tenant/user ID. Global означає поточну інсталяцію |
| Domain/repository boundary | `A/domain/src/main/java/com/sitionix/forgeagent/domain/model/ProjectAsset.java`, `domain/port/ProjectAssetRepository.java` |
| PostgreSQL adapter | `A/infrastructure/postgres/src/main/java/com/sitionix/forgeagent/infrastructure/postgres/adapter/PostgresProjectAssetRepository.java`, `entity/ProjectAssetEntity.java`, `repository/SpringDataProjectAssetRepository.java` |
| Migrations | `A/infrastructure/postgres/src/main/resources/db/migration/V23__add_project_assets.sql`; актуальний max — `V36__add_node_working_repositories.sql`; наступний номер перевизначити перед Stage 1 |
| DB/REST ForgeIT | `A/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentProjectAssetIT.java`, `ForgeAgentProjectIT.java`, `ForgeAgentRuntimeMigrationIT.java`; `it/infra/ForgeAgentMockMvcEndpoint.java`, `it/infra/db/ForgeAgentDbContracts.java`, `it/infra/ForgeAgentTestManager.java` |
| Unit tests reference | `A/application/src/test/java/com/sitionix/forgeagent/application/usecase/ProjectAssetUseCasesTest.java`, `ProjectUseCasesTest.java` |
| Existing SSH secrets — НЕ security reference | `A/infrastructure/postgres/src/main/java/com/sitionix/forgeagent/infrastructure/postgres/adapter/PostgresSshConnectionRepository.java`: `setPassword(connection.password())`; `db/migration/V20__add_ssh_password_authentication.sql`: password TEXT. Не перевикористовувати plaintext pattern для MCP |
| Execution/project authority | `A/application/src/main/java/com/sitionix/forgeagent/application/runtime/NodeExecutionClaim.java`, `WorkflowExecutionCoordinator.java`, `ExecutionWorkspaceResolver.java`, `ExecutionWorkspace.java` |
| Snapshot | `A/application/src/main/java/com/sitionix/forgeagent/application/runtime/WorkflowRunSnapshotBuilder.java`; зберігати лише safe connection/tool identifiers, не credentials; не плутати workflow graph snapshot із актуальною invocation policy |
| Session/recovery owner | `A/application/src/main/java/com/sitionix/forgeagent/application/runtime/AgentSessionLeaseService.java`, `AgentExecutionRecoveryService.java`; `A/infrastructure/postgres/src/main/resources/db/migration/V26__create_agent_execution_sessions.sql`, `V29__add_agent_execution_recovery.sql` |
| Runtime dispatch | `A/infrastructure/codex/src/main/java/com/sitionix/forgeagent/infrastructure/codex/CodexAgentExecutor.java`: формує `CodexTurnRequest`, callbacks зберігають provider conversation/turn identities; `CodexRuntimeAdapter.java` — capabilities/models |
| Fresh/resume | Той самий codex package: `CodexAppServerClient.java` (`threadStartParams`, `codexConfig`, durable dispatch), `CodexSessionProtocol.java` (`resumeThread`), `CodexTurnRequest.java` |
| Process boundary | Той самий package: `DefaultCodexAppServerProcessStarter.java`, `CodexAppServerProperties.java`: `new ProcessBuilder(command)`, cwd, без env allowlist, UID separation чи окремого CODEX_HOME |
| Existing activity | Той самий package: `CodexAgentExecutionEventMapper.java` (`mcpToolCall` → `TOOL_CALL`), `CodexEventPayloadSanitizer.java`; `A/application/.../runtime/AgentExecutionEventRecorder.java`. Sanitizer евристичний; це не гарантія довільного secret redaction |
| Codex regression references | `A/infrastructure/codex/src/test/java/com/sitionix/forgeagent/infrastructure/codex/CodexDurableSessionProtocolTest.java`, `CodexAppServerTurnClientTest.java`, `CodexDurableSessionE2ETest.java`, `CodexRecoveryE2ETest.java` |
| Deployment | `scripts/runtime/run-agent.sh`, `config/systemd/forge-agent.service.in`, `forge-nexus.service.in`, `A/boot/src/main/resources/application.yml`, `N/boot/src/main/resources/application.yml` |

Nexus лишається DTO → mapper → use case → domain client port → typed HTTP client. MCP schema/arguments належать SDK infrastructure; management API не стає `JsonNode` proxy. Наявний Agent asset IT містить також raw MockMvc виклик: для нового slice використовувати його ForgeIT contracts/fixtures, **не копіювати raw helper**. Окремого встановленого browser E2E runner у перевіреному Console package не знайдено; jsdom tests не називати browser live E2E.

## Installed protocol та config

Встановлений executable: `/home/vlad/.nvm/versions/node/v24.19.0/bin/codex`, CLI `0.155.1`. Підтримана команда:

```sh
mkdir -p /tmp/forge-mcp-stage0/home /tmp/forge-mcp-stage0/schema-experimental
CODEX_HOME=/tmp/forge-mcp-stage0/home codex --version
CODEX_HOME=/tmp/forge-mcp-stage0/home codex app-server generate-json-schema \
  --experimental --out /tmp/forge-mcp-stage0/schema-experimental
```

Згенеровано також stable schema без `--experimental`. У [protocol-summary.json](probes/protocol-summary.json) збережено SHA-256 та поля важливих experimental schemas. `runtimeWorkspaceRoots` є experimental; Forge уже ініціалізує `experimentalApi=true`. `thread/start.config` та `thread/resume.config` є в stable schema. `mcpServerStatus/list` має thread-scoped `threadId`, pagination; `mcpServer/tool/call` підтримує `threadId/server/tool/arguments`. RPC call сам по собі не доводить model-driven dispatch: останній окремо перевірено synthetic Responses turn.

У Forge `threadStartParams()` передає cwd, roots, sandbox, approval policy і config; durable start робить `ephemeral=false`. `CodexSessionProtocol.resumeThread()` наразі передає threadId, excludeTurns та optional developerInstructions — **не свіжі config/roots/policy**. Встановлений protocol підтримує потрібні overrides, тому upgrade Codex для цього не доведено необхідним; потрібно розширити чинний adapter у Stage 4.

Перевірена форма config для invocation (значення grants лише в process env):

```json
{
  "mcp_servers": {
    "forge_probe": {
      "url": "http://127.0.0.1:<fixture-port>/gateway",
      "bearer_token_env_var": "FORGE_PROBE_GRANT_B",
      "enabled_tools": ["echo"],
      "tools": {"echo": {"approval_mode": "approve"}}
    },
    "home_sentinel": {"enabled": false},
    "project_sentinel": {"enabled": false}
  },
  "plugins": {"sentinel@stage0": {"enabled": false}},
  "web_search": "disabled",
  "sandbox_workspace_write.network_access": false
}
```

Використовувати той самий config builder на fresh/resume, invocation-specific aliases/roots та grants. Runtime grant доступний процесу; це дозволено моделлю загроз. Зовнішній provider token, master key і DB credentials не повинні входити до цього env.

**Approval finding:** без explicit tool approval native model call завершився `MCP tool call requires approval, but approval policy is never`, хоча direct `mcpServer/tool/call` працював. Після `tools.echo.approval_mode=approve` реальний native dispatch пройшов. Це не підстава довіряти upstream `readOnlyHint`: Forge затверджує лише свій allowlist, gateway знову перевіряє його сервером.

**Layers finding:** `config/read(includeLayers=true,cwd=...)` показав system, user, trusted project layers; synthetic plugin встановлено через `plugin/install` у disposable HOME. У baseline inventory були `forge_probe`, `home_sentinel`, `project_sentinel`, `plugin_sentinel`. Після overrides у resume та окремому fresh thread callable тільки `forge_probe`; disabled home direct call повернув error, upstream counter для `must-not-call` = 0. Порожній або частковий `mcp_servers` не можна трактувати як replace-all.

**Durability finding:** створення порожнього durable thread і негайне завершення процесу дало `no rollout found`. Після завершеного synthetic turn resume в іншому PID працює; це failure-path evidence, не production fix. Збережено той самий thread ID, cwd, roots, provider та synthetic provider authentication. Native call після resume використав grant B. Наявні provider auth/history не копіювали з особистого HOME.

Пропонований production isolation path: сталий Forge-managed Codex home для довготривалих provider sessions; окремо provisioned provider auth; process/config allowlist до будь-якого MCP startup. Не робити ephemeral home на invocation. Перед turn перевіряти всі сторінки effective thread inventory, відхиляти незатверджені servers. Ця перевірка сама по собі запізніла для запобігання startup I/O: read-only controlled config/plugin stores або надійне відключення project/plugin layers потрібні як prerequisite. Race між inventory та зміною repository config і actual personal-history migration — **NOT_VERIFIED**. Не переносити особистий config автоматично.

## Реальні sandbox, secret і management boundaries

Read-only `systemctl show` активних `forge-agent.service` і `forge-nexus.service` встановив: обидва `User=vlad`, `Group=vlad`, `PrivateNetwork=no`, `ProtectHome=no`, `ProtectProc=default`, `NoNewPrivileges=yes`. Environment/production secret values не читали. `run-agent.sh` передає DB URL/user/password у Java env; ProcessBuilder успадковує його. Файл 0600 того самого UID не є межою між backend і runtime.

У справжньому Codex `command/exec` із `sandboxPolicy.workspaceWrite`, networkAccess=false отримано:

```text
key_file_readable=true
db_file_readable=true
management_file_readable=true
inherited_parent_env=true
shell_gateway_reachable=false (Operation not permitted)
parent_proc_error=ENOENT для host PID
```

Після unset synthetic parent env: `inherited_parent_env=false`, але всі три файли й далі readable. Це перевірені **негативні security results**. Файли містять тільки synthetic canaries, лежать поза workspace у власному temp root. Production `/proc/*/environ`, secret files чи БД не читали. Host parent PID не видимий у nested sandbox; інші PID aliases/siblings не перебирали, тому повна `/proc` secrecy — NOT_VERIFIED.

Шлях мережі: Codex app-server MCP HTTP client → Agent internal gateway; Agent SDK HTTP client → external server. Shell не потребує Internet чи localhost socket permission для MCP: у fixture MCP успішний одночасно з socket denial shell. Реальна нова gateway route ще не існує, зовнішній HTTPS/TLS/SSRF boundary буде Stage 2/3; production reachability/egress — NOT_VERIFIED. Наявну незакомічену `network-allowed-domains` гілку коду не вмикали й не змінювали.

Пошук `SecurityFilterChain`, `OncePerRequestFilter`, `PreAuthorize`, `HandlerInterceptor`, `FilterRegistrationBean`, `CrossOrigin`, `spring-security` у Agent/Nexus production Java/POM не знайшов operator/service security boundary. Typed controllers перевіряють форму та domain project, але не caller identity. Agent YAML не встановлює `server.address`. Nexus має context path `/fgaisox`; Console HTTP client не додає operator auth чи CSRF token. Loopback/default CORS не є автентифікацією. Живі management запити для підтвердження не робили, бо поточний сервіс/БД production.

**Одна prerequisite-задача перед secret-bearing Stage 1 API: «Відокремити Forge control plane від Codex runtime».** Мінімальний scope:

1. Окремий непривілейований runtime UID/process boundary; control-owned key/DB/operator-service credential files недоступні runtime; env allowlist. Закрити читання backend `/proc` і контрольних файлів actual deployment tests. Окремого мікросервісу не потрібно; використати чинний process starter/service packaging. DynamicUser Remote Access probe — лише reference, не вже готовий механізм.
2. Один installation operator login/session у Nexus, HttpOnly/SameSite cookie, CSRF та перевірка Origin для mutation; bootstrap credential поза runtime доступом. Agent management приймає лише автентифікований Nexus service caller. Runtime grants окремого audience/path не приймаються management boundary. Не додавати tenant/IAM framework.
3. Persistent runtime home/auth + контрольовані config layers; workspace roots лишаються чинними. Жодних зовнішніх MCP credentials у runtime. Explicit gateway-only access для MCP process без відкриття shell network.
4. Gate: disposable deployment proof того, що runtime не читає всі чотири класи canaries та не змінює management policy; authorized operator працює; unauthorized/Origin/CSRF rejection дає zero application/upstream calls. До цього management slice лишається закритим.

Це конкретний необхідний prerequisite, **не виконана реалізація**. Розташування control files, UID provisioning і перенесення existing provider auth треба перевірити у deployment fixture перед production enablement.

## SDK / protocol / OAuth matrix

Обрано офіційний `io.modelcontextprotocol.sdk:mcp:0.18.3` (Jackson 2 facade), без Spring AI. Використати `HttpClientStreamableHttpTransport` та `HttpServletStreamableServerTransportProvider` у наявному Agent. Не потрібні SDK Spring WebMVC/WebFlux wrappers, новий web stack чи ручний MCP codec.

| Плече | Перевірений контракт |
| --- | --- |
| Codex 0.155.1 → synthetic HTTP fixture | Client запропонував `2025-06-18`, fixture погодив `2025-03-26`; initialize → initialized → list/call працюють |
| Java SDK 0.18.3 client → SDK servlet server | Окремі initialize/list/call для `2025-03-26`, `2025-06-18`, `2025-11-25` успішні на Boot 3.3.4 |
| Майбутній Codex → Java gateway | Спільна ціль `2025-06-18`; прямий Codex-to-Java-server E2E **NOT_VERIFIED** (окремі плечі перевірені) |
| Майбутній gateway → provider | SDK підтримує negotiation; реально перевірені три revisions вище. Provider-specific compatibility **NOT_VERIFIED** |
| `2024-11-05` | Є в SDK transport source supported list; remote HTTP acceptance тут **NOT_VERIFIED** |
| `2026-07-28` | У runtime effective flags вимкнена; у SDK 0.18.3 supported list відсутня. Не заявляти підтримку й не змішувати lifecycle з older revisions |
| OAuth | `org.springframework.security:spring-security-oauth2-client:6.3.3` (Boot managed); PKCE S256 builder працює. OAuth MCP metadata/resource/issuer binding, refresh concurrency та callback — майбутні Stages 6–7 |

SDK POM базується на Java 17, тому Java 21 достатня. Опубліковані SDK dependencies новіші за Forge BOM, отже одного POM було б недостатньо. Disposable Maven fixture успішно compiled і реально виконав SDK HTTP calls із effective Spring 6.1.13, Tomcat 10.1.30 (Servlet 6.0), Jackson 2.17.2 та Reactor 3.6.10 — без підвищення Boot 3.3.4. Spring OAuth client підтягнув Nimbus OAuth2/OIDC SDK 9.43.4. Це обмежений compatibility smoke: повний SDK suite, schema-validator edge cases/structured content та security-maintenance audit dependencies — NOT_VERIFIED. Версії pin-ити; не використовувати `latest` у майбутньому POM.

Джерела: [SDK pinned source](https://github.com/modelcontextprotocol/java-sdk/tree/v0.18.3), [published MCP POM](https://repo.maven.apache.org/maven2/io/modelcontextprotocol/sdk/mcp/0.18.3/mcp-0.18.3.pom), [official SDK releases](https://github.com/modelcontextprotocol/java-sdk/releases), [Codex App Server](https://developers.openai.com/codex/app-server), [Codex MCP configuration](https://developers.openai.com/codex/mcp). Online docs використано для пошуку capabilities; generated schema та actual probes мають пріоритет для binary.

`mcp-test:0.18.3` source jar містить `AbstractMcpClientServerIntegrationTests`, `AbstractMcpSyncClientTests`, `AbstractMcpAsyncClientResiliencyTests` та server suites. Це abstract contracts, **не готовий universal mock transport/runner**. Їх не виконували тут. Достатній мінімум для наступних stages: SDK client/server fixture у наявному JUnit/ForgeIT + чинні WireMock endpoint contracts для HTTP negative cases. In-memory/mock transport не доводить headers, streaming, DNS, redirects чи auth-before-I/O. Окремого runner не створювати. Поточні Python/Java програми — тільки disposable Stage 0 probes, не production MCP implementation.

## Відтворення та результати

Збережено [runtime probe](probes/runtime_probe.py), [runtime raw evidence](probes/runtime-result.txt), [SDK fixture](probes/sdk/src/main/java/probe/Probe.java), [SDK output](probes/sdk-result.txt). Raw evidence містить **лише synthetic credentials** та temp paths. Fixtures залишають власні temp artifacts для audit, закривають listener і завершують Codex child processes. SDK context/client закриваються через try-with-resources. Не запускати з production HOME/config.

```sh
# Реальний binary, локальні HTTP fixtures, окремі HOME/CODEX_HOME.
# Потрібен дозвіл host створити loopback listener; shell policy не відкривається.
python3 docs/mcp-integrations/probes/runtime_probe.py > /tmp/forge-mcp-runtime-result.txt

# Maven settings порожні: особисті registry credentials не читаються.
mvn -B -s docs/mcp-integrations/probes/sdk/settings.xml \
  -Dmaven.repo.local=/tmp/forge-mcp-stage0/m2 \
  -f docs/mcp-integrations/probes/sdk/pom.xml \
  compile dependency:build-classpath -Dmdep.outputFile=/tmp/forge-mcp-sdk-classpath
python3 - <<'PY'
import pathlib, subprocess
root = pathlib.Path('docs/mcp-integrations/probes/sdk').resolve()
cp = str(root / 'target/classes') + ':' + pathlib.Path('/tmp/forge-mcp-sdk-classpath').read_text().strip()
subprocess.run(['java', '-cp', cp, 'probe.Probe'], check=True, timeout=40)
PY
```

Фактичні runs використовували ті самі fixture sources у `/tmp/forge-mcp-stage0/probe.py` і `/tmp/forge-mcp-stage0/sdk/`; перед копіюванням джерел проведено фінальний run. Runtime final exit 0, `NATIVE_CALL_ASSERTION "PASS"`; SDK final compile exit 0, runtime exit 0, три `SDK_PROBE_PASS` та PKCE marker. Calls із synthetic model пройшли fresh із A та resume із B. Жодних live OAuth grants чи real hosted-model calls.

Попередні невдалі спроби не зараховано як PASS: зовнішня sandbox забороняла DNS/socket; запуски fixture переведено через explicit execution approval поза нею, з Codex shell network=false. `codex sandbox linux --help` у цій інсталяції дав launcher/mount errors — shell boundary натомість перевірено робочим `command/exec`. Початковий порожній thread не мав rollout. Неправильний synthetic function name та відсутній tool approval не дали native upstream call. Один ранній model fixture повторював невдалий call; його процеси окремо завершено в host namespace, fixture зроблено bounded і фінальний чистий capture записано в інший файл. Ці ранні логи не використано як фінальний evidence.

Production unit/IT/browser suites не запускали: production змін немає, probe не потребує production DB. Наявні тести — reference implementations, а не заявлений зелений regression suite. Direct external provider, UI, restart persistence нових connections та повний Forge recovery — NOT_VERIFIED.

## Конкретний план Stage 1 — не реалізований

Перед початком: повторно перевірити branch/паралельні зміни, max migration, Remote Access auth/settings; якщо потрібна нова feature branch, отримати local+remote `feature/SITIONIX-*` і використати max+1 згідно AGENTS. Не створювати worktree.

1. **Prerequisite control/runtime boundary.** Виконати одну задачу вище та її negative security gate перед відкриттям будь-яких secret-bearing endpoints. Якщо deployment proof недоступний — API fail closed; не заявляти Stage 1 acceptance.
2. **Мінімальний domain та application slice.** Нові `McpConnection`, typed auth kind `NONE/BEARER/SECRET_HEADERS`, project selection `ALL/SELECTED`, explicit allowed-tool records; domain repository/credential port у наявних модулях Agent. Власник — інсталяція, без нової tenant сутності. Два connections з однаковим display name мають різні immutable UUID. Initial enabled=false, allowlist порожній; до Stage 2 discovery runtime readiness відсутня. Policy resolver повертає safe metadata, не secrets.
3. **Persistence.** Forward-only `V<next>__add_mcp_connections.sql` у чинному Flyway каталозі. Мінімальні tables `mcp_connections`, `mcp_connection_credentials`, `mcp_connection_projects`, `mcp_connection_tools`; constraints/FK на чинні project IDs, empty SELECTED = deny. Connection metadata та credentials update — одна application transaction. Delete project не розширює policy. Delete connection очищає credentials/policy, не видаляє historical execution ledger.
4. **Authenticated encryption adapter.** Java JCE AES-GCM, випадковий nonce, key ID; AAD містить installation identity + connection UUID + credential purpose. Ключі поза DB/workspace/runtime read boundary. Active key для write, явно configured попередні keys для read, bounded operator re-encryption; missing/wrong/unknown key, tag failure чи blob swap → safe controlled error. Жодного plaintext fallback. Explicit KEEP/REPLACE/REMOVE; UI mask не приймається за secret. OAuth-specific state/tokens не додавати до Stage 6.
5. **Typed Agent/Nexus management vertical.** Запропонований контракт: Agent `/api/v1/integrations/mcp/connections`; Nexus `/api/v1/infrastructure/agents/integrations/mcp/connections` (з наявним deployment context `/fgaisox`). `POST` create, `GET` list/detail, `PUT /{id}` update, `PUT /{id}/enabled` typed boolean, `DELETE /{id}` remove. Read DTO: identity/name/endpoint/auth kind/enabled/project selection/tool summaries/last-check metadata/credentialConfigured, без credential/ciphertext. Caller context визначає сервер. Нові `McpConnectionsController`, DTO/mapper, use cases/repository adapters за file map; Nexus `ForgeAiMcpConnectionsController` → typed mapper/use case → `ForgeAgentClient` / typed HTTP adapter. Не generic JSON forwarding і не Console → Agent.
6. **Logging boundary.** Secret-bearing requests не log/toString/bodyPreview; safe validation errors не віддзеркалюють submitted values. Service/operator credentials не потрапляють downstream у runtime. Перевірити failure serialization та synthetic canary scanning.
7. **Тести перед реалізацією.** Unit: All/Selected/empty/foreign-or-missing project, duplicate provider identity, unchanged/replace/remove/mask, wrong key/tag/AAD swap/key ID, active/previous key rotation. Agent DB ForgeIT: metadata round-trip, справжнє persistence після перестворення application context, rollback без orphan credentials, remove cleanup. Nexus ForgeIT: typed mapping, status/error mapping, unauthorized та invalid request із **zero upstream calls**, Origin/CSRF і runtime grant rejection. Використати endpoint contracts/JSON fixtures/TestManager з карти, не raw MockMvc/WireMock helpers. DB тільки disposable Testcontainers/чинний ForgeIT PostgreSQL.
8. **Audit та handoff.** Окремо перевірити actual diff/tests/secret canaries; evidence Stage 1 містить точні commands і limits. Без UI, OAuth, external probe, каталогу, gateway чи runtime implementation у цьому slice. PR/merge лише за окремим запитом.

Для Stage 3 зафіксовано routing candidate: Agent-only `/internal/mcp/connections/{connectionId}` із connection-scoped SDK protocol boundary. Він не проходить через Nexus management proxy, не приймає arbitrary upstream URL та не приймає operator/service credential як runtime grant. Route ще **не існує**; actual SDK dispatch/session isolation необхідно перевірити перед Stage 3 implementation.

## Progress / stop

| Stage | Робота | Verification | Live evidence | Відкриті blockers |
| --- | --- | --- | --- | --- |
| 0 | Audit/probes та цей документ завершено | Installed schema; native synthetic fresh/resume; config layers; negative boundary; SDK HTTP/PKCE | Hosted provider / Forge UI / full deployment NOT_VERIFIED | Control/runtime isolation; management auth; complete config isolation |
| 1–10 | Не розпочато | Не виконано | Не виконано | Потрібне окреме доручення на наступний stage |

Stage 0 не дає security acceptance для production. Мінімальний шлях технічно підтверджений на fixtures; головна перешкода — чинна control/runtime boundary, а не відсутність MCP API у Codex. На цьому роботу зупинено; план Stage 1 наведено для review, без його реалізації.

## Фінальна перевірка артефактів

Окремий read-only reviewer зіставив документ, source fixtures і raw results: матеріальних false PASS / невідповідностей не виявив; verdict **ACCEPT для Stage 0 документації**, не production readiness. Python AST parse, JSON evidence assertions (thread identity, native A/B, effective inventory), whitespace scan нових файлів і `git diff --check` пройшли. Host-namespace cleanup check за власними `/tmp/forge-mcp-probe-*` cwd повернув `OWNED_FIXTURE_PROCESSES []`.

SHA-256 виконаних і скопійованих sources:

```text
runtime_probe.py: 96e1b2fb0821e149694e7768c71acfd96a5b2cf96155564b37145023bf3afd59
sdk/pom.xml: 399c43d4aa92943fa820871bf7cef71636c977f951987164813273d6e5f05ce1
sdk/settings.xml: b546e42cf905023b479e33abc908fed827584e753d6402a372fb11fc1d343bca
sdk/src/main/java/probe/Probe.java: 0daa323b4b7ac6389e9adfe575dc72e200a8b31ea1fbb885803d53db54b55dff
```
