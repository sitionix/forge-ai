# 1. Purpose

This file describes the internal architecture of backend services and shared service modules in this repository. Its purpose is to help Codex preserve internal layering when implementing changes inside a service, not to restate system-level service interactions.

This document is architecture-only. It intentionally excludes testing strategy, test infrastructure, and Forge IT usage.

# 2. Common internal architecture model

The backend services in this repository follow a recurring multi-module layout:

- `domain`
- `application`
- `api-rest` when the service exposes HTTP
- `clients` when the service calls downstream HTTP services
- `pipe` when the service publishes or consumes Kafka events
- `infrastructure` when the service owns persistence
- `boot`

The recurring dependency shape is repository-backed and consistent:

- `application` depends on `domain`
- `api-rest` depends on `domain` plus generated API-first contracts
- `clients` depends on `domain` plus generated client contracts
- `pipe` depends on `domain` plus generated event contracts
- `infrastructure` depends on `domain`
- `boot` depends on all runtime modules and wires them together

In practice, this is a hexagonal or onion-like architecture at the module boundary level, not a strict pure onion inside every module.

What clearly supports the hexagonal reading:

- Core contracts are placed in `domain`: repository ports, outbound client ports, use-case interfaces, domain records/enums, and event payload types.
- Adapters are split into separate modules: HTTP controllers in `api-rest`, persistence in `infrastructure`, outbound HTTP in `clients`, Kafka producers/consumers in `pipe`.
- `boot` acts as composition root by depending on the whole runtime slice and declaring Spring configuration.

What prevents calling it a strict pure onion:

- Several `domain` modules are not framework-free. Examples:
  - `authorisationservice-sox/domain` uses `jakarta.validation` on `RegisterUserDO`
  - `siteservice-sox/domain` and `authorisationservice-sox/domain` depend on `forgecommon-outbox-core`
  - `workspaceaggregationservice-sox/domain` exposes `org.springframework.data.domain.Pageable`
- Several `application` modules include Spring configuration, bean declarations, or security/container concerns in addition to use-case orchestration.

The repository is therefore moving in a layered hexagonal direction, but Codex should treat it as partially pure rather than perfectly isolated.

Shared platform libraries support this shape:

- `forge-common` provides inbox/outbox infrastructure families used by write and projection services.
- `forge-security` provides server/client/user-JWT runtime concerns used mainly from `boot`, `application`, and browser-facing adapters.

These shared libraries provide cross-cutting runtime capabilities. They are not business-owner modules.

# 3. Layer responsibilities

## `domain`

Purpose:

- Holds service-core contracts and core data types.
- Usually contains:
  - domain models and enums
  - use-case interfaces
  - repository interfaces
  - outbound client interfaces
  - event payloads and event-type enums when the service emits or consumes messages

What belongs there:

- Business-facing records/classes such as `Site`, `WorkspaceSiteMeta`, auth user models, notification models.
- Port interfaces such as `SiteRepository`, `WorkspaceSiteMetaRepository`, `AuthUserClient`, `SiteClient`.
- Use-case interfaces such as `CreateSite`, `GetWorkspaceSites`, `VerifyEmail`.

What must not belong there:

- Controllers, HTTP request handling, REST exception handling.
- JPA entities, Spring Data repositories, SQL details, persistence mappers.
- Generated client implementations, `RestTemplate` setup, Kafka producer/consumer beans.

Repository-backed observations:

- In most services, `domain` is the central dependency target for the other runtime modules.
- `backendforfrontendservice-sox/domain` is not a rich business domain. It is closer to a core contract layer for a facade service: request/response models, outbound client ports, and use-case interfaces.
- `notificationservice-sox/domain` similarly acts as a service-core contract layer with notification models, handler interfaces, and outbound client ports.
- Domain purity is partial, not strict:
  - validation annotations appear in domain models
  - inbox/outbox contract types appear in domain
  - `workspaceaggregationservice-sox/domain` exposes `Pageable`

## `application`

Purpose:

- Implements use-case orchestration on top of domain contracts.
- Coordinates transactions, security context access, and calls to domain ports.

What belongs there:

- Use-case implementations such as `RegisterUserImpl`, `CreateSiteImpl`, `GetWorkspaceSitesImpl`.
- Application services and policies that support use cases.
- Transaction boundaries.
- Projection commands and event-handler-to-use-case handoff when the service consumes messages.

What must not belong there:

- HTTP controller logic.
- JPA entities or repository implementation details.
- Generated HTTP client setup or `RestTemplate` bean creation.
- Kafka envelope-to-wire mapping.

Repository-backed observations:

