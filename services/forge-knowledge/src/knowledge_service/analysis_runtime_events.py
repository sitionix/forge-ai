from __future__ import annotations

import hashlib
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable, Dict, Iterator, Mapping, Optional


ANALYSIS_RUNTIME_PREVIEW_CHARS = 2000
RuntimeEventRecorder = Callable[[Mapping[str, Any]], None]


@dataclass(frozen=True)
class AnalysisRuntimeContext:
    job_id: str
    source_id: Optional[str]
    inventory_file_id: Optional[int]
    analysis_file_id: Optional[int]
    relative_path: Optional[str]
    content_hash: Optional[str]
    attempt: int
    recorder: RuntimeEventRecorder


_current_runtime_context: ContextVar[AnalysisRuntimeContext | None] = ContextVar(
    "analysis_runtime_context",
    default=None,
)


@contextmanager
def analysis_runtime_context(context: AnalysisRuntimeContext) -> Iterator[None]:
    token = _current_runtime_context.set(context)
    try:
        yield
    finally:
        _current_runtime_context.reset(token)


def current_runtime_context() -> AnalysisRuntimeContext | None:
    return _current_runtime_context.get()


def emit_runtime_event(
    *,
    stage: str,
    event_type: str,
    status: str,
    metadata: Optional[Mapping[str, Any]] = None,
    started_at: Optional[str] = None,
    completed_at: Optional[str] = None,
    duration_ms: Optional[int] = None,
    error_code: Optional[str] = None,
    error_message: Optional[str] = None,
) -> None:
    context = current_runtime_context()
    if context is None:
        return
    context.recorder(
        {
            "job_id": context.job_id,
            "source_id": context.source_id,
            "inventory_file_id": context.inventory_file_id,
            "analysis_file_id": context.analysis_file_id,
            "relative_path": context.relative_path,
            "content_hash": context.content_hash,
            "attempt": context.attempt,
            "stage": stage,
            "event_type": event_type,
            "status": status,
            "started_at": started_at,
            "completed_at": completed_at,
            "duration_ms": duration_ms,
            "error_code": error_code,
            "error_message": error_message,
            "metadata": dict(metadata or {}),
        }
    )


def runtime_preview(value: Any, limit: int = ANALYSIS_RUNTIME_PREVIEW_CHARS) -> Dict[str, Any]:
    text = "" if value is None else str(value)
    bounded_limit = max(1, int(limit))
    truncated = len(text) > bounded_limit
    return {
        "head": text[:bounded_limit],
        "tail": text[-bounded_limit:] if truncated else text,
        "truncated": truncated,
        "charLength": len(text),
        "maxPreviewChars": bounded_limit,
    }


def text_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()
