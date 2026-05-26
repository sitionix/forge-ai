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
http://127.0.0.1:9099/fgaisox

## Delivery Verification (Mandatory)

Completion is considered submitted only after a real HTTP call to the callback endpoint succeeds.

Success criteria:

- HTTP status is `2xx`.
- Response body is present.
- Response body contains the same `ticketId` and `laneId` as in runtime input.

Rules:

- Do not claim "submitted successfully" without verifying the success criteria above.
- If request fails (connection error, timeout, non-2xx, or mismatched/invalid response body), report callback as failed.
- On failure, include exact transport evidence (HTTP status or curl error) in the agent output.
- Never fabricate callback success based on prepared payload only.
- Execute callback delivery outside sandbox using a host-network-enabled call path.
- Use callback curl wrapper for every callback attempt.

## Callback Wrapper Resolution (Mandatory)

Before first callback attempt, resolve wrapper path in this order:

1. `/forge-ai/scripts/forge-callback-curl.sh`
2. `forge-ai/scripts/forge-callback-curl.sh` from current workspace

Rules:

- Use the first existing executable path from the list above.
- If none exists, stop callback attempts and report explicit transport blocker:
  - `callback_wrapper_not_found`
  - include all checked paths in the error details.
- Do not run retries when wrapper is missing; this is a preflight failure, not a transient HTTP failure.

## Retry Scheme (Mandatory)

If callback delivery fails, retry using this exact scheme.

Attempt plan:

- max attempts: `5` total
- backoff between attempts:
  - after attempt 1 fails: wait `1s`
  - after attempt 2 fails: wait `2s`
  - after attempt 3 fails: wait `4s`
  - after attempt 4 fails: wait `8s`

Retryable failures:

- transport/network errors (for example `curl: (7)`, timeout, DNS, connection reset)
- HTTP `5xx`
- HTTP `429`
- invalid success response shape (missing body, or mismatched `ticketId` / `laneId`)

Non-retryable failures:

- HTTP `4xx` except `429` (treat as final failure)
- transport permission errors indicating sandbox/network policy block (for example `Operation not permitted`)

Rules:

- Stop retries immediately on first valid success (2xx + valid body + matching `ticketId` and `laneId`).
- Do not retry sandbox policy failures (`Operation not permitted`); switch to unsandboxed callback execution immediately.
- For each failed attempt, record exact evidence (HTTP status or curl error).
- If all attempts fail, report callback as failed and include all attempt evidences in order.
- For each attempt, use verbose curl transport logging:
  - `bash <resolved-wrapper-path> -v ...`