- `siteservice-sox/application` is close to the intended role: transactional orchestration over domain ports and outbox.
- `workspaceaggregationservice-sox/application` owns both query use cases and inbox event handlers; the handlers are thin and forward to projection commands.
- `backendforfrontendservice-sox/application` is especially thin: most use cases delegate directly to outbound client ports, which matches the service’s facade role.
- `authorisationservice-sox/application` is broader than pure orchestration. It also contains:
  - token and key services
  - security provider classes
  - configuration/property classes
- `notificationservice-sox/application` is the least pure application layer. It contains handler orchestration, but also container-level registration code such as `NotificationHandlerBeanFactoryProcessor`.

## `api-rest`

Purpose:

- HTTP adapter layer.
- Exposes generated API-first interfaces and maps between generated DTOs and core service models.

What belongs there:

- Controllers implementing generated `app-afesox` APIs.
- API mappers.
- HTTP exception translation.
- HTTP-only helpers, such as BFF cookie/header handling.

What must not belong there:

- Persistence logic.
- Primary business rules.
- Downstream client implementation details.
- Kafka/event handling.

Repository-backed observations:

- `api-rest` modules depend on `domain`, not on `application`.
- Controllers inject use-case interfaces from `domain`, which means the domain module is the contract surface between transport and application.
- Controllers are mostly thin:
  - map generated DTOs to core request models
  - call a use case
  - map the result back to generated DTOs
- `backendforfrontendservice-sox/api-rest` additionally contains HTTP-specific helpers such as refresh-cookie header building. That still fits the transport layer because it is browser/HTTP-specific.
- `workspaceaggregationservice-sox/api-rest` constructs `Pageable` directly in the controller, which is a real transport-to-core leak because the core contract already accepts Spring Data pagination.

## `clients`

Purpose:

- Outbound HTTP adapter layer around generated downstream clients.

What belongs there:

- Implementations of outbound client ports declared in `domain`.
- Mapping between core models and generated client DTOs.
- Low-level client-call execution and downstream error translation.

What must not belong there:

- Ownership of business state.
- Cross-service orchestration decisions that belong in `application`.
- Browser/HTTP controller concerns.

Repository-backed observations:

- `backendforfrontendservice-sox/clients` is a clean example of outbound adapters:
  - core port in `domain`
  - adapter implementation in `clients`
  - generated client artifact from `app-afesox`
  - boot-time `ApiClient`/`RestTemplate` configuration in `boot`
- `notificationservice-sox/clients` follows the same pattern for auth and BFF calls.
- These modules are adapters only. They translate and execute calls, but do not own the downstream behavior.

## `pipe`

Purpose:

- Event adapter layer for Kafka producers and consumers.

What belongs there:

- Envelope mapping between domain event payloads and generated event artifacts.
- Producer/consumer handler implementations.
- Transport-specific event metadata handling.

What must not belong there:

- Primary domain rules.
- Persistence ownership.
- Controller/browser logic.

Repository-backed observations:

- `authorisationservice-sox/pipe` and `siteservice-sox/pipe` are producer adapters. They take outbox events, map them to generated envelopes, and send them.
- `workspaceaggregationservice-sox/pipe` is a thin consumer adapter. It parses the envelope, resolves the event type, maps to inbox payload, and hands off to `ForgeInbox.receive(...)`.
- `notificationservice-sox/pipe` is thicker than the other pipe modules. Its consumer maps the event to a domain `Notification` and immediately dispatches `notification.getTemplate().send(notification)`. This is still adapter-led, but it brings more runtime orchestration into the event boundary than the workspace consumer does.

## `infrastructure`

Purpose:

- Persistence adapter layer.

What belongs there:

- JPA entities.
- Spring Data repositories.
- Repository implementations for domain ports.
- Persistence mappers.

What must not belong there:

- Core business rules.
- HTTP/Kafka transport logic.
- Use-case orchestration.

Repository-backed observations:

- `authorisationservice-sox/infrastructure/postgresql`, `siteservice-sox/infrastructure/postgresql`, and `workspaceaggregationservice-sox/infrastructure/postgresql` are adapter-level modules implementing domain repository contracts.
- Their repository implementations are thin: map core models to entities, call JPA repositories, map back.
- Current active persistence ownership is Postgres-based in the main service graphs.
- `siteservice-sox/infrastructure/pom.xml` includes only `postgresql`; the `mongodb` subtree visible in the repository is stale build residue rather than an active runtime module.

## `boot`

Purpose:

- Composition root and runtime wiring layer.

What belongs there:

- Spring Boot application entrypoint.
- Security configuration.
- `ApiClient`/`RestTemplate` bean configuration for generated clients.
- Inbox/outbox event-type bean registration.
- Externalized runtime property binding.

What must not belong there:

- Core business rules.
- Persistence ownership logic.
- Controller behavior.

