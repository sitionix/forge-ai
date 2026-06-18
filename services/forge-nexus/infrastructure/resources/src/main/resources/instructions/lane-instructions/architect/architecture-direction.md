# Architect Architecture Direction

## Architecture Context

Use `architecture/architecture-rules.md` as the mandatory architecture decision guide.

Read additional architecture files only when needed:

- read `architecture/system-architecture.md` when service interaction, end-to-end flow, or cross-service ownership is unclear;
- read `architecture/internal-service-architecture.md` when module/layer placement inside a backend service is unclear.

Use the smallest architecture context needed for the current scope decision.

## Direction

Produce scope-local architecture direction for the downstream implementer.

Include:

- owned requirements;
- implementation approach;
- module or layer placement;
- affected components when known;
- constraints;
- non-goals;
- risks;
- dependencies;
- API dependency status;
- event dependency status;
- acceptance-oriented implementation notes.

## Flow Understanding

For cross-boundary work, reconstruct the relevant flow before decomposition.

Identify:

- entrypoint;
- owning module or service;
- downstream dependencies;
- state transitions and persistence owner;
- synchronous versus asynchronous boundary;
- browser-facing versus internal-only boundary.

## Decomposition

Decompose work by ownership boundary when multiple boundaries are involved.
Separate these concerns when present:

- contract change;
- generated artifact update;
- write model change;
- read model change;
- event propagation;
- BFF exposure;
- frontend usage.

Use repository ownership and existing flow shape as the placement source of truth.