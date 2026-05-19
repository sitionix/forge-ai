# Completion Callback Rules

## Goal

When lane work is complete, submit completion to Forge AI.
This callback is mandatory.

## Runtime Completion Contract

Use runtime values from:

- `baseUrl`
- `contractApi.path`
- `contractApi.endpoint`
- `ticketId`
- `laneId`
- `scope`

These are the runtime source of truth.

## Contract Source

Build the completion request only from the provided OpenAPI contract reference.

Flow:

1. Open/read the file from `contractApi.path`.
2. Locate the endpoint reference from `contractApi.endpoint`.
3. Extract from that endpoint: method, path, path params, request body schema, required fields, names, types, examples, validation.
4. For each request field, read that field's `description` in the OpenAPI schema and use it as the semantic instruction for what content must be provided.
5. Populate fields with meaningful, task-specific content that satisfies the field descriptions and lane outcome.
6. Fill all required fields.
7. Use runtime values for `ticketId`, `laneId`, and `scope`.

Do not derive completion payload from any source other than this contract reference.

If `contractApi.path` cannot be read or `contractApi.endpoint` cannot be found, stop and report the missing contract reference.

## Base URL

Use this base URL when constructing the request:
http://localhost:9099/fgaisox
