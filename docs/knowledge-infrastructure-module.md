# Knowledge Infrastructure Module

Knowledge is a generic local infrastructure module under `infrastructure/knowledge`. It reads the Forge AI service catalog as source of truth, builds a local SQLite inventory, exposes keyword/path search, and builds retrieval context bundles.

The local config `infrastructure/knowledge/config/knowledge-sources.yaml` is gitignored and contains machine-specific paths only. It points to the service catalog and workspace root, not to a duplicated list of services.

V1 intentionally excludes embeddings, vector DB, semantic search, RAG answer generation, Jarvis integration, Ollama calls, and prompt augmentation execution.

The retrieval context endpoint returns structured snippets from indexed files only. It is the contract Jarvis can consume later, but Knowledge does not generate answers.
