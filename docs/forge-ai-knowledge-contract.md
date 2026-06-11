# Forge AI Knowledge Contract

Forge AI exposes proxy endpoints under `/api/v1/infrastructure/knowledge/*` and does not scan files directly.

Endpoints:

- `GET /status`
- `GET /sources`
- `POST /inventory/build`
- `GET /inventory/status`
- `GET /inventory/files`
- `POST /search`

Controlled proxy failures are mapped to `KNOWLEDGE_UNAVAILABLE`, `KNOWLEDGE_TIMEOUT`, and `KNOWLEDGE_BAD_RESPONSE`.
