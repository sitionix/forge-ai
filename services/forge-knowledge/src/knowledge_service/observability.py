from __future__ import annotations

import re
import sqlite3
import time
import uuid
from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Any, Dict, Optional

CORRELATION_HEADER = "X-Correlation-Id"
_CORRELATION_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
_route_metrics: ContextVar[Optional["RouteMetrics"]] = ContextVar("knowledge_route_metrics", default=None)


@dataclass
class RouteMetrics:
    route_key: str
    correlation_id: str
    started_ns: int = field(default_factory=time.perf_counter_ns)
    query_count: int = 0
    db_time_ns: int = 0
    response_bytes: int = 0

    def record_query(self, elapsed_ns: int) -> None:
        self.query_count += 1
        self.db_time_ns += max(0, elapsed_ns)

    @property
    def total_ms(self) -> float:
        return (time.perf_counter_ns() - self.started_ns) / 1_000_000

    @property
    def db_ms(self) -> float:
        return self.db_time_ns / 1_000_000

    def public(self) -> Dict[str, Any]:
        return {
            "routeKey": self.route_key,
            "correlationId": self.correlation_id,
            "durationMs": round(self.total_ms, 3),
            "dbDurationMs": round(self.db_ms, 3),
            "queryCount": self.query_count,
            "responseBytes": self.response_bytes,
        }

    def server_timing(self) -> str:
        return f'route;dur={self.total_ms:.3f}, db;dur={self.db_ms:.3f};desc="queries={self.query_count}"'


class ObservedConnection(sqlite3.Connection):
    def execute(self, *args: Any, **kwargs: Any):  # type: ignore[override]
        return _record_sqlite_call(lambda: super(ObservedConnection, self).execute(*args, **kwargs))

    def executemany(self, *args: Any, **kwargs: Any):  # type: ignore[override]
        return _record_sqlite_call(lambda: super(ObservedConnection, self).executemany(*args, **kwargs))

    def executescript(self, *args: Any, **kwargs: Any):  # type: ignore[override]
        return _record_sqlite_call(lambda: super(ObservedConnection, self).executescript(*args, **kwargs))


def observed_connect(path: Any, *, timeout: float) -> sqlite3.Connection:
    return sqlite3.connect(path, timeout=timeout, factory=ObservedConnection)


def sanitize_correlation_id(value: Optional[str]) -> str:
    candidate = (value or "").strip()
    if _CORRELATION_PATTERN.fullmatch(candidate):
        return candidate
    return f"corr-{uuid.uuid4().hex}"


def route_key(method: str, path: str) -> str:
    normalized = path.strip("/") or "root"
    normalized = re.sub(r"/[A-Za-z0-9_-]{8,}(?=/|$)", "/{id}", normalized)
    return f"{method.upper()} {normalized.replace('/', '.')}"


def start_route_metrics(route: str, correlation_id: str):
    return _route_metrics.set(RouteMetrics(route_key=route, correlation_id=correlation_id))


def current_route_metrics() -> Optional[RouteMetrics]:
    return _route_metrics.get()


def reset_route_metrics(token: Any) -> None:
    _route_metrics.reset(token)


def _record_sqlite_call(call):
    metrics = _route_metrics.get()
    start_ns = time.perf_counter_ns()
    try:
        return call()
    finally:
        if metrics is not None:
            metrics.record_query(time.perf_counter_ns() - start_ns)


class ObservabilityMiddleware:
    def __init__(self, app: Any) -> None:
        self.app = app

    async def __call__(self, scope: Dict[str, Any], receive: Any, send: Any) -> None:
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return
        headers = {key.decode("latin1").lower(): value.decode("latin1") for key, value in scope.get("headers", [])}
        correlation_id = sanitize_correlation_id(headers.get(CORRELATION_HEADER.lower()))
        token = start_route_metrics(f"{scope.get('method', 'GET')} {scope.get('path', '')}", correlation_id)

        async def observed_send(message: Dict[str, Any]) -> None:
            if message.get("type") == "http.response.start":
                metrics = current_route_metrics()
                raw_headers = list(message.get("headers", []))
                content_length = _header_value(raw_headers, "content-length")
                if metrics is not None:
                    metrics.response_bytes = int(content_length or 0)
                    _set_header(raw_headers, "server-timing", metrics.server_timing())
                    _set_header(raw_headers, "x-response-bytes", str(metrics.response_bytes))
                    _set_header(raw_headers, "x-route-key", metrics.route_key)
                    _set_header(raw_headers, CORRELATION_HEADER.lower(), correlation_id)
                message["headers"] = raw_headers
            await send(message)

        try:
            await self.app(scope, receive, observed_send)
        finally:
            reset_route_metrics(token)


def _header_value(headers: list[tuple[bytes, bytes]], name: str) -> Optional[str]:
    expected = name.lower().encode("latin1")
    for key, value in headers:
        if key.lower() == expected:
            return value.decode("latin1")
    return None


def _set_header(headers: list[tuple[bytes, bytes]], name: str, value: str) -> None:
    expected = name.lower().encode("latin1")
    encoded = (expected, value.encode("latin1"))
    for index, (key, _) in enumerate(headers):
        if key.lower() == expected:
            headers[index] = encoded
            return
    headers.append(encoded)
