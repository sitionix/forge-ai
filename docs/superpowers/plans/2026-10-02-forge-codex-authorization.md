# Forge-owned Codex Authorization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for native execution or superpowers:subagent-driven-development if explicitly selected. Steps use checkbox syntax for tracking.

**Goal:** Authorize Codex through Forge Settings and use only that authorization for every Forge Codex consumer.

**Architecture:** Agent owns managed ChatGPT login, a shared authorization gate, and isolated provider processes. Knowledge delegates its Codex operations to an authenticated, typed Agent internal API instead of launching terminal Codex. Nexus exposes only operator authorization endpoints, and Console displays their confirmed state.

**Tech Stack:** Java/Spring/Jackson, Python/asyncio/httpx, browser ES modules/Vitest, existing systemd runtime launcher.

**Spec:** `docs/superpowers/specs/2026-10-02-forge-codex-authorization-design.md`

## Global Constraints

- Stay on `feature/SITIONIX-156`; no commits or push. Preserve all existing local changes.
- One ChatGPT account per local Forge installation; no hosted/cross-machine authorization.
- No personal Codex credentials/configuration, inherited provider keys, or terminal fallback.
- Credentials remain in the protected runtime profile; never return/store/log provider tokens in UI or database.
- Validate protocol against installed Codex 0.160.0; use version-supported fields only.
- Do not weaken runtime isolation or Knowledge's `NoNewPrivileges=true`.
- Normal runtime verification uses `just stop`, `just start`, `just status` only.
- Existing MCP grant and project/tool permissions remain independent and enforced.

## Review Focus

- Logout races with turn admission: admission under an older authorization generation must be rejected (Task 3).
- Browser retries/another tab: one pending login, no disclosure to a different browser binding (Tasks 2, 4).
- Knowledge disconnects while generation runs: cancellation must stop the owned provider turn/process (Task 5).
- Personal/project config selects another credential source: isolated Forge configuration must win or reject the launch (Task 1).
- Provider restart/account refresh fails: do not retain a stale Connected state or fall back to terminal credentials (Tasks 2, 3).

## File map

- Runtime isolation: `scripts/runtime/forge-runtime-launcher.py`, `scripts/runtime/install_mcp.py`, and their tests.
- Authorization contracts: new records/ports under `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/{model,port}/`.
- Authorization orchestration: new `LlmAuthorizationService.java` under `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/llm/`.
- Codex protocol/lifecycle: existing infrastructure/codex plus new focused auth and internal-generation adapters.
- Operator routes: new llm controllers in Agent and Nexus; typed proxy operations alongside existing MCP proxy patterns.
- Knowledge bridge: new `services/forge-knowledge/src/knowledge_service/forge_codex_client.py`; configure it in existing bootstrap.
- UI: new `llm-api.js` and `llm-providers-view.js` alongside `settings-page.js`; modify settings HTML/CSS and lifecycle wiring.

## Task 1: Unify isolated Codex launch configuration

**Files:** Modify `scripts/runtime/forge-runtime-launcher.py`, `scripts/runtime/install_mcp.py`; test `scripts/runtime/tests/test_runtime_launcher.py` and installer tests in that directory.

**Interfaces:** Existing `start codex <executionUUID> <workspace> [--mcp]` remains compatible. Every Codex launch applies validated Forge-owned config, empty system config, plugin isolation, clean environment, and the dedicated CODEX_HOME. Only MCP launches receive MCP credentials.

- [ ] Add `test_non_mcp_codex_has_same_config_isolation_as_mcp`: assert both config bind mounts and inaccessible plugin paths; assert no LoadCredential for non-MCP.
- [ ] Add `test_personal_credentials_and_project_provider_override_cannot_authorize`: use personal HOME/env/config sentinels; assert no inherited provider keys and no accepted project auth/provider override.
- [ ] Run `python3 -m unittest discover -s scripts/runtime/tests -v`; confirm new tests fail for missing non-MCP isolation.
- [ ] Refactor the existing launch builder to apply isolation independently of grant-envelope presence; preserve grant validation and existing security assertions. Reject unsupported auth-changing project configuration rather than silently using it.
- [ ] Rerun runtime tests; verify installer preserves existing runtime credentials and does not copy personal auth/config. Record baseline git diff hash before changing overlapping launcher files.