Repository-backed observations:

- `boot` modules depend on all runtime submodules, which is the clearest sign that `boot` is the composition root.
- `authorisationservice-sox/boot` wires security and outbox runtime.
- `backendforfrontendservice-sox/boot` wires downstream generated clients and browser/runtime HTTP concerns.
- `siteservice-sox/boot` and `workspaceaggregationservice-sox/boot` register outbox/inbox event types and runtime integration beans.
- `notificationservice-sox/boot` wires generated auth/BFF clients and runtime HTTP stack for those calls.

# 4. Service-by-service architecture analysis

## `authorisationservice-sox`

Internal structure:

- `domain`, `application`, `api-rest`, `infrastructure/postgresql`, `pipe/pipe-producer-notification-v1`, `boot`

Observed patterns:

- Strong modular split around a stateful write service.
- `domain` contains auth models, repository ports, use-case interfaces, and notification payload types.
- `application` implements auth use cases and application services for token handling, crypto, resend policy, and auth-specific security support.
- `api-rest` is a thin generated-contract HTTP adapter.
- `infrastructure/postgresql` implements repository ports with JPA.
- `pipe` is an outbox publisher adapter for notification events.
- `boot` is a real composition root with Spring Boot entrypoint, security filter chain, and outbox runtime wiring.

Architectural direction:

- The service is evolving toward a clearly layered write service with explicit ports and adapters.
- It already has stronger module separation than the in-module package purity suggests.

Smells / exceptions / deviations:

- `domain` is not framework-free because it includes validation and outbox-facing contract types.
- `application` is broader than use-case orchestration; it also owns security/provider/config/service machinery.
- This is still a layered service, but not a strict pure onion.

## `backendforfrontendservice-sox`

Internal structure:

- `domain`, `application`, `api-rest`, `clients`, `boot`

Observed patterns:

- This is a facade service, so its internal “domain” is mainly a core contract layer:
  - outbound client ports
  - use-case interfaces
  - browser-facing request/response models
- `application` is almost entirely thin orchestration and delegation to outbound ports.
- `clients` is the main adapter layer, wrapping generated downstream clients for auth, site service, and workspace aggregation.
- `api-rest` is the browser-facing HTTP adapter and contains browser-specific helpers such as cookie header building.
- `boot` wires security, downstream clients, CORS/CSRF/browser runtime behavior.

Architectural direction:

- The service is evolving toward a clear facade/orchestrator architecture, not toward a rich domain model.
- Thin use-case implementations are consistent with that role.

Smells / exceptions / deviations:

- The module name `domain` overstates what it contains; this is not a rich domain in practice.
- `domain/pom.xml` pulls in Spring Boot even though the source there is mostly ports and data models.
- Some HTTP/browser helper classes live in `api-rest`; that is appropriate for this service, but it reinforces that the BFF is transport-heavy by design.

## `siteservice-sox`

Internal structure:

- `domain`, `application`, `api-rest`, `infrastructure/postgresql`, `pipe/pipe-producer-site-meta`, `boot`

Observed patterns:

- This is the cleanest write-side service in the repository.
- `domain` contains the site aggregate model, write command model, repository port, use-case interface, and site-meta event payloads.
- `application` performs transactional orchestration and emits outbox events after persistence.
- `api-rest` is a thin generated-contract adapter.
- `infrastructure/postgresql` is a thin repository adapter.
- `pipe` is a thin event publisher adapter on top of generated event artifacts.
- `boot` wires outbox event typing and runtime assembly.

Architectural direction:

- The service is evolving toward a very explicit write-service hexagon with outbox-backed propagation.
- The internal structure is already stable and intentional.

Smells / exceptions / deviations:

- `domain` still depends on validation/Jackson/outbox abstractions, so it is not framework-free.
- Update/delete event adapter classes already exist, but the main write-side application flow currently exposes only create behavior in primary sources.
- A stale `mongodb` subtree exists as build residue, but the active infrastructure module is Postgres-only.

## `workspaceaggregationservice-sox`

Internal structure:

- `domain`, `application`, `api-rest`, `infrastructure/postgresql`, `pipe/pipe-consumer-site-meta`, `boot`

Observed patterns:

- This is a projection and query service.
- `domain` contains projection models, repository ports, query use-case interfaces, inbox event types, and inbox payloads.
- `application` owns query use cases, projection command logic, and thin inbox event handlers.
- `pipe` is intentionally thin: it maps Kafka envelopes to inbox payloads and hands them to inbox processing.
- `infrastructure/postgresql` persists and loads the projection read model.
- `api-rest` exposes workspace query APIs over generated contracts.
- `boot` wires inbox event typing and runtime integration.

Architectural direction:

- The service is evolving toward a clear read-model / projection-service architecture with inbox-backed event ingestion.
- The split between event transport and projection command handling is stronger here than in the notification service.

Smells / exceptions / deviations:

- `Pageable` leaks into `domain` and `application`, so the core contract is coupled to Spring Data pagination.
- The controller also constructs `PageRequest`/`Sort`, which reinforces the transport-framework coupling around query contracts.
- This is still layered, but not a strict framework-free core.

## `notificationservice-sox`

Internal structure:

- `domain`, `application`, `clients`, `pipe/pipe-consumer-notification`, `boot`

Observed patterns:

- This is an event-consumer service with outbound HTTP adapters, not a CRUD-style service.
- `domain` contains notification models, handler abstractions, template registry enum, and outbound client ports.
- `application` contains concrete handlers such as `EmailVerificationHandler`, plus runtime/configuration machinery that binds handlers to configured message properties.
- `clients` implements outbound auth/BFF adapters using generated clients.
- `pipe` consumes notification events and maps them into domain notifications.
- `boot` wires generated clients and runtime HTTP configuration.

Architectural direction:

- The service is evolving toward a pluggable handler architecture keyed by notification template.
- It is service-modularized, but less purely layered than the write/query services.

Smells / exceptions / deviations:

- `application` contains container-level bean registry processing, which is more composition-like than orchestration-like.
- `domain` contains a mutable `NotificationTemplate` enum that receives a handler from container-driven wiring. That softens the separation between core model and runtime assembly.
- `pipe` dispatches directly into the handler chain after mapping the event, so the event boundary carries more control flow than the workspace pipe does.

# 5. Internal architectural rules for Codex

- Treat module boundaries as the primary expression of internal architecture in this repository. Preserve those first.

- Place core models, use-case interfaces, repository ports, outbound client ports, and event payload types in `domain`.

- Place use-case implementations, transaction boundaries, projection commands, and application orchestration in `application`.

- Place HTTP request/response adaptation, generated API controller implementations, and HTTP-only helpers in `api-rest`.

- Place downstream generated client implementations and client-call mappers/executors in `clients`.

- Place Kafka producer/consumer handlers and envelope mappers in `pipe`.

- Place JPA entities, Spring Data repositories, persistence mappers, and repository implementations in `infrastructure`.

- Place Spring Boot entrypoints, bean wiring, security configuration, generated `ApiClient` setup, and inbox/outbox runtime registration in `boot`.

- Do not move persistence logic upward into `application`, `api-rest`, `clients`, or `pipe`.

- Do not move HTTP or Kafka transport concerns into `domain`.

- Do not let controllers or consumers become business-owner layers. They should translate and hand off.

- Do not let outbound clients become orchestrators or owners of downstream business rules. They are adapters.

- Do not let `boot` become a business logic layer. If a class is mostly about bean assembly, security/runtime wiring, or generated client setup, keep it in `boot`; if it is about use-case behavior, keep it out of `boot`.

- Use the service role to choose the right internal pattern:
  - in write services, application should own transactional state changes and event emission decisions
  - in projection services, application should own projection commands while `pipe` remains transport-focused
  - in facade services, application may stay thin, but outbound HTTP remains in `clients`

- Existing framework leaks in `domain` and `application` are current repository reality, not permission to add more leaks casually.

- When a service already uses a dedicated adapter module, do not bypass it:
  - do not put generated client setup into `application`
  - do not put JPA entities into `domain`
  - do not put envelope mapping into `application`

# 6. Known deviations and caution areas

- The repository is hexagonal by module layout more than by strict framework isolation. Do not describe the current services as pure onion layers and then code against that fiction.

- `domain` modules are not universally framework-free:
  - validation annotations appear in domain models
  - outbox/inbox abstractions appear in domain
  - `workspaceaggregationservice-sox/domain` exposes `Pageable`
  Codex should avoid worsening these leaks.

- `application` is not universally “use cases only”:
  - auth application contains security/provider/config classes
  - notification application contains container-driven handler registration
  Codex should not normalize this into new transport or persistence leakage, but must account for the current layout.

- `backendforfrontendservice-sox/domain` is a facade core, not a rich business domain. Do not force rich-domain patterns into the BFF just because the module is named `domain`.

- `notificationservice-sox` is structurally different from the CRUD-style services. It is a consumer-plus-handler service with outbound adapters. Do not use it as the default template for write/query services.

- `workspaceaggregationservice-sox` already couples query contracts to Spring Data pagination. Do not spread that coupling further unless the existing flow already requires it.

- `siteservice-sox` contains stale Mongo build residue under `infrastructure/mongodb`, but the active infrastructure module is Postgres-only. Do not treat the stale subtree as a live architectural direction.
