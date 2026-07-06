# Stage 5 - DB / Persistence / API/UI Contract Audit for YAML-first Knowledge Graph

Date: 2026-07-03

Scope: audit and report only. No production code, tests, database schema, or migrations were changed.

## 12.1 Executive Verdict

Is the current DB schema clean enough for shadow-run? **NO.**

Runtime graph writing is mostly YAML-first, but persistence/API/UI still carries legacy and dirty contract surface:

- Legacy `symbol_count` / `relation_count` columns and `symbolCount` / `relationCount` API fields remain.
- Graph APIs still expose compatibility aliases: `graphNodeId`, `graphEdgeId`, `kind`, `relation`, `from`, `to`, `classification`, `resolved`.
- Active graph fact metadata persists parser/debug/scoring/raw-text keys.
- Evidence and claim relationships still use JSON arrays instead of enforced FKs.
- Several graph tables have only partial FK coverage.
- Semantic graph revision hashing includes `metadata_json`, `edge_kind`, and JSON evidence relationships, so debug metadata churn can change graph identity.
- Console still renders or preserves "symbols/relations" language in several places.

`sqlite3` CLI is not installed in this environment, so the live DB audit was done with Python `sqlite3` against `var/knowledge/knowledge.sqlite`.

Required grep summary:

- Legacy counter/API terms found in Knowledge, Console, Nexus tests: `symbol_count`, `relation_count`, `symbolCount`, `relationCount`, `symbols`, `relations`.
- Old status/config terms are mostly negative-guard tests only: `LOW_CONFIDENCE`, `DEBUG_ONLY`, `analysisMode`, `genericConfigEnrichment`.
- Metadata readers/writers are broad: `sourceKind`, `stableKey`, `parser`, `extractorId`, `engineVersion`, `flowDomain`, `factOrigin`, call classification fields, raw call text, resolver fields.
- Console still consumes graph aliases and metadata fields for URL state, filtering, styling, and tooltips.
- Nexus is a transparent proxy; stale contract risk is in route fixtures/tests, not DTO rewriting.

Live DB row counts:

| Table | Rows |
|---|---:|
| analysis_jobs | 5 |
| analysis_files | 142 |
| analysis_job_files | 438 |
| analysis_graph_state | 2 |
| analysis_graph_nodes | 1278 |
| analysis_graph_edges | 3892 |
| analysis_graph_claims | 276 |
| analysis_graph_evidence | 4187 |
| analysis_graph_diagnostics | 302 |
| semantic_documents | 1166 |
| semantic_vectors | 1166 |
| semantic_index_state | 2 |
| knowledge_source_overview | 10 |
| analysis_schema_migrations | 7 |

No live legacy tables were present for `symbols`, `edges`, `analysis_symbols`, `analysis_relations`, `analysis_symbol_roles`, `fact_builds`, `file_extraction_state`, or `symbol_tokens`.

## 12.2 Table Deletion Candidates

| Table | Current purpose | Writers | Readers | UI/API exposure | Verdict | Reason | Required follow-up |
|---|---|---|---|---|---|---|---|
| symbols / edges / symbol_tokens / fact_builds / file_extraction_state | Legacy graph storage | None active; cleanup paths only | Reset/migration cleanup checks | None | DELETE_LEGACY cleanup paths | Not present live; only retained for historical cleanup | Remove old cleanup branches after DB reset strategy is approved |
| analysis_symbols / analysis_relations / analysis_symbol_roles | Legacy analysis projection | None active | Negative tests only | Old endpoints tested as 404 | DELETE_LEGACY cleanup paths | Old symbol/relation model is not compatibility target | Keep at most one negative smoke test until cleanup lands |
| graph_* legacy tables | Historical rejected graph storage | `_drop_rejected_graph_storage` only | Migration/reset cleanup | None | DELETE_LEGACY cleanup paths | Cleanup-only residue | Drop cleanup code after clean schema reset |
| analysis_schema_migrations | SQLite migration ledger | `AnalysisStore._run_schema_migrations` | Store init | None | REDESIGN, not delete yet | Local reset is acceptable; current migrations mainly preserve old transitions | Replace with clean schema version/reset path |
| knowledge_source_overview | UI projection | `overview_projection.refresh_overview_for_sources` | `/knowledge/overview`, Console overview | Yes | KEEP, redesign small parts | Useful UI projection; no symbol/relation columns in schema | Remove dead `facts.symbolCount/relationCount` UI fallback |

## 12.3 Table Keep Candidates

