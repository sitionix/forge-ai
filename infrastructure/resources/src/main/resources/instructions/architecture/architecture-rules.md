# 1. Purpose

This file is not a system overview. It is a decision guide for Codex: where to implement a change, where not to implement it, and which repository artifact is the source of truth for the change.

It complements `forge-ai/infrastructure/resources/src/main/resources/instructions/architecture/system-architecture.md`. Use that file to understand what exists. Use this file to decide implementation placement and to avoid crossing current repository boundaries.

# 2. Core architectural rules

- Browser-facing backend access goes through the BFF, not directly to business services.
  Evidence: `sitionix-spa/apps/shell/vite.config.ts` proxies `/bffssox`; `sitionix-spa/deploy/frontend/config/applications.json` marks only `shell` with `proxyBrowserApi: true`; BFF serves `/bffssox` from `backendforfrontendservice-sox/boot/src/main/resources/application.yml`.

- The BFF is a facade/orchestrator, not a persistence owner.
  Evidence: BFF use cases such as `CreateSiteImpl`, `GetWorkspaceSitesImpl`, and `GetSiteOverviewImpl` delegate to downstream clients; no BFF repository/persistence code was found in main sources.

- Auth state belongs to `authorisationservice-sox`.
  Evidence: auth controllers, JWKS controller, refresh-token flow, email-verification token issuance, and auth persistence live in `authorisationservice-sox`.

- Site write state belongs to `siteservice-sox`.
  Evidence: `siteservice-sox/application/.../CreateSiteImpl.java` validates input, builds the aggregate, persists through `SiteRepository`, and emits `SiteCreatedPayload` through `ForgeOutbox`.

- Workspace read/query state belongs to `workspaceaggregationservice-sox`.
  Evidence: workspace query use cases read `WorkspaceSiteMetaRepository`; Kafka site-meta consumption lands in `ForgeInbox.receive(...)`; projection writes happen in `SiteMetaProjectionCommandImpl`.

- Site write-to-read synchronization is asynchronous through Kafka site-meta events, not synchronous service coupling.
  Evidence: `app-afesox/apis/stsssox/event/asyncapi.yml`, site outbox publishers in `siteservice-sox`, and inbox consumer in `workspaceaggregationservice-sox/pipe/pipe-consumer-site-meta/.../SiteMetaConsumer.java`.

- Notification delivery is event-driven from auth into notification service, then notification service performs downstream HTTP calls.
  Evidence: auth sends `EmailVerifyPayload` through outbox in `RegisterUserImpl` and `ResendEmailVerificationImpl`; `notificationservice-sox` consumes notification events and `EmailVerificationHandler` calls auth and then BFF.

- Backend REST and event contracts start in `app-afesox`.
  Evidence: `app-afesox/apis/metadata.yml` defines `api-first`, `client`, `event-producer`, and `event-consumer` generation entries used by services.

- Frontend-local TypeScript contracts are not backend source of truth.
  Evidence: `sitionix-spa/packages/contracts` is handwritten UI typing; backend Java APIs/clients/events are generated from `app-afesox`.

- Shared `forge-*` modules are platform libraries, not business ownership modules.
  Evidence: repo root contains `forge-common`, `forge-security`, `forge-it`, `forge-end-to-end`; business request flows live in service modules, while forge modules provide shared security, inbox/outbox, and testing infrastructure.

- Tooling and skill scripts are not runtime feature modules.
  Evidence: `skills`, `sitionix-infra`, `infrastructure`, `docker-compose.yml`, and root automation are operational/tooling assets, not request/event handlers for business flows.

# 3. Ownership rules by concern

## Auth, Session, JWKS, Email Verification Tokens

Owner:
- `authorisationservice-sox`

Non-owner:
- `backendforfrontendservice-sox`
- `notificationservice-sox`
- frontend apps

Consequences for implementation placement:
- Put token issuance, token verification, refresh-token storage, JWKS exposure, and auth-user status changes in `authorisationservice-sox`.
- Use the BFF only for browser-facing facade endpoints, cookie handling, and downstream auth calls.
- Do not make frontend-local state or notification handlers the source of truth for verification state.

## Browser Facade and Browser-Oriented Orchestration

Owner:
- `backendforfrontendservice-sox`

Non-owner:
- frontend apps
- downstream services

Consequences for implementation placement:
- Put browser-facing endpoints, request/response adaptation for browser UX, and multi-service orchestration in the BFF.
- Do not put browser-only transport concerns into downstream services if the browser already reaches them through the BFF.
- Do not add persistence ownership to the BFF.

## Site Write Model

Owner:
- `siteservice-sox`

