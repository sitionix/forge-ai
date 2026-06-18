# Knowledge Infrastructure

Knowledge is a generic Forge AI infrastructure module for local source discovery, deterministic inventory, and AI-assisted semantic analysis. Its source of truth is the Forge AI service catalog YAML configured by local runtime config.

V1 does not implement embeddings, a vector database, semantic search, or RAG answer generation. Ollama is used only by the local AI structural analysis layer.

Knowledge also includes an optional AI-assisted structural analysis layer. It analyzes indexed files with local Ollama, validates strict JSON output, and stores roles, confidence, evidence, and relations in SQLite. AI analysis is local-only and does not mutate source files.

## Local Setup

```bash
scripts/knowledge/bootstrap.sh
scripts/knowledge/init-local-config.sh
scripts/knowledge/validate-config.sh
scripts/knowledge/start.sh
```

The root `config/knowledge/knowledge-sources.yaml` points to the service catalog and workspace root; it must not duplicate the service list.

## Runtime Data

Inventory metadata is stored in `var/knowledge/knowledge.sqlite` by default. Knowledge never mutates source files.

Inventory builds persist indexed file metadata and a skipped summary only. `fileCount` is the number of indexed files. `skippedCount` is the number of files or paths seen during the build but not indexed because inventory rules or safety checks rejected them. Skipped files are not errors by default.

Skipped reasons are:

- `EXCLUDED_BY_PATTERN`
- `NOT_INCLUDED`
- `TOO_LARGE`
- `BINARY`
- `UNREADABLE`
- `UNSAFE_PATH`
- `SYMLINK_OUTSIDE_ROOT`
- `MISSING_SOURCE_ROOT`
- `UNKNOWN`

Missing source roots are catalog/configured roots that do not exist locally. They are counted in the skipped breakdown as `MISSING_SOURCE_ROOT`; no source files are created or changed.

## APIs

- `POST /api/v1/knowledge/context` returns a transitional line-bounded context bundle for local Jarvis chat. It is not exposed through the Forge AI Knowledge UI or Java proxy.
- `POST /api/v1/knowledge/analysis/build` queues an AI structural analysis job.
- `GET /api/v1/knowledge/analysis/jobs/{jobId}` returns background job progress.
- `POST /api/v1/knowledge/analysis/jobs/{jobId}/stop` requests cooperative cancellation for a running analysis job.
- `GET /api/v1/knowledge/analysis/status` returns latest/active analysis state.
- `GET /api/v1/knowledge/analysis/files` previews analyzed file state.
- `GET /api/v1/knowledge/analysis/symbols` previews AI-classified symbols and roles.
- `GET /api/v1/knowledge/analysis/relations` previews AI-classified relations.

Context retrieval is a transitional internal Jarvis chat dependency. New user-facing semantic UI and future Q&A flows should consume AI analysis results rather than treating inventory as a semantic knowledge system.

AI structural analysis is a separate evidence layer. Naming conventions may appear in evidence, but suffixes are not role truth. The model must choose from generic roles and relations, provide evidence, and may return `UNKNOWN` when uncertain. Invalid JSON, unsupported enums, missing evidence, and invalid line ranges are rejected.