## Task 2: Managed authorization state and account protocol

**Files:** Create domain `LlmAuthorizationState.java`, `LlmLoginAttempt.java`, port `LlmAuthorizationPort.java`; create application `llm/LlmAuthorizationService.java`; create infrastructure/codex `CodexAuthorizationAdapter.java`, `CodexAuthorizationSession.java`; extend `CodexProtocol.java` and infrastructure wiring. Add corresponding tests in application and infrastructure/codex.

**Interfaces:** `LlmAuthorizationPort.readAccount()`, `startLogin(String browserBinding)`, `readLogin(UUID loginId, String browserBinding)`, `cancelLogin(UUID loginId, String browserBinding)`, `logout()`. State contains providerId=`codex`, auth state, generation, nullable email/plan, availability, safe error code. Attempt contains UUID, status, expiration, and validated authUrl only while pending; no token fields. Service manages single-flight login with a 10-minute expiry.

- [ ] Add tests `signed_out_account_is_not_connected`, `completed_login_requires_account_confirmation`, `duplicate_start_same_binding_reuses_attempt`, `different_binding_cannot_read_attempt`, `cancelled_completion_cannot_connect`, `expired_attempt_stops_owned_process`, `restart_reads_account_not_pending_attempt`, `refresh_failure_invalidates_connected_state`.
- [ ] Run `mvn -pl services/forge-agent/infrastructure/codex -am -Dtest=CodexAuthorizationAdapterTest,CodexAuthorizationSessionTest,LlmAuthorizationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`; confirm new tests fail before implementation.
- [ ] Implement isolated neutral-workspace auth session using existing process starter/JSON-RPC transport. Route supported `account/*` methods/notifications only. Maintain attempt ownership, generation and cleanup without parsing CLI stdout.
- [ ] Validate browser authUrl as HTTPS with supported OpenAI login hosts, no userinfo; never log URLs containing OAuth state. Use `{type:"chatgpt"}` without newer optional branding fields until version compatibility is proven.
- [ ] Rerun tests; reject malformed account/login responses and redact errors/toString output. Account discovery uses cached credentials without triggering refresh on every poll.

## Task 3: Gate all Agent inference and invalidate old processes

**Files:** Create application `llm/ForgeCodexAuthorizationGate.java`; modify `CodexAppServerClient.java`, `CodexRuntimeAdapter.java`, `CodexRecoveryInspector.java`, `CodexAgentExecutor.java`, provider response records/mappers, and error mapping in Agent/Nexus. Add `ForgeCodexAuthorizationGateTest.java` and extend existing client/executor/runtime/recovery tests.

**Interfaces:** `AuthorizationLease requireAuthorized()` atomically checks current Forge account/generation and registers work before a turn starts; `registerCancellation(AuthorizationLease, Runnable)` attaches owned cleanup; `release(AuthorizationLease)` removes it. Logout blocks admission, advances generation, cancels leases, closes neutral/discovery/auth processes in defined order, logs out, and verifies account absence. Cleanup failure leaves inference blocked.

- [ ] Add `fresh_and_durable_turns_fail_without_forge_auth`, `terminal_login_and_env_key_do_not_satisfy_gate`, `logout_between_check_and_start_rejects_turn`, `logout_cancels_running_turn`, `logout_cleanup_failure_keeps_gate_blocked`, `model_catalog_is_not_authentication`, `recovery_inspection_does_not_restart_unauthorized_inference`.
- [ ] Run the gate/client/executor/runtime/recovery unit tests with Maven's `-pl services/forge-agent/infrastructure/codex -am` and `-Dsurefire.failIfNoSpecifiedTests=false`; confirm failures reflect absent gate behavior.
- [ ] Apply leases to all inference entrypoints, including untracked/fresh/durable/resumed execution and retries. Preserve recovery inspection without admitting new inference.
- [ ] Return `CODEX_AUTH_REQUIRED` for absent authorization and a distinct safe blocked/cleanup error where applicable. Keep auth state separate from `RuntimeProviderStatus`; update Nexus mapping rather than deleting current status fields.
- [ ] Rerun tests, updating protocol fixtures to explicitly simulate authorization; no implicit authenticated defaults. Regression-test model pagination and context modes.

