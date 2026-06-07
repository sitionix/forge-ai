# 1. System overview

This repository contains a browser-facing frontend, a browser-facing BFF, three business services, one notification consumer service, a contract-generation repository, and several shared platform libraries. The runtime architecture that is actually implemented in code is:

- Browser traffic goes to the SPA shell and then to the BFF path `/bffssox/*`.
- The BFF fans out to `authorisationservice-sox`, `siteservice-sox`, and `workspaceaggregationservice-sox` over generated REST clients.
- Write-side site changes are persisted in `siteservice-sox` and published through an outbox to Kafka.
- `workspaceaggregationservice-sox` consumes those site events through an inbox-driven projection flow and serves read-side workspace queries from its own Postgres schema.
- `authorisationservice-sox` publishes notification events through an outbox to Kafka.
- `notificationservice-sox` consumes those notification events directly and then makes HTTP calls back into auth/BFF flows.

This map excludes README statements as source of truth. It is based on runnable/configured code paths in Spring modules, Vite configs, package sources, `docker-compose.yml`, and configured contract repository metadata.

# 2. Module catalog

Module: configured contract source repository from `services.yaml`
Type: `shared`

Responsibilities:
- Defines OpenAPI contracts for `athssox`, `bffssox`, `stsssox`, and `wagssox`.
- Defines AsyncAPI contracts for `stsssox` site-meta events and `ntfssox` notification events.
- Drives generated Java API/client/event artifacts used by the services.

Entrypoints:
- Maven generation profiles in the configured contract source repository.
- Contract repository generation commands documented by that repository.
- Contract files exposed through service `contractRefs`.

Dependencies:
- OpenAPI Generator.
- AsyncAPI/Avro specs under `apis/**`.
- GitHub PR comment workflow from the configured contract repository generation command.

Owns data:
- no
- Contract definitions only.

Notes:
- This is the backend/source contract repository used by generated Java artifacts.
- Topic names come from the configured event contract refs for each producing service.

Module: `authorisationservice-sox`
Type: `backend`

Responsibilities:
- User registration, login, refresh-token flow, email verification, and JWKS exposure.
- Persists auth users, refresh tokens, email verification tokens, and device sessions.
- Publishes notification outbox events for email verification.

Entrypoints:
- REST controllers:
- `authorisationservice-sox/api-rest/.../AuthController.java`
- `authorisationservice-sox/api-rest/.../UserController.java`
- `authorisationservice-sox/api-rest/.../JwksController.java`
- Spring Boot app:
- `authorisationservice-sox/boot/.../Application.java`

Dependencies:
- Internal modules: `api-rest`, `application`, `domain`, `infrastructure/postgresql`, `pipe/pipe-producer-notification-v1`.
- External systems: Postgres `AUTHS_SOX`, Kafka, filesystem key material under `authorisationservice-sox/keys`.
- Shared libs: `forge-security-server`, `forgecommon-outbox-*`, generated auth API contract artifacts, generated notification producer artifacts.

Owns data:
- yes
- Users, user roles/status, refresh tokens, email verification tokens, device sessions, JWT/JWKS material.

Notes:
- `RegisterUserImpl`, `ResendEmailVerificationImpl`, and related flows call `ForgeOutbox.send(...)`.
- `SecurityConfig` requires authentication for all endpoints except JWKS and health endpoints.
- Service-to-service access rules are declared in `authorisationservice-sox/boot/src/main/resources/application.yml`.

Module: `backendforfrontendservice-sox`
Type: `backend`

Responsibilities:
- Browser-facing auth, registration, create-site, get-sites, and get-site-overview facade.
- Sets refresh-token cookie for browser flows.
- Verifies browser user JWTs against auth JWKS.
- Calls downstream services over generated REST clients.

Entrypoints:
- REST controllers:
- `backendforfrontendservice-sox/api-rest/.../AuthController.java`
- `backendforfrontendservice-sox/api-rest/.../UserController.java`
- `backendforfrontendservice-sox/api-rest/.../SiteController.java`
- Spring Boot app:
- `backendforfrontendservice-sox/boot/.../Application.java`

