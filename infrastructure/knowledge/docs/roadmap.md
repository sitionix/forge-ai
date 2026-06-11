# Knowledge Roadmap

V1 provides catalog-driven sources, local inventory, file metadata, keyword/path search, and retrieval context bundles.

Not implemented in v1:

- embeddings
- vector DB
- semantic search
- RAG answer generation
- Jarvis integration
- prompt augmentation execution

Future vector metadata should include `sourceId`, service label, group, tags, contract refs, relative path, and content hash.

The current context API is the pre-vector contract. It returns deterministic snippets from indexed files and can later feed Jarvis or a local model without changing the source catalog boundary.
