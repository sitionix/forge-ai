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

Controlled proxy failures are mapped to `KNOWLEDGE_UNAVAILABLE`, `KNOWLEDGE_TIMEOUT`, and `KNOWLEDGE_BAD_RESPONSE`. Blank context queries are rejected as `CONTEXT_QUERY_INVALID`.
