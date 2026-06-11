# Knowledge Infrastructure Module

Knowledge is a generic local infrastructure module under `infrastructure/knowledge`. It reads the Forge AI service catalog as source of truth, builds a local SQLite inventory, and exposes keyword/path search.

The local config `infrastructure/knowledge/config/knowledge-sources.yaml` is gitignored and contains machine-specific paths only. It points to the service catalog and workspace root, not to a duplicated list of services.

V1 intentionally excludes embeddings, vector DB, semantic search, RAG, Jarvis integration, and prompt augmentation.