| Table | Purpose in new model | Writers | Readers | Required constraints | Notes |
|---|---|---|---|---|
| analysis_jobs | Analysis job lifecycle | `create_job`, `update_job`, interrupted-job handlers | status/job endpoints, overview refresh | Optional source FK via join table; job files cascade | Drop symbol/relation counters |
| analysis_job_files | Per-file job lifecycle/retry | `create_job_files`, `update_job_file` | retry/status/overview | FK `job_id`, `source_id`, `inventory_file_id`, `analysis_file_id` | Current table has no FKs |
| analysis_files | Current analysis result per inventory file | `_insert_file`, `_update_analysis_file_row`, `_upsert_file` | files endpoint, graph joins, cleanup | FK to `sources`, `files(id)` | Keep lifecycle/error columns; drop symbol/relation counters |
| analysis_graph_state | Current graph pointer/counts per source | `_refresh_graph_state` | graph metadata/manifest/query/semantic | FK `source_id` cascade | Good concept; graph revision hashing needs cleanup |
| analysis_graph_nodes | YAML node facts | graph materializer/store | graph API, query, semantic | FKs to source/file/job/parent node | Active fact table; metadata must be allowlisted |
| analysis_graph_edges | YAML edge facts | graph materializer/store/resolver | graph API, query, semantic | FKs from node, optional target node, evidence relationship normalized | Drop `edge_kind`, JSON evidence duplication |
| analysis_graph_claims | YAML claim facts | graph materializer/store | node detail, query, semantic | FK node; claim-evidence join | Keep `rejection_reason` as lifecycle/diagnostic for rejected candidates |
| analysis_graph_evidence | Evidence anchors | graph materializer/store | graph API, semantic, query evidence paths | FK source/file/job; join tables to claims/edges | Decide whether excerpt is contract UI field |
| analysis_graph_diagnostics | Validation/analysis failure diagnostics | graph materializer/service | diagnostics endpoint, status counts | FK source/job/file with chosen delete behavior | Keep, but delete duplicate `diagnostic_code` |
| semantic_index_state | Semantic index lifecycle | `SemanticIndexStore` | semantic endpoints, worker | FK source cascade | Keep lifecycle/retry state |
| semantic_documents | Semantic retrieval documents | `semantic_builder` | semantic search/query | FK source, node cascade | JSON provenance acceptable only if document-local; otherwise normalize |
| semantic_vectors | Embeddings | semantic index writer | semantic search | FK document cascade; FK source | `vector_blob` unused; `vector_json` active |
| knowledge_source_overview | Console overview projection | overview refresh | `/knowledge/overview`, Console | FK source cascade or rebuild-on-start | Keep `factsProgress`, not legacy facts |
| analysis_schema_migrations | Schema lifecycle | store init | store init | None | Redesign with clean reset/version |

## 12.4 Column Deletion Candidates

| Table | Column | Writer | Reader/API/UI usage | Verdict | Reason | Deletion impact |
|---|---|---|---|---|---|---|
| analysis_jobs | symbol_count | `_job_params` | `_job`, `/analysis/status`, job endpoint, tests | DELETE_LEGACY | Node count is not a symbol count | Replace API with graph node counts if needed |
| analysis_jobs | relation_count | `_job_params` | `_job`, `/analysis/status`, job endpoint, tests | DELETE_LEGACY | Edge count is not a relation count | Replace API with graph edge counts if needed |
| analysis_files | symbol_count | `_analysis_file_values` | `/analysis/files`, tests | DELETE_LEGACY | Old projection terminology | Remove from DB/API/tests |
| analysis_files | relation_count | `_analysis_file_values` | `/analysis/files`, tests | DELETE_LEGACY | Old projection terminology | Remove from DB/API/tests |
| analysis_graph_edges | edge_kind | materializer/store fixtures | semantic revision, test fixtures | DELETE_LEGACY | Duplicates `edge_type`; no new contract need | Remove from revision hash/tests |
| analysis_graph_edges | evidence_ids_json | edge writer stores duplicate list | semantic revision only | DELETE_UNUSED | Duplicates `evidence_id`; JSON relationship | Replace with edge-evidence join if multi-evidence is needed |
| analysis_graph_diagnostics | diagnostic_code | diagnostics table writer mirrors `code` | tests only; API selects `code` | DELETE_LEGACY | Duplicate compatibility column | Tests update; diagnostics API unchanged |
| semantic_vectors | vector_blob | schema only | No active reader found | DELETE_UNUSED | `vector_json` is the active vector store | Either delete or migrate to blob deliberately |
| analysis_graph_evidence | excerpt | schema exists; current graph evidence projection does not populate/read it | UI expects `text || excerpt`, API omits top-level excerpt | QUESTION_USER_DECISION | Field is useful only if evidence snippets are desired | Either populate/expose as contract or delete with metadata text |
| active fact metadata_json | raw/debug keys | analyzers/materializer/resolver | API exposes subsets; semantic revision hashes all | DELETE_DEBUG_GARBAGE by key | Debug metadata inside active facts | Apply allowlist and move failure details to diagnostics |

## 12.5 Column Keep Candidates

Columns are grouped only when all listed columns have the same classification.

