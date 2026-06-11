# Knowledge Infrastructure

Knowledge is a generic Forge AI infrastructure module for local source discovery, inventory, and keyword search. Its source of truth is the Forge AI service catalog YAML configured by local runtime config.

V1 does not implement embeddings, a vector database, semantic search, RAG, prompt augmentation, or Jarvis integration.

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