Dependencies:
- Internal modules: `api-rest`, `application`, `domain`, `clients/client-athssox`, `clients/client-stsssox`, `clients/client-wagssox`.
- External systems: downstream HTTP services `authorisationservice-sox`, `siteservice-sox`, `workspaceaggregationservice-sox`.
- Shared libs: `forge-security-client`, `forge-security-user-jwt`, generated BFF API contract artifacts, generated downstream client artifacts.

Owns data:
- no
- No repository or persistence code found.

Notes:
- Local/dev downstream base paths are configured in `backendforfrontendservice-sox/boot/src/main/resources/application-local.yml` and `application-dev.yml`.
- The BFF serves `/bffssox/api/v1/*` because `server.servlet.context-path` is `/bffssox`.
- Current BFF code exposes only auth, user registration, create site, get sites, and get site overview.

Module: `notificationservice-sox`
Type: `backend`

Responsibilities:
- Consumes notification Kafka events.
- Maps notification envelopes into domain notifications.
- For email verification, requests verification-link material from auth service and then triggers verification via the BFF.

Entrypoints:
- Kafka consumer:
- `notificationservice-sox/pipe/pipe-consumer-notification/.../NotificationConsumer.java`
- Spring Boot app:
- `notificationservice-sox/boot/.../Application.java`

Dependencies:
- Internal modules: `application`, `domain`, `pipe/pipe-consumer-notification`, `clients/client-athssox`, `clients/client-bffssox`.
- External systems: Kafka, HTTP calls to `authorisationservice-sox` and `backendforfrontendservice-sox`.
- Shared libs: `forge-security-client`, generated notification event consumer artifacts, generated auth/BFF client artifacts.

Owns data:
- no
- No persistence adapters or repositories found in main code.

Notes:
- `EmailVerificationHandler` calls auth first for `issueEmailVerificationLink(...)`, then calls the BFF `verifyEmail(...)`.
- No inbox/persistence-backed deduplication is visible in this service.

Module: `siteservice-sox`
Type: `backend`

Responsibilities:
- Owns site creation and site persistence.
- Publishes site metadata events through outbox-backed Kafka publishing.

Entrypoints:
- REST controller:
- `siteservice-sox/api-rest/.../SiteController.java`
- Spring Boot app:
- `siteservice-sox/boot/.../Application.java`

Dependencies:
- Internal modules: `api-rest`, `application`, `domain`, `infrastructure/postgresql`, `pipe/pipe-producer-site-meta`.
- External systems: Postgres `SITES_SOX`, Kafka.
- Shared libs: `forge-security-server`, `forgecommon-outbox-*`, generated site API contract artifacts, generated site-meta producer artifacts.

Owns data:
- yes
- Site aggregate records in Postgres.

Notes:
- `CreateSiteImpl` persists the site and immediately sends `new SiteCreatedPayload(savedSite)` to the outbox.
- `forge.security.server.user.ForgeUserClient` is used to read the authenticated user id from inbound request context.
- Update/delete event publisher classes exist, but no corresponding update/delete write flow was found in current main sources.

Module: `workspaceaggregationservice-sox`
Type: `backend`

Responsibilities:
- Owns the workspace read model for sites.
- Consumes site-meta events and projects them into a Postgres read model.
- Serves workspace site list and site overview queries.

Entrypoints:
- REST controllers:
- `workspaceaggregationservice-sox/api-rest/.../SiteController.java`
- `workspaceaggregationservice-sox/api-rest/.../HealthController.java`
- Kafka consumer:
- `workspaceaggregationservice-sox/pipe/pipe-consumer-site-meta/.../SiteMetaConsumer.java`
- Spring Boot app:
- `workspaceaggregationservice-sox/boot/.../Application.java`

Dependencies:
- Internal modules: `api-rest`, `application`, `domain`, `infrastructure/postgresql`, `pipe/pipe-consumer-site-meta`.
- External systems: Postgres `WAGS_SOX`, Kafka.
- Shared libs: `forge-security-server`, `forgecommon-inbox-*`, generated workspace API contract artifacts, generated site-meta consumer artifacts.

Owns data:
- yes
- Workspace site projection data in `workspace_site_meta` and related status/type tables.

