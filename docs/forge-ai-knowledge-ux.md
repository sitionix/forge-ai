# Forge AI Knowledge UX

The Operator UI page is `boot/src/main/resources/static/operator/knowledge.html`.

The browser calls Forge AI backend endpoints only. It does not call the local Knowledge service port directly.

The page shows runtime status, catalog state, catalog-derived sources, inventory status, a build button, indexed files, keyword search results, and retrieval context results.

The Retrieval Context section calls `/fgaisox/api/v1/infrastructure/knowledge/context` through Forge AI only. It displays budget usage, sources used, diagnostics, context item path, line range, score, reason, and snippet content. It does not implement chat, RAG answer generation, Jarvis integration, or Ollama calls.
