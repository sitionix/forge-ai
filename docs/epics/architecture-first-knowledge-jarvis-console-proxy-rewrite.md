# Architecture-First Knowledge, Jarvis, Console and Proxy Rewrite

Date: 2026-06-21

This epic describes the live target architecture for the current Knowledge, Jarvis, Operator Console and Nexus/Forge infrastructure proxy surface. It is one end-to-end PR scope; the numbered waves are verification domains, not staged deliverables.

## Component Ownership

- Knowledge owns inventory, context indexing, analysis jobs, graph snapshots, source overview projection, SQLite schema migration, retention and Knowledge API contracts.
- Jarvis owns Jarvis liveness/readiness, chat prompt construction, lifecycle-owned Knowledge/Ollama HTTP clients and allowlisted command execution.
- Nexus/Forge owns only the infrastructure forwarding boundary for Knowledge and Jarvis routes exposed to the Console.
- Operator Console owns browser request lifecycle, polling, stale-response handling and lazy graph/detail rendering.

Verification belongs in existing Python, Java and Console test suites plus normal runtime observability. The architecture does not include standalone Wave 0 scripts, benchmark wrappers, backup helpers, copied databases, generated reports or fixture products.

## Endpoint Inventory

Knowledge active endpoints:

- `GET /api/v1/knowledge/status`
- `GET /api/v1/knowledge/sources`
- `GET /api/v1/knowledge/overview`
- `POST /api/v1/knowledge/inventory/build`
- `GET /api/v1/knowledge/inventory/status`
- `GET /api/v1/knowledge/inventory/files`
- `POST /api/v1/knowledge/context`
- `POST /api/v1/knowledge/analysis/build`
- `POST /api/v1/knowledge/analysis/retry-failed`
- `GET /api/v1/knowledge/analysis/jobs/{job_id}`
- `POST /api/v1/knowledge/analysis/jobs/{job_id}/stop`
- `GET /api/v1/knowledge/analysis/status`
- `GET /api/v1/knowledge/analysis/files`
- `GET /api/v1/knowledge/analysis/diagnostics`
- `GET /api/v1/knowledge/analysis/graph/manifest`
- `GET /api/v1/knowledge/analysis/graph/nodes`
- `GET /api/v1/knowledge/analysis/graph/edges`
- `GET /api/v1/knowledge/analysis/graph/node/{node_id}`
- `GET /api/v1/knowledge/analysis/graph/edge/{edge_id}`

Jarvis active endpoints:

- `GET /api/v1/jarvis/status`
- `GET /api/v1/jarvis/actions`
- `POST /api/v1/jarvis/command`
- `POST /api/v1/jarvis/chat`

Nexus/Forge exposes the corresponding `/api/v1/infrastructure/knowledge/**` and `/api/v1/infrastructure/jarvis/**` allowlisted routes for the Console. It forwards request bodies, query parameters, upstream statuses and approved response headers; it does not own Knowledge/Jarvis storage or business semantics.

## Storage Model

Knowledge SQLite stores current operational state in these owned models:

- `knowledge_source_overview`: one durable flat current row per source for Console overview KPIs.
- `sources`, `files`, `current_file_index`: current source and inventory state.
- `context_chunks`, `context_chunks_fts`: indexed context chunks with source/path/line/content-version metadata.
- `analysis_jobs`, `analysis_job_files`, `analysis_files`: durable job and current file-analysis state.
- `graph_snapshots`, `graph_current_snapshots`: immutable graph snapshot lifecycle and active pointer.
- `analysis_graph_nodes`, `analysis_graph_edges`, `analysis_graph_evidence`, `analysis_graph_claims`, `analysis_graph_diagnostics`: snapshot-owned graph rows with `(snapshot_id, id)` identity.

Foreign keys are enabled on application connections. WAL/busy-timeout behavior is configured through the normal SQLite connection path; retention is owned by Knowledge code, not shell scripts.

## Graph Publication

Analysis writes graph rows into a non-active `BUILDING` snapshot. Completion publishes the snapshot by one transaction that stores manifest metadata and advances `graph_current_snapshots`. Failed, cancelled or interrupted snapshots do not replace the active pointer. Readers bind to the active snapshot ID and page bounded node/edge summaries before loading selected details.

## Job State Machine

Analysis jobs use durable states:

```text
QUEUED -> RUNNING -> COMPLETED
                  -> FAILED
                  -> STOP_REQUESTED -> STOPPED
                  -> FAILED after restart interruption when not stop-requested
```

Stop requests persist `STOP_REQUESTED` before acknowledgement and do not set `completed_at` until finalization. Application shutdown marks non-terminal jobs deterministically and shuts down the lifespan-owned executor.

## Jarvis Runtime Model

Jarvis creates Knowledge and Ollama HTTP clients once per application lifespan and closes them on shutdown. Liveness is local. Readiness/dependency status is bounded. Chat may use full retrieved context internally, while browser responses return compact source/path/line/reason/score metadata only. Commands are allowlisted, executed off the event loop through a bounded executor, timed out, output-capped and terminated by process group on timeout.

## Console Request Ownership

Console Knowledge and Jarvis modules own one current request per resource, use abort/sequence protection, avoid duplicate initialization, pause or slow polling when hidden, and load graph manifest/page/detail lazily. Closing or navigating away disposes timers, listeners and in-flight detail requests.

## Deleted Architecture

The current target removes:

- Wave 0 baseline, backup, benchmark and generated-report artifacts.
- Nexus/Forge SQLite Knowledge adapter and mode selection.
- Per-request Jarvis dependency clients.
- Request-time filesystem context scanning.
- Read-time reconstruction of overview KPI payloads.
- In-place graph mutation as the active graph publication strategy.
- Daemon analysis worker ownership.
