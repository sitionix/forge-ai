# IT PostgreSQL / Persistence Flow

## Flow

Use `forgeit.postgresql()` for DB setup and verification when persistence is part of expected behavior.

Use existing service-local DB contracts.
Use contract graphs for setup when available.
Use lookup or reference contracts with the existing cleanup policy.
Use mutable business entities with the existing cleanup policy.
Seed only the minimum state required for the test case.
Verify DB state only when persistence or projection state is part of the QA case.

## Assertions

Use existing assertion style:

- contract/entity assertions;
- fetched entities;
- ignored dynamic fields for generated IDs, timestamps, hashes, relations, or metadata;
- relation fetching only when relation content is relevant.

## Boundary

Do not add manual cleanup when ForgeIT cleanup already handles the test lifecycle.
Do not bypass Forge PostgreSQL support with custom SQL or probing utilities when Forge contracts/entities cover the scenario.