# Forge AI Knowledge AI Analysis

Knowledge now has an AI-assisted structural analysis layer for local indexed source files.

Naming conventions alone are not enough. A class name suffix can be misleading, absent, or different across languages/frameworks. Knowledge therefore treats names, paths, imports, annotations, methods, and calls as evidence, not graph truth.

The analysis pipeline is:

```text
inventory -> AI file analysis -> strict validation -> SQLite analysis tables -> Forge proxy/UI preview
```

Each changed indexed file is processed through the YAML-selected extractor and, when policy requires it, sent to local Ollama with file metadata, content, static anchors, and the resolved analysis policy contract. Oversized files are marked `SKIPPED_TOO_LARGE_FOR_AI_ANALYSIS`. The model must return strict JSON only. Unknown fields, unsupported node kinds, edge types, claim kinds, resolution status values, missing evidence, invalid confidence, invalid JSON, and out-of-range line numbers are rejected.

Stored analysis includes:

- file state and diagnostics;
- graph nodes with policy-allowed `nodeKind`;
- graph edges with policy-allowed `edgeType` and first-class `resolutionStatus`;
- evidence-backed graph claims;
- background job progress.

SQLite is a local rebuildable runtime cache. It stores analysis jobs, file states, graph nodes, graph edges, graph claims, graph evidence, evidence join rows, diagnostics, and semantic index rows. It is not the source catalog and is not committed.

The analyzer is local-only. Ollama base URLs must point to localhost. Knowledge never mutates source files, never executes indexed code, and does not use AI as a source of catalog truth.

There is no business hardcode. Production code must not contain project-specific domain synonyms or service-specific behavior. Naming suffixes may be weak evidence in prompts/results, but they are not graph classification logic.

Future retrieval should build on validated graph nodes, edges, claims, evidence, and semantic index rows.
