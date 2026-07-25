# ForgeIT Setup

## Integration Test Boundary

Integration tests verify observable behavior through real application boundaries.
Use integration tests for:

- boot-module HTTP/API flows;
- Spring request pipeline behavior;
- persistence effects;
- projection state;
- outbox/inbox behavior;
- Kafka producer or consumer behavior;
- real outbound HTTP dependency behavior through WireMock;
- service wiring that cannot be validated by unit tests.

Direct class-level behavior belongs to unit tests.
Controller tests that directly instantiate controllers with Mockito are unit tests.

## ForgeIT Setup

Use the existing service-local ForgeIT setup.
Prefer the existing service-local support interface that extends `ForgeIT`.
Use `@IntegrationTest` for backend ITs unless an existing local test is an explicit infrastructure exception.
Autowire exactly one non-static ForgeIT support field per test class.
Use only ForgeIT features already used by the target service unless the QA case requires an additional supported feature.
Common ForgeIT entrypoints:

- `forgeit.mockMvc()` for HTTP/API flows;
- `forgeit.postgresql()` for DB setup and assertions;
- `forgeit.wiremock()` for outbound HTTP dependencies;
- `forgeit.kafka()` for Kafka, outbox, or inbox flows.

## Mandatory Style

Use service-local Forge support as the single entry point for infrastructure features.
Keep HTTP execution in Forge MockMvc DSL and service endpoint contracts.
Keep fixture-driven requests and responses in:

`src/test/resources/forge-it/**`

Use Forge PostgreSQL support for setup and verification.
Rely on Forge cleanup lifecycle.
If an existing integration test is not in ForgeIT style, refactor it to ForgeIT style before reporting success.

## Composition Rules

Keep `@Autowired` fields minimal.

Use only one Forge entry point or manager per IT class.
Do not autowire extra application beans in IT tests.
Use mocks only for true external dependencies that cannot be validated through Forge runtime boundaries.
Do not mock internal application collaborators when the flow is testable through ForgeIT.
Do not create ad-hoc infrastructure setup when ForgeIT support already exists.