| Table | Column | Category | Why required | Constraints/index notes |
|---|---|---|---|---|
| analysis_jobs | job_id, status, started_at, completed_at, source_count, file_count, processed_file_count, failed_file_count, current_source_id, current_relative_path, source_ids_json, last_progress_at, diagnostics_json, engine_version, mode | LIFECYCLE_KEEP | Job status, progress, retry/failure visibility | Replace `source_ids_json` with job-source rows if source relationships matter |
| analysis_files | file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status, analyzed_at, attempt_count, last_attempt_at, last_error_code, last_error_message, last_raw_response_preview, diagnostics_json, engine_version, flow_domain | LIFECYCLE_KEEP | Per-file analysis state and retry/debug lifecycle | Add FK to `sources` and `files(id)` |
| analysis_job_files | id, job_id, source_id, inventory_file_id, analysis_file_id, relative_path, extension, content_hash, line_count, decode_policy, flow_domain, status, attempt_count, started_at, completed_at, diagnostics_json, engine_version, created_at, updated_at | LIFECYCLE_KEEP | Job/file processing lifecycle | Add FKs; cascade from job |
| analysis_graph_state | source_id, graph_id, content_identity, node_count, edge_count, claim_count, evidence_count, updated_at | CONTRACT_KEEP | Current graph identity and counts | FK source; compute revision from contract fields only |
| analysis_graph_nodes | id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash, stable_key, node_kind, language, name, qualified_name, display_name, parent_node_id, line_start, line_end, confidence, status, created_at, updated_at, fact_origin, flow_domain | CONTRACT_KEEP | YAML graph node contract, UI/query/semantic identity | Add FKs for source/file/job/parent; indexes by source/kind/flow/path |
| analysis_graph_nodes | metadata_json | QUESTION_USER_DECISION | Some structural details may be useful, but current blob is dirty | Convert to allowlisted metadata policy |
| analysis_graph_edges | id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash, from_node_id, to_node_id, edge_type, resolution_status, confidence, evidence_id, unresolved_target_json, status, created_at, updated_at, fact_origin, flow_domain | CONTRACT_KEEP | YAML edge contract and query traversal | Normalize evidence relationship; review `to_node_id` delete behavior |
| analysis_graph_edges | metadata_json | QUESTION_USER_DECISION | UI uses call tooltip/styling keys; resolver uses method/type hints | Allowlist only traversal/display keys |
| analysis_graph_claims | id, job_id, source_id, node_id, claim_kind, summary, confidence, status, rejection_reason, created_at, updated_at, fact_origin, flow_domain | CONTRACT_KEEP | YAML claims, semantic summaries, rejected candidate explanation | Add FK source/job; keep `rejection_reason` diagnostic/lifecycle |
| analysis_graph_claims | evidence_ids_json | QUESTION_USER_DECISION | Semantic builder currently uses it | Replace with `analysis_graph_claim_evidence` |
| analysis_graph_claims | metadata_json | QUESTION_USER_DECISION | Entrypoint/config metadata may be useful | Allowlist domain metadata only |
| analysis_graph_evidence | id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash, line_start, line_end, excerpt_hash, evidence_kind, created_at, updated_at, fact_origin, flow_domain | CONTRACT_KEEP | Evidence identity/location/provenance | Add FKs and owner join tables |
| analysis_graph_evidence | metadata_json | QUESTION_USER_DECISION | Currently holds raw text/debug residue | Keep only evidence-domain keys or delete |
| analysis_graph_diagnostics | id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash, severity, stage, code, message, candidate_id, line_start, line_end, metadata_json, created_at, fact_origin, flow_domain | DIAGNOSTIC_KEEP | Failure analysis, rejected facts, retry visibility | Add FKs; metadata can retain debug/lifecycle payloads |
| semantic_index_state | source_id, graph_revision, status, builder_version, embedding_model, embedding_dimension, total_node_count, indexed_node_count, last_build_id, last_error, diagnostics_json, created_at, updated_at, started_at, completed_at | SEMANTIC_KEEP | Semantic build lifecycle | FK source |
| semantic_documents | document_id, source_id, node_id, node_kind, graph_id, document_type, builder_version, text_hash, text, claim_ids_json, evidence_ids_json, status, created_at, updated_at | SEMANTIC_KEEP | Retrieval corpus and provenance | FK source/node; provenance JSON acceptable if semantic-local |
| semantic_vectors | document_id, source_id, node_id, graph_id, embedding_model, embedding_dimension, vector_json, created_at, updated_at | SEMANTIC_KEEP | Active vector search | FK document/source |
| knowledge_source_overview | source_id, display_name, group_name, source_path, root_exists, inventory_status, inventory_file_count, skipped_file_count, analysis_state, analysis_total_files, analysis_processed_files, analysis_succeeded_files, analysis_partial_files, analysis_failed_files, analysis_skipped_files, analysis_pending_files, completion_percent, active_job_id, active_job_total_files, active_job_processed_files, active_job_failed_files, active_job_current_relative_path, updated_at, version | UI_KEEP | Console overview | FK source or projection rebuild |
| analysis_schema_migrations | version, name, applied_at | LIFECYCLE_KEEP | Store initialization until reset path exists | Redesign later |

## 12.6 Questionable Columns / User Decision Needed

| Table | Column | Why questionable | Where exposed | UX/backend reason unclear | Question for user |
|---|---|---|---|---|---|
| analysis_graph_nodes | metadata_json | Contains useful structural keys mixed with debug/provenance duplicates | Graph node API, revision hash | Need a strict allowlist | Which node metadata belongs in the graph contract versus semantic-only text? |
| analysis_graph_edges | metadata_json | UI uses some call keys; many keys are scoring/debug | Graph edge API, revision hash, resolver | Need split between resolver input and persisted display data | Do we still need call scoring fields in persisted graph facts? |
| analysis_graph_claims | evidence_ids_json | Needed by semantic builder but violates FK principle | Semantic builder, summary evidence count | Should become join table | Should claim evidence be normalized now? |
| analysis_graph_claims | metadata_json | Entrypoint/domain keys useful; `qualityIssue` and provenance duplicates dirty | Node detail API | Mixed domain/debug data | Should claim metadata be only domain-specific keys like route/topic/schedule? |
| analysis_graph_evidence | excerpt | UI wants snippet text but writer/API do not use column | Evidence detail UI fallback | Broken current contract | Do we still need evidence snippets in UI? |
| analysis_graph_evidence | metadata_json | Holds raw `text` and legacy enrichment residue | Evidence API | Raw text should not live in metadata | Should evidence text move to bounded `excerpt` or be removed? |
| knowledge_source_overview | active_job_mode | Mapped in API, not clearly displayed | Overview API/Console mapper | UX value unclear | Do we still need to show job mode in overview? |
| analysis_files | last_raw_response_preview | Useful for failed analysis debugging but not broadly UI-displayed | Files API | Lifecycle/debug value only | Should raw preview be exposed only through diagnostics/failure details? |