Non-owner:
- `backendforfrontendservice-sox`
- `workspaceaggregationservice-sox`
- frontend apps

Consequences for implementation placement:
- Put site create/update/delete business rules, aggregate validation, and primary writes in `siteservice-sox`.
- Emit site-meta propagation from `siteservice-sox`.
- Do not implement write ownership inside the BFF or workspace aggregation service.

## Workspace Site Read Model

Owner:
- `workspaceaggregationservice-sox`

Non-owner:
- `siteservice-sox`
- `backendforfrontendservice-sox`
- frontend apps

Consequences for implementation placement:
- Put workspace site list, site overview, and projection-specific read shaping in `workspaceaggregationservice-sox`.
- Update the projection through consumed site-meta events.
- Do not turn `siteservice-sox` into the owner of workspace query behavior.

## Notification Delivery Flow

Owner:
- `notificationservice-sox` for notification consumption and delivery-side orchestration
- `authorisationservice-sox` for notification event production when auth flows trigger it

Non-owner:
- `backendforfrontendservice-sox` as a notification owner
- frontend apps

Consequences for implementation placement:
- Put notification-consumer behavior and template-specific delivery handlers in `notificationservice-sox`.
- Put notification-triggering decisions for auth flows in `authorisationservice-sox`.
- Do not move notification delivery state into the BFF.

## Backend Contract Generation

Owner:
- `app-afesox`

Non-owner:
- service-local generated classes
- frontend-local TypeScript contracts

Consequences for implementation placement:
- Start REST contract changes in `app-afesox/apis/*/rest`.
- Start event payload/topic changes in `app-afesox/apis/*/event`.
- Regenerate and consume produced artifacts instead of hand-creating DTOs, clients, or event wrappers in services.
- Follow the repository AGENTS rule: if a feature depends on generated artifacts from `app-afesox`, generation is mandatory and blocking.

## Frontend-Local UI Typing and UI-Level Adapters

Owner:
- `sitionix-spa/packages/contracts`
- app-local API adapters in `sitionix-spa/apps/*/src/features/**/api`

Non-owner:
- backend contracts

Consequences for implementation placement:
- UI-only types, view models, and adapter-level normalization may live in frontend packages.
- Do not treat frontend types as the source of truth for backend payloads.
- If the browser needs new server behavior or payload fields from the backend, start with backend contracts and backend services, not only frontend types.

## Shared Platform, Security, and Test Infrastructure

Owner:
- `forge-common`
- `forge-security`
- `forge-it`
- `forge-end-to-end`

Non-owner:
- service-specific business features

Consequences for implementation placement:
- Put cross-cutting security, inbox/outbox plumbing, and common test infrastructure in forge modules only when the change is genuinely shared.
- Do not place feature-specific business rules into forge modules just because multiple services depend on them.

# 4. Allowed interaction paths

- Browser -> shell SPA proxy -> BFF
  Current code path: shell proxies `/bffssox/*`, and auth/workspace frontend API calls are configured against that browser-visible base.

- BFF -> auth service for browser auth/session flows
  Current code path: BFF auth use cases call generated auth clients for login, refresh, resend verification, and verify-email flows.

- BFF -> site service for site write flows
  Current code path: BFF `CreateSiteImpl` delegates to `SiteClient`; site creation is implemented in `siteservice-sox`.

- BFF -> workspace aggregation service for workspace site queries
  Current code path: BFF `GetWorkspaceSitesImpl` and `GetSiteOverviewImpl` delegate to `WorkspaceClient`.

- Site service -> outbox -> Kafka `stsssox.${environment}.site-meta.public.v1` -> workspace aggregation inbox/projection
  Current code path: `siteservice-sox` emits `SiteCreatedPayload`; `workspaceaggregationservice-sox` consumes `SiteMetaEnvelope` and passes inbox payloads to `ForgeInbox.receive(...)`.

- Auth service -> outbox -> Kafka `ntfssox.${environment}.notification.public.unified.v1` -> notification service
  Current code path: `RegisterUserImpl` and `ResendEmailVerificationImpl` send `EmailVerifyPayload`; `NotificationConsumer` consumes notification events.

- Notification service -> auth service -> BFF
  Current code path: `EmailVerificationHandler` requests verification-link material from auth and then calls BFF `verifyEmail(...)`.

# 5. Forbidden or discouraged paths

- Frontend must not bypass the BFF to call business services directly for browser runtime flows.
  Repository basis: only shell proxies browser API traffic, and downstream services are configured as internal BFF dependencies.

- The BFF must not become a persistence owner.
  Repository basis: no BFF write/read repositories were found; current BFF role is HTTP facade plus downstream delegation.

