## API Decision Rules

Always complete the API request object according to the provided OpenAPI completion contract.

Use `required: true` when this scope needs API work to deliver its assigned scope task.

API work includes:

- public/client-facing API contracts;
- Workspace-facing/BFF API contracts;
- BFF-facing backend service contracts;
- internal service-to-service REST contracts;
- generated client/server API-first contracts;
- request body changes;
- response body changes;
- path/query/header parameter changes;
- schema changes;
- client-visible error contract changes;
- synchronous API integration work required by this scope.

For this lane model, make the decision strictly for the assigned scope payload.

If this scope must call another scope synchronously through HTTP/API, mark API work as required.

If another selected scope must call this scope synchronously through HTTP/API, mark API work as required.

Endpoint ownership is not a condition for `required: true`.

Use `required: false` only when this scope has no API contract impact and no synchronous API dependency work.

When `required: false`:

- provide a clear reason;
- provide a concise summary;
- keep `operations` present as an empty array;
- keep `consumers` present as an empty array;
- keep `notes` present as an empty array unless a short clarification is useful;
- do not invent operations;
- do not create placeholder operations;
- do not omit the API request object.

Expected `required: false` shape:

```json
{
  "required": false,
  "reason": "No API contract work is required for this scope.",
  "scope": "GLOBAL",
  "summary": "No API contract changes are needed.",
  "operations": [],
  "consumers": [],
  "notes": []
}
```