## 12.7 Metadata Key Deletion Candidates

| Owner | Metadata key | Writer | Reader/API/UI usage | Verdict | Reason |
|---|---|---|---|---|---|
| node/edge/claim/evidence | stableKey | analyzers/materializer | Duplicates `stable_key` or fact id | DELETE_DEBUG_GARBAGE | Real columns exist |
| node/edge/claim/evidence/diagnostic | factOrigin | analyzers/materializer | Duplicates `fact_origin` | DELETE_DEBUG_GARBAGE | Real column exists |
| node/edge/claim/evidence/diagnostic | flowDomain | analyzers/materializer | Duplicates `flow_domain` | DELETE_DEBUG_GARBAGE | Real column exists |
| node/edge/claim/evidence | status | analyzers/materializer | Duplicates `status` | DELETE_DEBUG_GARBAGE | Real column exists |
| node/edge/evidence | sourceKind | analyzers/materializer | API exposes subset; mostly duplicates kind/evidence kind | DELETE_LEGACY | Old source-kind projection leakage |
| node/edge | parser, extractorId, extractorImplementation, extractorFallbackUsed, engineVersion, analyzerName, analyzerVersion, structuralRangeSource | runtime/materializer | Revision hash only or debug | DELETE_DEBUG_GARBAGE | Provenance belongs on file/job/diagnostics, not every fact |
| edge | rawText, rawCallText, resolverSignals, ownerTypeHint, importHint, reasonCodes, flowUsefulness, noiseCategory, flowScore, displayScore, expansionScore, callImportance | structural analyzer/call classifier | Some API exposure; mostly not displayed | DELETE_DEBUG_GARBAGE | Debug/scoring fields inside active facts |
| edge | receiverTypeHint, targetTypeHint, targetTypeText, argumentCount, candidateCount, candidateKind, resolver | resolver/classifier | Resolver/test usage | DELETE_DEBUG_GARBAGE after resolver rewrite | Needed transiently for resolution, not graph contract |
| claim | qualityIssue | anchor enrichment | Duplicates `rejection_reason`/diagnostics | DELETE_DEBUG_GARBAGE | Rejection reason should be column/diagnostic |
| evidence | text | materializer | API metadata; UI wants top-level `text || excerpt` | DELETE_DEBUG_GARBAGE | Raw evidence text in metadata |
| evidence | identityCollisionOrdinal | materializer | No useful UI/query use | DELETE_DEBUG_GARBAGE | Debug collision marker |
| diagnostic | sourceId, relativePath, lineStart, lineEnd, factOrigin, flowDomain | diagnostics writers | Duplicates columns | DELETE_DEBUG_GARBAGE | Diagnostic table has real columns |
| claim/evidence | sourceKind = GENERIC_CONFIG_ENRICHMENT | old enrichment path/live DB residue | No new contract use | DELETE_LEGACY | Old config enrichment residue |

## 12.8 Metadata Key Keep Candidates

| Owner | Metadata key | Why required | Reader/API/UI usage |
|---|---|---|---|
| edge | callKind | Helpful call display/traversal classification | Console edge tooltip |
| edge | callTargetCategory | Used for edge styling and filtering semantics | Console edge CSS class/API metadata |
| edge | sliceDefaultVisibility | Used for graph edge visibility/styling | Console edge CSS class/API metadata |
| edge | methodName | Useful call display; currently resolver input | Console tooltip, resolver |
| edge | receiverText | Useful call display for unresolved calls | Console tooltip |
| edge | unresolvedReason | Explains unresolved call targets | Console tooltip/query diagnostics |
| edge | resolutionReason | Explains why target was resolved/external/unresolved | Backend/debug, could be UI later |
| claim | entrypointKind | Domain fact for entrypoints | Node summary/detail semantics |
| claim | httpMethod, route | Domain fact for HTTP entrypoints | Query/UI future value |
| claim | topic, schedule | Domain fact for messaging/scheduled entrypoints | Query/UI future value |
| claim | exceptionType | Domain fact for exception boundaries | Query/UI future value |
| node | signature, returnType, parameters, parameterNames, visibility | Potential semantic/query enrichment | Semantic text/query candidate value |
| node | packageName, importedName, isStatic, isWildcard, annotations, bodyLineStart, bodyLineEnd, typeName | Structural metadata that may support search/display | Mostly semantic/query; not primary UI today |

## 12.9 Metadata Keys Requiring User Decision

| Owner | Metadata key | Where exposed | Why questionable | Question |
|---|---|---|---|---|
| node | signature/returnType/parameters/visibility | Semantic/query only today | Useful but not part of YAML core kinds | Should structural API details remain as graph metadata or move to semantic text only? |
| node | packageName/importedName/isStatic/isWildcard | Not directly displayed | Parser detail, not graph contract | Do imports/packages need persisted metadata? |
| edge | methodName/receiverText/callKind | Console tooltip | UI value exists, but contract could be leaner | Do we still want call-specific tooltip metadata in Console? |
| edge | callTargetCategory/sliceDefaultVisibility | Console styling | Derived display policy, not domain fact | Should display policy be persisted or computed at read time? |
| claim | route/httpMethod/topic/schedule/exceptionType | Not broadly displayed yet | Strong domain value but sparse | Should entrypoint metadata become first-class columns for claims? |
| evidence | text/excerpt | Evidence UI | Snippet contract is currently broken | Should evidence snippets be retained and displayed? |

