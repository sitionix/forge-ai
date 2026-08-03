from __future__ import annotations

import asyncio
import hashlib
import inspect
import json
import logging
import uuid
from collections.abc import Awaitable
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Protocol

from knowledge_service.analysis_graph_contract import GraphContractProvider
from knowledge_service.analysis_progress import CurrentFileTargetProgressTracker
from knowledge_service.analysis_response_parser import MAX_RAW_PREVIEW_CHARS
from knowledge_service.analysis_runtime_events import AnalysisRuntimeContext, analysis_runtime_context
from knowledge_service.analysis_schema import AnalysisBuildRequest, RetryFailedAnalysisRequest
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.analyzer_runtime import AnalyzerProvider, AnalyzerRuntime, ExtractorRegistry
from knowledge_service.anchor_enrichment import AnchorAwareGraphValidator
from knowledge_service.config import AppConfig
from knowledge_service.errors import KnowledgeError
from knowledge_service.graph_analysis import GraphAnalysisEngine
from knowledge_service.graph_schema import GraphAnalysisResult
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.snippet_extractor import SnippetExtractor
from knowledge_service.structural_analysis import StaticGraphMaterializer, StructuralAnalysisEngine


class AnalysisProvider(Protocol):
    name: str
    version: str

    def analyze(
        self,
        payload: Dict[str, Any],
        line_count: int,
        repair_prompt: Optional[str] = None,
    ) -> GraphAnalysisResult | Awaitable[GraphAnalysisResult]: ...


ATTEMPT_KIND_GENERATION = "GENERATION"
ATTEMPT_KIND_FEEDBACK_REPAIR = "FEEDBACK_REPAIR"
ATTEMPT_KIND_PROVIDER_RETRY = "PROVIDER_RETRY"


@dataclass(frozen=True)
class TargetAttemptFailure:
    attempt_number: int
    attempt_kind: str
    target_ref: Optional[str]
    target_kind: Optional[str]
    model_response: Optional[str]
    response_preview: Optional[str]
    failure_code: str
    failure_message: str
    validation_errors: List[Dict[str, Any]]
    error_details: List[Dict[str, Any]]
    validation_report: Optional[Dict[str, Any]]
    response_truncated: Optional[bool]
    response_preview_truncated: bool
    response_preview_length: int
    response_length: Optional[int]
    retryable: bool
    correctable_output_failure: bool
    exception: KnowledgeError

    @property
    def failure_codes(self) -> List[str]:
        codes = [
            str(item.get("code") or item.get("errorType"))
            for item in (self.validation_errors or self.error_details)
            if item.get("code") or item.get("errorType")
        ]
        return codes or [self.failure_code]


