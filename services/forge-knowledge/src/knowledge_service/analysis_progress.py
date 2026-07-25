from __future__ import annotations

import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple


@dataclass(frozen=True)
class CurrentFileTargetProgress:
    job_id: str
    source_id: str
    relative_path: str
    total_targets: Optional[int]
    completed_targets: int
    status: str
    updated_at: str

    @property
    def show_target_progress(self) -> bool:
        return self.total_targets is not None and int(self.total_targets) > 1

    @property
    def percent(self) -> Optional[float]:
        if not self.show_target_progress:
            return None
        total = int(self.total_targets or 0)
        completed = max(0, min(int(self.completed_targets), total))
        return max(0.0, min(100.0, round((completed / total) * 100, 2)))

    def public_dict(self) -> Dict[str, Any]:
        total = None if self.total_targets is None else max(0, int(self.total_targets))
        completed = max(0, int(self.completed_targets))
        if total is not None:
            completed = min(completed, total)
        return {
            "jobId": self.job_id,
            "sourceId": self.source_id,
            "relativePath": self.relative_path,
            "totalTargets": total,
            "completedTargets": completed,
            "percent": self.percent,
            "showTargetProgress": self.show_target_progress,
            "status": self.status,
            "updatedAt": self.updated_at,
        }


class CurrentFileTargetProgressTracker:
    def __init__(self) -> None:
        self._entries: Dict[Tuple[str, str, str], CurrentFileTargetProgress] = {}
        self._lock = threading.RLock()

    def start_file(self, job_id: str, source_id: str, relative_path: str) -> None:
        if not job_id or not source_id or not relative_path:
            return
        key = self._key(job_id, source_id, relative_path)
        with self._lock:
            self._entries[key] = CurrentFileTargetProgress(
                job_id=job_id,
                source_id=source_id,
                relative_path=relative_path,
                total_targets=None,
                completed_targets=0,
                status="RUNNING",
                updated_at=self._now(),
            )

    def set_total_targets(self, job_id: str, source_id: str, relative_path: str, total_targets: int) -> None:
        if not job_id or not source_id or not relative_path:
            return
        total = max(0, int(total_targets or 0))
        with self._lock:
            current = self._entry_or_default(job_id, source_id, relative_path)
            completed = max(0, min(current.completed_targets, total))
            self._entries[self._key(job_id, source_id, relative_path)] = CurrentFileTargetProgress(
                job_id=job_id,
                source_id=source_id,
                relative_path=relative_path,
                total_targets=total,
                completed_targets=completed,
                status="RUNNING",
                updated_at=self._now(),
            )

    def increment_completed(self, job_id: str, source_id: str, relative_path: str) -> None:
        if not job_id or not source_id or not relative_path:
            return
        with self._lock:
            current = self._entry_or_default(job_id, source_id, relative_path)
            total = current.total_targets
            completed = current.completed_targets + 1
            if total is not None:
                completed = min(completed, max(0, int(total)))
            self._entries[self._key(job_id, source_id, relative_path)] = CurrentFileTargetProgress(
                job_id=job_id,
                source_id=source_id,
                relative_path=relative_path,
                total_targets=total,
                completed_targets=max(0, completed),
                status="RUNNING",
                updated_at=self._now(),
            )

    def mark_failed(self, job_id: str, source_id: str, relative_path: str) -> None:
        if not job_id or not source_id or not relative_path:
            return
        with self._lock:
            current = self._entry_or_default(job_id, source_id, relative_path)
            self._entries[self._key(job_id, source_id, relative_path)] = CurrentFileTargetProgress(
                job_id=job_id,
                source_id=source_id,
                relative_path=relative_path,
                total_targets=current.total_targets,
                completed_targets=current.completed_targets,
                status="FAILED",
                updated_at=self._now(),
            )

    def clear_file(self, job_id: str, source_id: str, relative_path: str) -> None:
        with self._lock:
            self._entries.pop(self._key(job_id, source_id, relative_path), None)

    def clear_job(self, job_id: str, source_id: Optional[str] = None) -> None:
        with self._lock:
            for key, entry in list(self._entries.items()):
                if entry.job_id == job_id and (source_id is None or entry.source_id == source_id):
                    self._entries.pop(key, None)

    def clear_sources(self, source_ids: Optional[List[str]]) -> None:
        if source_ids is None:
            with self._lock:
                self._entries.clear()
            return
        sources = {str(source_id) for source_id in (source_ids or []) if source_id}
        if not sources:
            return
        with self._lock:
            for key, entry in list(self._entries.items()):
                if entry.source_id in sources:
                    self._entries.pop(key, None)

    def snapshot(self) -> Dict[str, Any]:
        with self._lock:
            entries = sorted(self._entries.values(), key=lambda item: item.updated_at, reverse=True)
            public_entries = [entry.public_dict() for entry in entries]
        response: Dict[str, Any] = {
            "active": bool(public_entries),
            "entries": public_entries,
        }
        if public_entries:
            response["current"] = public_entries[0]
        return response

    def _entry_or_default(self, job_id: str, source_id: str, relative_path: str) -> CurrentFileTargetProgress:
        return self._entries.get(
            self._key(job_id, source_id, relative_path),
            CurrentFileTargetProgress(
                job_id=job_id,
                source_id=source_id,
                relative_path=relative_path,
                total_targets=None,
                completed_targets=0,
                status="RUNNING",
                updated_at=self._now(),
            ),
        )

    def _key(self, job_id: str, source_id: str, relative_path: str) -> Tuple[str, str, str]:
        return str(job_id), str(source_id), str(relative_path)

    def _now(self) -> str:
        return datetime.now(timezone.utc).isoformat()