- Site read-model/query concerns should not be implemented in `siteservice-sox` when the repository already routes them through `workspaceaggregationservice-sox`.
  Repository basis: workspace list/overview contracts and implementations live in `wagssox`, not `stsssox`.

- Site write concerns should not be implemented in `workspaceaggregationservice-sox`.
  Repository basis: workspace service owns projection state and query APIs; primary site creation lives in `siteservice-sox`.

- Frontend-local contracts must not be treated as backend contract source of truth.
  Repository basis: backend contracts are generated from `app-afesox`; frontend package contracts are handwritten adapters/types.

- Existing async site-to-workspace propagation should not be replaced by direct synchronous service calls without a repository-backed reason.
  Repository basis: site-meta outbox/inbox flow is already implemented end to end.

- Feature-specific business logic should not be added to `forge-*` modules, `skills`, local infra scripts, or deployment config.
  Repository basis: those modules are cross-cutting platform/test/tooling areas rather than request/event ownership modules.

- Do not handcraft generated DTO/client/event artifacts inside services when the real source of truth is in `app-afesox`.
  Repository basis: contracts and generation metadata are centralized in `app-afesox`, and AGENTS.md explicitly forbids manual generated-code reconstruction.

- Do not implement ownership logic locally just because another module exposes an API for reading or delegation.
  Repository basis: BFF calls downstream services and `notificationservice-sox` calls auth/BFF, but those callers do not own the downstream state or behavior. Reading or calling another module is not the same as owning its business logic.

# 6. Decision rules for future tasks

- If a task adds or changes a browser-facing backend endpoint, start at `app-afesox/apis/bffssox/rest`, then trace the BFF controller/use case/client, and only then change downstream services as needed.

- If a task changes browser authentication, refresh, JWKS, or verification-token semantics, inspect `authorisationservice-sox` first; the BFF is only the browser-facing facade for those capabilities.

- If a task adds or changes site write behavior, inspect `siteservice-sox` first.

- If a task adds or changes workspace site list or site overview behavior, inspect `workspaceaggregationservice-sox` first.

- If a task needs a new browser-visible view over downstream data, start from the BFF boundary even when the underlying owner is another service.

- If a task changes a REST contract used by Java services, start from `app-afesox`, not from generated code in service modules.

- If a task changes an event payload, event topic usage, producer, or consumer contract, start from `app-afesox/apis/*/event`, then update the producing service and consuming service against the regenerated artifacts.

- If a task only changes frontend rendering, mapping, or UI-local typing and does not change backend behavior, the change may stay in `sitionix-spa` packages/apps.

- If a frontend type change implies new backend fields or endpoints, do not stop in `sitionix-spa/packages/contracts`; trace the change back to BFF/downstream contracts and owners.

- If a task touches browser-facing workspace queries, check both the BFF contract and the workspace frontend adapter layer. Current frontend code contains endpoints that exceed the implemented backend surface.

- If a task touches notification templates or delivery behavior, inspect `notificationservice-sox` handlers and the auth event producer flow together. Treat them as one end-to-end chain.

- If a task affects site data seen in workspace, check whether the change belongs to:
  - `siteservice-sox` for primary write rules and emitted site-meta
  - `workspaceaggregationservice-sox` for projection shape and query behavior

- If a task looks like “add a shared helper” but the behavior is business-specific to one service flow, keep it inside that owning service instead of promoting it into `forge-*`.

- If a task requires generated artifacts from `app-afesox`, treat generation as part of the task, not as a follow-up.

- If a pattern appears only in a single flow and it conflicts with the core rules above, treat it as an exception, not as a template. Do not generalize it unless the repository evolves the same pattern into other flows.

# 6.0 Flow understanding rule

- Before decomposition or implementation starts, Codex MUST reconstruct the relevant end-to-end flow from repository code.

- This rule owns the architecture-side prerequisite only. Overall task orchestration, scope discipline, Definition of Done, contract-generation handoff, and IT handoff are controlled by the active Forge AI lane flow.

- The minimum flow reconstruction must identify:
  - entrypoint: browser interaction, HTTP endpoint, Kafka consumer/producer path, scheduler, or worker
  - owning module or service
  - downstream dependencies
  - state transitions and persistence owner
  - synchronous versus asynchronous boundaries
  - browser-facing versus internal-only boundaries

- Flow reconstruction must be based on actual repository code and runnable configuration, not on README assumptions or architectural preference.

- If the flow is not understood end to end, implementation must not start.

- If multiple flows intersect, reconstruct each ownership boundary separately before decomposition.

# 6.1 Execution order rules

