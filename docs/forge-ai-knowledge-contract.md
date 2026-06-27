# Forge AI Knowledge Contract

Forge AI exposes proxy endpoints under `/api/v1/infrastructure/knowledge/*` and does not scan files directly.

Endpoints:

- `GET /status`
- `GET /sources`
- `POST /inventory/build`
- `GET /inventory/status`
- `GET /inventory/files`
- `POST /analysis/build`
- `GET /analysis/jobs/{jobId}`
- `POST /analysis/jobs/{jobId}/stop`
- `GET /analysis/status`
- `GET /analysis/files`
- `GET /analysis/graph/manifest`
- `GET /analysis/graph/nodes`
- `GET /analysis/graph/edges`
- `GET /analysis/graph/node/{nodeId}`
- `GET /analysis/graph/edge/{edgeId}`

Forge AI Java no longer exposes public inventory-backed search, retrieval context, facts, or flow context proxy endpoints. Browser Knowledge flows use status, source, inventory diagnostics, and AI analysis endpoints only.

Inventory build, inventory status, and status responses keep the backward-compatible `skippedCount` field and also expose `skippedBreakdown`:

```json
{
  "skippedCount": 10221,
  "skippedBreakdown": {
    "total": 10221,
    "byReason": {
      "EXCLUDED_BY_PATTERN": 9800,
      "NOT_INCLUDED": 210
    }
  }
}
```

Forge AI Java only proxies this shape. It does not compute skipped reasons, inspect local files, or scan source roots. If an older Knowledge response omits `skippedBreakdown`, Forge maps it safely to `{ "total": skippedCount, "byReason": {} }`.

Skipped means the inventory builder saw a file/path/root candidate but did not index it. Valid reasons are `EXCLUDED_BY_PATTERN`, `NOT_INCLUDED`, `TOO_LARGE`, `BINARY`, `UNREADABLE`, `UNSAFE_PATH`, `SYMLINK_OUTSIDE_ROOT`, `MISSING_SOURCE_ROOT`, and `UNKNOWN`. Skipped files are not errors by default and are excluded from AI analysis candidates.

Controlled proxy failures are mapped to `KNOWLEDGE_UNAVAILABLE`, `KNOWLEDGE_TIMEOUT`, and `KNOWLEDGE_BAD_RESPONSE`.

Analysis endpoints are proxy-only in Forge Java. `POST /analysis/build` returns a queued job id immediately. `POST /analysis/jobs/{jobId}/stop` requests cooperative cancellation and frees the active slot for a new analysis job; any in-flight per-file Ollama call is prevented from writing results after it returns or times out. Job/status endpoints expose progress counters, current source/file, failures, and diagnostics. Graph manifest, page, and detail endpoints expose bounded Knowledge service JSON; Forge does not call Ollama, scan files, parse source, or classify roles.