class AnalysisSupervisor:
    RETRYABLE_AI_CODES = {
        "ANALYSIS_AI_BAD_RESPONSE",
        "ANALYSIS_AI_INVALID_JSON",
        "ANALYSIS_AI_SCHEMA_INVALID",
        "ANALYSIS_AI_EMPTY_RESPONSE",
        "ANALYSIS_AI_TRANSPORT_ERROR",
    }
    CORRECTABLE_OUTPUT_AI_CODES = {
        "ANALYSIS_AI_BAD_RESPONSE",
        "ANALYSIS_AI_INVALID_JSON",
        "ANALYSIS_AI_SCHEMA_INVALID",
        "ANALYSIS_AI_EMPTY_RESPONSE",
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
        self.target_progress_tracker = CurrentFileTargetProgressTracker()
        self.analyzer_runtime = AnalyzerRuntime(
            self.graph_contract_provider.policy,
            extractor_registry=ExtractorRegistry(self.structural_engine, self.static_materializer),
            anchor_validator=self.anchor_validator,
            target_progress_tracker=self.target_progress_tracker,
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
            self.target_progress_tracker.clear_sources(sorted(set(request.sourceIds)) if request.sourceIds else None)
            self.target_progress_tracker.clear_job(job_id)
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
            }
            if selected_rows is None:
                self.analysis_store.create_job(job)
            else:
                flow_domain_by_file_id = {int(row["id"]): self._row_flow_domain(row) for row in selected_rows}
                self.analysis_store.create_job_with_pending_files(
                    job,
                    selected_rows,
                    flow_domain_by_file_id,
                    reset_failed_current_state=True,
                )
                job_files_precreated = True
            self._log("job_created", jobId=job_id, sourceId=None, processed=0, failed=0)
            analyzer = (
                client
                or self.analysis_provider
            )
            if analyzer is None:
                raise KnowledgeError("ANALYSIS_PROVIDER_UNAVAILABLE", "Knowledge analysis provider is not configured")
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
        self.target_progress_tracker.clear_job(job_id)
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
        if self.config.startup_maintenance_enabled:
            self.analysis_store.mark_interrupted_jobs()
            self._finalize_dirty_sources(None)
        worker_count = max(1, self.config.analysis_concurrency)
        self._workers = [asyncio.create_task(self._worker(index), name=f"knowledge-analysis-worker-{index}") for index in range(worker_count)]

    async def shutdown(self) -> None:
        self._stopping = True
        running_job_ids = list(self._running.keys())
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
        for job_id in running_job_ids:
            self.target_progress_tracker.clear_job(job_id)
        provider = self.analysis_provider
        close = getattr(provider, "aclose", None)
        if close is not None:
            await close()

    def _finalize_dirty_sources(self, source_ids: Optional[List[str]]) -> None:
        dirty_source_ids = self.analysis_store.dirty_graph_source_ids(source_ids)
        for source_id in dirty_source_ids:
            try:
                self.analysis_store.finalize_source_graph(source_id)
            except Exception as exc:
                self.logger.warning("Knowledge source graph finalization failed for %s: %s", source_id, exc)

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
            unchanged_ids = self.analysis_store.unchanged_file_ids(rows, analyzer.name, analyzer.version)
            rows = [row for row in rows if row["id"] not in unchanged_ids]
        if request.maxFiles is not None:
            rows = rows[: max(0, request.maxFiles)]
        flow_domain_by_file_id = {int(row["id"]): self._row_flow_domain(row) for row in rows}
        if not job_files_precreated:
            self.analysis_store.create_job_files(job_id, rows, flow_domain_by_file_id)
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
                ):
                    processed += 1
                    self.analysis_store.update_job_file(
                        job_id,
                        int(row["id"]),
                        "SKIPPED_UNCHANGED",
                        analysis_file_id=int(row["id"]),
                        flow_domain=flow_domain_by_file_id.get(int(row["id"]), self._row_flow_domain(row)),
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
                    async def analyze_with_retry_for_job(
                        provider: AnalyzerProvider,
                        payload: Dict[str, Any],
                        payload_line_count: int,
                    ) -> tuple[GraphAnalysisResult, List[Dict[str, Any]], Dict[str, Any]]:
                        return await self._analyze_with_retry(provider, payload, payload_line_count, job_id=job_id, row=row_data)

                    runtime_result = await self.analyzer_runtime.execute(row_data, metadata, lines, analyzer, analyze_with_retry_for_job, job_id=job_id)
                    if self._stop_requested(job_id):
                        self._mark_job_stopped(job_id, diagnostics)
                        return
                    elapsed_seconds = self._elapsed_seconds(file_started_at)
                    graph_result = runtime_result.graph_result
                    if elapsed_seconds > self.config.analysis_per_file_timeout_seconds:
                        graph_result.diagnostics.append(
                            {
                                "code": "ANALYSIS_FILE_TIMEOUT",
                                "message": "File analysis completed after exceeding configured per-file timeout budget; result was accepted with diagnostics.",
                                "sourceId": row["source_id"],
                                "relativePath": row["relative_path"],
                                "stage": "FILE_ANALYSIS",
                                "severity": "WARN",
                                "elapsedSeconds": elapsed_seconds,
                                "recovered": True,
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
                        completed=True,
                    )
                    processed += 1
                    self._log("file_completed", jobId=job_id, sourceId=row["source_id"], relativePath=row["relative_path"], processed=processed, failed=failed)
                except Exception as exc:
                    self.target_progress_tracker.mark_failed(job_id, str(row["source_id"]), str(row["relative_path"]))
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
                finally:
                    self.target_progress_tracker.clear_file(job_id, str(row["source_id"]), str(row["relative_path"]))
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
            self._finalize_dirty_sources(scoped_source_ids)
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
        finally:
            self.target_progress_tracker.clear_job(job_id)

    def _stop_requested(self, job_id: str) -> bool:
        return self.analysis_store.stop_requested(job_id)

    def _mark_job_stopped(self, job_id: str, diagnostics: Optional[List[Dict[str, Any]]] = None) -> None:
        job = self.analysis_store.job(job_id)
        self.analysis_store.stop_incomplete_job_files(job_id)
        self.target_progress_tracker.clear_job(job_id)
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

    def current_file_progress(self) -> Dict[str, Any]:
        return self.target_progress_tracker.snapshot()

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

    async def _analyze_with_retry(
        self,
        analyzer: AnalysisProvider,
        payload: Dict[str, Any],
        line_count: int,
        *,
        job_id: Optional[str] = None,
        row: Any = None,
    ):
        attempts = max(1, self.config.analysis_max_attempts_per_file)
        diagnostics: List[Dict[str, Any]] = []
        previous_failure: TargetAttemptFailure | None = None
        for attempt in range(1, attempts + 1):
            attempt_kind = self._attempt_kind(previous_failure)
            validation_feedback_prompt = None
            if previous_failure is not None and previous_failure.correctable_output_failure:
                validation_feedback_prompt = self._validation_feedback_prompt(payload, previous_failure, attempt, attempts)
            try:
                context = self._runtime_context(job_id, row, payload, attempt, attempts, attempt_kind, previous_failure)
                if context is None:
                    pending = analyzer.analyze(payload, line_count, validation_feedback_prompt)
                    result = await pending if inspect.isawaitable(pending) else pending
                else:
                    with analysis_runtime_context(context):
                        pending = analyzer.analyze(payload, line_count, validation_feedback_prompt)
                        result = await pending if inspect.isawaitable(pending) else pending
                if attempt > 1:
                    retry_success = {
                        "code": "ANALYSIS_AI_RETRY_SUCCEEDED",
                        "message": f"AI analysis succeeded after {attempt} attempts.",
                        "sourceId": payload.get("sourceId"),
                        "relativePath": payload.get("relativePath"),
                        "attempts": attempt,
                    }
                    if previous_failure is not None:
                        retry_success["metadata"] = {
                            "previousAttemptNumber": previous_failure.attempt_number,
                            "previousAttemptKind": previous_failure.attempt_kind,
                            "previousFailureCode": previous_failure.failure_code,
                            "previousFailureCodes": previous_failure.failure_codes,
                            "previousErrorSummary": self._failure_summary(previous_failure),
                            "previousErrorDetails": previous_failure.error_details[:5],
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
                    raw_preview_value = exc.details.get("raw_preview")
                    bounded_preview = self._raw_preview(raw_preview_value)
                    exc.details.setdefault("raw_response_length", len(str(raw_preview_value)))
                    exc.details.setdefault("raw_preview_length", len(bounded_preview or ""))
                    exc.details.setdefault(
                        "raw_preview_truncated",
                        len(str(raw_preview_value)) > len(bounded_preview or ""),
                    )
                    exc.details["raw_preview"] = bounded_preview
                if exc.details.get("error_details") is not None:
                    exc.details["error_details"] = self._bounded_error_details(self._error_details(exc))
                current_failure = self._target_attempt_failure(payload, exc, attempt, attempt_kind)
                exc.details.setdefault("attemptKind", attempt_kind)
                exc.details.setdefault("configuredMaxAttempts", attempts)
                exc.details.setdefault("targetRef", payload.get("targetRef"))
                exc.details.setdefault("targetKind", payload.get("targetKind"))
                exc.details.setdefault("targetIndex", payload.get("_targetIndex"))
                exc.details.setdefault("targetCount", payload.get("_targetCount"))
                exc.details["attemptsPerformed"] = attempt
                exc.details["lastAttemptKind"] = current_failure.attempt_kind
                exc.details["lastFailureCode"] = current_failure.failure_code
                exc.details["lastValidationErrors"] = current_failure.validation_errors
                if exc.code not in self.RETRYABLE_AI_CODES or attempt >= attempts:
                    if attempt >= attempts and exc.code in self.RETRYABLE_AI_CODES:
                        exc.details["max_attempts_exceeded"] = True
                    exc.details["diagnostics"] = [*diagnostics, self._attempt_diagnostic(payload, exc, attempt)]
                    raise
                diagnostics.append(self._retry_diagnostic(payload, exc, current_failure, attempt, attempts))
                previous_failure = current_failure
        raise KnowledgeError("ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED", "AI analysis exceeded maximum attempts")

    def _runtime_context(
        self,
        job_id: Optional[str],
        row: Any,
        payload: Dict[str, Any],
        attempt: int,
        configured_max_attempts: int,
        attempt_kind: str,
        previous_failure: TargetAttemptFailure | None,
    ) -> Optional[AnalysisRuntimeContext]:
        if not job_id:
            return None
        inventory_file_id = self._optional_int(self._row_value(row, "id"))
        return AnalysisRuntimeContext(
            job_id=job_id,
            source_id=str(payload.get("sourceId") or self._row_value(row, "source_id") or "") or None,
            inventory_file_id=inventory_file_id,
            analysis_file_id=inventory_file_id,
            relative_path=str(payload.get("relativePath") or self._row_value(row, "relative_path") or "") or None,
            content_hash=str(payload.get("contentHash") or self._row_value(row, "content_hash") or "") or None,
            attempt=attempt,
            recorder=self._record_runtime_event,
            configured_max_attempts=configured_max_attempts,
            attempt_kind=attempt_kind,
            previous_attempt_number=previous_failure.attempt_number if previous_failure is not None else None,
            previous_failure_codes=tuple(previous_failure.failure_codes) if previous_failure is not None else (),
            previous_response_available=bool(
                previous_failure
                and previous_failure.correctable_output_failure
                and previous_failure.response_preview
            ),
            previous_response_preview_truncated=previous_failure.response_preview_truncated if previous_failure is not None else None,
            previous_response_preview_length=previous_failure.response_preview_length if previous_failure is not None else None,
            previous_response_length=previous_failure.response_length if previous_failure is not None else None,
        )

    def _record_runtime_event(self, event: Any) -> None:
        try:
            self.analysis_store.record_runtime_event(dict(event))
        except Exception as exc:
            self.logger.warning("Analysis runtime diagnostic event write failed: %s", exc)

    def _optional_int(self, value: Any) -> Optional[int]:
        if value is None:
            return None
        try:
            return int(value)
        except (TypeError, ValueError):
            return None

    def _attempt_kind(self, previous_failure: TargetAttemptFailure | None) -> str:
        if previous_failure is None:
            return ATTEMPT_KIND_GENERATION
        if previous_failure.correctable_output_failure:
            return ATTEMPT_KIND_FEEDBACK_REPAIR
        return ATTEMPT_KIND_PROVIDER_RETRY

    def _target_attempt_failure(
        self,
        payload: Dict[str, Any],
        exc: KnowledgeError,
        attempt: int,
        attempt_kind: str,
    ) -> TargetAttemptFailure:
        details = self._bounded_error_details(self._error_details(exc))
        validation_report = (getattr(exc, "details", {}) or {}).get("validation_report") or (getattr(exc, "details", {}) or {}).get("validationReport")
        validation_errors: List[Dict[str, Any]] = []
        if isinstance(validation_report, dict) and isinstance(validation_report.get("validationErrors"), list):
            validation_errors = self._bounded_error_details([dict(item) for item in validation_report.get("validationErrors", []) if isinstance(item, dict)])
        if not validation_errors:
            validation_errors = details
        exc_details = getattr(exc, "details", {}) or {}
        raw_preview = self._raw_preview(exc_details.get("raw_preview"))
        response_length = self._optional_int(
            exc_details.get("raw_response_length")
            if exc_details.get("raw_response_length") is not None
            else exc_details.get("rawResponseLength")
        )
        response_preview_length = self._optional_int(
            exc_details.get("raw_preview_length")
            if exc_details.get("raw_preview_length") is not None
            else exc_details.get("rawPreviewLength")
        )
        if raw_preview is not None:
            response_preview_length = len(raw_preview)
        response_preview_truncated = self._detail_bool(exc_details, "raw_preview_truncated", "rawPreviewTruncated")
        if response_preview_truncated is None:
            response_preview_truncated = bool(response_length is not None and raw_preview is not None and response_length > len(raw_preview))
        response_truncated = self._response_truncated(validation_errors)
        return TargetAttemptFailure(
            attempt_number=attempt,
            attempt_kind=attempt_kind,
            target_ref=str(payload.get("targetRef") or "") or None,
            target_kind=str(payload.get("targetKind") or "") or None,
            model_response=raw_preview,
            response_preview=raw_preview,
            failure_code=exc.code,
            failure_message=exc.message,
            validation_errors=validation_errors,
            error_details=details,
            validation_report=dict(validation_report) if isinstance(validation_report, dict) else None,
            response_truncated=response_truncated,
            response_preview_truncated=bool(response_preview_truncated),
            response_preview_length=response_preview_length or 0,
            response_length=response_length,
            retryable=exc.code in self.RETRYABLE_AI_CODES,
            correctable_output_failure=exc.code in self.CORRECTABLE_OUTPUT_AI_CODES,
            exception=exc,
        )

    def _response_truncated(self, details: List[Dict[str, Any]]) -> Optional[bool]:
        for detail in details:
            if detail.get("responseTruncated") is not None:
                return bool(detail.get("responseTruncated"))
        return None

    def _retry_diagnostic(
        self,
        payload: Dict[str, Any],
        exc: KnowledgeError,
        failure: TargetAttemptFailure,
        attempt: int,
        attempts: int,
    ) -> Dict[str, Any]:
        diagnostic = {
            "code": exc.code,
            "message": f"{exc.message}; retrying analysis attempt {attempt + 1} of {attempts}.",
            "sourceId": payload.get("sourceId"),
            "relativePath": payload.get("relativePath"),
            "attempt": attempt,
            "attemptKind": failure.attempt_kind,
            "configuredMaxAttempts": attempts,
            "rawPreview": exc.details.get("raw_preview"),
        }
        if payload.get("targetRef"):
            diagnostic["targetRef"] = payload.get("targetRef")
        if payload.get("_targetIndex") is not None:
            diagnostic["targetIndex"] = payload.get("_targetIndex")
        if payload.get("_targetCount") is not None:
            diagnostic["targetCount"] = payload.get("_targetCount")
        metadata = self._error_metadata(exc)
        metadata.update(
            {
                "attemptKind": failure.attempt_kind,
                "configuredMaxAttempts": attempts,
                "attemptsPerformed": attempt,
                "lastAttemptKind": failure.attempt_kind,
                "lastFailureCode": failure.failure_code,
                "lastValidationErrors": failure.validation_errors,
                "retryable": failure.retryable,
                "correctableOutputFailure": failure.correctable_output_failure,
            }
        )
        if metadata:
            diagnostic["metadata"] = metadata
        return diagnostic

    def _validation_feedback_prompt(self, payload: Dict[str, Any], previous_failure: TargetAttemptFailure | KnowledgeError, attempt: int, attempts: int) -> str:
        if isinstance(previous_failure, KnowledgeError):
            previous_failure = self._target_attempt_failure(payload, previous_failure, max(1, attempt - 1), ATTEMPT_KIND_GENERATION)
        validation_errors = self._bounded_error_details(previous_failure.validation_errors or previous_failure.error_details)
        validation_errors = validation_errors or [
            {
                "code": previous_failure.failure_code,
                "jsonPath": "$",
                "message": previous_failure.failure_message,
                "expected": "valid target-anchor JSON response",
            }
        ]
        json_parse_only = bool(validation_errors) and all(str(item.get("code") or item.get("errorType")) == "JSON_PARSE_ERROR" for item in validation_errors)
        target_context = self._target_feedback_context(payload)
        lines = [
            f"Validation feedback retry attempt {attempt} of {attempts}.",
            f"Previous attempt number: {previous_failure.attempt_number}.",
            "Current target:",
            self._json_for_prompt(target_context, limit=2000),
            "Structured validationErrors:",
            self._json_for_prompt(validation_errors, limit=6000),
        ]
        if json_parse_only:
            lines.extend(
                [
                    "Output must be one valid JSON object.",
                    "Corrected response must match the target-anchor response shape.",
                    "Return corrected JSON only.",
                ]
            )
        else:
            lines.extend(
                [
                    "Return corrected JSON only.",
                    "Fix only the listed validation errors.",
                    "Correct the previous response and preserve already valid information where possible.",
                    "If a claim cannot be supported with valid evidence, remove that claim.",
                    "Do not invent facts outside the current target.",
                    "Do not add unrelated fields.",
                ]
            )
        preview = previous_failure.response_preview
        if preview:
            if previous_failure.response_preview_truncated:
                lines.extend(
                    [
                        "Previous invalid response preview:",
                        preview,
                        "",
                        "The previous response was truncated for prompt safety.",
                        "Use the validation errors and the available preview to produce a complete corrected response.",
                    ]
                )
            else:
                lines.extend(["Previous invalid response:", preview])
        return "\n".join(lines)

    def _target_feedback_context(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        llm_input = payload.get("llmInput")
        target_anchor = llm_input.get("targetAnchor") if isinstance(llm_input, dict) else {}
        return {
            "targetRef": payload.get("targetRef"),
            "targetKind": payload.get("targetKind"),
            "targetName": target_anchor.get("name") if isinstance(target_anchor, dict) else None,
            "targetLineStart": target_anchor.get("lineStart") if isinstance(target_anchor, dict) else None,
            "targetLineEnd": target_anchor.get("lineEnd") if isinstance(target_anchor, dict) else None,
            "targetIndex": payload.get("_targetIndex"),
            "targetCount": payload.get("_targetCount"),
        }

    def _format_error_detail(self, detail: Dict[str, Any]) -> str:
        error_code = str(detail.get("code") or detail.get("errorType") or "ERROR")
        if error_code == "JSON_PARSE_ERROR":
            position = detail.get("charPosition")
            position_text = f" char {position}" if position is not None else ""
            truncated = detail.get("responseTruncated")
            truncated_text = f" responseTruncated={truncated}." if truncated is not None else ""
            return (
                f"JSON parse error at line {detail.get('line')} column {detail.get('column')}{position_text}: "
                f"{detail.get('message')}.{truncated_text}"
            )
        path = detail.get("jsonPath") or detail.get("graphEntityId") or "$"
        text = f"{error_code} at {path}"
        if detail.get("message") or detail.get("reason"):
            text += f": {detail.get('message') or detail.get('reason')}"
        missing = detail.get("missingRequiredField")
        if missing:
            text += f" Missing required field: {missing}."
        if detail.get("field"):
            text += f" Field: {detail.get('field')}."
        if detail.get("actual") is not None:
            text += f" Actual: {self._json_for_prompt(detail.get('actual'))}."
        if detail.get("expected"):
            text += f" Expected: {detail.get('expected')}."
        target_range = detail.get("targetRange")
        if isinstance(target_range, dict):
            text += f" Target range: {target_range.get('lineStart')}-{target_range.get('lineEnd')}."
        elif detail.get("targetLineStart") is not None and detail.get("targetLineEnd") is not None:
            text += f" Target range: {detail.get('targetLineStart')}-{detail.get('targetLineEnd')}."
        evidence_range = detail.get("evidenceRange")
        if isinstance(evidence_range, dict):
            text += f" Evidence range: {evidence_range.get('lineStart')}-{evidence_range.get('lineEnd')}."
        elif detail.get("evidenceLineStart") is not None and detail.get("evidenceLineEnd") is not None:
            text += f" Evidence range: {detail.get('evidenceLineStart')}-{detail.get('evidenceLineEnd')}."
        allowed = detail.get("allowedValues") or []
        if allowed:
            text += f" Allowed values: {self._json_for_prompt(allowed)}."
        return text

    def _json_for_prompt(self, value: Any, limit: int = 240) -> str:
        text = json.dumps(value, ensure_ascii=False, default=str)
        if len(text) > limit:
            return text[:limit].rstrip() + "..."
        return text

    def _error_details(self, exc: Exception) -> List[Dict[str, Any]]:
        details = getattr(exc, "details", {}) or {}
        validation_report = details.get("validation_report") or details.get("validationReport")
        raw_details = (
            details.get("error_details")
            or details.get("errorDetails")
            or details.get("validation_errors")
            or details.get("validationErrors")
            or []
        )
        if not raw_details and isinstance(validation_report, dict):
            raw_details = validation_report.get("validationErrors") or []
        if isinstance(raw_details, dict):
            raw_details = [raw_details]
        return [dict(item) for item in raw_details if isinstance(item, dict)]

    def _detail_bool(self, details: Dict[str, Any], *keys: str) -> Optional[bool]:
        for key in keys:
            if key in details and details[key] is not None:
                return bool(details[key])
        return None

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

    def _failure_summary(self, failure: TargetAttemptFailure) -> str:
        details = failure.error_details or failure.validation_errors
        if details:
            summary = "; ".join(self._format_error_detail(detail) for detail in details[:3])
            if len(details) > 3:
                summary = f"{summary}; and {len(details) - 3} more error(s)"
            return self._raw_preview(summary) or summary
        return f"{failure.failure_code}: {failure.failure_message}"

    def _error_metadata(self, exc: Exception) -> Dict[str, Any]:
        details = self._bounded_error_details(self._error_details(exc))
        exc_details = getattr(exc, "details", {}) or {}
        validation_report = exc_details.get("validation_report") or exc_details.get("validationReport")
        metadata: Dict[str, Any] = {}
        if isinstance(validation_report, dict):
            metadata["validationReport"] = validation_report
            validation_errors = validation_report.get("validationErrors")
            if isinstance(validation_errors, list):
                metadata["validationErrors"] = self._bounded_error_details([dict(item) for item in validation_errors if isinstance(item, dict)])
            for key in ("targetRef", "targetKind", "targetName", "targetRange"):
                if validation_report.get(key) is not None:
                    metadata[key] = validation_report.get(key)
        if details:
            metadata["errorDetails"] = details
            metadata["errorSummary"] = self._error_summary(exc)
            first = details[0]
            for key in (
                "code",
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
                "targetRef",
                "targetKind",
                "targetName",
                "targetRange",
                "evidenceRange",
                "responseHash",
                "attemptKind",
                "configuredMaxAttempts",
                "attemptsPerformed",
                "lastAttemptKind",
                "lastFailureCode",
                "lastValidationErrors",
                "targetIndex",
                "targetCount",
            ):
                if first.get(key) is not None:
                    metadata[key] = first.get(key)
            if first.get("rawPreview"):
                metadata["errorPreview"] = self._raw_preview(first.get("rawPreview"))
        raw_preview = self._raw_preview((getattr(exc, "details", {}) or {}).get("raw_preview"))
        if raw_preview:
            metadata.setdefault("rawPreview", raw_preview)
        for key in (
            "attemptKind",
            "configuredMaxAttempts",
            "attemptsPerformed",
            "lastAttemptKind",
            "lastFailureCode",
            "lastValidationErrors",
            "targetRef",
            "targetKind",
            "targetIndex",
            "targetCount",
            "providerId",
            "providerVersion",
            "modelId",
            "providerErrorClass",
            "providerErrorCode",
            "stream",
            "configuredLimitBytes",
            "method",
            "pendingMethods",
            "exceptionClass",
        ):
            if exc_details.get(key) is not None:
                metadata[key] = exc_details.get(key)
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
        if payload.get("targetRef"):
            diagnostic["targetRef"] = payload.get("targetRef")
        if payload.get("_targetIndex") is not None:
            diagnostic["targetIndex"] = payload.get("_targetIndex")
        if payload.get("_targetCount") is not None:
            diagnostic["targetCount"] = payload.get("_targetCount")
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