## Task 4: Operator authorization API and Nexus browser boundary

**Files:** Create Agent `api/llm/LlmAuthorizationController.java`; Nexus `api/llm/ForgeAiLlmAuthorizationController.java`, browser guard/properties/response advice; add typed Agent-client and application proxy operations. Extend boot application YAML and HTTP/MockMvc tests following existing MCP OAuth contract layout.

**Interfaces:** Public Nexus prefix `/api/v1/infrastructure/agents/integrations/llm`; Agent prefix `/api/v1/integrations/llm`. GET `/providers`, POST `/codex/login`, GET/DELETE `/codex/logins/{id}`, POST `/codex/logout`. Start/cancel bodies are empty in browser API; Nexus adds write-only browserBinding to Agent requests. Status API returns safe account/attempt DTOs from Task 2.

- [ ] Add HTTP tests asserting same-origin mutations, JSON content type, HttpOnly SameSite=Lax browser binding cookie, binding enforcement on attempt reads, no-store responses, duplicate-click behavior, unavailable Agent errors and credential-free JSON.
- [ ] Run new Agent/Nexus boot contract tests; confirm missing routes/guards fail.
- [ ] Implement typed controllers/proxy operations and safe errors. Require an Origin on mutations; accept missing Origin only on same-origin status GET with the correct cookie. Do not expose the internal generation API through Nexus.
- [ ] Rerun tests; verify 404/503/malformed responses do not surface raw upstream diagnostics, URLs or tokens.

## Task 5: Knowledge uses Agent-owned Codex only

**Files:** Create domain `ForgeCodexGenerationRequest.java`, `ForgeCodexGenerationResult.java`, port `ForgeCodexOperationsPort.java`; infrastructure/codex `ForgeCodexOperationsAdapter.java`; Agent API `llm/ForgeCodexInternalController.java` and service-auth filter. Create Knowledge `forge_codex_client.py`, `tests/test_forge_codex_client.py`; modify Knowledge `bootstrap.py`, `config.py`, `codex_usage.py`, `ai_runtime_discovery.py` as needed; modify installer and environment wiring for a protected service credential.

**Interfaces:** Agent internal prefix `/internal/v1/codex`, authenticated using a generated installation token loaded from a mode-0600 file available to the service control account only. GET `/models`, GET `/usage`, POST `/generations`, GET `/generations/{uuid}`, DELETE `/generations/{uuid}`. Generation request is `{requestId,prompt,modelId,effortId,responseMode,timeoutSeconds}`; result is `{status,rawText,threadId,turnId,serverVersion,tokenUsage,warnings,modelMetadata,errorCode}`. No arbitrary RPC/config/tools fields. Bound prompt to 1 MiB, deadline to `(0,5400]` seconds, and retained completed jobs to 5 minutes.

- [ ] Add internal API tests for absent/wrong token, unknown fields, oversized prompt, invalid deadline, schema-preserving structured output, duplicate request ID, cancellation, job expiry, no MCP grants, and Forge signed-out rejection.
- [ ] Add Python tests `bootstrap_never_spawns_terminal_codex`, `generation_preserves_codex_turn_result`, `models_and_usage_use_forge_bridge`, `timeout_and_cancellation_delete_owned_job`, `bridge_unavailable_never_falls_back`, `service_token_never_reaches_provider_or_logs`.
- [ ] Run new Agent tests and `services/forge-knowledge/.venv/bin/python -m pytest services/forge-knowledge/tests/test_forge_codex_client.py -v`; confirm new bridge tests fail.
- [ ] Implement bounded asynchronous generation jobs with leases from Task 3. All generation occurs in a neutral workspace with no repository/MCP access and no approved shell/tools; tool requests fail closed. Output validation remains in Knowledge's existing provider normalization.
- [ ] Implement Python bridge sync/async methods matching currently used client interfaces, including initialization/version, run_turn, request_sync/request for model/usage calls, and bounded close/aclose. Explicitly whitelist calls; unsupported methods fail, never spawn a fallback.
- [ ] Provision the internal service token separately from provider credentials, preserve it on reinstall, and configure file paths for Agent/Knowledge. No sudo/privilege changes to Knowledge.
- [ ] Rerun bridge, generative, discovery, usage, config and lifecycle tests. Preserve Ollama behavior; audit all production Codex subprocess references and disable/remove the direct Knowledge launch path from production bootstrap.

