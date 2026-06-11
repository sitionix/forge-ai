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

## APIs

- `POST /api/v1/knowledge/search` returns keyword/path matches.
- `POST /api/v1/knowledge/context` returns line-bounded snippets with source metadata, scores, reasons, budget usage, and diagnostics.

Context retrieval reads only files already present in the inventory. It uses catalog-derived source metadata and indexed file paths; it does not scan arbitrary directories.
