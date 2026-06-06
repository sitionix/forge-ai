# Scope Context Usage

Use the rendered `Scope context` JSON to understand the assigned service boundary.

Use:

- `scope` for the assigned lane scope;
- `service.label` for assigned service identity;
- `service.group` for backend/frontend/tool category;
- `service.tags` for technical/runtime characteristics;
- `service.domainKeywords` for supporting domain hints;
- `service.ownBusinessAreas` for ownership;
- `service.contractRefs` for API/event/generated artifact source locations;
- `service.architectureRefs` only when architecture context is needed;
- `relatedServices` only for global lanes that coordinate contract or cross-service work.

Rules:

- `service.tags` are not requirements.
- `service.domainKeywords` are not requirements.
- Ownership comes from `service.ownBusinessAreas` first.
- For global lanes, use `relatedServices` to resolve concrete service-specific contract surfaces; do not treat `GLOBAL` as a service code.
- If ownership is unclear, use task wording, analyzer input, service tags, and service domain keywords only as supporting context.
- Non-owned behavior becomes dependency, constraint, risk, or is omitted when unrelated.
