from __future__ import annotations

import hashlib
import json
import logging
import sqlite3
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Sequence

from knowledge_service.embedding_provider import EmbeddingProvider, EmbeddingProviderError
from knowledge_service.graph_query_contract import graph_query_contract, sql_in_clause
from knowledge_service.observability import observed_connect
from knowledge_service.semantic_index import (
    SEMANTIC_BUILDER_VERSION,
    SEMANTIC_INVENTORY_MEMBERSHIP_NODE_FILTER_SQL,
    SQLITE_SEMANTIC_BUSY_TIMEOUT_MS,
    SemanticGraphInfo,
    SemanticIndexStatus,
    SemanticIndexStore,
    ensure_semantic_index_schema,
)

SEMANTIC_DOCUMENT_TYPE = "NODE_CONTEXT"
LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True)
class SemanticBuildConfig:
    enabled: bool = True
    embedding_model: str = "embeddinggemma"
    batch_size: int = 16
    max_document_chars: int = 4000
    max_edges_per_document: int = 20
    max_documents_per_build: int = 20000
    builder_version: int = SEMANTIC_BUILDER_VERSION


@dataclass(frozen=True)
class SemanticDocument:
    document_id: str
    source_id: str
    node_id: str
    node_kind: str
    document_type: str
    graph_id: str
    builder_version: int
    text_hash: str
    text: str
    claim_ids: tuple[str, ...] = ()
    evidence_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class SemanticSourceBuildResult:
    source_id: str
    status: str
    graph_revision: str | None
    total_node_count: int
    indexed_node_count: int
    diagnostics: list[dict[str, Any]] = field(default_factory=list)


@dataclass(frozen=True)
class SemanticBuildRunResult:
    build_id: str
    status: str
    source_ids: list[str]
    diagnostics: list[dict[str, Any]] = field(default_factory=list)
    results: list[SemanticSourceBuildResult] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "jobId": self.build_id,
            "status": self.status,
            "sourceIds": self.source_ids,
            "diagnostics": self.diagnostics,
            "results": [
                {
                    "sourceId": result.source_id,
                    "status": result.status,
                    "graphRevision": result.graph_revision,
                    "totalNodeCount": result.total_node_count,
                    "indexedNodeCount": result.indexed_node_count,
                    "diagnostics": result.diagnostics,
                }
                for result in self.results
            ],
        }