## 12.10 FK / Orphan-Removal Gaps

| Parent | Child | Current FK | Current delete behavior | Expected behavior | Verdict | Fix recommendation |
|---|---|---|---|---|---|---|
| sources | analysis_files | No | Manual cleanup by source/path/hash | Cascade or strict FK | GAP | Add `source_id REFERENCES sources(source_id) ON DELETE CASCADE` |
| files | analysis_files | No | Manual matching on source/path/hash | Cascade from inventory file | GAP | `analysis_files.file_id REFERENCES files(id) ON DELETE CASCADE` or separate inventory FK |
| analysis_jobs | analysis_job_files | No | Manual retention delete | Cascade | GAP | Add FK `job_id ON DELETE CASCADE` |
| sources/files/jobs | analysis_graph_nodes | Only `analysis_file_id` FK | Partial cascade | Enforced parent graph ownership | GAP | Add FKs to source, file, job; parent node FK |
| sources/files/jobs | analysis_graph_evidence | Only `analysis_file_id` FK | Partial cascade | Enforced parent ownership | GAP | Add FKs to source, file, job |
| analysis_graph_nodes | analysis_graph_claims | Yes | `ON DELETE CASCADE` | Cascade | OK | Add source/job FKs too |
| analysis_graph_nodes | analysis_graph_edges.from_node_id | Yes | `ON DELETE CASCADE` | Cascade | OK | Keep |
| analysis_graph_nodes | analysis_graph_edges.to_node_id | Yes | `ON DELETE CASCADE` | Decide cascade vs set null | QUESTION | For unresolved-capable edges, `ON DELETE SET NULL` may be better |
| analysis_graph_edges | analysis_graph_evidence | Partial via `evidence_id` | `ON DELETE SET NULL` | Join table with cascades | GAP | Add `analysis_graph_edge_evidence(edge_id,evidence_id)` |
| analysis_graph_claims | analysis_graph_evidence | No; JSON only | None | Join table with cascades | GAP | Add `analysis_graph_claim_evidence(claim_id,evidence_id)` |
| analysis_graph_state | sources | No | Manual delete when counts zero | Cascade | GAP | FK source cascade |
| semantic_documents | analysis_graph_nodes | Yes | `ON DELETE CASCADE` | Cascade | OK | Add source FK |
| semantic_vectors | semantic_documents | Yes | `ON DELETE CASCADE` | Cascade | OK | Add source FK |
| knowledge_source_overview | sources | No | Rebuild/delete projection | Cascade or rebuild | GAP | FK source cascade or explicit projection rebuild contract |
| analysis_graph_diagnostics | jobs/files/sources | No | Manual retention/file delete | Cascade or set null by lifecycle policy | GAP | Add FKs; likely file/source cascade, job set null or cascade based retention |

## 12.11 API Fields To Delete

| Endpoint | Field | Source DB field/key | UI usage | Backend usage | Verdict | Reason |
|---|---|---|---|---|---|---|
| `/analysis/status` | symbolCount | `analysis_jobs.symbol_count` / graph node count alias | Tests only | None needed | DELETE_LEGACY | Old symbol terminology |
| `/analysis/status` | relationCount | `analysis_jobs.relation_count` / graph edge count alias | Tests only | None needed | DELETE_LEGACY | Old relation terminology |
| `/analysis/jobs/{job_id}` | symbolCount, relationCount | `analysis_jobs` | None | None | DELETE_LEGACY | Replace with graph counts only if needed |
| `/analysis/files` | symbolCount, relationCount | `analysis_files` | Not displayed by current graph page | None | DELETE_LEGACY | Old per-file projection counts |
| `/analysis/graph/nodes` and view | graphNodeId | node id alias | URL/search fallback only | None | DELETE_LEGACY | Use `id` |
| `/analysis/graph/nodes` and view | kind | `node_kind` alias | UI prefers `nodeKind`; query has its own `kind` | Legacy tolerance | DELETE_LEGACY | Use `nodeKind` for graph API |
| `/analysis/graph/nodes` and view | summaryAvailable | metadata-derived | Not displayed | None | DELETE_UNUSED | No current UX |
| `/analysis/graph/nodes` and view | importance | metadata score/confidence | No clear current use | None | DELETE_UNUSED | Derived scoring leakage |
| `/analysis/graph/nodes` and view | metadata.sourceKind/displayScore/flowScore/unresolvedReason | metadata | Not displayed for nodes | None | DELETE_DEBUG_GARBAGE | Debug/display internals |
| `/analysis/graph/edges` and view | graphEdgeId | edge id alias | URL fallback only | Query tolerates aliases | DELETE_LEGACY | Use `id` |
| `/analysis/graph/edges` and view | from, to | aliases for `fromNodeId`, `toNodeId` | UI fallback only | Query tolerates aliases | DELETE_LEGACY | Use explicit node id fields |
| `/analysis/graph/edges` and view | relation | alias for `edgeType` | UI fallback only | Query tolerates alias | DELETE_LEGACY | Use `edgeType` |
| `/analysis/graph/edges` and view | classification | metadata derived | Not directly needed; CSS uses metadata keys | None | DELETE_UNUSED | Duplicate derived display field |
| `/analysis/graph/edges` and view | resolved | derived bool | Not displayed | None | DELETE_UNUSED | `resolutionStatus` is enough |
| `/analysis/graph/edges` and view | metadata.displayScore/flowScore/receiverTypeHint | metadata | Not displayed | None | DELETE_DEBUG_GARBAGE | Scoring/type-hint leakage |
| `/analysis/diagnostics` | metadata duplicate source/path/line/origin/domain keys | diagnostic metadata | Not needed if columns exposed | None | DELETE_DEBUG_GARBAGE | Duplicate real fields |
| `/analysis/graph/* evidence` | metadata.text | evidence metadata | UI expects top-level text/excerpt, not metadata | None | DELETE_DEBUG_GARBAGE | Raw text hidden in metadata |

