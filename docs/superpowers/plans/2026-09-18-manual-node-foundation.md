# Manual Node Foundation — Phase 1

**Goal:** Preserve `AGENT` / `MANUAL` through workflow templates, immutable runtime snapshots and node runs, without changing routing, frames or ports.

**Spec:** Manual Node Roadmap, Phase 1, supplied in the conversation. Each phase ships in a separate PR; stop after opening this PR for external review and corrections.

**Architecture:** Add an explicit node type with legacy `AGENT` defaults. Validate agent references only for agent nodes and reject targets on manual nodes. Snapshot manual nodes without agent metadata. Preserve the discriminator in persistence, API responses and runtime record copies.

**Tech stack:** Java 21, Spring Boot, Jackson, JPA, PostgreSQL/Flyway, JUnit/AssertJ.

## Constraints

- No MCP, fake agents, executor registry or SystemNode framework.
- No manual waiting lifecycle or manual-selection endpoint in this PR. The Agent lifecycle skips MANUAL so the intermediate foundation leaves it PENDING rather than failing it for a missing agent model.
- Retain existing agent behavior and historical data compatibility.
- Existing optional agent fields remain optional; fields previously required remain required for AGENT.
- Use the existing directory on a new `feature/SITIONIX-*` branch.

## Implementation checklist

- [x] Add API contract tests for omitted type, invalid type and AGENT/MANUAL target rules; run them before implementation.
- [x] Add `NodeType` to domain records and API DTOs; preserve old Java constructors and default old payloads to AGENT.
- [x] Make validator and snapshot builder type-aware; preserve the type in every NodeRun copy.
- [x] Add a forward migration and JPA mappings for all three tables. Enforce required agent metadata conditionally and require no agent metadata for MANUAL.
- [x] Exercise real PostgreSQL roundtrips, immutable snapshots and migration from the current schema.
- [x] Run Forge Agent verification and inspect the diff.
- Delivery: commit and open the Phase 1 PR.
- Review checkpoint: stop after opening the PR; do not start Phase 2 until external review and corrections are complete.

## Verification

`mvn -q -Dapi.version=1.44 -pl services/forge-agent/boot -am verify` passed. The Docker API override is required by the local Docker 29 environment. The lifecycle regression was first observed failing (MANUAL became FAILED), then passed with the Agent-only guard.