class SemanticBuildError(Exception):
    def __init__(self, code: str, message: str, *, diagnostics: list[dict[str, Any]] | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.diagnostics = diagnostics or [{"code": code, "message": message, "severity": "WARN"}]


class SemanticDocumentBuilder:
    def __init__(self, config: SemanticBuildConfig | None = None) -> None:
        self.config = config or SemanticBuildConfig()

    def build_source_documents(
        self,
        conn: sqlite3.Connection,
        source_id: str,
        *,
        graph_info: SemanticGraphInfo | None = None,
    ) -> list[SemanticDocument]:
        graph = graph_info or SemanticIndexStore.current_graph_info_conn(conn, source_id)
        if not graph.graph_id or not graph.graph_revision:
            return []
        nodes = self._load_nodes(conn, source_id)
        if not nodes:
            return []
        evidence_ids = self._load_evidence_ids(conn, source_id)
        claims_by_node = self._load_trusted_responsibility_claims(conn, source_id, evidence_ids)
        edges_by_node = self._load_edge_facts(conn, source_id)
        return [
            self._build_node_document(node, claims_by_node.get(str(node["id"]), ()), edges_by_node, graph.graph_id)
            for node in nodes
        ]

    def _load_nodes(self, conn: sqlite3.Connection, source_id: str) -> list[sqlite3.Row]:
        contract = graph_query_contract()
        status_sql, status_params = sql_in_clause(contract.statuses_for_current_graph())
        node_kind_sql, node_kind_params = sql_in_clause(contract.semantic_node_kinds)
        return conn.execute(
            f"""
            SELECT n.*,
                   af.relative_path AS relative_path,
                   parent.name AS parent_name,
                   parent.qualified_name AS parent_qualified_name,
                   parent.display_name AS parent_display_name
            FROM analysis_graph_nodes n
            LEFT JOIN analysis_files af
              ON af.file_id = n.analysis_file_id
             AND af.source_id = n.source_id
            LEFT JOIN analysis_graph_nodes parent
              ON parent.source_id = n.source_id
             AND parent.id = n.parent_node_id
            WHERE n.source_id = ?
              AND n.status IN ({status_sql})
              AND n.node_kind IN ({node_kind_sql})
              {SEMANTIC_INVENTORY_MEMBERSHIP_NODE_FILTER_SQL}
            ORDER BY n.source_id, lower(COALESCE(n.display_name, n.qualified_name, n.name, n.id)), n.id
            """,
            (source_id, *status_params, *node_kind_params),
        ).fetchall()

    def _load_evidence_ids(self, conn: sqlite3.Connection, source_id: str) -> set[str]:
        return {
            str(row["id"])
            for row in conn.execute(
                """
                SELECT id
                FROM analysis_graph_evidence
                WHERE source_id = ?
                """,
                (source_id,),
            ).fetchall()
        }

    def _load_trusted_responsibility_claims(
        self,
        conn: sqlite3.Connection,
        source_id: str,
        evidence_ids: set[str],
    ) -> dict[str, tuple[dict[str, Any], ...]]:
        contract = graph_query_contract()
        rows = conn.execute(
            """
            SELECT claim.id, claim.node_id, claim.summary, link.evidence_id
            FROM analysis_graph_claims claim
            JOIN analysis_graph_claim_evidence link ON link.claim_id = claim.id
            WHERE claim.source_id = ?
              AND claim.claim_kind = ?
              AND claim.status = ?
            ORDER BY claim.node_id, claim.id, link.evidence_id
            """,
            (source_id, contract.responsibility_claim_kind, contract.trusted_status),
        ).fetchall()
        grouped: dict[str, list[dict[str, Any]]] = {}
        for row in rows:
            parsed_evidence_ids = (str(row["evidence_id"]),) if str(row["evidence_id"]) in evidence_ids else ()
            summary = str(row["summary"] or "").strip()
            if not parsed_evidence_ids or not summary:
                continue
            node_claims = grouped.setdefault(str(row["node_id"]), [])
            existing = next((item for item in node_claims if item["id"] == str(row["id"])), None)
            if existing:
                existing["evidence_ids"] = tuple(sorted({*existing["evidence_ids"], *parsed_evidence_ids}))
            else:
                node_claims.append({"id": str(row["id"]), "summary": summary, "evidence_ids": parsed_evidence_ids})
        return {node_id: tuple(values) for node_id, values in grouped.items()}

    def _load_edge_facts(self, conn: sqlite3.Connection, source_id: str) -> dict[str, dict[str, list[dict[str, str]]]]:
        contract = graph_query_contract()
        status_sql, status_params = sql_in_clause(contract.statuses_for_current_graph())
        rows = conn.execute(
            f"""
            SELECT e.id, e.from_node_id, e.to_node_id, e.edge_type, e.resolution_status, e.unresolved_target_json,
                   from_node.name AS from_name,
                   from_node.qualified_name AS from_qualified_name,
                   from_node.display_name AS from_display_name,
                   to_node.name AS to_name,
                   to_node.qualified_name AS to_qualified_name,
                   to_node.display_name AS to_display_name
            FROM analysis_graph_edges e
            LEFT JOIN analysis_graph_nodes from_node
              ON from_node.source_id = e.source_id
             AND from_node.id = e.from_node_id
            LEFT JOIN analysis_graph_nodes to_node
              ON to_node.source_id = e.source_id
             AND to_node.id = e.to_node_id
            WHERE e.source_id = ?
              AND e.status IN ({status_sql})
            ORDER BY e.edge_type, e.id
            """,
            (source_id, *status_params),
        ).fetchall()
        allowed_edge_types = set(contract.semantic_edge_types)
        external_target_status = contract.external_target_status
        grouped: dict[str, dict[str, list[dict[str, str]]]] = {}
        for row in rows:
            edge_type = str(row["edge_type"] or "").upper()
            resolution_status = str(row["resolution_status"] or "").upper()
            if edge_type not in allowed_edge_types and resolution_status != external_target_status:
                continue
            from_node_id = str(row["from_node_id"] or "")
            to_node_id = str(row["to_node_id"] or "")
            from_label = _node_label(row, "from")
            to_label = _node_label(row, "to") or _unresolved_target_label(row["unresolved_target_json"])
            if from_node_id and to_label:
                grouped.setdefault(from_node_id, {"outgoing": [], "incoming": []})["outgoing"].append(
                    {"edge_type": edge_type, "resolution_status": resolution_status, "label": to_label}
                )
            if to_node_id and from_label:
                grouped.setdefault(to_node_id, {"outgoing": [], "incoming": []})["incoming"].append(
                    {"edge_type": edge_type, "resolution_status": resolution_status, "label": from_label}
                )
        return grouped

    def _build_node_document(
        self,
        node: sqlite3.Row,
        claims: Sequence[dict[str, Any]],
        edges_by_node: Mapping[str, Mapping[str, Sequence[Mapping[str, str]]]],
        graph_id: str,
    ) -> SemanticDocument:
        node_id = str(node["id"])
        lines = [
            f"Node kind: {node['node_kind']}",
            f"Node ID: {node_id}",
            f"Name: {node['name']}",
        ]
        _append_line(lines, "Qualified name", node["qualified_name"])
        _append_line(lines, "Display name", node["display_name"])
        _append_line(lines, "Source", node["source_id"])
        relative_path = str(node["relative_path"] or "")
        if not relative_path and str(node["node_kind"]).upper() == "FILE":
            relative_path = str(node["name"] or "")
        _append_line(lines, "Path", relative_path)
        if node["line_start"] is not None or node["line_end"] is not None:
            lines.append(f"Line range: {node['line_start'] or ''}-{node['line_end'] or ''}".rstrip("-"))
        parent_label = _first_text(node["parent_qualified_name"], node["parent_display_name"], node["parent_name"], node["parent_node_id"])
        _append_line(lines, "Parent", parent_label)

        claim_ids: list[str] = []
        evidence_ids: list[str] = []
        if claims:
            lines.extend(["", "Responsibility:"])
            for claim in claims:
                lines.append(f"- {claim['summary']}")
                claim_ids.append(str(claim["id"]))
                for evidence_id in claim["evidence_ids"]:
                    if evidence_id not in evidence_ids:
                        evidence_ids.append(str(evidence_id))

        edge_facts = edges_by_node.get(node_id, {})
        outgoing, incoming = self._bounded_edges(edge_facts)
        if outgoing:
            lines.extend(["", "Outgoing edges:"])
            lines.extend(_edge_line(edge) for edge in outgoing)
        if incoming:
            lines.extend(["", "Incoming edges:"])
            lines.extend(_edge_line(edge) for edge in incoming)
        if claim_ids:
            lines.extend(["", "Claim IDs:"])
            lines.extend(f"- {claim_id}" for claim_id in claim_ids)
        if evidence_ids:
            lines.extend(["", "Evidence IDs:"])
            lines.extend(f"- {evidence_id}" for evidence_id in evidence_ids)

        text = "\n".join(lines).strip()
        max_chars = max(1, int(self.config.max_document_chars or 1))
        if len(text) > max_chars:
            text = text[:max_chars].rstrip()
        text_hash = hashlib.sha256(text.encode("utf-8")).hexdigest()
        document_id = _document_id(str(node["source_id"]), node_id, graph_id, self.config.builder_version)
        return SemanticDocument(
            document_id=document_id,
            source_id=str(node["source_id"]),
            node_id=node_id,
            node_kind=str(node["node_kind"]),
            document_type=SEMANTIC_DOCUMENT_TYPE,
            graph_id=graph_id,
            builder_version=self.config.builder_version,
            text_hash=text_hash,
            text=text,
            claim_ids=tuple(claim_ids),
            evidence_ids=tuple(evidence_ids),
        )

    def _bounded_edges(self, edge_facts: Mapping[str, Sequence[Mapping[str, str]]]) -> tuple[list[Mapping[str, str]], list[Mapping[str, str]]]:
        remaining = max(0, int(self.config.max_edges_per_document or 0))
        outgoing = sorted(edge_facts.get("outgoing") or (), key=_edge_sort_key)[:remaining]
        remaining -= len(outgoing)
        incoming = sorted(edge_facts.get("incoming") or (), key=_edge_sort_key)[: max(0, remaining)]
        return list(outgoing), list(incoming)


class SemanticIndexBuilder:
    def __init__(
        self,
        db_path: Path,
        embedding_provider: EmbeddingProvider,
        *,
        config: SemanticBuildConfig | None = None,
        document_builder: SemanticDocumentBuilder | None = None,
    ) -> None:
        self.db_path = db_path
        self.embedding_provider = embedding_provider
        self.config = config or SemanticBuildConfig(embedding_model=embedding_provider.model)
        self.document_builder = document_builder or SemanticDocumentBuilder(self.config)

    def build(self, source_ids: Sequence[str] | None = None, *, force: bool = False, build_id: str | None = None) -> SemanticBuildRunResult:
        build_id = build_id or f"semantic-build-{uuid.uuid4()}"
        if not self.config.enabled:
            diagnostic = {"code": "SEMANTIC_DISABLED", "message": "Semantic indexing is disabled.", "severity": "INFO"}
            return SemanticBuildRunResult(build_id=build_id, status="COMPLETED", source_ids=[], diagnostics=[diagnostic], results=[])
        selected = self._select_source_ids(source_ids, force=force)
        results: list[SemanticSourceBuildResult] = []
        diagnostics: list[dict[str, Any]] = []
        for source_id in selected:
            result = self._build_source(source_id, force=force, build_id=build_id)
            results.append(result)
            diagnostics.extend(result.diagnostics)
        status = "COMPLETED" if all(result.status in {"READY", "SKIPPED"} for result in results) else "FAILED"
        return SemanticBuildRunResult(build_id=build_id, status=status, source_ids=selected, diagnostics=_dedupe_diagnostics(diagnostics), results=results)

    def _build_source(self, source_id: str, *, force: bool, build_id: str) -> SemanticSourceBuildResult:
        graph = self._graph_info(source_id)
        if not graph.graph_id or not graph.graph_revision or graph.total_node_count <= 0:
            return SemanticSourceBuildResult(source_id, "SKIPPED", graph.graph_revision, 0, 0)
        if not force:
            status = self._status_for_source(source_id)
            if status.status == SemanticIndexStatus.READY and status.graph_revision == graph.graph_revision:
                if status.ready:
                    return SemanticSourceBuildResult(source_id, "SKIPPED", graph.graph_revision, status.total_node_count, status.indexed_node_count)
            elif status.status not in {SemanticIndexStatus.PENDING, SemanticIndexStatus.STALE, SemanticIndexStatus.FAILED, SemanticIndexStatus.MISSING}:
                return SemanticSourceBuildResult(source_id, "SKIPPED", graph.graph_revision, status.total_node_count, status.indexed_node_count)
        documents = self._build_documents(source_id, graph)
        total = len(documents)
        if total <= 0:
            return SemanticSourceBuildResult(source_id, "SKIPPED", graph.graph_revision, 0, 0)
        if total > self.config.max_documents_per_build:
            return self._mark_failed(
                source_id,
                graph,
                build_id,
                "Semantic document build exceeded maxDocumentsPerBuild.",
                [{"code": "SEMANTIC_BUILD_FAILED", "message": "Semantic document build exceeded maxDocumentsPerBuild.", "severity": "WARN"}],
            )
        self._mark_building(source_id, graph, total, 0, build_id)
        try:
            vectors = self._embed_documents(source_id, graph, documents, build_id)
            dimension = self._validate_dimensions(vectors)
            self._replace_documents_and_vectors(source_id, documents, vectors, dimension)
            self._mark_ready(source_id, graph, total, len(vectors), dimension, build_id)
            return SemanticSourceBuildResult(source_id, "READY", graph.graph_revision, total, len(vectors))
        except SemanticBuildError as exc:
            return self._mark_failed(source_id, graph, build_id, exc.message, exc.diagnostics)
        except EmbeddingProviderError as exc:
            return self._mark_failed(source_id, graph, build_id, exc.message, [exc.diagnostic()])
        except Exception:  # noqa: BLE001 - per-source builds fail closed and let other sources continue.
            diagnostic = {"code": "SEMANTIC_BUILD_FAILED", "message": "Semantic index build failed.", "severity": "WARN"}
            return self._mark_failed(source_id, graph, build_id, "Semantic index build failed.", [diagnostic])

    def _select_source_ids(self, source_ids: Sequence[str] | None, *, force: bool) -> list[str]:
        explicit = sorted({str(source_id) for source_id in (source_ids or []) if str(source_id)})
        if explicit:
            return explicit
        with self._connect() as conn:
            if not _table_exists(conn, "analysis_graph_nodes"):
                return []
            rows = conn.execute(
                """
                SELECT DISTINCT source_id
                FROM analysis_graph_nodes
                ORDER BY source_id
                """,
            ).fetchall()
            selected: list[str] = []
            for row in rows:
                source_id = str(row["source_id"])
                status = SemanticIndexStore.status_for_source_conn(conn, source_id)
                if force or status.status in {SemanticIndexStatus.PENDING, SemanticIndexStatus.STALE, SemanticIndexStatus.FAILED}:
                    selected.append(source_id)
            return selected

    def _build_documents(self, source_id: str, graph: SemanticGraphInfo) -> list[SemanticDocument]:
        with self._connect() as conn:
            return self.document_builder.build_source_documents(conn, source_id, graph_info=graph)

    def _embed_documents(
        self,
        source_id: str,
        graph: SemanticGraphInfo,
        documents: Sequence[SemanticDocument],
        build_id: str,
    ) -> list[list[float]]:
        batch_size = max(1, int(self.config.batch_size or 1))
        vectors: list[list[float]] = []
        for start in range(0, len(documents), batch_size):
            batch = documents[start : start + batch_size]
            batch_vectors = self.embedding_provider.embed_texts([document.text for document in batch])
            if len(batch_vectors) != len(batch):
                raise SemanticBuildError(
                    "SEMANTIC_BUILD_FAILED",
                    "Semantic embedding provider returned a different vector count than requested.",
                    diagnostics=[
                        {
                            "code": "SEMANTIC_BUILD_FAILED",
                            "message": "Semantic embedding provider returned a different vector count than requested.",
                            "severity": "WARN",
                        }
                    ],
                )
            vectors.extend(batch_vectors)
            self._mark_building(source_id, graph, len(documents), len(vectors), build_id)
        return vectors

    def _validate_dimensions(self, vectors: Sequence[Sequence[float]]) -> int:
        dimension: int | None = None
        for vector in vectors:
            if not vector:
                raise SemanticBuildError(
                    "SEMANTIC_BUILD_FAILED",
                    "Semantic embedding provider returned an empty vector.",
                    diagnostics=[{"code": "SEMANTIC_BUILD_FAILED", "message": "Semantic embedding provider returned an empty vector.", "severity": "WARN"}],
                )
            current = len(vector)
            if dimension is None:
                dimension = current
            elif current != dimension:
                raise SemanticBuildError(
                    "SEMANTIC_BUILD_FAILED",
                    "Semantic embedding provider returned mixed vector dimensions.",
                    diagnostics=[
                        {
                            "code": "SEMANTIC_BUILD_FAILED",
                            "message": "Semantic embedding provider returned mixed vector dimensions.",
                            "severity": "WARN",
                            "metadata": {"expectedDimension": dimension, "actualDimension": current},
                        }
                    ],
                )
        return int(dimension or 0)

    def _replace_documents_and_vectors(
        self,
        source_id: str,
        documents: Sequence[SemanticDocument],
        vectors: Sequence[Sequence[float]],
        dimension: int,
    ) -> None:
        now = _now()
        with self._connect() as conn:
            conn.execute(
                """
                DELETE FROM semantic_vectors
                WHERE source_id = ?
                """,
                (source_id,),
            )
            conn.execute(
                """
                DELETE FROM semantic_documents
                WHERE source_id = ?
                """,
                (source_id,),
            )
            for document, vector in zip(documents, vectors):
                conn.execute(
                    """
                    INSERT INTO semantic_documents(
                        document_id, source_id, node_id, node_kind, document_type, graph_id, builder_version,
                        text_hash, text, claim_ids_payload, evidence_ids_payload, status, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?, ?)
                    """,
                    (
                        document.document_id,
                        document.source_id,
                        document.node_id,
                        document.node_kind,
                        document.document_type,
                        document.graph_id,
                        document.builder_version,
                        document.text_hash,
                        document.text,
                        json.dumps(list(document.claim_ids), separators=(",", ":")),
                        json.dumps(list(document.evidence_ids), separators=(",", ":")),
                        now,
                        now,
                    ),
                )
                conn.execute(
                    """
                    INSERT INTO semantic_vectors(
                        document_id, source_id, node_id, graph_id, embedding_model, embedding_dimension,
                        vector_json, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        document.document_id,
                        document.source_id,
                        document.node_id,
                        document.graph_id,
                        self.embedding_provider.model,
                        dimension,
                        json.dumps([float(value) for value in vector], separators=(",", ":")),
                        now,
                        now,
                    ),
                )

    def _graph_info(self, source_id: str) -> SemanticGraphInfo:
        with self._connect() as conn:
            return SemanticIndexStore.current_graph_info_conn(conn, source_id)

    def _status_for_source(self, source_id: str):
        with self._connect() as conn:
            return SemanticIndexStore.status_for_source_conn(conn, source_id)

    def _mark_building(self, source_id: str, graph: SemanticGraphInfo, total: int, indexed: int, build_id: str) -> None:
        with self._connect() as conn:
            SemanticIndexStore.mark_source_building_conn(
                conn,
                source_id,
                graph.graph_revision or "",
                total,
                indexed_node_count=indexed,
                build_id=build_id,
                builder_version=self.config.builder_version,
            )

    def _mark_ready(self, source_id: str, graph: SemanticGraphInfo, total: int, indexed: int, dimension: int, build_id: str) -> None:
        with self._connect() as conn:
            SemanticIndexStore.mark_source_ready_conn(
                conn,
                source_id,
                graph.graph_revision or "",
                total,
                indexed,
                embedding_model=self.embedding_provider.model,
                embedding_dimension=dimension,
                build_id=build_id,
                builder_version=self.config.builder_version,
            )

    def _mark_failed(
        self,
        source_id: str,
        graph: SemanticGraphInfo,
        build_id: str,
        error: str,
        diagnostics: list[dict[str, Any]],
    ) -> SemanticSourceBuildResult:
        with self._connect() as conn:
            status = SemanticIndexStore.mark_source_failed_conn(
                conn,
                source_id,
                graph.graph_revision or "",
                graph.total_node_count,
                error=error,
                diagnostics=diagnostics,
                build_id=build_id,
                builder_version=self.config.builder_version,
            )
        diagnostic = diagnostics[0] if diagnostics else {}
        LOGGER.warning(
            "Semantic index build failed sourceId=%s buildId=%s diagnosticCode=%s diagnosticMessage=%s",
            source_id,
            build_id,
            diagnostic.get("code"),
            diagnostic.get("message") or error,
        )
        return SemanticSourceBuildResult(
            source_id,
            "FAILED",
            graph.graph_revision,
            status.total_node_count,
            status.indexed_node_count,
            diagnostics,
        )

    def _connect(self) -> sqlite3.Connection:
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        conn = observed_connect(self.db_path, timeout=SQLITE_SEMANTIC_BUSY_TIMEOUT_MS / 1000.0)
        conn.execute(f"PRAGMA busy_timeout = {SQLITE_SEMANTIC_BUSY_TIMEOUT_MS}")
        conn.execute("PRAGMA foreign_keys = ON")
        conn.row_factory = sqlite3.Row
        ensure_semantic_index_schema(conn)
        return conn


def _document_id(source_id: str, node_id: str, graph_id: str, builder_version: int) -> str:
    digest = hashlib.sha256()
    for value in (source_id, node_id, graph_id, str(builder_version), SEMANTIC_DOCUMENT_TYPE):
        digest.update(value.encode("utf-8"))
        digest.update(b"\0")
    return f"semantic-doc:{digest.hexdigest()}"


def _append_line(lines: list[str], label: str, value: Any) -> None:
    text = str(value or "").strip()
    if text:
        lines.append(f"{label}: {text}")


def _node_label(row: sqlite3.Row, prefix: str) -> str:
    return _first_text(row[f"{prefix}_qualified_name"], row[f"{prefix}_display_name"], row[f"{prefix}_name"])


def _unresolved_target_label(value: Any) -> str:
    try:
        parsed = json.loads(value or "{}")
    except (TypeError, ValueError):
        return ""
    if not isinstance(parsed, dict):
        return ""
    return _first_text(parsed.get("qualifiedName"), parsed.get("qualified_name"), parsed.get("displayName"), parsed.get("name"), parsed.get("target"))


def _first_text(*values: Any) -> str:
    for value in values:
        text = str(value or "").strip()
        if text:
            return text
    return ""


def _edge_sort_key(edge: Mapping[str, str]) -> tuple[str, str, str]:
    return (str(edge.get("edge_type") or ""), str(edge.get("label") or "").lower(), str(edge.get("resolution_status") or ""))


def _edge_line(edge: Mapping[str, str]) -> str:
    edge_type = str(edge.get("edge_type") or "")
    resolution_status = str(edge.get("resolution_status") or "")
    label = str(edge.get("label") or "")
    suffix = f" [{resolution_status}]" if resolution_status else ""
    return f"- {edge_type}: {label}{suffix}"


def _table_exists(conn: sqlite3.Connection, table_name: str) -> bool:
    return conn.execute("SELECT 1 FROM sqlite_master WHERE type IN ('table', 'virtual table') AND name = ?", (table_name,)).fetchone() is not None


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _dedupe_diagnostics(diagnostics: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    deduped: list[dict[str, Any]] = []
    seen: set[tuple[str, str, str, str, str]] = set()
    for diagnostic in diagnostics:
        item = dict(diagnostic or {})
        metadata = item.get("metadata") if isinstance(item.get("metadata"), Mapping) else {}
        key = (
            str(item.get("code") or ""),
            str(item.get("message") or ""),
            str(item.get("severity") or ""),
            str(item.get("sourceId") or ""),
            json.dumps(metadata, sort_keys=True, separators=(",", ":")),
        )
        if key in seen:
            continue
        seen.add(key)
        deduped.append(item)
    return deduped
