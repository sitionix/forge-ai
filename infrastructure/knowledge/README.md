# Knowledge Infrastructure

Knowledge is a generic Forge AI infrastructure module for local source discovery, inventory, keyword search, and retrieval context bundles. Its source of truth is the Forge AI service catalog YAML configured by local runtime config.

V1 does not implement embeddings, a vector database, semantic search, RAG answer generation, Ollama calls, or Jarvis integration.

## Local Setup

```bash
scripts/knowledge/bootstrap.sh
scripts/knowledge/init-local-config.sh
scripts/knowledge/validate-config.sh
scripts/knowledge/start.sh
```

The generated `config/knowledge-sources.yaml` is local and gitignored. It points to the service catalog and workspace root; it must not duplicate the service list.

## Runtime Data

Inventory metadata is stored in `infrastructure/knowledge/var/knowledge.sqlite`. Knowledge never mutates source files.

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

- `POST /api/v1/knowledge/search` returns keyword/path matches.
- `POST /api/v1/knowledge/context` returns line-bounded snippets with source metadata, scores, reasons, budget usage, and diagnostics.

Context retrieval reads only files already present in the inventory. It uses catalog-derived source metadata and indexed file paths; it does not scan arbitrary directories.

Context ranking is keyword based. It prioritizes service catalog metadata, source IDs, path and file-name matches, then content matches. For general explanation and flow queries, runtime source files rank above tests. Test files are still preferred for test-specific queries, workflow/action files are preferred for workflow/deploy/CI queries, and OpenAPI/API contract files are boosted when the query asks for APIs, endpoints, contracts, schemas, or OpenAPI.
