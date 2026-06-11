# Forge AI Knowledge UX

The Operator UI page is `boot/src/main/resources/static/operator/knowledge.html`.

The browser calls Forge AI backend endpoints only. It does not call the local Knowledge service port directly.

The page shows runtime status, catalog state, catalog-derived sources, inventory status, a build button, indexed files, and keyword search results. It does not implement chat, RAG answer generation, or Ollama calls.