Notes:
- `SiteMetaConsumer` converts Avro envelopes to inbox payloads and hands them to `ForgeInbox.receive(...)`.
- Query use cases only read the projection repository; they do not call site service directly.

Module: `sitionix-spa/apps/shell`
Type: `frontend`

Responsibilities:
- Hosts the shell SPA.
- Loads `auth`, `workspace`, and `builder` as module-federated remotes.
- Proxies browser API traffic to the BFF in local runtime.
- Bootstraps browser auth session on `/`.

Entrypoints:
- Vite app entry: `sitionix-spa/apps/shell/src/main.tsx`
- App component: `sitionix-spa/apps/shell/src/App.tsx`
- Federation config: `sitionix-spa/apps/shell/vite.config.ts`

Dependencies:
- External systems: BFF path `/bffssox`.
- Internal packages: `@sitionix/auth-session` and shell-local router/federation config.

Owns data:
- no
- UI/session bootstrap state only.

Notes:
- `vite.config.ts` proxies `/bffssox` to `VITE_BFF_PROXY_TARGET`.
- `deploy/frontend/config/applications.json` marks shell as the only app with `proxyBrowserApi: true`.

Module: `sitionix-spa/apps/auth`
Type: `frontend`

Responsibilities:
- Browser login and registration UI.
- Configures browser auth session manager.
- Exposes a module-federated remote mount.

Entrypoints:
- Vite app entry: `sitionix-spa/apps/auth/src/main.tsx`
- App component: `sitionix-spa/apps/auth/src/app/App.tsx`
- Remote entry mount: `sitionix-spa/apps/auth/src/mf/mount.tsx`

Dependencies:
- Internal packages: `@sitionix/auth-session`, `@sitionix/http-client`, `@sitionix/contracts`, `@sitionix/ui`.
- External systems: `VITE_API_BASE_URL`, which local runtime points to `/bffssox`.

Owns data:
- no
- UI/form state only.

Notes:
- `loginUserApi.ts` calls `POST /api/v1/auth/login`.
- `registerUserApi.ts` calls `POST /api/v1/users`.

Module: `sitionix-spa/apps/workspace`
Type: `frontend`

Responsibilities:
- Browser workspace UI for dashboard, sites, trash, collections, domains, CRM, and editor views.
- Bootstraps browser auth session and redirects unauthenticated users back to auth routes.
- Exposes a module-federated remote mount.

Entrypoints:
- Vite app entry: `sitionix-spa/apps/workspace/src/main.tsx`
- App component: `sitionix-spa/apps/workspace/src/app/App.tsx`
- Remote entry mount: `sitionix-spa/apps/workspace/src/mf/mount.tsx`

Dependencies:
- Internal packages: `@sitionix/auth-session`, `@sitionix/http-client`, `@sitionix/contracts`, `@sitionix/ui`.
- External systems: `VITE_API_BASE_URL`, which local runtime points to `/bffssox`.

Owns data:
- no
- UI state only.

Notes:
- The app uses two API layers:
- `sitesApi.ts` calls `POST /api/v1/sites` and `GET /api/v1/sites`.
- `workspaceHttpApi.ts` expects many `/api/v1/workspace/*` endpoints plus delete/duplicate/editor endpoints.
- Only a subset of those endpoints exists in current backend code.

Module: `sitionix-spa/apps/builder`
Type: `frontend`

Responsibilities:
- Browser site-builder UI with local state, drag-and-drop, and preview/editor components.
- Exposes a module-federated remote mount.

Entrypoints:
- Vite app entry: `sitionix-spa/apps/builder/src/main.tsx`
- Remote entry mount: `sitionix-spa/apps/builder/src/mf/mount.tsx`

Dependencies:
- Internal packages: `@sitionix/ui`.
- Browser-only libraries: React, `@dnd-kit/*`, `lucide-react`.

Owns data:
- no
- Local browser/editor state only.

Notes:
- No backend HTTP code was found in the builder app sources.

Module: `sitionix-spa/packages/auth-session`
Type: `shared`

Responsibilities:
- Stores access/refresh auth session state in the browser.
- Bootstraps session and refreshes access tokens.
- Provides unauthenticated callbacks to host apps.

