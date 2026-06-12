# Knowledge Architecture

Operator UI calls the Forge AI backend. Forge AI proxies through `KnowledgeGateway` to the local Knowledge service. The Python service reads the configured service catalog, normalizes source metadata, builds a local SQLite inventory, serves keyword/path search, and builds retrieval context bundles.

Knowledge is generic infrastructure. It does not depend on tickets, lanes, agents, Jarvis internals, or Mongo ticket/lane collections.

The inventory stores metadata only: source, relative path, extension, size, hash, and modified time. File contents are read on demand for keyword snippets and retrieval context items. Source files are never mutated.

Each inventory build also stores skipped summary metadata in SQLite as JSON, not a per-file skipped audit. `skippedCount` is the number of candidates ignored by the build, and `skippedBreakdown` explains why. Reasons are `EXCLUDED_BY_PATTERN`, `NOT_INCLUDED`, `TOO_LARGE`, `BINARY`, `UNREADABLE`, `UNSAFE_PATH`, `SYMLINK_OUTSIDE_ROOT`, `MISSING_SOURCE_ROOT`, and `UNKNOWN`.

Indexed files are searchable inventory entries. Skipped files or paths were observed but rejected by include/exclude rules, size limits, binary detection, IO/safety checks, or missing configured roots. Missing source roots are tracked separately in the breakdown because they describe absent local roots rather than indexed files. These skipped cases are not errors by default; they are visibility into normal inventory filtering.

`/api/v1/knowledge/context` is owned by the Python Knowledge service. It coordinates inventory filtering, keyword/path matching, snippet extraction, ranking, deduplication, and character budgeting. Forge AI Java does not scan files or assemble snippets; it only validates the request shape and proxies the contract.
