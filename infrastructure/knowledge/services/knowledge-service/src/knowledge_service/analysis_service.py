from __future__ import annotations

import hashlib
import json
import threading
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from knowledge_service.analysis_client import OllamaAnalysisClient
from knowledge_service.analysis_response_parser import MAX_RAW_PREVIEW_CHARS
from knowledge_service.analysis_schema import AnalysisBuildRequest, AnalysisResult
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.config import AppConfig
from knowledge_service.errors import KnowledgeError
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.snippet_extractor import SnippetExtractor


class AnalysisJobRunner:
    REPAIR_PROMPT = (
        "Your previous response was invalid JSON or did not match the schema. "
        "Return the same analysis as one valid JSON object matching the schema. "
        "No markdown. No prose."
    )
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
        self.snippets = SnippetExtractor()
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
                "skippedUnchangedFileCount": 0,
                "failedFileCount": 0,
                "currentSourceId": request.sourceIds[0] if request.sourceIds else None,
                "currentRelativePath": None,
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
        rows, _ = self.inventory_store.search_rows(request.sourceIds, request.groups)
        scoped_source_ids = sorted({row["source_id"] for row in rows}) or request.sourceIds
        self.analysis_store.cleanup_stale_files(scoped_source_ids or None)
        if not request.force:
            unchanged_ids = self.analysis_store.unchanged_file_ids(rows, analyzer.name, analyzer.version)
            rows = [row for row in rows if row["id"] not in unchanged_ids]
        if request.maxFiles is not None:
            rows = rows[:max(0, request.maxFiles)]
        if self._stop_requested(job_id):
            self._mark_job_stopped(job_id)
            return
        self.analysis_store.update_job(job_id, {
            "status": "RUNNING",
            "startedAt": started_at,
            "lastProgressAt": started_at,
            "sourceCount": len({row["source_id"] for row in rows}),
            "fileCount": len(rows),
        })
        processed = failed = symbols_total = relations_total = 0
        diagnostics: List[Dict[str, Any]] = []
        try:
            for row in rows:
                if self._stop_requested(job_id):
                    self._mark_job_stopped(job_id, diagnostics)
                    return
                self.analysis_store.update_job(job_id, {
                    "currentSourceId": row["source_id"],
                    "currentRelativePath": row["relative_path"],
                    "lastProgressAt": self._now(),
                })
                metadata = json.loads(row["metadata_json"])
                lines = self.snippets.read_lines(row["absolute_path"], metadata.get("absoluteRoot") or row["source_path"])
                if lines is None:
                    processed += 1
                    failed += 1
                    self._mark(row, analyzer, "FAILED", [{"code": "FILE_UNREADABLE", "message": "Indexed file could not be read safely"}])
                    self.analysis_store.update_job(job_id, {
                        "processedFileCount": processed,
                        "failedFileCount": failed,
                        "lastProgressAt": self._now(),
                    })
                    continue
                content = "\n".join(lines)
                if len(content) > self.config.analysis_max_file_chars:
                    processed += 1
                    self._mark(row, analyzer, "SKIPPED_TOO_LARGE_FOR_AI_ANALYSIS", [{"code": "ANALYSIS_FILE_TOO_LARGE", "message": "File exceeds AI analysis size limit"}])
                    self.analysis_store.update_job(job_id, {
                        "processedFileCount": processed,
                        "lastProgressAt": self._now(),
                    })
                    continue
                try:
                    result, retry_diagnostics, attempt_state = self._analyze_with_retry(analyzer, self._payload(row, metadata, content), len(lines))
                    if self._stop_requested(job_id):
                        self._mark_job_stopped(job_id, diagnostics)
                        return
                    symbols, roles, relations = self._materialize(row, analyzer, result)
                    self.analysis_store.replace_file_analysis(row["id"], self._state(row, analyzer, "ANALYZED", len(symbols), len(relations), retry_diagnostics, attempt_state), symbols, roles, relations)
                    processed += 1
                    symbols_total += len(symbols)
                    relations_total += len(relations)
                except Exception as exc:
                    if self._stop_requested(job_id):
                        self._mark_job_stopped(job_id, diagnostics)
                        return
                    processed += 1
                    failed += 1
                    diag = self._diagnostic(row, exc, getattr(exc, "details", {}).get("attempt"))
                    diagnostics.append(diag)
                    file_diagnostics = [*getattr(exc, "details", {}).get("diagnostics", []), diag]
                    self._mark(row, analyzer, "FAILED", file_diagnostics, self._attempt_state(exc))
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

    def _payload(self, row, metadata: Dict[str, Any], content: str) -> Dict[str, Any]:
        return {
            "sourceId": row["source_id"],
            "serviceLabel": row["display_name"],
            "group": row["group_name"],
            "tags": json.loads(row["tags_json"] or "[]"),
            "relativePath": row["relative_path"],
            "extension": row["extension"],
            "sizeBytes": row["size_bytes"],
            "contentHash": row["content_hash"],
            "metadata": {k: v for k, v in metadata.items() if k != "absoluteRoot"},
            "content": content,
        }

    def _analyze_with_retry(self, analyzer: OllamaAnalysisClient, payload: Dict[str, Any], line_count: int):
        attempts = max(1, self.config.analysis_max_attempts_per_file)
        repair_attempts = max(0, self.config.analysis_repair_attempts_per_file)
        diagnostics: List[Dict[str, Any]] = []
        repair_used = 0
        last_error: KnowledgeError | None = None
        for attempt in range(1, attempts + 1):
            repair_prompt = None
            if last_error is not None and last_error.code in {"ANALYSIS_AI_INVALID_JSON", "ANALYSIS_AI_SCHEMA_INVALID", "ANALYSIS_AI_EMPTY_RESPONSE", "ANALYSIS_AI_BAD_RESPONSE"} and repair_used < repair_attempts:
                repair_prompt = self.REPAIR_PROMPT
                repair_used += 1
            try:
                result = analyzer.analyze(payload, line_count, repair_prompt)
                if attempt > 1:
                    diagnostics.append({
                        "code": "ANALYSIS_AI_RETRY_SUCCEEDED",
                        "message": f"AI analysis succeeded after {attempt} attempts.",
                        "sourceId": payload.get("sourceId"),
                        "relativePath": payload.get("relativePath"),
                        "attempts": attempt,
                    })
                return result, diagnostics, {
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
                last_error = exc
                if exc.code not in self.RETRYABLE_AI_CODES or attempt >= attempts:
                    if attempt >= attempts and exc.code in self.RETRYABLE_AI_CODES:
                        exc.details["max_attempts_exceeded"] = True
                    exc.details["diagnostics"] = [*diagnostics, self._attempt_diagnostic(payload, exc, attempt)]
                    raise
                diagnostics.append({
                    "code": exc.code,
                    "message": f"{exc.message}; retrying analysis attempt {attempt + 1} of {attempts}.",
                    "sourceId": payload.get("sourceId"),
                    "relativePath": payload.get("relativePath"),
                    "attempt": attempt,
                    "rawPreview": exc.details.get("raw_preview"),
                })
        raise KnowledgeError("ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED", "AI analysis exceeded maximum attempts")

    def _materialize(self, row, analyzer: OllamaAnalysisClient, result: AnalysisResult):
        local_to_stable: Dict[str, str] = {}
        symbols: List[Dict[str, Any]] = []
        roles: List[Dict[str, Any]] = []
        for symbol in result.symbols:
            symbol_id = self._stable_id("analysis-symbol", row["source_id"], row["relative_path"], symbol.localId, symbol.name, str(symbol.lineStart))
            local_to_stable[symbol.localId] = symbol_id
            symbols.append({
                "symbol_id": symbol_id,
                "source_id": row["source_id"],
                "relative_path": row["relative_path"],
                "name": symbol.name,
                "kind": symbol.kind,
                "line_start": symbol.lineStart,
                "line_end": symbol.lineEnd,
                "summary": result.fileSummary,
                "metadata": symbol.metadata,
            })
            for role in symbol.roles:
                roles.append({
                    "symbol_id": symbol_id,
                    "role": role.role,
                    "confidence": role.confidence,
                    "evidence": role.evidence,
                    "classifier": analyzer.name,
                    "classifier_version": analyzer.version,
                })
        relations: List[Dict[str, Any]] = []
        seen_relations: set[str] = set()
        for relation in result.relations:
            from_id = local_to_stable.get(relation.fromLocalId)
            to_id = local_to_stable.get(relation.toLocalId)
            if not from_id or not to_id:
                continue
            relation_id = self._stable_id("analysis-relation", row["source_id"], row["relative_path"], from_id, to_id, relation.relation, str(relation.lineStart))
            if relation_id in seen_relations:
                continue
            seen_relations.add(relation_id)
            relations.append({
                "relation_id": relation_id,
                "source_id": row["source_id"],
                "from_symbol_id": from_id,
                "to_symbol_id": to_id,
                "relation": relation.relation,
                "confidence": relation.confidence,
                "evidence": relation.evidence,
                "line_start": relation.lineStart,
                "line_end": relation.lineEnd,
                "metadata": relation.metadata,
            })
        return symbols, roles, relations

    def _mark(self, row, analyzer: OllamaAnalysisClient, status: str, diagnostics: List[Dict[str, Any]], attempt_state: Optional[Dict[str, Any]] = None) -> None:
        self.analysis_store.mark_file(row["id"], self._state(row, analyzer, status, 0, 0, diagnostics, attempt_state))

    def _state(self, row, analyzer: OllamaAnalysisClient, status: str, symbol_count: int, relation_count: int, diagnostics: List[Dict[str, Any]], attempt_state: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        state = {
            "source_id": row["source_id"],
            "relative_path": row["relative_path"],
            "content_hash": row["content_hash"],
            "analyzer_name": analyzer.name,
            "analyzer_version": analyzer.version,
            "status": status,
            "analyzed_at": datetime.now(timezone.utc).isoformat(),
            "symbol_count": symbol_count,
            "relation_count": relation_count,
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
        if getattr(exc, "details", {}).get("max_attempts_exceeded"):
            code = "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"
            message = f"AI analysis exceeded maximum attempts: {message}"
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
        return diagnostic

    def _raw_preview(self, value: Any) -> Optional[str]:
        if value is None:
            return None
        return str(value)[:MAX_RAW_PREVIEW_CHARS]

    def _now(self) -> str:
        return datetime.now(timezone.utc).isoformat()
