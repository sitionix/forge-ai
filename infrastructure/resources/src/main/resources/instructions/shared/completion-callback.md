# Completion Callback Rules

## Goal

When your agent work is complete, submit the result to Forge AI through the completion API contract provided in the runtime context.

This completion callback is mandatory.

## Runtime Completion Contract

The runtime prompt provides a completion contract block with:

- `baseUrl`
- `contractApi.path`
- `contractApi.endpoint`
- `ticketId`
- `laneId`
- `scope`

Use these values as runtime source of truth.

## Contract Source

Use only the provided OpenAPI contract reference as the source of truth for the completion request.

The contract lookup flow is:

1. Open/read the file from `contractApi.path`.
2. Locate the endpoint reference from `contractApi.endpoint`.
3. Use that endpoint definition to determine:
    - HTTP method,
    - endpoint path,
    - path parameters,
    - request body,
    - required fields,
    - field names,
    - field types,
    - examples,
    - validation rules.
4. For each request field, read that field's `description` in the OpenAPI schema and use it as the semantic instruction for what content must be provided.
5. Populate fields with meaningful, task-specific content that satisfies the field description.
6. Fill each field with complete, specific, and semantically meaningful content that directly satisfies the field description and the assigned lane outcome.
7. Build the request body according to that endpoint contract.
8. Fill all required fields.
9. Use runtime values for `ticketId`, `laneId`, and `scope`.

Do not derive the completion request from any source other than the provided OpenAPI contract reference.

If `contractApi.path` cannot be read or `contractApi.endpoint` cannot be found, stop and report the missing contract reference.

## Base URL

Use this base URL when constructing the request:
http://localhost:9099/fgaisox