## 12.12 API Fields Requiring User Decision

| Endpoint | Field | UI component | Displayed? | Why questionable | Question |
|---|---|---|---|---|---|
| `/analysis/graph/metadata` | source.path, source.rootExists | graph metadata mapper | No | Backend context, not visible | Keep in graph metadata or leave to sources API? |
| `/analysis/graph/metadata` | currentGraphNodeCount/currentGraphEdgeCount | progress mapper | Indirect | Useful only as progress fallback | Should graph metadata show raw graph counts? |
| `/analysis/graph/metadata` | degradedReason/promotionReason | progress mapper | Not currently visible | Placeholder fields | Delete until implemented? |
| `/analysis/graph/manifest` | connectedComponentCount/largestComponent* | graph client | No | Always `None` currently | Delete until computed? |
| `/analysis/graph/manifest` and view | status | graph client | No | Empty object today | Delete until meaningful? |
| `/analysis/graph/nodes` | entrypoint | graph node rendering/search | Indirect | Could be useful, not clearly displayed | Should entrypoint be a visible node property? |
| `/analysis/graph/node/{id}` | claims[].metadata | node detail | Not rendered | Mixed debug/domain data | Expose only allowlisted claim domain metadata? |
| `/analysis/graph/node/{id}` | claims[].rejectionReason | node detail | Not clearly rendered | Diagnostic value only | Keep rejected reason in API detail? |
| `/analysis/graph/evidence` | excerpt/text | evidence preview | Intended, currently broken | UX decision | Do we show evidence snippets? |

## 12.13 UI Field Usage Matrix

| UI file/component | Endpoint | Field | Rendered/used? | UX purpose | Verdict |
|---|---|---|---|---|---|
| `knowledge-overview-page.js` | `/knowledge/overview` | `facts.symbolCount`, `facts.relationCount` | Render fallback if present | Old facts cell | DELETE_LEGACY |
| `knowledge-overview-page.js` | `/knowledge/overview` | `factsProgress` | Used for progress bar | Analysis/semantic progress | UI_KEEP |
| `knowledge-graph-page.js` | `/analysis/graph/metadata` | source label/group/status/progress | Rendered | Header/progress | UI_KEEP |
| `knowledge-graph-page.js` | `/analysis/graph/metadata` | degradedReason/promotionReason/currentPointerGraphId | Mapped, not displayed | Placeholder | DELETE_UNUSED or QUESTION |
| `knowledge-graph-client.js` | `/analysis/graph/manifest` | graphRevision/etag/filters/counts | Used | Pagination/revision safety | UI_KEEP |
| `knowledge-graph-client.js` | `/analysis/graph/view` | nodes/edges/counts/hasMore | Used | Main graph rendering | UI_KEEP |
| `knowledge-graph-page.js` | graph nodes | `id,label,nodeKind,qualifiedName,relativePath,lineStart,lineEnd,flowDomain,factOrigin,degree` | Rendered/searched/styled | Node display/detail | UI_KEEP |
| `knowledge-graph-page.js` | graph nodes | `graphNodeId` | Search/URL fallback only | Compatibility | DELETE_LEGACY |
| `knowledge-graph-page.js` | graph edges | `edgeType,fromNodeId,toNodeId,fromLabel,toLabel,resolutionStatus,flowDomain,factOrigin,evidenceCount` | Rendered/styled | Edge display/detail | UI_KEEP |
| `knowledge-graph-page.js` | graph edges | `relation,from,to,graphEdgeId` | Fallback only | Compatibility | DELETE_LEGACY |
| `knowledge-graph-page.js` | graph edge metadata | `callKind,receiverText,methodName,unresolvedReason,callTargetCategory,sliceDefaultVisibility` | Tooltip/CSS | Call context | QUESTION_USER_DECISION |
| `knowledge-graph-page.js` | evidence | `text` or `excerpt` | Intended | Evidence snippet | QUESTION_USER_DECISION |
| `knowledge-graph-page.js` | labels | "relation(s)" text | Rendered | Old terminology | DELETE_LEGACY, rename to edges/relationships |

## 12.14 Semantic / Query Dependencies

