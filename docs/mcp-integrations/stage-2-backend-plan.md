# Stage 2 backend — Custom MCP probe and inventory

Scope follows `roadmap.md` Stage 2 and `flows.md` Flow 2. Stage 1 owns saved global connections and credentials; Flow 1 owns Registry metadata. This work adds only the Agent protocol boundary and typed Nexus management path. Settings UI, execution gateway, OAuth and connection creation from the catalog remain later work.

1. Add a small domain port for bounded remote MCP discovery. Keep SDK types, wire JSON and credential bytes inside Agent infrastructure. Resolve the saved credential only for the call and clear its plaintext after use.
2. Use SDK 0.18.4 for initialize and explicit, bounded `tools/list` pagination. Apply endpoint policy before outbound traffic, disable redirects, cap response size, duration, pages and tool count. Return distinct safe diagnostics for authorization, permission, protocol and upstream failures.
3. Persist the discovered tool summaries and schema fingerprints. A new or changed fingerprint is never approved automatically. Add an explicit approval mutation limited to the current inventory; keep project policy separate.
4. Expose Test connection, inventory and approval through the existing Agent management guard and typed Nexus proxy. The adapter remains map → execute → map; no direct Console → Agent path.
5. Verify with synthetic local MCP fixtures and negative security tests, then focused and full Agent/Nexus builds. Record unverified network or deployment properties explicitly.

The accepted no-DNS-pinning SDK choice weakens the roadmap's strict DNS-rebinding guarantee. Do not claim that guarantee without a transport-level check; keep this as an explicit acceptance gap until the narrow transport adapter has proven it.