- These order rules are mandatory when a task spans multiple layers. Do not reorder them for convenience.

- If a task involves contracts, generated artifacts, backend services, BFF exposure, and frontend usage, the implementation order MUST be:
  1. `app-afesox` contracts
  2. generated artifacts
  3. owning backend service or services
  4. BFF, if the capability is browser-facing
  5. frontend

- If a REST or event contract is part of the change, do not implement service logic first. Change the contract first, then regenerate, then implement against the generated artifact.

- If frontend behavior depends on backend payloads or endpoints, do not update the frontend first. The frontend must follow an existing backend contract, not define it.

- If a task touches both site write ownership and workspace read ownership, implement the write-side owner first, then event propagation, then projection/read-side behavior.

- If a task is browser-facing but the owning business logic is downstream, implement the downstream owner first and expose it through the BFF only after the owner behavior exists.

- If a task only changes frontend rendering or local mapping and does not require backend contract or behavior changes, the frontend may be updated directly. This is the exception because no cross-layer dependency exists.

- Do not leave a cross-boundary task in a partially broken state. If a task spans contract, owner service, event flow, BFF, or frontend layers, do not stop after changing one layer in a way that breaks the others.

- If a cross-boundary task cannot be completed because of a blocker, identify the task as blocked or intentionally deferred. Do not silently leave incompatible contracts, generated artifacts, service behavior, BFF exposure, or frontend usage behind.

# 6.2 Task decomposition rules

- Tasks MUST be decomposed by ownership boundary before implementation starts.

- Treat each of the following as a separate implementation step when they are involved:
  - contract change
  - write model change
  - read model change
  - event propagation
  - BFF exposure
  - frontend usage

- Do not mix multiple ownership concerns into one implementation step.

- Do not implement write model, read model, and browser facade work in a single change block unless the repository already contains the complete, contract-aligned behavior and the remaining edits are mechanical wiring.

- Each ownership boundary carries a separate responsibility and must be validated separately in code:
  - `app-afesox` owns contract definition
  - write owners such as `siteservice-sox` and `authorisationservice-sox` own primary state changes
  - read owners such as `workspaceaggregationservice-sox` own projections and query shaping
  - `backendforfrontendservice-sox` owns browser exposure and browser-oriented orchestration
  - `sitionix-spa` owns frontend usage and UI mapping

- For a site capability that becomes browser-visible in workspace, the default decomposition is:
  1. contract change in `app-afesox`
  2. generated artifact update
  3. write-side change in `siteservice-sox` if primary site state changes
  4. event propagation update if site-meta payload or event semantics change
  5. read-side projection/query change in `workspaceaggregationservice-sox` if workspace data shape changes
  6. BFF exposure change in `backendforfrontendservice-sox` if the browser needs access
  7. frontend usage change in `sitionix-spa`

- For an auth-triggered notification flow, the default decomposition is:
  1. contract/event change in `app-afesox` if payload or endpoint shape changes
  2. generated artifact update
  3. auth-side event production change in `authorisationservice-sox`
  4. notification-consumer/handler change in `notificationservice-sox`
  5. BFF change only if browser-facing verification behavior or browser endpoint exposure must change
  6. frontend change only if browser UX changes

- If a task spans several boundaries, finish one boundary cleanly before moving to the next. Do not blur ownership by scattering partial logic across all modules at once.

- Decomposition does not start until the relevant flow is understood. If the flow is unclear, return to section `6.0` instead of guessing a split.

# 7. Architectural risk checks for Codex

These are hard stop conditions. If any condition below is true, stop and reassess implementation before changing code.

- If the change is being added outside the module that currently owns the state or contract, this is a violation.

- If logic added to the BFF persists data, becomes the primary write owner, or becomes the primary read-model owner, this is a violation.

- If a workspace read concern is being implemented in a write owner such as `siteservice-sox`, or a site write concern is being implemented in `workspaceaggregationservice-sox`, this is a violation.

- If a backend contract change is being implemented without starting from `app-afesox`, this is a violation.

- If a frontend change implies backend endpoint or payload changes but backend contracts and owning services are untouched, this is a violation.

- If an existing async propagation path is being replaced with direct synchronous coupling without repository evidence that the architecture already moved that way, this is a violation.

- If observable behavior is being changed without explicitly recognizing and justifying that change, this is a violation.

- If feature-specific business logic is being placed in shared platform or tooling modules such as `forge-*`, `skills`, or deployment config, this is a violation.

- If a change depends on generated artifacts but generation and artifact update are skipped, this is a violation.

- If a single-flow exception is being copied into a new feature as if it were a core rule, this is a violation.