Entrypoints:
- Package entry: `sitionix-spa/packages/auth-session/src/index.ts`
- Refresh client: `sitionix-spa/packages/auth-session/src/refresh/refreshClient.ts`

Dependencies:
- Browser `fetch`.
- BFF refresh endpoint `/api/v1/auth/refresh`.

Owns data:
- no
- Browser-side auth/session state only.

Notes:
- Refresh requests always use `credentials: "include"` and target `${baseUrl}/api/v1/auth/refresh`.

Module: `sitionix-spa/packages/http-client`
Type: `shared`

Responsibilities:
- Central browser HTTP wrapper.
- Adds bearer tokens to protected requests.
- Retries once after refresh on `401`.

Entrypoints:
- Package entry: `sitionix-spa/packages/http-client/src/index.ts`

Dependencies:
- Browser `fetch`.
- `AuthSessionBridge` supplied by host apps.

Owns data:
- no
- Request/response logic only.

Notes:
- Public paths are hardcoded in `PUBLIC_AUTH_PATHS`.
- Any other `/api/*` path is treated as protected unless overridden.

Module: `sitionix-spa/packages/contracts`
Type: `shared`

Responsibilities:
- TypeScript DTO/type package used by frontend apps.

Entrypoints:
- Package entry: `sitionix-spa/packages/contracts/src/index.ts`

Dependencies:
- No runtime dependencies in package code.

Owns data:
- no
- Type definitions only.

Notes:
- These contracts are handwritten TypeScript types, not generated from the configured backend contract source repository.
- Current workspace types include objects/endpoints not backed by current backend contracts.

Module: `sitionix-spa/packages/ui`
Type: `shared`

Responsibilities:
- Reusable frontend UI and navigation helpers shared across SPA apps.

Entrypoints:
- Package entry: `sitionix-spa/packages/ui/src/index.ts`

Dependencies:
- React and browser DOM APIs.

Owns data:
- no
- UI components only.

Notes:
- Includes navigation helpers such as `navigateInBrowser` and `redirectStandaloneToShell`.

Module: `sitionix-spa/packages/build-config`
Type: `tool`

Responsibilities:
- Shared Vite/Vitest config factory for SPA apps.

Entrypoints:
- Package entry: `sitionix-spa/packages/build-config/src/index.ts`

Dependencies:
- Vite and Vitest.

Owns data:
- no
- Build configuration only.

Notes:
- Used by `auth` and `workspace` Vite configs.

Module: `sitionix-spa/packages/tailwind-config`
Type: `tool`

Responsibilities:
- Shared Tailwind preset package.

Entrypoints:
- `sitionix-spa/packages/tailwind-config/preset.ts`

Dependencies:
- Tailwind.

Owns data:
- no
- Styling preset only.

Notes:
- No runtime/backend interaction found.

Module: `forge-common`
Type: `shared`

Responsibilities:
- Shared inbox/outbox infrastructure.
- Provides core models/ports, Spring Boot auto-config, and Postgres/Mongo storage adapters.
- Schedules outbox/inbox dispatch and cleanup workers.

Entrypoints:
- Auto-configs:
- `forgecommon-outbox-boot/.../ForgeOutboxAutoConfiguration.java`
- `forgecommon-inbox-boot/.../ForgeInboxAutoConfiguration.java`
- Marker annotation:
- `forgecommon-inbox-boot/.../EnableInbox.java`

Dependencies:
- Spring Boot auto-configuration and scheduling.
- Postgres or Mongo storage adapters.
- Services that provide typed event enums and publishers/handlers.

Owns data:
- no
- Framework infrastructure only; concrete data lives in consuming services' databases.

Notes:
- Outbox is used by auth and site services.
- Inbox is used by workspace aggregation service.

Module: `forge-security`
Type: `shared`

Responsibilities:
- Internal service-to-service authentication.
- User JWT verification against auth JWKS.
- Current-user extraction for downstream application code.

Entrypoints:
- Auto-configs:
- `forge-security-client/.../ForgeSecurityClientAutoConfiguration.java`
- `forge-security-server/.../ForgeSecurityServerAutoConfiguration.java`
- `forge-security-user-jwt/.../ForgeUserJwtAutoConfiguration.java`

