# Knowledge Infrastructure Module

Knowledge is a generic local infrastructure module under `infrastructure/knowledge`. It reads the Forge AI service catalog as source of truth, builds a local SQLite inventory, exposes a transitional snippet context endpoint for Jarvis, and runs AI structural analysis over inventory files.

The local config `infrastructure/knowledge/config/knowledge-sources.yaml` is gitignored and contains machine-specific paths only. It points to the service catalog and workspace root, not to a duplicated list of services.

V1 intentionally excludes embeddings, vector DB, semantic search, RAG answer generation, Jarvis integration, and prompt augmentation execution. Ollama is used only by the optional local AI structural analysis layer.

The AI graph analysis layer is optional local analysis, not answer generation. It uses the YAML analysis policy as the graph contract, calls local Ollama for policy-selected enrichment, validates strict JSON, and stores graph nodes, edges, claims, evidence, diagnostics, and evidence join rows as rebuildable SQLite facts.

Inventory builds write local SQLite metadata only. Indexed files are files accepted by source, include/exclude, size, text, and safety rules. Skipped files or paths are candidates seen during a build but not indexed. `skippedCount` is the total skipped candidate count, and `skippedBreakdown` stores the local runtime summary by reason as JSON.

Skipped reasons are `EXCLUDED_BY_PATTERN`, `NOT_INCLUDED`, `TOO_LARGE`, `BINARY`, `UNREADABLE`, `UNSAFE_PATH`, `SYMLINK_OUTSIDE_ROOT`, `MISSING_SOURCE_ROOT`, and `UNKNOWN`. Missing source roots mean configured/catalog roots absent on the local machine. Skipped items are normal inventory filtering unless diagnostics or logs indicate a real configuration problem.

Knowledge never mutates source files and does not store the full skipped file list.

The retrieval context endpoint returns structured snippets from indexed files only. It is a transitional internal Jarvis dependency and is not exposed through the Forge Knowledge UI or Java proxy.

AI analysis uses separate tables for jobs, file state, graph nodes, graph edges, graph claims, graph evidence, graph diagnostics, and evidence joins. It runs in an in-process background job, reports current source/file progress, skips unchanged files by content hash and analyzer version, and marks oversized or failed files without crashing the service.

Production Knowledge code must stay generic. It must not hardcode business domains, service IDs, local paths, or project-specific query synonyms. Analysis uses catalog metadata, file paths, class/method names, annotations, imports, calls, config keys, and contract operations as evidence.

Production code must also not treat naming suffixes as graph truth. Suffixes can be evidence in AI prompts/results, but graph facts require validated output with policy-allowed fields, confidence, and evidence.