- If logic that depends on state owned by another module is being implemented locally in a non-owner module, this is a violation unless the repository already implements that exact pattern intentionally.

- If a task is being left with broken cross-boundary compatibility between contracts, generated artifacts, owner services, BFF, or frontend layers, this is a violation.

# 7.1 Fallback decision rules

- If ownership is unclear, do not assume. Find the closest existing repository flow and follow its placement pattern.

- If multiple placements seem possible, prefer consistency with an existing repository pattern over theoretical architectural cleanliness.

- If no precedent exists in code, explicitly mark the uncertainty in the task reasoning and avoid inventing architecture silently.

- If contract ownership is unclear, default to checking `app-afesox` first before editing service-local DTOs, clients, or events.

- If browser-facing placement is unclear, default to tracing the existing browser path from `sitionix-spa` to BFF to downstream owner rather than opening a direct frontend-to-service path.

# 7.2 Anti-overengineering rules

- Do not introduce new architectural patterns unless the same pattern already exists in repository code or the task explicitly requires it.

- Do not introduce new abstraction layers to “clean up” an area when the repository already has a working local pattern for that concern.

- Do not create new cross-cutting modules for feature-specific behavior.

- Prefer the existing module structure, existing flow shape, and minimal necessary change over conceptual redesign.

- If an existing local pattern is imperfect but repository-wide, extend it carefully rather than replacing it with a new pattern inside one feature.

# 7.3 Source of truth priority

- When several layers participate in one change, source of truth priority is mandatory and must be followed in this order:
  1. backend REST contracts and event contracts in `app-afesox`
  2. owning backend service implementation
  3. async event semantics, producers, and consumers
  4. BFF exposure and browser-facing orchestration
  5. frontend usage and UI-local typing

- Lower-priority layers must not redefine higher-priority layers.

- Frontend-local types must not become the source of truth for backend behavior.

- BFF request/response adaptation must not become the source of truth for domain state or state-transition semantics.

- Generated service-local classes must not become the source of truth over the originating contract in `app-afesox`.

- If two layers disagree, the higher-priority owner determines the change direction. Do not “fix” the mismatch by redefining the contract in a lower-priority layer.

# 7.4 Verification responsibility

- Every architectural change must be verifiable through repository-appropriate checks.

- If code changed but the resulting architectural behavior cannot be verified with the module-appropriate build, test, or other repository-standard validation flow, the task is incomplete.

- Prefer the existing verification pattern already used by the affected module or flow instead of inventing a one-off validation approach.

- Architecture reasoning alone is not sufficient once code has changed. The changed owner path, and any changed cross-boundary path, must be validated as far as the repository allows.

- If full verification is blocked, state the blocker explicitly and identify which affected boundary remains unverified.

# 8. Known current inconsistencies

- The workspace frontend expects a larger API surface than the currently implemented backend contracts expose.
  Repository basis: `sitionix-spa/apps/workspace/src/features/workspace/api/workspaceHttpApi.ts` calls `/api/v1/workspace/*` endpoints for dashboard, trash, collections, domains, CRM, editor, duplicate, restore, and delete flows, while current BFF and workspace OpenAPI contracts expose only `/api/v1/sites`, `/api/v1/sites/{siteId}/overview`, plus auth/user endpoints on the BFF.

- Notification email verification currently depends on an extra hop through the BFF.
  Repository basis: `notificationservice-sox` requests verification-link material from auth, then calls BFF `verifyEmail(...)` rather than calling auth verification directly. Treat this as an exception in the current codebase, not as a general architectural rule and not as a template for new features. Treat it as legacy or temporary behavior unless repository code evolves.

- `notificationservice-sox` does not show the inbox-style deduplication used by workspace aggregation.
  Repository basis: `NotificationConsumer` consumes and dispatches directly; no `ForgeInbox.receive(...)` path or notification persistence owner was found in main code.

- Local runtime config still contains Mongo residue for site service even though the main site-service code path is Postgres plus outbox.
  Repository basis: root `docker-compose.yml` still defines `site-mongo` and `MONGODB_URI`.

- Site updated/deleted event infrastructure exists ahead of clearly implemented write flows in the main site-service code.
  Repository basis: `SiteUpdatedPayload`, `SiteDeletedPayload`, and publisher classes exist, but the main `siteservice-sox` sources currently show only create-site write handling.

- Some architecture details are still ambiguous unless a task reaches them.
  Current example: the repository clearly shows the implemented read/write split for site metadata, but broader workspace domains such as CRM/editor/collections are present in frontend code without matching backend ownership in current contracts. Treat those areas as missing or not yet implemented until code proves otherwise.
