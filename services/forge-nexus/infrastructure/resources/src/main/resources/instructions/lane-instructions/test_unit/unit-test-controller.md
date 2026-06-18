# Test Unit Controller Rules

Read this file only when affected source files include controllers or API adapter classes.

- Call controller methods directly.
- Mock controller dependencies (use cases, mappers, request/context/passive transport objects when needed).
- Verify delegation, mapped input, mapped output, and response shape at method level.
- Do not use MockMvc.
- Do not start Spring context.
- HTTP wiring belongs to integration tests.
