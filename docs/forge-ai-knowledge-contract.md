# Forge AI Knowledge Contract

Forge AI exposes proxy endpoints under `/api/v1/infrastructure/knowledge/*` and does not scan files directly.

Endpoints:

- `GET /status`
- `GET /sources`
- `POST /inventory/build`
- `GET /inventory/status`
- `GET /inventory/files`
- `POST /search`
- `POST /context`

`/context` forwards `KnowledgeContextRequest` to the Python Knowledge service and returns `KnowledgeContextView`. Forge AI Java does not read source files, build snippets, or duplicate catalog discovery.

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

Skipped means the inventory builder saw a file/path/root candidate but did not index it. Valid reasons are `EXCLUDED_BY_PATTERN`, `NOT_INCLUDED`, `TOO_LARGE`, `BINARY`, `UNREADABLE`, `UNSAFE_PATH`, `SYMLINK_OUTSIDE_ROOT`, `MISSING_SOURCE_ROOT`, and `UNKNOWN`. Skipped files are not errors by default; they are excluded from search/context because only indexed files participate in retrieval.

Controlled proxy failures are mapped to `KNOWLEDGE_UNAVAILABLE`, `KNOWLEDGE_TIMEOUT`, and `KNOWLEDGE_BAD_RESPONSE`. Blank context queries are rejected as `CONTEXT_QUERY_INVALID`.
