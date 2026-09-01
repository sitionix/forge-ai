# Knowledge Infrastructure

Knowledge is a generic Forge AI infrastructure module for local source discovery, deterministic inventory, and AI-assisted semantic analysis. Its source of truth is the Forge AI service catalog YAML configured by local runtime config.

V1 does not implement embeddings, a vector database, semantic search, or RAG answer generation. Ollama is used only by the local AI structural analysis layer.

Knowledge also includes an optional AI-assisted graph analysis layer. It analyzes indexed files with policy-selected static extractors and local Ollama enrichment, validates strict graph JSON, and stores nodes, edges, claims, evidence, diagnostics, and evidence join rows in SQLite. AI analysis is local-only and does not mutate source files.

## Local Setup

```bash
scripts/knowledge/bootstrap.sh
scripts/knowledge/init-local-config.sh
scripts/knowledge/validate-config.sh
just start
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
- `GET /api/v1/knowledge/analysis/graph/manifest` returns the stored current graph manifest.
- `GET /api/v1/knowledge/analysis/graph/nodes` returns a bounded snapshot-bound node page.
- `GET /api/v1/knowledge/analysis/graph/edges` returns a bounded snapshot-bound edge page.
- `GET /api/v1/knowledge/analysis/graph/node/{nodeId}` returns bounded node details and optional evidence.
- `GET /api/v1/knowledge/analysis/graph/edge/{edgeId}` returns bounded edge details and optional evidence.

Context retrieval is a transitional internal Jarvis chat dependency. New user-facing semantic UI and future Q&A flows should consume AI analysis results rather than treating inventory as a semantic knowledge system.

AI graph analysis is a separate evidence layer. Naming conventions may appear in evidence, but suffixes are not graph truth. The runtime validates policy-allowed node kinds, edge types, claim kinds, resolution status, evidence, and line ranges. Invalid JSON, unsupported graph fields, missing evidence, and invalid line ranges are rejected.
