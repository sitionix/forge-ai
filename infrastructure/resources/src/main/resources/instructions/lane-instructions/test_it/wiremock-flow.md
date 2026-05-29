# IT WireMock / External HTTP Dependencies

## Flow

Use `forgeit.wiremock()` only for real outbound HTTP dependencies.

Use existing WireMock endpoint helpers and fixture conventions.
Create stubs before executing the system under test.
Verify outbound calls only when the flow depends on those calls.
Use default mappings when the service already uses default-driven WireMock style.
Override only the request, response, status, path params, query params, or delay needed by the scenario.

## Boundary

Do not use WireMock to replace internal service logic that should be tested through application behavior.
Do not introduce WireMock when the assigned flow has no outbound HTTP dependency.