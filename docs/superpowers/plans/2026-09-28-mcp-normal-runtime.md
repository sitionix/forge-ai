# MCP normal runtime implementation plan

Spec: [current design](../specs/2026-09-28-mcp-normal-runtime-design.md).
The user's latest instruction supersedes the former operator/session and internal
service-bearer requirements. This remains inline work in `feature/SITIONIX-154`.

1. Keep unconditional main MCP composition and dedicated Remote Access composition
   roots; remove the former global activation and downgrade branches.
2. Remove Forge operator authentication, its session API and general Agent bearer
   guard/client injection. Keep authentication scoped to existing Remote Access
   routes. Test MCP reads, mutations and catalog without cookies or bearer headers
   using existing typed ForgeIT contracts; preserve scoped RA regressions.
3. Provision only existing encryption/database material, with exclusive creation,
   protected owner/modes, validation and idempotent repeat. Remove MCP operator and
   service secret generation and the Nexus authentication environment dependency.
   Preserve the isolated launcher/runtime identity and managed workspace path.
4. Preserve source workspace changes during adoption, reject reserved adoption
   markers, and keep clone-attempt parent permissions compatible with the existing
   production Java clone adapter. Cover the actual adoption-to-clone boundary.
5. Load Settings directly without login controls or session state. Keep the shared
   sidebar and compact MCP integrations card, empty state, Add action, dialogs,
   section errors and explicit connection Enable/Disable semantics.
6. Run focused and full Console/Agent/Nexus/runtime regressions. Run normal
   `just stop → just start` and a repeat `just start`; validate new process timestamps,
   generated/effective configuration, persisted key/database bytes and real Chrome
   on :9099. Record exact results/skips and fresh exact-commit CI.

Historical OS probes are not fresh acceptance. Live provider/Registry, macOS,
full model execution and production Remote Access pairing remain NOT_VERIFIED
unless actually executed. Do not modify PR metadata/comments/reviews or merge.