## Task 6: Codex card and browser login lifecycle

**Files:** Create `services/forge-console/src/operator/llm-api.js`, `llm-providers-view.js`, bundled `codex-icon.svg`; modify `settings.html`, `settings-page.js`, `operator-ui.css`. Create corresponding Vitest tests alongside existing Console test conventions.

**Interfaces:** `LlmApi.providers(signal)`, `startLogin(signal)`, `login(id,signal)`, `cancelLogin(id,signal)`, `logout(signal)`; `LlmProvidersView.start()` and `.dispose()`. API uses Task 4's prefix and same-origin credentials. Settings owns separate LLM and MCP controllers.

- [ ] Add tests for signed-out/connected/pending/unavailable rendering, popup blocking with explicit open-link retry, confirmed account display, browser rejection, cancellation, deadline expiry, duplicate clicks, logout failure, aborted requests and disposal during polling.
- [ ] Run `npm --prefix services/forge-console test -- llm-api llm-providers-view settings`; confirm failures before implementation.
- [ ] Implement card with icon and exact actions “Sign in with ChatGPT” / “Sign out”; render provider email as text, never HTML. Open a placeholder popup synchronously from user action, navigate only to the validated returned URL, poll boundedly until confirmed completion. Keep retry explicit to avoid automatic popup loops.
- [ ] Add independent error/status regions and accessible controls; dispose timers/listeners without silently logging out. Keep MCP management unchanged.
- [ ] Run Console tests, typecheck and build; test page reload with persisted authorization and refresh of Agent model/auth state after login/logout.

## Task 7: Joined verification, actual browser login, normal Agent MCP E2E

**Files:** Add joined Agent/Nexus authorization fixtures/tests alongside boot HTTP contracts; update runtime isolation fixtures and setup documentation. Do not modify real MCP PR or disposable artifacts until the authorized live workflow/cleanup stage.

- [ ] Add integration tests proving an authenticated terminal profile cannot authorize Forge, Forge login survives normal service restart, Knowledge uses the same account, logout denies every inference path, and MCP permissions still require selected project/tools.
- [ ] Run runtime tests, relevant Agent/Nexus Maven reactor tests, full Knowledge pytest suite and Console tests/typecheck/build. Record exact failures; distinguish unavailable test dependencies from product failures.
- [ ] Verify git diff/check and branch; confirm all original local changes remain. No commit step.
- [ ] Start normal runtime with `just stop`, `just start`, `just status`; stop at startup failure. Open Settings, let the user complete actual ChatGPT login, and verify isolated account/read plus a harmless inference turn. Never automate entering user credentials.
- [ ] Test normal Knowledge generation and model/usage reads. Logout/relogin through the UI and verify signed-out rejection without terminal fallback.
- [ ] Re-test the existing GitHub OAuth MCP connection; record actual inventory count. Use only the selected disposable project and approved PR tools.
- [ ] Execute the existing disposable normal two-Agent workflow with the documentation branch. Agent 1 creates exactly one PR and reads/verifies it; Agent 2 closes and reads/verifies it. Both use GitHub MCP only; do not invoke shell/gh/curl/direct GitHub API for workflow operations.
- [ ] Inspect actual run/turn/tool events, normal grant/config path, GitHub operation outputs and final closed PR state. No success based only on natural-language claims.
- [ ] After successful evidence, remove disposable workflow/agents/project where safe, delete disposable branch, leave PR closed, and retain local Just Start work. If any live stage fails, collect evidence and stop without unrelated fixes.

## Plan self-review

- Spec coverage: lifecycle/security Tasks 1–4; all consumers Task 5; UI Task 6; full regression/live proof Task 7.
- Types and endpoints: public/internal APIs are separate; Knowledge returns the existing Codex turn result shape, not a new provider response format.
- All five Review Focus cases have explicit owning tests. No terminal fallback is allowed even when the bridge/provider is unavailable.
- No dependencies, external projects, commits, or privilege weakening are assumed. Ordinary implementation begins only after plan review and execution-method selection.
