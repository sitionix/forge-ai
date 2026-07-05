from __future__ import annotations

import asyncio
import hashlib
import inspect
import json
import logging
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Protocol

from knowledge_service.analysis_graph_contract import GraphContractProvider
from knowledge_service.analysis_client import OllamaAnalysisClient
from knowledge_service.analysis_response_parser import MAX_RAW_PREVIEW_CHARS
from knowledge_service.analysis_schema import AnalysisBuildRequest, RetryFailedAnalysisRequest
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.anchor_enrichment import AnchorAwareGraphValidator
from knowledge_service.analyzer_runtime import AnalyzerRuntime, ExtractorRegistry
from knowledge_service.config import AppConfig
from knowledge_service.errors import KnowledgeError
from knowledge_service.graph_analysis import GraphAnalysisEngine
from knowledge_service.graph_schema import GraphAnalysisResult
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.snippet_extractor import SnippetExtractor
from knowledge_service.structural_analysis import GRAPH_ENGINE_VERSION, StaticGraphMaterializer, StructuralAnalysisEngine


class AnalysisProvider(Protocol):
    name: str
    version: str

    async def analyze(
        self,
        payload: Dict[str, Any],
        line_count: int,
        repair_prompt: Optional[str] = None,
    ) -> GraphAnalysisResult: ...


