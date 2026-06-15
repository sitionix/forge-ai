# Knowledge Roadmap

V1 provides catalog-driven sources, local inventory, file metadata, a transitional retrieval context endpoint for Jarvis chat, and AI-assisted structural analysis.

Not implemented in v1:

- embeddings
- vector DB
- semantic search
- RAG answer generation
- direct Jarvis action execution
- prompt augmentation execution

Future vector metadata should include `sourceId`, service label, group, tags, contract refs, relative path, content hash, and validated AI analysis identifiers when useful.

The current context API is transitional. It returns budgeted snippets from indexed files for local Jarvis chat without changing the source catalog boundary.

AI structural analysis is the current structural metadata contract. SQLite stores analysis jobs, file states, symbols, roles, relations, evidence, and diagnostics.

The next retrieval step should use validated AI roles, relations, evidence, and diagnostics while keeping strict validation, local-only execution, and source read-only behavior.
