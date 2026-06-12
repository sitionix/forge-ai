# Knowledge Infrastructure Module

Knowledge is a generic local infrastructure module under `infrastructure/knowledge`. It reads the Forge AI service catalog as source of truth, builds a local SQLite inventory, exposes keyword/path search, and builds retrieval context bundles.

The local config `infrastructure/knowledge/config/knowledge-sources.yaml` is gitignored and contains machine-specific paths only. It points to the service catalog and workspace root, not to a duplicated list of services.

V1 intentionally excludes embeddings, vector DB, semantic search, RAG answer generation, Jarvis integration, Ollama calls, and prompt augmentation execution.

Inventory builds write local SQLite metadata only. Indexed files are files accepted by source, include/exclude, size, text, and safety rules. Skipped files or paths are candidates seen during a build but not indexed. `skippedCount` is the total skipped candidate count, and `skippedBreakdown` stores the local runtime summary by reason as JSON.

Skipped reasons are `EXCLUDED_BY_PATTERN`, `NOT_INCLUDED`, `TOO_LARGE`, `BINARY`, `UNREADABLE`, `UNSAFE_PATH`, `SYMLINK_OUTSIDE_ROOT`, `MISSING_SOURCE_ROOT`, and `UNKNOWN`. Missing source roots mean configured/catalog roots absent on the local machine. Skipped items are normal inventory filtering unless diagnostics or logs indicate a real configuration problem.

Knowledge never mutates source files and does not store the full skipped file list.

The retrieval context endpoint returns structured snippets from indexed files only. It is the contract Jarvis can consume later, but Knowledge does not generate answers.
