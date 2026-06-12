# Forge AI Knowledge Retrieval Contract

## Purpose

Search answers “which indexed files match this query?” Context retrieval answers “which line-bounded snippets should be passed later to a model?” It is deterministic keyword/path retrieval over the existing inventory, not semantic search.

## Local Endpoint

`POST /api/v1/knowledge/context`

Request:

```json
{
  "query": "поясни як працює Jarvis",
  "sourceIds": [],
  "groups": [],
  "maxChars": 12000,
  "maxItems": 12,
  "includeContent": true
}
```

`query` is required and non-blank. Empty `sourceIds` and `groups` mean no explicit filter. `maxChars` is clamped by schema to `1000..50000`; `maxItems` is `1..50`.

## Response

The response contains:

- `context`: ordered snippets from indexed files.
- `sourcesUsed`: source ids and labels represented in the bundle.
- `budget`: requested max chars, used chars, and truncation flag.
- `diagnostics`: controlled non-stacktrace messages such as `INVENTORY_EMPTY`.

A context item includes source id, display name, group, relative path, line range, optional content, match type, reason, score, and source metadata (`tags`, `domainKeywords`, `ownsBusinessAreas`).

## Retrieval Algorithm

Knowledge filters inventory rows by `sourceIds` and `groups`, reads only indexed files, matches query terms against path, filename, content, and catalog-derived source metadata, extracts line-bounded snippets, deduplicates overlapping snippets from the same file, ranks deterministically, and enforces `maxItems` and `maxChars`.

Content matches include the matched line with default surrounding context of 8 lines before and 16 lines after. Path and metadata matches use the first meaningful section: heading section for Markdown where possible, declaration area for code where possible, otherwise the top of file.

## Boundaries

There is no vector DB, embeddings, semantic search, RAG answer generation, Ollama call, or Jarvis integration in this contract. Jarvis can later call the Forge proxy and use the returned bundle as prompt augmentation input.

Forge AI Java exposes `POST /api/v1/infrastructure/knowledge/context` and only proxies the request to Knowledge. Java must not scan files or assemble snippets because source discovery, inventory safety, ranking, and snippet extraction belong to the Knowledge infrastructure module.