Dependencies:
- Spring Security.
- Auth JWKS endpoint via generated `athssox` client in `forge-security-user-jwt`.

Owns data:
- no
- Security infrastructure only.

Notes:
- `forge-security-client` instruments `RestTemplate` with service JWT headers.
- `forge-security-server` enforces internal-service policies on backend services.
- `forge-security-user-jwt` is used by the BFF to accept browser bearer tokens.

Module: `forge-it`
Type: `shared`

Responsibilities:
- Reusable integration-testing toolkit.
- Provides test annotations, database/kafka/wiremock/mockmvc helpers, and container orchestration.

Entrypoints:
- Spring/test auto-config and listeners across `forge-it-*`.
- Example consumer app in `forge-it-consumer-it`.

Dependencies:
- Testcontainers, Spring Test, WireMock, Kafka, Postgres, Mongo.

Owns data:
- no
- Test-only infrastructure.

Notes:
- Business services depend on ForgeIT in test scope.
- This is not part of production runtime architecture.

Module: `forge-end-to-end`
Type: `tool`

Responsibilities:
- End-to-end test harness service that calls the BFF through generated clients.

Entrypoints:
- Spring Boot app: `forge-end-to-end/src/main/java/com/sitionix/forgee2e/ForgeEndToEndApplication.java`
- REST controllers under `forge-end-to-end/src/main/java/com/sitionix/forgee2e/api`

Dependencies:
- Generated BFF client artifacts.
- Spring Web/JPA/Postgres in the harness application.

Owns data:
- no
- No business-owned mainline persistence code was found.

Notes:
- This module is test/support code, not a business service in the main runtime flow.

Module: `skills`
Type: `tool`

Responsibilities:
- Repository-local task and test automation scripts used from `just`.
- Stores Codex-facing prompt/info assets.

Entrypoints:
- Forge AI launcher and orchestration scripts from the current `forge-ai` flow.
- Root `justfile` recipes `test-flow` and `new-task`

Dependencies:
- Bash.
- Root `justfile`.

Owns data:
- no
- Script/prompt files only.

Notes:
- This module is developer automation, not product runtime.

Module: `postgres`
Type: `tool`

Responsibilities:
- Local Postgres bootstrap helper used by root `docker-compose.yml`.

Entrypoints:
- `postgres/entrypoint.sh`

Dependencies:
- Docker Postgres image.
- SQL script directories mounted from service test resources.

Owns data:
- no
- Bootstraps local DB state only.

Notes:
- Used to initialize auth/site/workspace local schemas from checked-in SQL directories.

Module: `sitionix-infra`
Type: `tool`

Responsibilities:
- Separate nested infrastructure repository checkout with contracts/docs/scripts.

Entrypoints:
- No runnable application entrypoint found in the checked files inside this repo.

Dependencies:
- Independent nested Git repository.

Owns data:
- no
- No application data ownership found in checked code.

Notes:
- The visible files are contracts/docs/scripts, not deployable service code.

# 3. Interaction flows

## HTTP flows

Browser -> shell -> BFF:
- `sitionix-spa/apps/shell/vite.config.ts` proxies `/bffssox/*` to the BFF target.
- `docker-compose.yml` sets `VITE_API_BASE_URL=/bffssox` for SPA local runtime.
- `auth`, `workspace`, and `shell` apps all read `VITE_API_BASE_URL` from `publicEnv.ts`.

Auth UI -> BFF -> auth service:
- `auth` app calls:
- `POST /api/v1/auth/login`
- `POST /api/v1/users`
- BFF implements those browser-facing endpoints and forwards them through generated `athssox` clients.
- BFF downstream config points at `http://authorisationservice-sox:9090/authsox`.

Workspace UI -> BFF -> site/workspace services:
- `CreateSiteSheet` and `sitesApi.ts` call `POST /api/v1/sites`; BFF forwards this to `siteservice-sox`.
- BFF `GET /api/v1/sites` and `GET /api/v1/sites/{siteId}/overview` forward to `workspaceaggregationservice-sox`.
- `siteservice-sox` writes the site aggregate.
- `workspaceaggregationservice-sox` serves list/overview from its projection DB.