| Component | Field/table/key | Usage | Keep/delete/question | Reason |
|---|---|---|---|---|
| `semantic_builder.py` | nodes `id,node_kind,name,qualified_name,display_name,relative_path,line_start,line_end,status,source_id` | Builds semantic documents | KEEP | Core semantic text/context |
| `semantic_builder.py` | claims `summary,claim_kind,status,evidence_ids_json` | Responsibility document enrichment | KEEP then REDESIGN | Evidence JSON should become join table |
| `semantic_builder.py` | edges `edge_type,resolution_status,unresolved_target_json` | Flow/context text | KEEP | Query/retrieval value |
| `semantic_index.py` | `metadata_json` in revision hash | Graph revision identity | DELETE_DEBUG_GARBAGE from hash | Debug metadata should not affect graph identity |
| `semantic_index.py` | `edge_kind` in revision hash | Revision compatibility | DELETE_LEGACY | Duplicate of `edge_type` |
| `semantic_index.py` | `evidence_ids_json` in edge revision | Revision compatibility | DELETE_UNUSED | Edge has `evidence_id` |
| `semantic_documents` | `claim_ids_json`, `evidence_ids_json` | Semantic document provenance | SEMANTIC_KEEP | Document-local provenance is acceptable |
| `semantic_vectors` | `vector_json` | Active vector search | SEMANTIC_KEEP | Current reader uses it |
| `semantic_vectors` | `vector_blob` | None found | DELETE_UNUSED | Dead alternative representation |
| `knowledge_query_service.py` | `kind`, `relation`, `graphNodeId`, `graphEdgeId` aliases | Legacy-tolerant parsing | DELETE_LEGACY | Standardize query graph payload to `nodeKind`, `edgeType`, `id` |
| `knowledge_query_service.py` | `flowDomain`, `factOrigin` | Filtering/display/provenance | QUERY_KEEP | New contract provenance fields |
| `knowledge_query_service.py` | evidence ids and edge ids | Verified paths/evidence | QUERY_KEEP | Jarvis flow needs traceability |

## 12.15 Legacy Code/Test Deletion Candidates

| File | Symbol/test/function | Why legacy | Delete/update | Notes |
|---|---|---|---|---|
| `graph_analysis.py` | `LegacyAnalysisProjectionAdapter` | Converts old `symbols/relations` payloads | DELETE_LEGACY | Old compatibility target is explicitly rejected |
| `analysis_store.py` | `replace_file_analysis` legacy error path | Compatibility stub | DELETE_LEGACY | Remove after tests stop referencing old writer |
| `analysis_store.py` | `_node_kind_from_source_kind` | Old source-kind mapping | DELETE_LEGACY or internal-only | Depends on metadata cleanup |
| `analysis_store.py` | `_drop_rejected_graph_storage`, old reset table list | Historical cleanup | DELETE_LEGACY after reset | Keep only until clean reset approved |
| `analysis_store.py` | `symbolCount/relationCount` projections | Old API contract | DELETE_LEGACY | Replace with node/edge count if useful |
| `test_analysis.py` | parser tests using `symbols`/`relations` | Old AI response contract | DELETE_LEGACY_TEST | Replace with graph response parser tests |
| `test_analysis.py` | `diagnostic_code` schema assertions | Duplicate column preservation | DELETE_LEGACY_TEST | Use `code` |
| `test_analysis.py` | `symbol_count/relation_count` assertions | Old counters | UPDATE_AFTER_DB_CLEANUP | Use graph counts if needed |
| `test_semantic_index.py` / `semantic_test_support.py` | fixtures inserting `edge_kind`, `evidence_ids_json`, symbol/relation counts | Old schema fixtures | UPDATE_AFTER_DB_CLEANUP | Normalize evidence relationships |
| `test_semantic_worker.py` | job fixture `symbol_count/relation_count` | Old counters | UPDATE_AFTER_DB_CLEANUP | Remove columns |
| `forge_it/test_knowledge_service_it.py` | `symbolCount` status assertions | Old API | DELETE_LEGACY_TEST | Use graph node/edge counts only if needed |
| `forge_it/test_graph_api.py` | negative legacy table guard | Guards old table absence | KEEP_NEW_CONTRACT_TEST short-term | Can shrink after reset |
| `OperatorStaticUiRegressionTest` | contains `symbols`/`relations` expectations | Old UI text | UPDATE_AFTER_UI_CLEANUP | Rename to graph/edges |
| Nexus proxy fixtures | graph/Jarvis fixtures with alias-tolerant payloads | Stale contract examples | UPDATE_AFTER_API_CLEANUP | Nexus itself proxies transparently |

## 12.16 Proposed Clean DB Contract

### `analysis_jobs`

- Primary key: `job_id`
- Required columns: lifecycle/progress only, no graph fact counters
- Relationships/FKs: job files cascade; optional job-source join table
- ON DELETE: cascade to job files; graph facts should not depend on job retention unless job history is retained
- Indexes: status/start time
- Metadata policy: diagnostics JSON only for job lifecycle

### `analysis_job_files`

- Primary key: `id`
- Required columns: job/source/file identity, status, attempts, timestamps, diagnostics
- Relationships/FKs: `job_id`, `source_id`, `inventory_file_id`, optional `analysis_file_id`
- ON DELETE: cascade from job; set null/cascade from analysis file by lifecycle choice
- Indexes: job/status/source/file
- Metadata policy: diagnostics only

### `analysis_files`

- Primary key: inventory file id or explicit analysis file id
- Required columns: source/path/hash/analyzer/status/retry/error/engine/flow domain
- Relationships/FKs: source and inventory file
- ON DELETE: cascade from source/file
- Indexes: source/path, source/status, current identity
- Metadata policy: no symbol/relation counters

### `analysis_graph_state`

