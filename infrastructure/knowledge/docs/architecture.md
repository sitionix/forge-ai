# Knowledge Architecture

Operator UI calls the Forge AI backend. Forge AI proxies through `KnowledgeGateway` to the local Knowledge service. The Python service reads the configured service catalog, normalizes source metadata, builds a local SQLite inventory, and serves keyword/path search.

Knowledge is generic infrastructure. It does not depend on tickets, lanes, agents, Jarvis internals, or Mongo ticket/lane collections.

The inventory stores metadata only: source, relative path, extension, size, hash, and modified time. File contents are read on demand for keyword snippets.
