from __future__ import annotations

import logging
import math
from datetime import datetime, timezone
from typing import Any, Mapping

from knowledge_service.active_profile import LlmUsageResponse, LlmUsageWindowResponse
from knowledge_service.codex_app_server import CodexAppServerClient

LOGGER = logging.getLogger(__name__)


class CodexLlmUsageSource:
    provider_id = "codex"

    def __init__(self, client: CodexAppServerClient) -> None:
        self._client = client

    async def usage(self) -> LlmUsageResponse | None:
        payload = await self._client.request("account/rateLimits/read")
        rate_limits = payload.get("rateLimits") if isinstance(payload, Mapping) else None
        rate_limits_by_limit_id = payload.get("rateLimitsByLimitId") if isinstance(payload, Mapping) else None
        if not isinstance(rate_limits, Mapping):
            self._audit_rate_limits(rate_limits, rate_limits_by_limit_id, [])
            return None
        windows: list[tuple[str, LlmUsageWindowResponse]] = []
        active_limit_id = _quota_limit_id(rate_limits)
        self._append_window(windows, "PRIMARY", rate_limits.get("primary"))
        self._append_window(windows, "SECONDARY", rate_limits.get("secondary"))
        if isinstance(rate_limits_by_limit_id, Mapping):
            matched_raw = rate_limits_by_limit_id.get(active_limit_id) if active_limit_id is not None else None
            if isinstance(matched_raw, Mapping) and _quota_limit_id(matched_raw) == active_limit_id:
                for nested_kind, nested_raw in _quota_candidates(matched_raw):
                    self._append_window(windows, f"LIMIT:{active_limit_id}:{nested_kind}", nested_raw)
        deduped = _dedupe_windows([window for _, window in windows])
        deduped.sort(key=lambda window: (window.windowDurationMinutes, window.resetAt, window.kind))
        self._audit_rate_limits(rate_limits, rate_limits_by_limit_id, deduped)
        return LlmUsageResponse(windows=deduped)

    def _append_window(
        self,
        windows: list[tuple[str, LlmUsageWindowResponse]],
        kind: str,
        raw: Any,
    ) -> None:
        if not isinstance(raw, Mapping):
            return
        try:
            used_percent = float(raw["usedPercent"])
            duration_minutes = int(raw["windowDurationMins"])
            reset_seconds = int(raw["resetsAt"])
        except (KeyError, TypeError, ValueError):
            return
        if duration_minutes <= 0 or reset_seconds <= 0 or not math.isfinite(used_percent):
            return
        try:
            reset_at = datetime.fromtimestamp(reset_seconds, tz=timezone.utc).isoformat().replace("+00:00", "Z")
        except (OverflowError, OSError, ValueError):
            return
        windows.append((
            kind,
            LlmUsageWindowResponse(
                kind=kind,
                usedPercent=max(0, min(100, round(used_percent))),
                windowDurationMinutes=duration_minutes,
                resetAt=reset_at,
            ),
        ))

    def _audit_rate_limits(self, rate_limits: Any, rate_limits_by_limit_id: Any, windows: list[LlmUsageWindowResponse]) -> None:
        primary = rate_limits.get("primary") if isinstance(rate_limits, Mapping) else None
        secondary = rate_limits.get("secondary") if isinstance(rate_limits, Mapping) else None
        LOGGER.info(
            "Codex rate-limit usage shape: rateLimits=%s rateLimitsByLimitId=%s primaryDuration=%s secondaryDuration=%s validWindows=%s",
            isinstance(rate_limits, Mapping),
            isinstance(rate_limits_by_limit_id, Mapping),
            _quota_duration(primary),
            _quota_duration(secondary),
            len(windows),
        )


def _quota_limit_id(raw: Any) -> str | None:
    if not isinstance(raw, Mapping):
        return None
    value = raw.get("limitId")
    if isinstance(value, str) and value.strip():
        return value.strip()
    return None


def _quota_duration(raw: Any) -> int | None:
    if not isinstance(raw, Mapping):
        return None
    try:
        duration = int(raw["windowDurationMins"])
    except (KeyError, TypeError, ValueError):
        return None
    return duration if duration > 0 else None


def _quota_candidates(raw: Any) -> list[tuple[str, Any]]:
    if not isinstance(raw, Mapping):
        return []
    nested: list[tuple[str, Any]] = []
    for key in ("primary", "secondary"):
        value = raw.get(key)
        if isinstance(value, Mapping):
            nested.append((key.upper(), value))
    if nested:
        return nested
    return [("WINDOW", raw)]


def _dedupe_windows(windows: list[LlmUsageWindowResponse]) -> list[LlmUsageWindowResponse]:
    deduped: list[LlmUsageWindowResponse] = []
    seen: set[tuple[int, str]] = set()
    for window in windows:
        key = (window.windowDurationMinutes, window.resetAt)
        if key in seen:
            continue
        seen.add(key)
        deduped.append(window)
    return deduped
