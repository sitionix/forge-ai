# Forge AI Knowledge UX

The Operator UI page is `boot/src/main/resources/static/operator/knowledge.html`.

The browser calls Forge AI backend endpoints only. It does not call the local Knowledge service port directly.

The page shows runtime status, catalog state, catalog-derived sources, inventory status, a build button, indexed files, keyword search results, and retrieval context results.

The Inventory card shows indexed file count, skipped count, and a compact skipped breakdown. Zero-count reasons are hidden. If the backend response does not contain breakdown metadata, the page asks the operator to rebuild inventory.

Skipped means files or paths were seen during inventory build but were not indexed because they matched exclude rules, did not match include rules, were too large, binary, unsafe, unreadable, symlinked outside the source root, or came from missing configured roots. Skipped items are not already processed files and are not errors by default.

The Retrieval Context section calls `/fgaisox/api/v1/infrastructure/knowledge/context` through Forge AI only. It displays budget usage, sources used, diagnostics, context item path, line range, score, reason, and snippet content. It does not implement chat, RAG answer generation, Jarvis integration, or Ollama calls.
