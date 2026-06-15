# Knowledge Architecture

Operator UI calls the Forge AI backend. Forge AI proxies through `KnowledgeGateway` to the local Knowledge service. The Python service reads the configured service catalog, normalizes source metadata, builds a local SQLite inventory, exposes transitional retrieval context bundles, and runs AI-assisted structural analysis.

Knowledge is generic infrastructure. It does not depend on tickets, lanes, agents, Jarvis internals, or Mongo ticket/lane collections.

The inventory stores metadata only: source, relative path, extension, size, hash, and modified time. File contents are read on demand for keyword snippets and retrieval context items. Source files are never mutated.

Each inventory build also stores skipped summary metadata in SQLite as JSON, not a per-file skipped audit. `skippedCount` is the number of candidates ignored by the build, and `skippedBreakdown` explains why. Reasons are `EXCLUDED_BY_PATTERN`, `NOT_INCLUDED`, `TOO_LARGE`, `BINARY`, `UNREADABLE`, `UNSAFE_PATH`, `SYMLINK_OUTSIDE_ROOT`, `MISSING_SOURCE_ROOT`, and `UNKNOWN`.

Indexed files are searchable inventory entries. Skipped files or paths were observed but rejected by include/exclude rules, size limits, binary detection, IO/safety checks, or missing configured roots. Missing source roots are tracked separately in the breakdown because they describe absent local roots rather than indexed files. These skipped cases are not errors by default; they are visibility into normal inventory filtering.

`/api/v1/knowledge/context` is owned by the Python Knowledge service. It coordinates inventory filtering, keyword/path matching, snippet extraction, ranking, deduplication, and character budgeting. Forge AI Java does not scan files or assemble snippets; it only validates the request shape and proxies the contract.

AI-assisted structural analysis is a separate layer after inventory. It sends one indexed file at a time to local Ollama, receives strict JSON, validates allowed symbol kinds, roles, relations, confidence, evidence, and line ranges, then stores the result in `analysis_jobs`, `analysis_files`, `analysis_symbols`, `analysis_symbol_roles`, and `analysis_relations`. It is asynchronous, service-by-service, content-hash incremental, local-only, and source read-only.

Naming conventions are weak evidence only. Production code must not classify a role solely because a class or file name ends with a conventional suffix. Role classification comes from validated AI output with evidence and confidence, or `UNKNOWN`.

The context API is kept as a transitional retrieval contract. It ranks inventory snippets by query/source/path signals, returns budgeted context items and diagnostics, and does not call Jarvis or generate a model answer.

No production Knowledge extractor or retrieval code may hardcode business domains, service names, or project-specific query expansions. Domain words belong in service catalogs, indexed source files, local config, docs, and tests.