BFF auth verification path:
- BFF user-JWT support uses `forge.user-jwt.auth-base-url`.
- In local/dev config that points directly to `authorisationservice-sox`.
- Result: browser bearer-token verification in the BFF depends on auth JWKS endpoints from auth service.

Notification service HTTP path:
- `notificationservice-sox` consumes a notification event.
- `EmailVerificationHandler` calls auth service `issueEmailVerificationLink(...)`.
- Then the same handler calls BFF `verifyEmail(...)`.
- BFF `verifyEmail(...)` forwards to auth service `verifyEmail(...)`.
- Effective path: auth outbox -> Kafka -> notification service -> auth service -> BFF -> auth service.

Internal service auth:
- Auth, site, and workspace services use `forge-security-server`.
- Notification service and BFF downstream calls use `forge-security-client`.
- BFF itself uses `forge-security-user-jwt` for browser user tokens, not `forge-security-server`.

## Kafka flows

Auth notification flow:
- Producer origin:
- `authorisationservice-sox/application/.../RegisterUserImpl.java`
- `authorisationservice-sox/application/.../ResendEmailVerificationImpl.java`
- Both send `EmailVerifyPayload` through `ForgeOutbox`.
- Outbox publisher:
- `authorisationservice-sox/pipe/pipe-producer-notification-v1/.../NotificationPublisherV1.java`
- Generated topic contract:
- `ntfssox.${environment}.notification.public.unified.v1`
- Envelope/payload type:
- `NotificationEnvelope` carrying notification payloads from generated `ntfssox` artifacts.
- Consumer:
- `notificationservice-sox/pipe/pipe-consumer-notification/.../NotificationConsumer.java`

Site meta projection flow:
- Producer origin:
- `siteservice-sox/application/.../CreateSiteImpl.java`
- Sends `SiteCreatedPayload` through `ForgeOutbox`.
- Outbox publishers:
- `SiteMetaPublisherV1`
- `SiteMetaUpdatedPublisherV1`
- `SiteMetaDeletedPublisherV1`
- Generated topic contract:
- `stsssox.${environment}.site-meta.public.v1`
- Envelope/payload type:
- `SiteMetaEnvelope` containing `SiteCreatedEvent`, `SiteUpdatedEvent`, or `SiteDeletedEvent`.
- Consumer:
- `workspaceaggregationservice-sox/pipe/pipe-consumer-site-meta/.../SiteMetaConsumer.java`
- Consumer hands payloads to `ForgeInbox.receive(...)`.
- Inbox handlers:
- `SiteCreatedInboxEventHandler`
- `SiteUpdatedInboxEventHandler`
- `SiteDeletedInboxEventHandler`
- Projection writer:
- `SiteMetaProjectionCommandImpl`

## DB usage

`authorisationservice-sox`:
- Writes and reads `AUTHS_SOX` Postgres.
- Repositories live under `authorisationservice-sox/infrastructure/postgresql/...`.

`siteservice-sox`:
- Writes and reads `SITES_SOX` Postgres.
- Repository implementation is `SiteRepositoryImpl`.

`workspaceaggregationservice-sox`:
- Writes and reads `WAGS_SOX` Postgres.
- Repository implementation is `WorkspaceSiteMetaRepositoryImpl`.
- This database is a projection/read-model store, not the primary site-write store.

`backendforfrontendservice-sox`:
- No DB usage found in main code.

`notificationservice-sox`:
- No DB usage found in main code.

Local runtime residue:
- `docker-compose.yml` still provisions `site-mongo` and passes `MONGODB_URI` to `siteservice-sox`.
- No main-code Mongo usage was found in `siteservice-sox` sources.

# 4. Architectural patterns

- BFF pattern is present.
- Evidence: browser apps target `/bffssox`, and BFF controllers forward to auth/site/workspace generated clients.

- Layered module structure is present.
- Evidence: each backend service is split into `api-rest` -> `application` -> `domain` -> `infrastructure` and/or `clients`/`pipe`.

- Outbox pattern is present.
- Evidence: `authorisationservice-sox` and `siteservice-sox` call `ForgeOutbox.send(...)`, and both include outbox boot/storage/publisher wiring.

