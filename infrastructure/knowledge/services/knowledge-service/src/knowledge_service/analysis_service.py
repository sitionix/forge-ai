from __future__ import annotations

import json
import threading
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from knowledge_service.analysis_client import OllamaAnalysisClient
from knowledge_service.analysis_schema import AnalysisBuildRequest
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.config import AppConfig
from knowledge_service.errors import KnowledgeError
from knowledge_service.graph_analysis import GraphAnalysisEngine
from knowledge_service.graph_model import GraphMaterialization
from knowledge_service.graph_response_parser import MAX_RAW_PREVIEW_CHARS
from knowledge_service.graph_schema import GRAPH_ANALYSIS_ENGINE_VERSION, GraphDiagnosticSeverity, GraphDiagnosticStage, GraphFactStatus, GraphNodeKind, classify_flow_domain
from knowledge_service.graph_validation import GraphRepairPromptBuilder, GraphValidationError, GraphValidationErrorCode
from knowledge_service.inventory_file_resolver import InventoryFileResolver
from knowledge_service.inventory_store import InventoryStore


class AnalysisJobRunner:
    RETRYABLE_AI_CODES = {
        "ANALYSIS_AI_BAD_RESPONSE",
        "ANALYSIS_AI_INVALID_JSON",
        "ANALYSIS_AI_SCHEMA_INVALID",
        "ANALYSIS_AI_EMPTY_RESPONSE",
        "ANALYSIS_AI_TRANSPORT_ERROR",
    }

    def __init__(self, inventory_store: InventoryStore, config: AppConfig):
        self.inventory_store = inventory_store
        self.config = config
        self.analysis_store = AnalysisStore(inventory_store.db_path)
        self.file_resolver = InventoryFileResolver(inventory_store)
        self.graph_engine = GraphAnalysisEngine()
        self.repair_prompt_builder = GraphRepairPromptBuilder()
        self._lock = threading.Lock()

    def start(self, request: AnalysisBuildRequest, client: Optional[OllamaAnalysisClient] = None) -> Dict[str, Any]:
        with self._lock:
            active = self.analysis_store.active_job()
            if active:
                raise KnowledgeError("ANALYSIS_JOB_ALREADY_RUNNING", "Knowledge analysis job already running")
            job_id = str(uuid.uuid4())
            job = {
                "jobId": job_id,
                "status": "QUEUED",
                "startedAt": None,
                "completedAt": None,
                "sourceCount": len(request.sourceIds),
                "fileCount": 0,
                "processedFileCount": 0,
                "failedFileCount": 0,
                "currentSourceId": request.sourceIds[0] if request.sourceIds else None,
                "currentRelativePath": None,
                "sourceIds": sorted(set(request.sourceIds)),
                "engineVersion": GRAPH_ANALYSIS_ENGINE_VERSION,
                "lastProgressAt": None,
                "symbolCount": 0,
                "relationCount": 0,
                "diagnostics": [],
            }
            self.analysis_store.create_job(job)
            analyzer = client or OllamaAnalysisClient(
                self.config.analysis_base_url,
                self.config.analysis_model,
                self.config.analysis_request_timeout_seconds,
                self.config.module_dir / "config" / "analysis-prompt.md",
                self.config.analysis_context_tokens,
            )
            thread = threading.Thread(target=self._run, args=(job_id, request, analyzer), daemon=True)
            thread.start()
        return {"jobId": job_id, "status": "QUEUED", "message": "Knowledge analysis job queued"}

    def stop(self, job_id: str) -> Dict[str, Any]:
        job = self.analysis_store.request_stop(job_id)
        if job is None:
            raise KnowledgeError("ANALYSIS_JOB_NOT_FOUND", "Analysis job not found")
        return {
            "jobId": job["jobId"],
            "status": job["status"],
            "message": "Knowledge analysis stop requested" if job["status"] == "STOP_REQUESTED" else "Knowledge analysis job is not running",
        }

    def _run(self, job_id: str, request: AnalysisBuildRequest, analyzer: OllamaAnalysisClient) -> None:
        started_at = datetime.now(timezone.utc).isoformat()
        all_rows, _ = self.inventory_store.search_rows(request.sourceIds, request.groups)
        scoped_source_ids = sorted({row["source_id"] for row in all_rows}) or request.sourceIds
        self.analysis_store.cleanup_stale_files(scoped_source_ids or None)
        rows = all_rows
        if not request.force:
            rows, _ = self.inventory_store.search_rows(
                request.sourceIds,
                request.groups,
                analyzer.name,
                analyzer.version,
                only_needing_analysis=True,
                engine_version=GRAPH_ANALYSIS_ENGINE_VERSION,
            )
        if request.maxFiles is not None:
            rows = rows[:max(0, request.maxFiles)]
        self.analysis_store.create_job_files(job_id, rows)
        if self._stop_requested(job_id):
            self._mark_job_stopped(job_id)
            return
        self.analysis_store.update_job(job_id, {
            "status": "RUNNING",
            "startedAt": started_at,
            "lastProgressAt": started_at,
            "sourceCount": len({row["source_id"] for row in rows}),
            "fileCount": len(rows),
            "sourceIds": scoped_source_ids,
        })
        processed = failed = symbols_total = relations_total = 0
        diagnostics: List[Dict[str, Any]] = []
        try:
            for row in rows:
                if self._stop_requested(job_id):
                    self._mark_job_stopped(job_id, diagnostics)
                    return
                self.analysis_store.start_job_file(job_id, row)
                self.analysis_store.update_job(job_id, {
                    "currentSourceId": row["source_id"],
                    "currentRelativePath": row["relative_path"],
                    "lastProgressAt": self._now(),
                })
                resolved = self.file_resolver.read(row)
                if not resolved.ok:
                    processed += 1
                    failed += 1
                    file_diagnostics = [resolved.diagnostic or {"code": "FILE_UNREADABLE", "message": "Indexed file could not be read safely"}]
                    self._mark(row, analyzer, "FAILED", file_diagnostics, job_id=job_id)
                    self.analysis_store.update_job(job_id, {
                        "processedFileCount": processed,
                        "failedFileCount": failed,
                        "lastProgressAt": self._now(),
                    })
                    continue
                file_content = resolved.content
                content = file_content.content
                if len(content) > self.config.analysis_max_file_chars:
                    processed += 1
                    file_diagnostics = [{"code": "ANALYSIS_FILE_TOO_LARGE", "message": "File exceeds AI analysis size limit"}]
                    self._mark(row, analyzer, "SKIPPED_TOO_LARGE_FOR_AI_ANALYSIS", file_diagnostics, job_id=job_id, job_file_status="SKIPPED_TOO_LARGE")
                    self.analysis_store.update_job(job_id, {
                        "processedFileCount": processed,
                        "lastProgressAt": self._now(),
                    })
                    continue
                try:
                    graph, retry_diagnostics, attempt_state = self._analyze_graph_with_retry(
                        analyzer,
                        self._payload(row, file_content.metadata, content),
                        file_content.lineCount,
                        job_id,
                        row,
                        file_content,
                    )
                    if self._stop_requested(job_id):
                        self._mark_job_stopped(job_id, diagnostics)
                        return
                    file_diagnostics = [*[diagnostic.to_record() for diagnostic in graph.diagnostics], *retry_diagnostics]
                    state = self._state(
                        row,
                        analyzer,
                        "ANALYZED",
                        graph.projected_symbol_count,
                        graph.projected_relation_count,
                        file_diagnostics,
                        attempt_state,
                        job_id,
                    )
                    self.analysis_store.replace_file_graph_analysis(row["id"], state, graph.to_store_payload())
                    self.analysis_store.update_job_file(job_id, row["id"], {
                        "status": "ANALYZED",
                        "attempt_count": attempt_state.get("attempt_count", 0),
                        "completed_at": self._now(),
                        "diagnostics": file_diagnostics,
                    })
                    self.analysis_store.resolve_graph_for_sources([row["source_id"]])
                    processed += 1
                    symbols_total += graph.projected_symbol_count
                    relations_total += graph.projected_relation_count
                except Exception as exc:
                    if self._stop_requested(job_id):
                        self._mark_job_stopped(job_id, diagnostics)
                        return
                    processed += 1
                    failed += 1
                    diag = self._diagnostic(row, exc, getattr(exc, "details", {}).get("attempt"))
                    diagnostics.append(diag)
                    file_diagnostics = [*getattr(exc, "details", {}).get("diagnostics", []), diag]
                    attempt_state = self._attempt_state(exc)
                    self._mark(row, analyzer, "FAILED", file_diagnostics, attempt_state, job_id=job_id)
                self.analysis_store.update_job(job_id, {
                    "processedFileCount": processed,
                    "failedFileCount": failed,
                    "symbolCount": symbols_total,
                    "relationCount": relations_total,
                    "diagnostics": diagnostics[-20:],
                    "lastProgressAt": self._now(),
                })
            if self._stop_requested(job_id):
                self._mark_job_stopped(job_id, diagnostics)
                return
            self.analysis_store.resolve_graph_for_sources(scoped_source_ids or None)
            self.analysis_store.update_job(job_id, {
                "status": "COMPLETED",
                "completedAt": datetime.now(timezone.utc).isoformat(),
                "lastProgressAt": self._now(),
                "currentSourceId": None,
                "currentRelativePath": None,
            })
        except Exception as exc:
            diagnostics.append({"code": "ANALYSIS_JOB_FAILED", "message": str(exc)})
            self.analysis_store.update_job(job_id, {
                "status": "FAILED",
                "completedAt": datetime.now(timezone.utc).isoformat(),
                "lastProgressAt": self._now(),
                "diagnostics": diagnostics[-20:],
            })

    def _stop_requested(self, job_id: str) -> bool:
        return self.analysis_store.stop_requested(job_id)

    def _mark_job_stopped(self, job_id: str, diagnostics: Optional[List[Dict[str, Any]]] = None) -> None:
        job = self.analysis_store.job(job_id)
        merged = [*(job or {}).get("diagnostics", []), *(diagnostics or [])]
        if not any(item.get("code") == "ANALYSIS_JOB_STOPPED" for item in merged):
            merged.append({
                "code": "ANALYSIS_JOB_STOPPED",
                "message": "Analysis job stopped before processing the next file.",
            })
        self.analysis_store.update_job(job_id, {
            "status": "STOPPED",
            "completedAt": datetime.now(timezone.utc).isoformat(),
            "lastProgressAt": self._now(),
            "currentSourceId": None,
            "currentRelativePath": None,
            "diagnostics": merged[-20:],
        })
        self.analysis_store.stop_pending_job_files(job_id)

    def _payload(self, row, metadata: Dict[str, Any], content: str) -> Dict[str, Any]:
        return {
            "sourceId": row["source_id"],
            "inventoryFileId": row["id"],
            "serviceLabel": row["display_name"],
            "group": row["group_name"],
            "tags": json.loads(row["tags_json"] or "[]"),
            "relativePath": row["relative_path"],
            "extension": row["extension"],
            "sizeBytes": row["size_bytes"],
            "contentHash": row["content_hash"],
            "lineCount": row["line_count"],
            "decodePolicy": row["decode_policy"],
            "languageHint": self._language(row),
            "flowDomain": self._flow_domain(row),
            "metadata": {k: v for k, v in metadata.items() if k != "absoluteRoot"},
            "content": content,
        }

    def _analyze_graph_with_retry(
        self,
        analyzer: OllamaAnalysisClient,
        payload: Dict[str, Any],
        line_count: int,
        job_id: str,
        row: Any,
        file_content: Any,
    ) -> tuple[GraphMaterialization, List[Dict[str, Any]], Dict[str, Any]]:
        attempts = max(1, self.config.analysis_max_attempts_per_file)
        repair_attempts = max(0, self.config.analysis_repair_attempts_per_file)
        diagnostics: List[Dict[str, Any]] = []
        repair_used = 0
        next_repair_prompt: Optional[str] = None
        for attempt in range(1, attempts + 1):
            repair_prompt = next_repair_prompt
            next_repair_prompt = None
            try:
                result = analyzer.analyze(payload, line_count, repair_prompt)
                graph = self.graph_engine.materialize(job_id, row, file_content, result, analyzer.name, analyzer.version)
                validation_errors = self._validation_errors_from_graph(graph)
                if (
                    validation_errors
                    and self._should_repair_semantic(graph, validation_errors)
                    and repair_used < repair_attempts
                    and attempt < attempts
                ):
                    raw_preview = self._raw_response_preview(result)
                    diagnostics.append(self._repair_requested_diagnostic(
                        payload,
                        validation_errors,
                        raw_preview,
                        attempt,
                        "Candidate validation rejected all projected facts; retrying with structured repair feedback.",
                    ))
                    repair_used += 1
                    next_repair_prompt = self.repair_prompt_builder.build(
                        payload,
                        raw_preview,
                        validation_errors,
                        attempt + 1,
                        attempts,
                        compact=(attempt + 1 >= attempts),
                    )
                    continue
                if attempt > 1:
                    diagnostics.append({
                        "code": "ANALYSIS_AI_RETRY_SUCCEEDED",
                        "message": f"AI analysis succeeded after {attempt} attempts.",
                        "sourceId": payload.get("sourceId"),
                        "relativePath": payload.get("relativePath"),
                        "attempts": attempt,
                    })
                return graph, diagnostics, {
                    "attempt_count": attempt,
                    "last_attempt_at": self._now(),
                    "last_error_code": None,
                    "last_error_message": None,
                    "last_raw_response_preview": None,
                }
            except KnowledgeError as exc:
                exc.details.setdefault("attempt", attempt)
                exc.details.setdefault("last_attempt_at", self._now())
                if exc.details.get("raw_preview") is not None:
                    exc.details["raw_preview"] = self._raw_preview(exc.details.get("raw_preview"))
                validation_errors = self._validation_errors_from_exception(exc)
                if repair_prompt is not None:
                    repair_failed = {
                        "code": "ANALYSIS_AI_REPAIR_FAILED",
                        "message": "AI analysis repair attempt did not return a usable graph response.",
                        "sourceId": payload.get("sourceId"),
                        "relativePath": payload.get("relativePath"),
                        "attempt": attempt,
                        "rawPreview": exc.details.get("raw_preview"),
                        "validationErrors": [error.to_dict() for error in validation_errors],
                    }
                    self._copy_first_validation_error(repair_failed, validation_errors)
                    diagnostics.append(repair_failed)
                if exc.code not in self.RETRYABLE_AI_CODES or attempt >= attempts:
                    if attempt >= attempts and exc.code in self.RETRYABLE_AI_CODES:
                        exc.details["max_attempts_exceeded"] = True
                    exc.details["diagnostics"] = [*diagnostics, self._attempt_diagnostic(payload, exc, attempt, validation_errors)]
                    raise
                if self._can_repair_error(exc) and repair_used < repair_attempts:
                    repair_used += 1
                    next_repair_prompt = self.repair_prompt_builder.build(
                        payload,
                        self._raw_preview(exc.details.get("raw_preview")),
                        validation_errors,
                        attempt + 1,
                        attempts,
                        compact=(attempt + 1 >= attempts),
                    )
                diagnostics.append(self._attempt_diagnostic(payload, exc, attempt, validation_errors, retrying=True, next_attempt=attempt + 1, max_attempts=attempts))
        raise KnowledgeError("ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED", "AI analysis exceeded maximum attempts")

    def _can_repair_error(self, exc: KnowledgeError) -> bool:
        return exc.code in {
            "ANALYSIS_AI_INVALID_JSON",
            "ANALYSIS_AI_SCHEMA_INVALID",
            "ANALYSIS_AI_EMPTY_RESPONSE",
            "ANALYSIS_AI_BAD_RESPONSE",
        }

    def _should_repair_semantic(self, graph: GraphMaterialization, errors: List[GraphValidationError]) -> bool:
        if self._trusted_ai_symbol_count(graph) > 0:
            return False
        return any(str(error.to_dict().get("severity")) == GraphDiagnosticSeverity.ERROR.value for error in errors)

    def _trusted_ai_symbol_count(self, graph: GraphMaterialization) -> int:
        return sum(
            1
            for node in graph.nodes
            if node.status in {GraphFactStatus.TRUSTED, GraphFactStatus.DERIVED}
            and node.node_kind != GraphNodeKind.FILE
            and not (node.metadata or {}).get("deterministic")
        )

    def _validation_errors_from_graph(self, graph: GraphMaterialization) -> List[GraphValidationError]:
        result: List[GraphValidationError] = []
        for diagnostic in graph.diagnostics:
            validation_error = (diagnostic.metadata or {}).get("validationError")
            if isinstance(validation_error, dict):
                result.append(GraphValidationError.from_dict(validation_error))
        return result

    def _validation_errors_from_exception(self, exc: KnowledgeError) -> List[GraphValidationError]:
        raw_errors = exc.details.get("validation_errors") or exc.details.get("validationErrors") or []
        result = [
            GraphValidationError.from_dict(item)
            for item in raw_errors
            if isinstance(item, dict)
        ]
        if result:
            return result
        code = GraphValidationErrorCode.SCHEMA_INVALID
        stage = GraphDiagnosticStage.SCHEMA_VALIDATE
        expected = "Graph analysis response schema."
        repair_hint = "Return one graph response JSON object matching the schema."
        if exc.code == "ANALYSIS_AI_INVALID_JSON":
            code = GraphValidationErrorCode.INVALID_JSON
            stage = GraphDiagnosticStage.JSON_PARSE
            expected = "Valid JSON object matching the graph analysis schema."
            repair_hint = "Return valid JSON only. Remove markdown, prose, trailing commas, and malformed strings."
        elif exc.code == "ANALYSIS_AI_EMPTY_RESPONSE":
            code = GraphValidationErrorCode.EMPTY_RESPONSE
            stage = GraphDiagnosticStage.JSON_PARSE
            expected = "One JSON object matching the graph analysis schema."
            repair_hint = "Return exactly one JSON object."
        return [GraphValidationError(
            code=code,
            path="$",
            stage=stage,
            message=exc.message,
            expected=expected,
            actual=exc.details.get("raw_preview"),
            repair_hint=repair_hint,
        )]

    def _repair_requested_diagnostic(
        self,
        payload: Dict[str, Any],
        validation_errors: List[GraphValidationError],
        raw_preview: Optional[str],
        attempt: int,
        message: str,
    ) -> Dict[str, Any]:
        diagnostic = {
            "code": "ANALYSIS_AI_VALIDATION_REPAIR_REQUESTED",
            "stage": GraphDiagnosticStage.CANDIDATE_VALIDATE.value,
            "severity": GraphDiagnosticSeverity.WARN.value,
            "message": message,
            "sourceId": payload.get("sourceId"),
            "relativePath": payload.get("relativePath"),
            "attempt": attempt,
            "rawPreview": raw_preview,
            "validationErrors": [error.to_dict() for error in validation_errors],
        }
        self._copy_first_validation_error(diagnostic, validation_errors)
        return diagnostic

    def _raw_response_preview(self, result: Any) -> str:
        if hasattr(result, "dict"):
            value = result.dict()
        else:
            value = result
        return self._raw_preview(json.dumps(value, ensure_ascii=False, default=str)) or ""

    def _mark(
        self,
        row,
        analyzer: OllamaAnalysisClient,
        status: str,
        diagnostics: List[Dict[str, Any]],
        attempt_state: Optional[Dict[str, Any]] = None,
        job_id: Optional[str] = None,
        job_file_status: Optional[str] = None,
    ) -> None:
        self.analysis_store.mark_file(row["id"], self._state(row, analyzer, status, 0, 0, diagnostics, attempt_state, job_id))
        if job_id:
            self.analysis_store.update_job_file(job_id, row["id"], {
                "status": job_file_status or status,
                "attempt_count": (attempt_state or {}).get("attempt_count", 0),
                "completed_at": self._now(),
                "diagnostics": diagnostics,
            })

    def _state(self, row, analyzer: OllamaAnalysisClient, status: str, symbol_count: int, relation_count: int, diagnostics: List[Dict[str, Any]], attempt_state: Optional[Dict[str, Any]] = None, job_id: Optional[str] = None) -> Dict[str, Any]:
        state = {
            "job_id": job_id,
            "source_id": row["source_id"],
            "relative_path": row["relative_path"],
            "content_hash": row["content_hash"],
            "analyzer_name": analyzer.name,
            "analyzer_version": analyzer.version,
            "engine_version": GRAPH_ANALYSIS_ENGINE_VERSION,
            "flow_domain": self._flow_domain(row),
            "status": status,
            "analyzed_at": datetime.now(timezone.utc).isoformat(),
            "symbol_count": symbol_count,
            "relation_count": relation_count,
            "diagnostics": diagnostics,
        }
        state.update(attempt_state or {})
        return state

    def _diagnostic(self, row, exc: Exception, attempt: Optional[int]) -> Dict[str, Any]:
        code = getattr(exc, "code", "ANALYSIS_FILE_FAILED")
        message = str(getattr(exc, "message", "AI analysis failed"))
        if getattr(exc, "details", {}).get("max_attempts_exceeded"):
            code = "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"
            message = f"AI analysis exceeded maximum attempts: {message}"
        validation_errors = self._validation_errors_from_exception(exc) if isinstance(exc, KnowledgeError) else []
        diagnostic = {
            "sourceId": row["source_id"],
            "relativePath": row["relative_path"],
            "code": code,
            "message": message,
        }
        details = getattr(exc, "details", {})
        if attempt:
            diagnostic["attempt"] = attempt
        if details.get("raw_preview"):
            diagnostic["rawPreview"] = self._raw_preview(details.get("raw_preview"))
        if validation_errors:
            diagnostic["validationErrors"] = [error.to_dict() for error in validation_errors]
            self._copy_first_validation_error(diagnostic, validation_errors)
        return diagnostic

    def _attempt_state(self, exc: Exception) -> Dict[str, Any]:
        details = getattr(exc, "details", {})
        code = getattr(exc, "code", "ANALYSIS_FILE_FAILED")
        if details.get("max_attempts_exceeded"):
            code = "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"
        return {
            "attempt_count": details.get("attempt", 0),
            "last_attempt_at": details.get("last_attempt_at") or self._now(),
            "last_error_code": code,
            "last_error_message": str(getattr(exc, "message", "AI analysis failed")),
            "last_raw_response_preview": self._raw_preview(details.get("raw_preview")),
        }

    def _attempt_diagnostic(
        self,
        payload: Dict[str, Any],
        exc: KnowledgeError,
        attempt: int,
        validation_errors: Optional[List[GraphValidationError]] = None,
        retrying: bool = False,
        next_attempt: Optional[int] = None,
        max_attempts: Optional[int] = None,
    ) -> Dict[str, Any]:
        validation_errors = validation_errors or self._validation_errors_from_exception(exc)
        message = exc.message
        if retrying and next_attempt and max_attempts:
            message = f"{exc.message}; retrying analysis attempt {next_attempt} of {max_attempts}."
        diagnostic = {
            "code": exc.code,
            "message": message,
            "sourceId": payload.get("sourceId"),
            "relativePath": payload.get("relativePath"),
            "attempt": attempt,
        }
        raw_preview = self._raw_preview(exc.details.get("raw_preview"))
        if raw_preview:
            diagnostic["rawPreview"] = raw_preview
        if validation_errors:
            diagnostic["validationErrors"] = [error.to_dict() for error in validation_errors]
            self._copy_first_validation_error(diagnostic, validation_errors)
        return diagnostic

    def _copy_first_validation_error(self, diagnostic: Dict[str, Any], validation_errors: List[GraphValidationError]) -> None:
        first = validation_errors[0].to_dict()
        diagnostic["stage"] = first.get("stage")
        diagnostic["validationCode"] = first.get("code")
        for key in ("path", "expected", "actual", "allowedValues", "repairHint"):
            value = first.get(key)
            if value not in (None, [], ""):
                diagnostic[key] = value

    def _raw_preview(self, value: Any) -> Optional[str]:
        if value is None:
            return None
        return str(value)[:MAX_RAW_PREVIEW_CHARS]

    def _now(self) -> str:
        return datetime.now(timezone.utc).isoformat()

    def _language(self, row: Any) -> str:
        return self._row_value(row, "language") or "unknown"

    def _flow_domain(self, row: Any) -> str:
        return self._row_value(row, "flow_domain") or classify_flow_domain(row["relative_path"], row["extension"]).value

    def _row_value(self, row: Any, key: str) -> Optional[Any]:
        try:
            if hasattr(row, "keys") and key not in row.keys():
                return None
            return row[key]
        except (KeyError, IndexError):
            return None
