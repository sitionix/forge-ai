# Knowledge Architecture

Operator UI calls the Forge AI backend. Forge AI proxies through `KnowledgeGateway` to the local Knowledge service. The Python service reads the configured service catalog, normalizes source metadata, builds a local SQLite inventory, serves keyword/path search, and builds retrieval context bundles.

Knowledge is generic infrastructure. It does not depend on tickets, lanes, agents, Jarvis internals, or Mongo ticket/lane collections.

The inventory stores metadata only: source, relative path, extension, size, hash, and modified time. File contents are read on demand for keyword snippets and retrieval context items.

`/api/v1/knowledge/context` is owned by the Python Knowledge service. It coordinates inventory filtering, keyword/path matching, snippet extraction, ranking, deduplication, and character budgeting. Forge AI Java does not scan files or assemble snippets; it only validates the request shape and proxies the contract.