- Inbox pattern is present.
- Evidence: `workspaceaggregationservice-sox` enables inbox, receives Kafka envelopes, and dispatches typed inbox handlers through `ForgeInbox`.

- Event-driven projection is present.
- Evidence: site writes happen in `siteservice-sox`, while workspace reads come from `workspaceaggregationservice-sox` projection state populated by Kafka site-meta events.

- CQRS-style split is present for site/workspace concerns.
- Evidence: `siteservice-sox` owns write-side site persistence; `workspaceaggregationservice-sox` owns read-side workspace site views.

- Contract-first/generated client usage is present.
- Evidence: controllers implement generated APIs and downstream modules depend on generated API/client/event artifacts from the configured contract source repository.

- Microfrontend/module federation is present.
- Evidence: `shell`, `auth`, `workspace`, and `builder` Vite configs use `@originjs/vite-plugin-federation`.

# 5. Boundaries and rules (DERIVED)

- Frontend browser API traffic is intended to go through the BFF path, not directly to backend services.
- Derived from `VITE_API_BASE_URL=/bffssox`, shell Vite proxy, and deployment config where only shell has `proxyBrowserApi: true`.

- The BFF is an orchestrator/facade, not a persistence owner.
- Derived from controller/usecase/client code and the absence of repositories/DB config in BFF main code.

- `authorisationservice-sox` owns authentication state and JWKS, not the BFF.
- Derived from auth repositories, auth token services, and JWKS controller living only in auth service.

- `siteservice-sox` owns the write model for sites.
- Derived from `CreateSiteImpl` persisting through `SiteRepository`.

- `workspaceaggregationservice-sox` owns the read model for workspace site views.
- Derived from its dedicated Postgres repository plus Kafka inbox projection handlers.

- Site-to-workspace synchronization is asynchronous through Kafka, not synchronous service-to-service HTTP.
- Derived from site outbox publishers and workspace inbox consumer/handlers.

- Notification processing is Kafka-driven, not HTTP-triggered.
- Derived from `NotificationConsumer` being the main entrypoint and no notification REST controller being present.

- The configured contract source repository is the Java contract source for backend APIs/events.
- Derived from generated Java API/client/event dependencies across services.

- `@sitionix/contracts` is frontend-local typing, not the backend contract source of truth.
- Derived from frontend package code being handwritten TypeScript while backend uses generated artifacts from the configured contract source repository.

- `builder` is currently isolated from backend APIs.
- Derived from no HTTP client usage in builder sources.

# 6. Risks / inconsistencies

- The workspace frontend expects endpoints that do not exist in the current backend code.
- Evidence: `workspaceHttpApi.ts` calls `/api/v1/workspace/dashboard`, `/api/v1/workspace/trash`, `/api/v1/workspace/editor/{siteId}`, collection/domain/CRM endpoints, and delete/duplicate flows.
- Current BFF and generated backend contracts expose only `/api/v1/sites`, `/api/v1/sites/{siteId}/overview`, auth endpoints, and `/api/v1/users`.

- Notification processing is coupled to the browser-facing BFF.
- Evidence: `notificationservice-sox` calls BFF `verifyEmail(...)`, and BFF then forwards that call to auth.
- This adds an extra hop and ties an internal backend flow to the browser facade.

- Notification consumption has no visible inbox/idempotency layer.
- Evidence: `NotificationConsumer` directly maps the envelope and executes `notification.getTemplate().send(notification)`.
- No persistence-backed dedupe equivalent to `ForgeInbox` was found in this service.

- Local runtime config still carries Mongo residue for site service.
- Evidence: `docker-compose.yml` provisions `site-mongo` and sets `MONGODB_URI`.
- No main-code Mongo usage was found in `siteservice-sox`.

- Site update/delete event infrastructure exists ahead of matching write flows.
- Evidence: site-meta updated/deleted publishers, payloads, and workspace consumers exist.
- No matching update/delete site HTTP/usecase implementation was found in current `siteservice-sox` main sources.

- Frontend contracts are ahead of backend/generated contracts.
- Evidence: `@sitionix/contracts` defines dashboard, CRM, trash, editor, collection, and domain models that are not represented in current configured BFF/workspace REST contracts.