class AnalysisSupervisor:
    RETRYABLE_AI_CODES = {
        "ANALYSIS_AI_BAD_RESPONSE",
        "ANALYSIS_AI_INVALID_JSON",
        "ANALYSIS_AI_SCHEMA_INVALID",
        "ANALYSIS_AI_EMPTY_RESPONSE",
        "ANALYSIS_AI_TRANSPORT_ERROR",
    }

    def __init__(
        self,
        inventory_store: InventoryStore,
        config: AppConfig,
        analysis_provider: Optional[AnalysisProvider] = None,
        logger: Optional[logging.Logger] = None,
    ):
        self.inventory_store = inventory_store
        self.config = config
        self.analysis_store = AnalysisStore(inventory_store.db_path)
        self.analysis_provider = analysis_provider
        self.logger = logger or logging.getLogger("knowledge_service.analysis")
        self.snippets = SnippetExtractor()
        self.graph_engine = GraphAnalysisEngine()
        self.structural_engine = StructuralAnalysisEngine()
        self.static_materializer = StaticGraphMaterializer()
        self.anchor_validator = AnchorAwareGraphValidator()
        self.graph_contract_provider = GraphContractProvider()
        self.analyzer_runtime = AnalyzerRuntime(
            self.graph_contract_provider.policy,
            extractor_registry=ExtractorRegistry(self.structural_engine, self.static_materializer),
            anchor_validator=self.anchor_validator,
        )
        self._admission_lock: Optional[asyncio.Lock] = None
        self._queue: Optional[asyncio.Queue[tuple[str, AnalysisBuildRequest, AnalysisProvider, Optional[List[Any]], str, bool]]] = None
        self._workers: list[asyncio.Task[None]] = []
        self._running: dict[str, asyncio.Task[None]] = {}
        self._stopping = False
        self._started = False

    async def start(self, request: AnalysisBuildRequest, client: Optional[AnalysisProvider] = None) -> Dict[str, Any]:
        if self._admission_lock is None or self._queue is None:
            raise KnowledgeError("ANALYSIS_SUPERVISOR_NOT_STARTED", "Knowledge analysis supervisor is not started")
        async with self._admission_lock:
            if self._stopping:
                raise KnowledgeError("ANALYSIS_SUPERVISOR_STOPPING", "Knowledge analysis supervisor is shutting down")
            active = self.analysis_store.active_job()
            if active:
                raise KnowledgeError("ANALYSIS_JOB_ALREADY_RUNNING", "Knowledge analysis job already running")
            if self._queue.full():
                raise KnowledgeError("ANALYSIS_QUEUE_FULL", "Knowledge analysis queue is full")
            selection = str(request.selection or "DEFAULT").upper()
            selected_rows: Optional[List[Any]] = None
            failure_breakdown: Dict[str, int] = {}
            job_files_precreated = False
            if selection == "FAILED_ONLY":
                source_ids = sorted(set(request.sourceIds or []))
                selected_rows = list(self.analysis_store.current_failed_inventory_rows(source_ids or None))
                failure_breakdown = self.analysis_store.current_failure_breakdown(source_ids or None)
                if not selected_rows:
                    return {
                        "result": "NO_FAILED_FILES",
                        "job": {
                            "jobId": None,
                            "selection": "FAILED_ONLY",
                            "status": "NO_FAILED_FILES",
                            "selectedFileCount": 0,
                        },
                        "jobId": None,
                        "selection": "FAILED_ONLY",
                        "status": "NO_FAILED_FILES",
                        "selectedFileCount": 0,
                        "sourceIds": source_ids,
                        "failureCodeBreakdown": {},
                        "analysisState": self.analysis_store.current_analysis_state(source_ids or None),
                    }
                request = AnalysisBuildRequest(
                    sourceIds=sorted({row["source_id"] for row in selected_rows}),
                    groups=[],
                    force=True,
                    maxFiles=None,
                    concurrency=request.concurrency,
                    selection="FAILED_ONLY",
                )
            job_id = str(uuid.uuid4())
            now = self._now()
            job = {
                "jobId": job_id,
                "mode": selection,
                "status": "QUEUED",
                "startedAt": None,
                "completedAt": None,
                "sourceCount": len(request.sourceIds),
                "fileCount": len(selected_rows) if selected_rows is not None else 0,
                "processedFileCount": 0,
                "failedFileCount": 0,
                "currentSourceId": request.sourceIds[0] if request.sourceIds else None,
                "currentRelativePath": None,
                "sourceIds": sorted(set(request.sourceIds)),
                "lastProgressAt": now if selected_rows is not None else None,
                "diagnostics": [],
                "engineVersion": GRAPH_ENGINE_VERSION,
            }
            if selected_rows is None:
                self.analysis_store.create_job(job)
            else:
                flow_domain_by_file_id = {int(row["id"]): self._row_flow_domain(row) for row in selected_rows}
                self.analysis_store.create_job_with_pending_files(
                    job,
                    selected_rows,
                    flow_domain_by_file_id,
                    GRAPH_ENGINE_VERSION,
                    reset_failed_current_state=True,
                )
                job_files_precreated = True
            self._log("job_created", jobId=job_id, sourceId=None, processed=0, failed=0)
            analyzer = (
                client
                or self.analysis_provider
                or OllamaAnalysisClient(
                    self.config.analysis_base_url,
                    self.config.analysis_model,
                    min(self.config.analysis_ai_call_timeout_seconds, self.config.analysis_per_file_timeout_seconds),
                    self.config.analysis_prompt_path,
                    self.config.analysis_context_tokens,
                )
            )
            self._queue.put_nowait((job_id, request, analyzer, selected_rows, selection, job_files_precreated))
            analysis_state = self.analysis_store.current_analysis_state(request.sourceIds or None)
        response: Dict[str, Any] = {"jobId": job_id, "selection": selection, "status": "QUEUED", "message": "Knowledge analysis job queued"}
        if selection == "FAILED_ONLY":
            response.update(
                {
                    "job": {
                        "jobId": job_id,
                        "selection": "FAILED_ONLY",
                        "status": "QUEUED",
                        "selectedFileCount": len(selected_rows or []),
                    },
                    "selectedFileCount": len(selected_rows or []),
                    "sourceIds": request.sourceIds,
                    "failureCodeBreakdown": failure_breakdown,
                    "analysisState": analysis_state,
                }
            )
        return response

    async def retry_failed(self, request: RetryFailedAnalysisRequest, client: Optional[AnalysisProvider] = None) -> Dict[str, Any]:
        return await self.start(
            AnalysisBuildRequest(
                sourceIds=request.sourceIds,
                groups=[],
                force=True,
                maxFiles=None,
                concurrency=request.concurrency,
                selection="FAILED_ONLY",
            ),
            client,
        )

    async def stop(self, job_id: str) -> Dict[str, Any]:
        job = self.analysis_store.request_stop(job_id)
        if job is None:
            raise KnowledgeError("ANALYSIS_JOB_NOT_FOUND", "Analysis job not found")
        running = self._running.get(job_id)
        if running is not None:
            running.cancel()
        self._log(
            "stop_requested",
            jobId=job_id,
            sourceId=job.get("currentSourceId"),
            processed=job.get("processedFileCount", 0),
            failed=job.get("failedFileCount", 0),
        )
        return {
            "jobId": job["jobId"],
            "status": job["status"],
            "message": "Knowledge analysis stop requested" if job["status"] == "STOP_REQUESTED" else "Knowledge analysis job is not running",
        }

    async def start_lifespan(self) -> None:
        if self._started:
            return
        self._started = True
        self._admission_lock = asyncio.Lock()
        self._queue = asyncio.Queue(maxsize=max(1, self.config.analysis_queue_capacity))
        self._stopping = False
        self.analysis_store.mark_interrupted_jobs()
        worker_count = max(1, self.config.analysis_concurrency)
        self._workers = [asyncio.create_task(self._worker(index), name=f"knowledge-analysis-worker-{index}") for index in range(worker_count)]

    async def shutdown(self) -> None:
        self._stopping = True
        for task in list(self._running.values()):
            task.cancel()
        for worker in self._workers:
            worker.cancel()
        tasks = [*self._workers, *self._running.values()]
        if tasks:
            try:
                await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True), timeout=self.config.analysis_shutdown_grace_seconds)
            except asyncio.TimeoutError:
                self.logger.warning("Knowledge analysis supervisor shutdown exceeded grace budget")
        self._workers.clear()
        self._running.clear()
        self.analysis_store.mark_interrupted_jobs()
        provider = self.analysis_provider
        close = getattr(provider, "aclose", None)
        if close is not None:
            await close()

    async def _worker(self, index: int) -> None:
        while not self._stopping:
            try:
                if self._queue is None:
                    return
                job_id, request, analyzer, selected_rows, mode, job_files_precreated = await self._queue.get()
            except asyncio.CancelledError:
                return
            task = asyncio.create_task(
                self._run(job_id, request, analyzer, selected_rows=selected_rows, mode=mode, job_files_precreated=job_files_precreated),
                name=f"knowledge-analysis-job-{job_id}",
            )
            self._running[job_id] = task
            try:
                await task
            except asyncio.CancelledError:
                self._mark_job_stopped(job_id)
            finally:
                self._running.pop(job_id, None)
                if self._queue is not None:
                    self._queue.task_done()

    async def _run(
        self,
        job_id: str,
        request: AnalysisBuildRequest,
        analyzer: AnalysisProvider,
        selected_rows: Optional[List[Any]] = None,
        mode: str = "FULL",
        job_files_precreated: bool = False,
    ) -> None:
        started_at = datetime.now(timezone.utc).isoformat()
        started_monotonic = datetime.now(timezone.utc)
        if selected_rows is None:
            rows, _ = self.inventory_store.search_rows(request.sourceIds, request.groups)
        else:
            rows = list(selected_rows)
        scoped_source_ids = sorted({row["source_id"] for row in rows}) or request.sourceIds
        if selected_rows is None:
            self.analysis_store.cleanup_stale_files(scoped_source_ids or None)
        if selected_rows is None and not request.force:
            unchanged_ids = self.analysis_store.unchanged_file_ids(rows, analyzer.name, analyzer.version, GRAPH_ENGINE_VERSION)
            rows = [row for row in rows if row["id"] not in unchanged_ids]
        if request.maxFiles is not None:
            rows = rows[: max(0, request.maxFiles)]
        flow_domain_by_file_id = {int(row["id"]): self._row_flow_domain(row) for row in rows}
        if not job_files_precreated:
            self.analysis_store.create_job_files(job_id, rows, flow_domain_by_file_id, GRAPH_ENGINE_VERSION)
        if self._stop_requested(job_id):
            self._mark_job_stopped(job_id)
            return
        self.analysis_store.update_job(
            job_id,
            {
                "status": "RUNNING",
                "startedAt": started_at,
                "lastProgressAt": started_at,
                "sourceCount": len({row["source_id"] for row in rows}),
                "fileCount": len(rows),
                "sourceIds": scoped_source_ids,
                "mode": mode,
            },
        )
        self._log("job_started", jobId=job_id, sourceId=None, processed=0, failed=0)
        processed = failed = 0
        diagnostics: List[Dict[str, Any]] = []
        try:
            for row in rows:
                if self._stop_requested(job_id):
                    self._mark_job_stopped(job_id, diagnostics)
                    return
                if not request.force and self.analysis_store.unchanged(
                    int(row["id"]),
                    row["content_hash"],
                    analyzer.name,
                    analyzer.version,
                    GRAPH_ENGINE_VERSION,
                ):
                    processed += 1
                    self.analysis_store.update_job_file(
                        job_id,
                        int(row["id"]),
                        "SKIPPED_UNCHANGED",
                        analysis_file_id=int(row["id"]),
                        flow_domain=flow_domain_by_file_id.get(int(row["id"]), self._row_flow_domain(row)),
                        engine_version=GRAPH_ENGINE_VERSION,
                        completed=True,
                    )
                    self.analysis_store.update_job(
                        job_id,
                        {
                            "processedFileCount": processed,
                            "failedFileCount": failed,
                            "lastProgressAt": self._now(),
                        },
                    )
                    self._log(
                        "file_skipped_unchanged",
                        jobId=job_id,
                        sourceId=row["source_id"],
                        relativePath=row["relative_path"],
                        processed=processed,
                        failed=failed,
                    )
                    continue
                self.analysis_store.update_job(
                    job_id,
                    {
                        "currentSourceId": row["source_id"],
                        "currentRelativePath": row["relative_path"],
                        "lastProgressAt": self._now(),
                    },
                )
                self._log("file_started", jobId=job_id, sourceId=row["source_id"], relativePath=row["relative_path"], processed=processed, failed=failed)
                flow_domain = flow_domain_by_file_id.get(int(row["id"]), self._row_flow_domain(row))
                self.analysis_store.update_job_file(
                    job_id,
                    int(row["id"]),
                    "RUNNING",
                    flow_domain=flow_domain,
                    engine_version=GRAPH_ENGINE_VERSION,
                    started=True,
                )
                metadata = json.loads(row["metadata_json"])
                lines = self.snippets.read_lines(row["absolute_path"], metadata.get("absoluteRoot") or row["source_path"])
                if lines is None:
                    processed += 1
                    failed += 1
                    file_diagnostics = [{"code": "FILE_UNREADABLE", "message": "Indexed file could not be read safely"}]
                    self._mark(row, analyzer, "FAILED", file_diagnostics, flow_domain=flow_domain)
                    self.analysis_store.update_job_file(
                        job_id,
                        int(row["id"]),
                        "FAILED",
                        diagnostics=file_diagnostics,
                        flow_domain=flow_domain,
                        engine_version=GRAPH_ENGINE_VERSION,
                        completed=True,
                    )
                    self.analysis_store.update_job(
                        job_id,
                        {
                            "processedFileCount": processed,
                            "failedFileCount": failed,
                            "lastProgressAt": self._now(),
                        },
                    )
                    self._log(
                        "file_failed",
                        jobId=job_id,
                        sourceId=row["source_id"],
                        relativePath=row["relative_path"],
                        processed=processed,
                        failed=failed,
                        errorCode="FILE_UNREADABLE",
                    )
                    self._log(
                        "progress_updated", jobId=job_id, sourceId=row["source_id"], relativePath=row["relative_path"], processed=processed, failed=failed
                    )
                    continue
                try:
                    file_started_at = datetime.now(timezone.utc)
                    row_data = dict(row)
                    runtime_result = await self.analyzer_runtime.execute(row_data, metadata, lines, analyzer, self._analyze_with_retry)
                    if self._stop_requested(job_id):
                        self._mark_job_stopped(job_id, diagnostics)
                        return
                    elapsed_seconds = self._elapsed_seconds(file_started_at)
                    graph_result = runtime_result.graph_result
                    if elapsed_seconds > self.config.analysis_per_file_timeout_seconds:
                        graph_result.diagnostics.append(
                            {
                                "code": "ANALYSIS_FILE_TIMEOUT",
                                "message": "File analysis exceeded configured per-file timeout.",
                                "sourceId": row["source_id"],
                                "relativePath": row["relative_path"],
                                "stage": "FILE_ANALYSIS",
                                "severity": "WARN",
                                "elapsedSeconds": elapsed_seconds,
                            }
                        )
                    graph_diagnostics = self._file_diagnostics_from_graph(graph_result)
                    graph = self.graph_engine.materialize(row_data, job_id, analyzer.name, analyzer.version, graph_result, lines)
                    file_diagnostics = self._merge_file_diagnostics(runtime_result.diagnostics, graph_diagnostics)
                    self.analysis_store.replace_file_graph_analysis(
                        row["id"],
                        self._state(
                            row,
                            analyzer,
                            "ANALYZED",
                            file_diagnostics,
                            runtime_result.attempt_state,
                            flow_domain=flow_domain,
                        ),
                        graph,
                    )
                    job_file_status = "ANALYZED_WITH_DIAGNOSTICS" if file_diagnostics else "ANALYZED"
                    self.analysis_store.update_job_file(
                        job_id,
                        int(row["id"]),
                        job_file_status,
                        analysis_file_id=int(row["id"]),
                        attempt_count=runtime_result.attempt_state.get("attempt_count", 0),
                        diagnostics=file_diagnostics,
                        line_count=len(lines),
                        flow_domain=flow_domain,
                        engine_version=GRAPH_ENGINE_VERSION,
                        completed=True,
                    )
                    processed += 1
                    self._log("file_completed", jobId=job_id, sourceId=row["source_id"], relativePath=row["relative_path"], processed=processed, failed=failed)
                except Exception as exc:
                    if self._stop_requested(job_id):
                        self._mark_job_stopped(job_id, diagnostics)
                        return
                    processed += 1
                    failed += 1
                    diag = self._diagnostic(row, exc, getattr(exc, "details", {}).get("attempt"))
                    diagnostics.append(diag)
                    file_diagnostics = [*getattr(exc, "details", {}).get("diagnostics", []), diag]
                    self._mark(row, analyzer, "FAILED", file_diagnostics, self._attempt_state(exc), flow_domain=flow_domain)
                    self.analysis_store.update_job_file(
                        job_id,
                        int(row["id"]),
                        "FAILED",
                        attempt_count=self._attempt_state(exc).get("attempt_count", 0),
                        diagnostics=file_diagnostics,
                        line_count=len(lines),
                        flow_domain=flow_domain,
                        engine_version=GRAPH_ENGINE_VERSION,
                        completed=True,
                    )
                    self._log(
                        "file_failed",
                        jobId=job_id,
                        sourceId=row["source_id"],
                        relativePath=row["relative_path"],
                        processed=processed,
                        failed=failed,
                        errorCode=diag.get("code"),
                    )
                self.analysis_store.update_job(
                    job_id,
                    {
                        "processedFileCount": processed,
                        "failedFileCount": failed,
                        "diagnostics": diagnostics[-20:],
                        "lastProgressAt": self._now(),
                    },
                )
                self._log("progress_updated", jobId=job_id, sourceId=row["source_id"], relativePath=row["relative_path"], processed=processed, failed=failed)
            if self._stop_requested(job_id):
                self._mark_job_stopped(job_id, diagnostics)
                return
            completed_at = datetime.now(timezone.utc)
            self.analysis_store.update_job(
                job_id,
                {
                    "status": "COMPLETED",
                    "completedAt": completed_at.isoformat(),
                    "lastProgressAt": self._now(),
                    "currentSourceId": None,
                    "currentRelativePath": None,
                },
            )
            self._log(
                "job_completed",
                jobId=job_id,
                sourceId=None,
                processed=processed,
                failed=failed,
                elapsedMs=int((completed_at - started_monotonic).total_seconds() * 1000),
            )
        except Exception as exc:
            diagnostics.append({"code": "ANALYSIS_JOB_FAILED", "message": str(exc)})
            completed_at = datetime.now(timezone.utc)
            self.analysis_store.update_job(
                job_id,
                {
                    "status": "FAILED",
                    "completedAt": completed_at.isoformat(),
                    "lastProgressAt": self._now(),
                    "diagnostics": diagnostics[-20:],
                },
            )
            self._log(
                "job_failed",
                jobId=job_id,
                sourceId=None,
                processed=processed,
                failed=failed,
                elapsedMs=int((completed_at - started_monotonic).total_seconds() * 1000),
                errorCode="ANALYSIS_JOB_FAILED",
            )

    def _stop_requested(self, job_id: str) -> bool:
        return self.analysis_store.stop_requested(job_id)

    def _mark_job_stopped(self, job_id: str, diagnostics: Optional[List[Dict[str, Any]]] = None) -> None:
        job = self.analysis_store.job(job_id)
        self.analysis_store.stop_incomplete_job_files(job_id)
        merged = [*(job or {}).get("diagnostics", []), *(diagnostics or [])]
        if not any(item.get("code") == "ANALYSIS_JOB_STOPPED" for item in merged):
            merged.append(
                {
                    "code": "ANALYSIS_JOB_STOPPED",
                    "message": "Analysis job stopped before processing the next file.",
                }
            )
        self.analysis_store.update_job(
            job_id,
            {
                "status": "STOPPED",
                "completedAt": datetime.now(timezone.utc).isoformat(),
                "lastProgressAt": self._now(),
                "currentSourceId": None,
                "currentRelativePath": None,
                "diagnostics": merged[-20:],
            },
        )
        self._log(
            "job_completed",
            jobId=job_id,
            sourceId=job.get("currentSourceId") if job else None,
            processed=job.get("processedFileCount", 0) if job else 0,
            failed=job.get("failedFileCount", 0) if job else 0,
            errorCode="ANALYSIS_JOB_STOPPED",
        )

    def _row_flow_domain(self, row: Any) -> str:
        configured = str(self._row_value(row, "flow_domain") or "").strip().upper()
        return configured or "UNKNOWN"

    def _row_value(self, row: Any, key: str) -> Any:
        if isinstance(row, dict):
            return row.get(key)
        try:
            if key in row.keys():
                return row[key]
        except AttributeError:
            return None
        return None

    async def _analyze_with_retry(self, analyzer: AnalysisProvider, payload: Dict[str, Any], line_count: int):
        attempts = max(1, self.config.analysis_max_attempts_per_file)
        repair_attempts = max(0, self.config.analysis_repair_attempts_per_file)
        diagnostics: List[Dict[str, Any]] = []
        repair_used = 0
        last_error: KnowledgeError | None = None
        for attempt in range(1, attempts + 1):
            repair_prompt = None
            if (
                last_error is not None
                and last_error.code in {"ANALYSIS_AI_INVALID_JSON", "ANALYSIS_AI_SCHEMA_INVALID", "ANALYSIS_AI_EMPTY_RESPONSE", "ANALYSIS_AI_BAD_RESPONSE"}
                and repair_used < repair_attempts
            ):
                repair_prompt = self._repair_prompt(payload, last_error, attempt, attempts)
                repair_used += 1
            try:
                pending = analyzer.analyze(payload, line_count, repair_prompt)
                result = await pending if inspect.isawaitable(pending) else pending
                if attempt > 1:
                    retry_success = {
                        "code": "ANALYSIS_AI_RETRY_SUCCEEDED",
                        "message": f"AI analysis succeeded after {attempt} attempts.",
                        "sourceId": payload.get("sourceId"),
                        "relativePath": payload.get("relativePath"),
                        "attempts": attempt,
                    }
                    if last_error is not None:
                        retry_success["metadata"] = {
                            "previousErrorSummary": self._error_summary(last_error),
                            "previousErrorDetails": self._bounded_error_details(self._error_details(last_error))[:5],
                        }
                    diagnostics.append(retry_success)
                return (
                    result,
                    diagnostics,
                    {
                        "attempt_count": attempt,
                        "last_attempt_at": self._now(),
                        "last_error_code": None,
                        "last_error_message": None,
                        "last_raw_response_preview": None,
                    },
                )
            except KnowledgeError as exc:
                exc.details.setdefault("attempt", attempt)
                exc.details.setdefault("last_attempt_at", self._now())
                if exc.details.get("raw_preview") is not None:
                    exc.details["raw_preview"] = self._raw_preview(exc.details.get("raw_preview"))
                if exc.details.get("error_details") is not None:
                    exc.details["error_details"] = self._bounded_error_details(self._error_details(exc))
                last_error = exc
                if exc.code not in self.RETRYABLE_AI_CODES or attempt >= attempts:
                    if attempt >= attempts and exc.code in self.RETRYABLE_AI_CODES:
                        exc.details["max_attempts_exceeded"] = True
                    exc.details["diagnostics"] = [*diagnostics, self._attempt_diagnostic(payload, exc, attempt)]
                    raise
                retry_diagnostic = {
                    "code": exc.code,
                    "message": f"{exc.message}; retrying analysis attempt {attempt + 1} of {attempts}.",
                    "sourceId": payload.get("sourceId"),
                    "relativePath": payload.get("relativePath"),
                    "attempt": attempt,
                    "rawPreview": exc.details.get("raw_preview"),
                }
                metadata = self._error_metadata(exc)
                if metadata:
                    retry_diagnostic["metadata"] = metadata
                diagnostics.append(retry_diagnostic)
        raise KnowledgeError("ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED", "AI analysis exceeded maximum attempts")

    def _repair_prompt(self, payload: Dict[str, Any], last_error: KnowledgeError, attempt: int, attempts: int) -> str:
        details = self._bounded_error_details(self._error_details(last_error))
        lines = [
            "Your previous response was invalid.",
            f"You are repairing attempt {attempt} of {attempts} for this same file.",
            "Exact errors to correct:",
        ]
        if details:
            lines.extend(f"- {self._format_error_detail(detail)}" for detail in details[:10])
        else:
            lines.append(f"- {last_error.code}: {last_error.message}")
        preview = self._error_preview(last_error)
        if preview:
            lines.extend(["Bounded invalid response preview:", preview])
        contract = self.graph_contract_provider.resolve_payload(payload)
        lines.extend(
            [
                "Allowed graph contract values:",
                self._json_for_prompt(
                    {
                        "nodeKind": list(contract.allowed_node_kinds),
                        "edgeType": list(contract.allowed_edge_types),
                        "claimKind": list(contract.allowed_claim_kinds),
                        "status": list(contract.allowed_statuses),
                        "factOrigin": list(contract.allowed_origins),
                        "evidenceKind": list(contract.allowed_evidence_kinds),
                        "resolutionStatus": list(contract.allowed_resolution_statuses),
                    },
                    limit=1200,
                ),
            ]
        )
        lines.extend(
            [
                "Return ONLY corrected JSON matching the requested schema.",
                "No markdown.",
                "No prose.",
                "No comments.",
                "Keep claims grounded in the provided contentLines and source evidence.",
            ]
        )
        return "\n".join(lines)

    def _format_error_detail(self, detail: Dict[str, Any]) -> str:
        error_type = str(detail.get("errorType") or "ERROR")
        if error_type == "JSON_PARSE_ERROR":
            position = detail.get("charPosition")
            position_text = f" char {position}" if position is not None else ""
            truncated = detail.get("responseTruncated")
            truncated_text = f" responseTruncated={truncated}." if truncated is not None else ""
            return (
                f"JSON parse error at line {detail.get('line')} column {detail.get('column')}{position_text}: "
                f"{detail.get('message')}.{truncated_text}"
            )
        if error_type == "SCHEMA_VALIDATION_ERROR":
            path = detail.get("jsonPath") or "$"
            missing = detail.get("missingRequiredField")
            if missing:
                return f"{path} is missing required field {missing}. Expected: {detail.get('expected')}."
            text = f"{path}"
            if detail.get("field"):
                text += f" ({detail.get('field')})"
            if "actual" in detail:
                text += f" = {self._json_for_prompt(detail.get('actual'))}"
            if detail.get("expected"):
                text += f" is invalid. Expected: {detail.get('expected')}."
            allowed = detail.get("allowedValues") or []
            if allowed:
                text += f" Allowed values: {self._json_for_prompt(allowed)}."
            if detail.get("message"):
                text += f" {detail.get('message')}"
            return text
        if error_type == "GRAPH_VALIDATION_ERROR":
            text = f"{detail.get('jsonPath') or detail.get('graphEntityId') or '$'}: {detail.get('reason') or detail.get('message')}"
            allowed = detail.get("allowedValues") or []
            if allowed:
                text += f" Allowed values: {self._json_for_prompt(allowed)}."
            return text
        return f"{error_type}: {detail.get('message') or detail}"

    def _json_for_prompt(self, value: Any, limit: int = 240) -> str:
        text = json.dumps(value, ensure_ascii=False, default=str)
        if len(text) > limit:
            return text[:limit].rstrip() + "..."
        return text

    def _error_details(self, exc: Exception) -> List[Dict[str, Any]]:
        details = getattr(exc, "details", {}) or {}
        raw_details = (
            details.get("error_details")
            or details.get("errorDetails")
            or details.get("validation_errors")
            or details.get("validationErrors")
            or []
        )
        if isinstance(raw_details, dict):
            raw_details = [raw_details]
        return [dict(item) for item in raw_details if isinstance(item, dict)]

    def _bounded_error_details(self, details: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        bounded: List[Dict[str, Any]] = []
        for detail in details[:25]:
            item = dict(detail)
            for key in ("rawPreview", "actual", "message", "reason"):
                if key in item and isinstance(item[key], str):
                    item[key] = self._raw_preview(item[key])
            bounded.append(item)
        return bounded

    def _error_preview(self, exc: Exception) -> Optional[str]:
        for detail in self._error_details(exc):
            preview = detail.get("rawPreview")
            if preview:
                return self._raw_preview(preview)
        return self._raw_preview((getattr(exc, "details", {}) or {}).get("raw_preview"))

    def _error_summary(self, exc: Exception) -> str:
        details = self._bounded_error_details(self._error_details(exc))
        if details:
            summary = "; ".join(self._format_error_detail(detail) for detail in details[:3])
            if len(details) > 3:
                summary = f"{summary}; and {len(details) - 3} more error(s)"
            return self._raw_preview(summary) or summary
        return f"{getattr(exc, 'code', 'ANALYSIS_FILE_FAILED')}: {getattr(exc, 'message', str(exc))}"

    def _error_metadata(self, exc: Exception) -> Dict[str, Any]:
        details = self._bounded_error_details(self._error_details(exc))
        metadata: Dict[str, Any] = {}
        if details:
            metadata["errorDetails"] = details
            metadata["errorSummary"] = self._error_summary(exc)
            first = details[0]
            for key in (
                "errorType",
                "message",
                "line",
                "column",
                "charPosition",
                "responseTruncated",
                "jsonPath",
                "field",
                "actual",
                "expected",
                "allowedValues",
                "missingRequiredField",
                "reason",
                "graphEntityId",
            ):
                if first.get(key) is not None:
                    metadata[key] = first.get(key)
            if first.get("rawPreview"):
                metadata["errorPreview"] = self._raw_preview(first.get("rawPreview"))
        raw_preview = self._raw_preview((getattr(exc, "details", {}) or {}).get("raw_preview"))
        if raw_preview:
            metadata.setdefault("rawPreview", raw_preview)
        return metadata

    def _file_diagnostics_from_graph(self, graph_result: GraphAnalysisResult) -> List[Dict[str, Any]]:
        result: List[Dict[str, Any]] = []
        for diagnostic in graph_result.diagnostics or []:
            result.append(
                {
                    key: value
                    for key, value in diagnostic.items()
                    if key in {"code", "message", "severity", "stage", "sourceId", "relativePath", "attempt", "rawPreview"}
                }
            )
        return result

    def _merge_file_diagnostics(self, primary: List[Dict[str, Any]], secondary: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        result = [dict(item) for item in primary]
        seen = {(item.get("code"), item.get("stage"), item.get("attempt")) for item in result}
        for item in secondary:
            key = (item.get("code"), item.get("stage"), item.get("attempt"))
            if key in seen:
                continue
            seen.add(key)
            result.append(dict(item))
        return result

    def _mark(
        self,
        row,
        analyzer: AnalysisProvider,
        status: str,
        diagnostics: List[Dict[str, Any]],
        attempt_state: Optional[Dict[str, Any]] = None,
        flow_domain: Optional[str] = None,
    ) -> None:
        state = self._state(row, analyzer, status, diagnostics, attempt_state, flow_domain=flow_domain)
        if status == "FAILED":
            self.analysis_store.mark_file_failed_attempt(row["id"], state)
            return
        self.analysis_store.mark_file(row["id"], state)

    def _state(
        self,
        row,
        analyzer: AnalysisProvider,
        status: str,
        diagnostics: List[Dict[str, Any]],
        attempt_state: Optional[Dict[str, Any]] = None,
        flow_domain: Optional[str] = None,
    ) -> Dict[str, Any]:
        state = {
            "source_id": row["source_id"],
            "relative_path": row["relative_path"],
            "content_hash": row["content_hash"],
            "analyzer_name": analyzer.name,
            "analyzer_version": analyzer.version,
            "engine_version": GRAPH_ENGINE_VERSION,
            "flow_domain": flow_domain or self._row_flow_domain(row),
            "status": status,
            "analyzed_at": datetime.now(timezone.utc).isoformat(),
            "diagnostics": diagnostics,
        }
        state.update(attempt_state or {})
        return state

    def _stable_id(self, *parts: str) -> str:
        digest = hashlib.sha256("\0".join(parts).encode("utf-8")).hexdigest()[:24]
        return f"{parts[0]}:{digest}"

    def _diagnostic(self, row, exc: Exception, attempt: Optional[int]) -> Dict[str, Any]:
        code = getattr(exc, "code", "ANALYSIS_FILE_FAILED")
        message = str(getattr(exc, "message", "AI analysis failed"))
        details = getattr(exc, "details", {})
        if getattr(exc, "details", {}).get("max_attempts_exceeded"):
            code = "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"
            message = f"AI analysis exceeded maximum attempts: {message}"
        diagnostic = {
            "sourceId": row["source_id"],
            "relativePath": row["relative_path"],
            "code": code,
            "message": message,
        }
        if details.get("stage"):
            diagnostic["stage"] = details["stage"]
        if details.get("severity"):
            diagnostic["severity"] = details["severity"]
        metadata = {key: details[key] for key in ("exceptionType", "sqliteMessage", "table", "operation") if key in details}
        metadata.update(self._error_metadata(exc))
        if not metadata and code == "ANALYSIS_FILE_FAILED":
            metadata = {"exceptionType": type(exc).__name__, "rootCause": str(exc)[:MAX_RAW_PREVIEW_CHARS]}
        if metadata:
            diagnostic["metadata"] = metadata
        if attempt:
            diagnostic["attempt"] = attempt
        if details.get("raw_preview"):
            diagnostic["rawPreview"] = self._raw_preview(details.get("raw_preview"))
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

    def _attempt_diagnostic(self, payload: Dict[str, Any], exc: KnowledgeError, attempt: int) -> Dict[str, Any]:
        diagnostic = {
            "code": exc.code,
            "message": exc.message,
            "sourceId": payload.get("sourceId"),
            "relativePath": payload.get("relativePath"),
            "attempt": attempt,
        }
        raw_preview = self._raw_preview(exc.details.get("raw_preview"))
        if raw_preview:
            diagnostic["rawPreview"] = raw_preview
        metadata = self._error_metadata(exc)
        if metadata:
            diagnostic["metadata"] = metadata
        return diagnostic

    def _raw_preview(self, value: Any) -> Optional[str]:
        if value is None:
            return None
        return str(value)[:MAX_RAW_PREVIEW_CHARS]

    def _now(self) -> str:
        return datetime.now(timezone.utc).isoformat()

    def _elapsed_seconds(self, started_at: datetime) -> int:
        return max(0, int((datetime.now(timezone.utc) - started_at).total_seconds()))

    def _log(self, event: str, **fields: Any) -> None:
        payload = {
            "event": event,
            "jobId": fields.get("jobId"),
            "sourceId": fields.get("sourceId"),
            "relativePath": fields.get("relativePath"),
            "processed": fields.get("processed", 0),
            "failed": fields.get("failed", 0),
            "elapsedMs": fields.get("elapsedMs"),
            "errorCode": fields.get("errorCode"),
        }
        self.logger.info(event, extra={"knowledge": {key: value for key, value in payload.items() if value is not None}})