- Primary key: `source_id`
- Required columns: `graph_id`, `content_identity`, counts, `updated_at`
- Relationships/FKs: source cascade
- ON DELETE: cascade from source
- Indexes: source
- Metadata policy: revision computed from contract fields only

### `analysis_graph_nodes`

- Primary key: `id`
- Required columns: source/file/job ids, stable key, YAML node kind, name/qualified/display, parent, range, confidence, status, origin, domain, timestamps
- Relationships/FKs: source, file, analysis file, parent node
- ON DELETE: cascade from source/file/parent as appropriate
- Indexes: source/kind, source/domain, source/path, stable key
- Metadata policy: allowlisted structural/domain metadata only

### `analysis_graph_edges`

- Primary key: `id`
- Required columns: source/file/job ids, from node, optional to node, YAML edge type, resolution status, confidence, unresolved target, status, origin, domain, timestamps
- Relationships/FKs: from node cascade; to node set null or cascade by approved policy
- ON DELETE: cascade from source/file/from-node
- Indexes: source/type, source/domain, from/to
- Metadata policy: allowlisted call display/traversal keys only

### `analysis_graph_claims`

- Primary key: `id`
- Required columns: source/job/node, YAML claim kind, summary, confidence, status, rejection reason, origin, domain, timestamps
- Relationships/FKs: node cascade; source/job
- ON DELETE: cascade from node/source
- Indexes: node/kind/status
- Metadata policy: allowlisted domain claim keys only

### `analysis_graph_evidence`

- Primary key: `id`
- Required columns: source/file/job, location, evidence kind, excerpt hash, optional approved excerpt, origin, domain, timestamps
- Relationships/FKs: source/file/job
- ON DELETE: cascade from source/file
- Indexes: source/path, source/kind
- Metadata policy: no raw text in metadata; excerpt is first-class if retained

### `analysis_graph_claim_evidence`

- Primary key: `(claim_id, evidence_id)`
- Relationships/FKs: claim cascade, evidence cascade/file-owned
- Purpose: replace `claims.evidence_ids_json`

### `analysis_graph_edge_evidence`

- Primary key: `(edge_id, evidence_id)`
- Relationships/FKs: edge cascade, evidence cascade/file-owned
- Purpose: replace `edges.evidence_id/evidence_ids_json` if multi-evidence is needed

### `analysis_graph_diagnostics`

- Primary key: `id`
- Required columns: job/source/file refs, severity, stage, code, message, candidate id, range, metadata, created at, origin, domain
- Relationships/FKs: source/file cascade; job set null or cascade by retention policy
- ON DELETE: cascade from file/source
- Indexes: source/severity/code, created_at
- Metadata policy: debug and lifecycle details allowed here, but no duplicate real columns

### `semantic_index_state`

- Primary key: `source_id`
- Required columns: graph revision, status, builder/model/dimension, counts, error, diagnostics, timestamps
- Relationships/FKs: source cascade
- ON DELETE: cascade from source
- Indexes: status, revision
- Metadata policy: lifecycle diagnostics only

### `semantic_documents`

- Primary key: `document_id`
- Required columns: source/node/graph/document type/text/provenance/status/timestamps
- Relationships/FKs: source, node cascade
- ON DELETE: cascade from node/source
- Indexes: source/graph/status, source/node
- Metadata policy: semantic-local provenance allowed

### `semantic_vectors`

- Primary key: `document_id`
- Required columns: source/node/graph/model/dimension/vector/timestamps
- Relationships/FKs: document cascade, source
- ON DELETE: cascade from document/source
- Indexes: source/graph/model, source/node
- Metadata policy: no extra metadata

### `knowledge_source_overview`

- Primary key: `source_id`
- Required columns: display/source/inventory/analysis/progress/active job/timestamps/version
- Relationships/FKs: source cascade or rebuild contract
- ON DELETE: cascade or projection rebuild
- Indexes: version/source
- Metadata policy: no symbol/relation facts

## 12.17 Implementation Plan After Approval

Step 1: remove unused API/UI fields

- Remove `symbolCount`, `relationCount`, graph alias fields, unused manifest/status placeholders, dead Console `facts.symbolCount/relationCount` fallback.

Step 2: remove legacy code/tests

- Delete old symbol/relation parser/projection tests and compatibility adapters.
- Keep only short negative guards for old tables/endpoints if still useful.

Step 3: clean DB schema and migrations/reset

- Introduce clean schema/reset path.
- Drop legacy columns: `symbol_count`, `relation_count`, `edge_kind`, `diagnostic_code`, unused vector/blob fields.

Step 4: update FK constraints/orphan removal

- Add source/file/job FKs.
- Add claim/edge evidence join tables.
- Remove JSON-only evidence relationships.

Step 5: update materializer/store writers

- Write only YAML contract columns and allowlisted metadata.
- Move failure/debug details to diagnostics.

Step 6: update readers/projections

- Standardize graph API on `id`, `nodeKind`, `edgeType`, `fromNodeId`, `toNodeId`.
- Remove legacy aliases and raw metadata exposure.

Step 7: update tests

- Replace legacy assertions with YAML graph contract assertions.
- Update semantic fixtures for normalized evidence relations.

Step 8: run full validation

- Run Knowledge unit tests, integration tests, Console static regression, Nexus proxy tests, semantic worker/search tests, and live DB reset/shadow-run smoke.

Recommended next implementation task:

- YES
- Title: Stage 6 - Remove Legacy API Fields and Normalize Graph Persistence Contract
