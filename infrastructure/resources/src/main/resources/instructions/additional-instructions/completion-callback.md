# Completion Callback Rules

## Goal

When lane work is complete, submit completion to Forge AI.
Completion callback is mandatory for lane completion.

## Runtime Completion Contract

Use runtime values from:

- `baseUrl`;
- `contractApi.path`;
- `contractApi.endpoint`;
- `ticketId`;
- `laneId`;
- `scope`.

These runtime values are the source of truth.

## Contract Source

Build the completion request from the provided OpenAPI contract reference.

Flow:

1. open/read the file from `contractApi.path`;
2. locate the endpoint reference from `contractApi.endpoint`;
3. extract method, path, path params, request body schema, required fields, field names, types, examples, and validation;
4. for each request field, read the field `description` in the OpenAPI schema and use it as semantic instruction;
5. fill all required fields with task-specific lane outcome facts;
6. use runtime values for `ticketId`, `laneId`, and `scope`;
7. if the endpoint has no request body, send no custom body.

Do not derive completion payload shape from memory, examples, old lane templates, or previous requests.

## Base URL

Use runtime `baseUrl` when present.

If runtime `baseUrl` is missing, use:

`http://127.0.0.1:9099/fgaisox`

## Delivery Verification

Completion is submitted only after a real HTTP call to the callback endpoint succeeds.

Success criteria:

- HTTP status is `2xx`;
- response body is present;
- response body contains the same `ticketId` and `laneId` as runtime input.

Do not claim callback success without verified HTTP success.

## Callback Wrapper Resolution

Before first callback attempt, resolve wrapper path in this order:

1. `/forge-ai/scripts/forge-callback-curl.sh`
2. `forge-ai/scripts/forge-callback-curl.sh` from current workspace

Use the first existing executable path.

If none exists, report:

`callback_wrapper_not_found`

Include all checked paths.

## Retry Scheme

If callback delivery fails, retry using this scheme.

Max attempts: `5`.

Backoff:

- after attempt 1: `1s`;
- after attempt 2: `2s`;
- after attempt 3: `4s`;
- after attempt 4: `8s`.

Retryable failures:

- transport/network errors;
- HTTP `5xx`;
- HTTP `429`;
- missing response body;
- response body with mismatched `ticketId` or `laneId`.

Non-retryable failures:

- HTTP `4xx` except `429`;
- sandbox/network policy errors such as `Operation not permitted`.

For each failed attempt, keep exact evidence:

- HTTP status; or
- curl error; or
- response mismatch.

Use verbose curl transport logging:

`bash <resolved-wrapper-path> -v ...`