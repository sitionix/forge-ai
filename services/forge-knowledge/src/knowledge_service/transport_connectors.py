from __future__ import annotations

import hashlib
import json
import re
import sqlite3
from dataclasses import dataclass
from typing import Any, Iterable, Sequence

from knowledge_service.entrypoint_kinds import EntrypointExecutionKind, EntrypointKind
from knowledge_service.graph_query_contract import graph_query_contract, sql_in_clause


CONNECTOR_REASON = "CROSS_SOURCE_HTTP_EXECUTION_CONNECTOR"
CONNECTOR_AMBIGUOUS = "CROSS_SOURCE_HTTP_CONNECTOR_AMBIGUOUS"
CONNECTOR_INCOMPLETE = "CROSS_SOURCE_HTTP_CONNECTOR_INCOMPLETE"


@dataclass(frozen=True)
class TransportConnectorResolution:
    affected_source_ids: tuple[str, ...]
    inserted_connector_count: int
    deleted_connector_count: int
    diagnostic_count: int


class TransportConnectorResolver:
    def resolve(self, conn: sqlite3.Connection, created_at: str) -> TransportConnectorResolution:
        raise NotImplementedError


class HttpTransportConnectorResolver(TransportConnectorResolver):
    def __init__(self, store: Any) -> None:
        self.store = store

    def resolve(self, conn: sqlite3.Connection, created_at: str) -> TransportConnectorResolution:
        affected: set[str] = set()
        affected.update(self._refresh_http_client_operations(conn, created_at))
        old_connector_sources = self._existing_connector_sources(conn)
        old_diagnostic_sources = self._connector_diagnostic_sources(conn)
        self._delete_connector_diagnostics(conn)
        desired_edge_ids: set[str] = set()
        inserted = 0
        diagnostics = 0
        targets = self._http_executable_targets(conn)
        targets_by_method_route: dict[tuple[str, str], list[sqlite3.Row]] = {}
        for target in targets:
            key = (str(target["entrypoint_http_method"] or "").upper(), self._normalize_route_template(target["entrypoint_route"]))
            targets_by_method_route.setdefault(key, []).append(target)
        for operation in self._http_client_operations(conn):
            method = str(operation["http_method"] or "").upper()
            route = self._normalize_route_template(operation["normalized_route_template"])
            if not method or not route:
                self._insert_connector_diagnostic(
                    conn,
                    operation,
                    CONNECTOR_INCOMPLETE,
                    "A persisted HTTP client operation did not include enough exact transport facts.",
                    created_at,
                )
                affected.add(str(operation["source_id"]))
                diagnostics += 1
                continue
            candidates = [
                target
                for target in targets_by_method_route.get((method, route), [])
                if str(target["source_id"]) != str(operation["source_id"]) or str(target["node_id"]) != str(operation["node_id"])
            ]
            if len(candidates) != 1:
                code = CONNECTOR_AMBIGUOUS if len(candidates) > 1 else CONNECTOR_INCOMPLETE
                message = (
                    "A persisted HTTP client operation matched more than one executable target; no connector was persisted."
                    if len(candidates) > 1
                    else "A persisted HTTP client operation did not have one exact executable target."
                )
                self._insert_connector_diagnostic(
                    conn,
                    operation,
                    code,
                    message,
                    created_at,
                    candidate_count=len(candidates) if candidates else None,
                )
                affected.add(str(operation["source_id"]))
                diagnostics += 1
                continue
            target = candidates[0]
            edge_id = self._connector_edge_id(operation, target)
            desired_edge_ids.add(edge_id)
            if self._insert_connector_edge(conn, operation, target, edge_id, method, route, created_at):
                inserted += 1
            affected.update((str(operation["source_id"]), str(target["source_id"])))
        deleted_sources = self._delete_stale_connectors(conn, desired_edge_ids)
        deleted = len(deleted_sources["edgeIds"])
        affected.update(deleted_sources["sourceIds"])
        affected.update(old_connector_sources)
        affected.update(old_diagnostic_sources)
        return TransportConnectorResolution(
            affected_source_ids=tuple(sorted(source_id for source_id in affected if source_id)),
            inserted_connector_count=inserted,
            deleted_connector_count=deleted,
            diagnostic_count=diagnostics,
        )

    def _refresh_http_client_operations(self, conn: sqlite3.Connection, created_at: str) -> set[str]:
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
        rows = conn.execute(
            f"""
            SELECT edge.id AS edge_id,
                   edge.source_id,
                   edge.from_node_id AS node_id,
                   edge.job_id,
                   edge.inventory_file_id,
                   edge.analysis_file_id,
                   edge.file_id,
                   edge.relative_path,
                   edge.content_hash,
                   edge.fact_origin,
                   edge.flow_domain,
                   caller.qualified_name,
                   caller.name,
                   ev.id AS evidence_id,
                   ev.source_id AS evidence_source_id,
                   ev.excerpt
            FROM analysis_graph_edges edge
            JOIN analysis_graph_nodes caller
              ON caller.source_id = edge.source_id
             AND caller.id = edge.from_node_id
             AND caller.status IN ({current_status_sql})
            JOIN analysis_graph_owner_evidence owner
              ON owner.owner_kind = 'EDGE'
             AND owner.owner_source_id = edge.source_id
             AND owner.owner_edge_id = edge.id
            JOIN analysis_graph_evidence ev
              ON ev.source_id = owner.evidence_source_id
             AND ev.id = owner.evidence_id
            WHERE edge.edge_type = ?
              AND edge.status IN ({current_status_sql})
              AND edge.resolution_status IN (?, ?, ?)
            ORDER BY edge.source_id, edge.from_node_id, edge.id, ev.line_start, ev.id
            """,
            (
                *current_status_params,
                contract.calls_edge_type,
                *current_status_params,
                contract.resolved_status,
                contract.unresolved_status,
                contract.external_target_status,
            ),
        ).fetchall()
        operations: dict[tuple[str, str, str, str], sqlite3.Row] = {}
        for row in rows:
            operation = self._operation_from_evidence(row)
            if operation is None:
                continue
            operations.setdefault((operation[0], operation[1], operation[2], operation[3]), row)
        desired_ids: set[str] = set()
        affected: set[str] = set()
        desired_claim_ids: set[str] = set()
        for source_id, node_id, method, route in sorted(operations):
            row = operations[(source_id, node_id, method, route)]
            op_id = self._derived_id("analysis-graph-transport-operation", source_id, node_id, method, route)
            claim_id = self._derived_id("analysis-graph-claim", source_id, node_id, "HTTP_CLIENT_OPERATION", method, route)
            desired_ids.add(op_id)
            desired_claim_ids.add(claim_id)
            operation_payload = {
                "id": op_id,
                "source_id": source_id,
                "node_id": node_id,
                "transport_kind": "HTTP",
                "operation_role": EntrypointExecutionKind.CLIENT_OPERATION.value,
                "http_method": method,
                "normalized_route_template": route,
                "operation_identity": f"HTTP {method} {route}",
                "request_contract_identity": None,
                "response_contract_identity": None,
                "target_service_identity": None,
                "evidence_source_id": row["evidence_source_id"],
                "evidence_id": row["evidence_id"],
                "status": contract.derived_status,
                "fact_origin": row["fact_origin"],
                "flow_domain": row["flow_domain"],
            }
            if self._transport_operation_changed(conn, operation_payload):
                affected.add(source_id)
                conn.execute(
                    """
                    INSERT OR REPLACE INTO analysis_graph_transport_operations(
                        id, source_id, node_id, transport_kind, operation_role, http_method, normalized_route_template,
                        operation_identity, request_contract_identity, response_contract_identity, target_service_identity,
                        evidence_source_id, evidence_id, status, created_at, updated_at, fact_origin, flow_domain
                    )
                    VALUES (?, ?, ?, 'HTTP', ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        op_id,
                        source_id,
                        node_id,
                        EntrypointExecutionKind.CLIENT_OPERATION.value,
                        method,
                        route,
                        f"HTTP {method} {route}",
                        row["evidence_source_id"],
                        row["evidence_id"],
                        contract.derived_status,
                        created_at,
                        created_at,
                        row["fact_origin"],
                        row["flow_domain"],
                    ),
                )
            claim_payload = {
                "id": claim_id,
                "job_id": row["job_id"],
                "source_id": source_id,
                "node_id": node_id,
                "claim_kind": contract.entrypoint_claim_kind,
                "summary": f"HTTP client operation {method} {route}",
                "confidence": 1.0,
                "status": contract.derived_status,
                "entrypoint_kind": EntrypointKind.HTTP.value,
                "entrypoint_http_method": method,
                "entrypoint_route": route,
                "entrypoint_interface_method": f"HTTP {method} {route}",
                "entrypoint_execution_kind": EntrypointExecutionKind.CLIENT_OPERATION.value,
                "fact_origin": row["fact_origin"],
                "flow_domain": row["flow_domain"],
            }
            if self._client_operation_claim_changed(conn, claim_payload):
                affected.add(source_id)
                conn.execute(
                    """
                    INSERT OR REPLACE INTO analysis_graph_claims(
                        id, job_id, source_id, node_id, claim_kind, summary, confidence, status,
                        rejection_reason, created_at, updated_at, entrypoint_kind,
                        entrypoint_http_method, entrypoint_route, entrypoint_topic, entrypoint_schedule,
                        entrypoint_interface_method, entrypoint_execution_kind, fact_origin, flow_domain
                    )
                    VALUES (?, ?, ?, ?, ?, ?, 1.0, ?, NULL, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?)
                    """,
                    (
                        claim_id,
                        row["job_id"],
                        source_id,
                        node_id,
                        contract.entrypoint_claim_kind,
                        f"HTTP client operation {method} {route}",
                        contract.derived_status,
                        created_at,
                        created_at,
                        EntrypointKind.HTTP.value,
                        method,
                        route,
                        f"HTTP {method} {route}",
                        EntrypointExecutionKind.CLIENT_OPERATION.value,
                        row["fact_origin"],
                        row["flow_domain"],
                    ),
                )
            if self._insert_claim_evidence(
                conn,
                claim_id,
                source_id,
                node_id,
                str(row["evidence_id"]),
                evidence_source_id=str(row["evidence_source_id"]),
            ):
                affected.add(source_id)
        stale_rows = conn.execute(
            """
            SELECT source_id
            FROM analysis_graph_transport_operations
            WHERE transport_kind = 'HTTP'
              AND operation_role = ?
              AND id NOT IN ({})
            """.format(",".join("?" for _ in desired_ids) or "''"),
            (EntrypointExecutionKind.CLIENT_OPERATION.value, *sorted(desired_ids)),
        ).fetchall()
        affected.update(str(row["source_id"]) for row in stale_rows if row["source_id"])
        if desired_ids:
            placeholders = ",".join("?" for _ in desired_ids)
            conn.execute(
                f"""
                DELETE FROM analysis_graph_transport_operations
                WHERE transport_kind = 'HTTP'
                  AND operation_role = ?
                  AND id NOT IN ({placeholders})
                """,
                (EntrypointExecutionKind.CLIENT_OPERATION.value, *sorted(desired_ids)),
            )
        else:
            conn.execute(
                """
                DELETE FROM analysis_graph_transport_operations
                WHERE transport_kind = 'HTTP'
                  AND operation_role = ?
                """,
                (EntrypointExecutionKind.CLIENT_OPERATION.value,),
            )
        self._delete_stale_client_operation_claims(conn, desired_claim_ids)
        return affected

    def _transport_operation_changed(self, conn: sqlite3.Connection, payload: dict[str, Any]) -> bool:
        row = conn.execute(
            """
            SELECT source_id, node_id, transport_kind, operation_role, http_method, normalized_route_template,
                   operation_identity, request_contract_identity, response_contract_identity, target_service_identity,
                   evidence_source_id, evidence_id, status, fact_origin, flow_domain
            FROM analysis_graph_transport_operations
            WHERE id = ?
            """,
            (payload["id"],),
        ).fetchone()
        if row is None:
            return True
        for key, value in payload.items():
            if key == "id":
                continue
            if (row[key] if key in row.keys() else None) != value:
                return True
        return False

    def _client_operation_claim_changed(self, conn: sqlite3.Connection, payload: dict[str, Any]) -> bool:
        row = conn.execute(
            """
            SELECT job_id, source_id, node_id, claim_kind, summary, confidence, status,
                   entrypoint_kind, entrypoint_http_method, entrypoint_route, entrypoint_interface_method,
                   entrypoint_execution_kind, fact_origin, flow_domain
            FROM analysis_graph_claims
            WHERE id = ?
            """,
            (payload["id"],),
        ).fetchone()
        if row is None:
            return True
        for key, value in payload.items():
            if key == "id":
                continue
            if key == "confidence":
                if float(row[key]) != float(value):
                    return True
                continue
            if (row[key] if key in row.keys() else None) != value:
                return True
        return False

    def _delete_stale_client_operation_claims(self, conn: sqlite3.Connection, desired_claim_ids: set[str]) -> None:
        contract = graph_query_contract()
        if desired_claim_ids:
            placeholders = ",".join("?" for _ in desired_claim_ids)
            conn.execute(
                f"""
                DELETE FROM analysis_graph_claims
                WHERE claim_kind = ?
                  AND status = ?
                  AND entrypoint_execution_kind = ?
                  AND id NOT IN ({placeholders})
                """,
                (
                    contract.entrypoint_claim_kind,
                    contract.derived_status,
                    EntrypointExecutionKind.CLIENT_OPERATION.value,
                    *sorted(desired_claim_ids),
                ),
            )
        else:
            conn.execute(
                """
                DELETE FROM analysis_graph_claims
                WHERE claim_kind = ?
                  AND status = ?
                  AND entrypoint_execution_kind = ?
                """,
                (contract.entrypoint_claim_kind, contract.derived_status, EntrypointExecutionKind.CLIENT_OPERATION.value),
            )

    def _operation_from_evidence(self, row: sqlite3.Row) -> tuple[str, str, str, str] | None:
        excerpt = str(row["excerpt"] or "")
        route = self._extract_route_template(excerpt)
        method = self._extract_http_method(excerpt)
        if not route or not method:
            return None
        return (str(row["source_id"]), str(row["node_id"]), method, route)

    def _extract_route_template(self, excerpt: str) -> str:
        for match in re.finditer(r'''["'](/[^"']*)["']''', excerpt):
            route = self._normalize_route_template(match.group(1))
            if route:
                return route
        return ""

    def _extract_http_method(self, excerpt: str) -> str:
        match = re.search(r"\b(?:HttpMethod|RequestMethod)\s*\.\s*([A-Z]+)\b", excerpt)
        if match:
            return match.group(1).upper()
        match = re.search(r"\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\b", excerpt)
        return match.group(1).upper() if match else ""

    def _http_client_operations(self, conn: sqlite3.Connection) -> list[sqlite3.Row]:
        contract = graph_query_contract()
        return conn.execute(
            """
            SELECT op.*,
                   node.job_id,
                   node.inventory_file_id,
                   node.analysis_file_id,
                   node.file_id,
                   node.relative_path,
                   node.content_hash,
                   node.qualified_name,
                   node.name
            FROM analysis_graph_transport_operations op
            JOIN analysis_graph_nodes node
              ON node.source_id = op.source_id
             AND node.id = op.node_id
             AND node.status IN (?, ?)
            WHERE op.transport_kind = 'HTTP'
              AND op.operation_role = ?
              AND op.status IN (?, ?)
            ORDER BY op.source_id, op.node_id, op.http_method, op.normalized_route_template, op.id
            """,
            (
                contract.trusted_status,
                contract.derived_status,
                EntrypointExecutionKind.CLIENT_OPERATION.value,
                contract.trusted_status,
                contract.derived_status,
            ),
        ).fetchall()

    def _http_executable_targets(self, conn: sqlite3.Connection) -> list[sqlite3.Row]:
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
        return conn.execute(
            f"""
            SELECT claim.id AS claim_id,
                   claim.source_id,
                   claim.node_id,
                   claim.entrypoint_http_method,
                   claim.entrypoint_route,
                   claim.entrypoint_interface_method,
                   claim.summary,
                   claim.confidence,
                   node.job_id,
                   node.inventory_file_id,
                   node.analysis_file_id,
                   node.file_id,
                   node.relative_path,
                   node.content_hash,
                   node.qualified_name,
                   node.name,
                   node.fact_origin,
                   node.flow_domain
            FROM analysis_graph_claims claim
            JOIN analysis_graph_nodes node
              ON node.source_id = claim.source_id
             AND node.id = claim.node_id
             AND node.status IN ({current_status_sql})
            WHERE claim.claim_kind = ?
              AND claim.status IN ({current_status_sql})
              AND claim.entrypoint_kind = ?
              AND COALESCE(claim.entrypoint_execution_kind, ?) = ?
              AND claim.entrypoint_http_method IS NOT NULL
              AND claim.entrypoint_route IS NOT NULL
            ORDER BY claim.source_id, claim.node_id, claim.id
            """,
            (
                *current_status_params,
                contract.entrypoint_claim_kind,
                *current_status_params,
                EntrypointKind.HTTP.value,
                EntrypointExecutionKind.EXECUTABLE.value,
                EntrypointExecutionKind.EXECUTABLE.value,
            ),
        ).fetchall()

    def _insert_connector_edge(
        self,
        conn: sqlite3.Connection,
        operation: sqlite3.Row,
        target: sqlite3.Row,
        edge_id: str,
        method: str,
        route: str,
        created_at: str,
    ) -> bool:
        contract = graph_query_contract()
        metadata = self.store._edge_metadata_for_storage(
            {
                "callKind": "TRANSPORT_CONNECTOR",
                "connectorKind": "HTTP",
                "httpMethod": method,
                "routeTemplate": route,
                "targetEntrypoint": target["qualified_name"] or target["name"],
                "targetSource": target["source_id"],
                "transportConnector": True,
                "operationRole": EntrypointExecutionKind.CLIENT_OPERATION.value,
                "resolutionReason": CONNECTOR_REASON,
            }
        )
        existing = conn.execute(
            "SELECT metadata_json FROM analysis_graph_edges WHERE id = ?",
            (edge_id,),
        ).fetchone()
        if existing is not None and self.store._json_dict(existing["metadata_json"]) == metadata:
            return self._ensure_connector_evidence(conn, edge_id, operation, target)
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_graph_edges(
                id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                from_node_id, to_node_id, to_source_id, edge_type, resolution_status, confidence,
                argument_count, unresolved_target_json, metadata_json, status, created_at, updated_at, fact_origin, flow_domain
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1.0, NULL, NULL, ?, ?, ?, ?, ?, ?)
            """,
            (
                edge_id,
                operation["job_id"],
                operation["source_id"],
                operation["inventory_file_id"],
                operation["analysis_file_id"],
                operation["file_id"],
                operation["relative_path"],
                operation["content_hash"],
                operation["node_id"],
                target["node_id"],
                target["source_id"],
                contract.calls_edge_type,
                contract.resolved_status,
                json.dumps(metadata),
                contract.derived_status,
                created_at,
                created_at,
                "RESOLVER",
                operation["flow_domain"],
            ),
        )
        self._ensure_connector_evidence(conn, edge_id, operation, target)
        return True

    def _ensure_connector_evidence(
        self,
        conn: sqlite3.Connection,
        edge_id: str,
        operation: sqlite3.Row,
        target: sqlite3.Row,
    ) -> bool:
        evidence_changed = self._insert_edge_evidence(
            conn,
            edge_id,
            str(operation["source_id"]),
            str(operation["evidence_id"]),
            evidence_source_id=str(operation["evidence_source_id"]),
        )
        for evidence_source_id, evidence_id in self._claim_evidence_refs(conn, str(target["claim_id"])):
            if self._insert_edge_evidence(
                conn,
                edge_id,
                str(operation["source_id"]),
                evidence_id,
                evidence_source_id=evidence_source_id,
            ):
                evidence_changed = True
        return evidence_changed

    def _delete_stale_connectors(self, conn: sqlite3.Connection, desired_edge_ids: set[str]) -> dict[str, set[str]]:
        existing = conn.execute(
            """
            SELECT id, source_id, to_source_id
            FROM analysis_graph_edges
            WHERE edge_type = ?
              AND status = ?
              AND json_extract(metadata_json, '$.resolutionReason') = ?
            """,
            (graph_query_contract().calls_edge_type, graph_query_contract().derived_status, CONNECTOR_REASON),
        ).fetchall()
        stale = [row for row in existing if str(row["id"]) not in desired_edge_ids]
        if not stale:
            return {"edgeIds": set(), "sourceIds": set()}
        stale_ids = sorted(str(row["id"]) for row in stale)
        stale_sources = {str(row["source_id"]) for row in stale if row["source_id"]}
        stale_sources.update(str(row["to_source_id"]) for row in stale if row["to_source_id"])
        placeholders = ",".join("?" for _ in stale_ids)
        conn.execute(
            f"""
            DELETE FROM analysis_graph_owner_evidence
            WHERE owner_kind = 'EDGE'
              AND owner_edge_id IN ({placeholders})
            """,
            stale_ids,
        )
        conn.execute(
            f"""
            DELETE FROM analysis_graph_edges
            WHERE id IN ({placeholders})
            """,
            stale_ids,
        )
        return {"edgeIds": set(stale_ids), "sourceIds": stale_sources}

    def _existing_connector_sources(self, conn: sqlite3.Connection) -> set[str]:
        rows = conn.execute(
            """
            SELECT source_id, to_source_id
            FROM analysis_graph_edges
            WHERE edge_type = ?
              AND status = ?
              AND json_extract(metadata_json, '$.resolutionReason') = ?
            """,
            (graph_query_contract().calls_edge_type, graph_query_contract().derived_status, CONNECTOR_REASON),
        ).fetchall()
        result: set[str] = set()
        for row in rows:
            result.add(str(row["source_id"]))
            if row["to_source_id"]:
                result.add(str(row["to_source_id"]))
        return result

    def _connector_diagnostic_sources(self, conn: sqlite3.Connection) -> set[str]:
        rows = conn.execute(
            """
            SELECT DISTINCT source_id
            FROM analysis_graph_diagnostics
            WHERE code IN (?, ?)
            """,
            (CONNECTOR_AMBIGUOUS, CONNECTOR_INCOMPLETE),
        ).fetchall()
        return {str(row["source_id"]) for row in rows if row["source_id"]}

    def _delete_connector_diagnostics(self, conn: sqlite3.Connection) -> None:
        conn.execute(
            """
            DELETE FROM analysis_graph_diagnostics
            WHERE code IN (?, ?)
            """,
            (CONNECTOR_AMBIGUOUS, CONNECTOR_INCOMPLETE),
        )

    def _insert_connector_diagnostic(
        self,
        conn: sqlite3.Connection,
        operation: sqlite3.Row,
        code: str,
        message: str,
        created_at: str,
        *,
        candidate_count: int | None = None,
    ) -> None:
        diagnostic_id = self._derived_id(
            "analysis-graph-diagnostic",
            str(operation["source_id"]),
            str(operation["node_id"]),
            code,
            str(operation["http_method"] or ""),
            str(operation["normalized_route_template"] or ""),
            str(candidate_count or 0),
        )
        metadata = {
            "node": operation["qualified_name"] or operation["name"],
            "transportKind": "HTTP",
            "httpMethod": operation["http_method"],
            "routeTemplate": operation["normalized_route_template"],
            "candidateCount": candidate_count,
        }
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_graph_diagnostics(
                id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                severity, stage, code, message, candidate_id, line_start, line_end, metadata_json, created_at, fact_origin, flow_domain
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'WARN', 'GRAPH_RESOLVE', ?, ?, ?, NULL, NULL, ?, ?, 'RESOLVER', ?)
            """,
            (
                diagnostic_id,
                operation["job_id"],
                operation["source_id"],
                operation["inventory_file_id"],
                operation["analysis_file_id"],
                operation["file_id"],
                operation["relative_path"],
                operation["content_hash"],
                code,
                message,
                operation["node_id"],
                json.dumps({key: value for key, value in metadata.items() if value is not None}),
                created_at,
                operation["flow_domain"],
            ),
        )

    def _insert_edge_evidence(
        self,
        conn: sqlite3.Connection,
        edge_id: str,
        owner_source_id: str,
        evidence_id: str,
        *,
        evidence_source_id: str | None = None,
    ) -> bool:
        legacy_cursor = conn.execute(
            "INSERT OR IGNORE INTO analysis_graph_edge_evidence(edge_id, evidence_id) VALUES (?, ?)",
            (edge_id, evidence_id),
        )
        owner_inserted = self.store._insert_owner_evidence_link(
            conn,
            owner_kind="EDGE",
            owner_source_id=owner_source_id,
            owner_node_id="",
            owner_edge_id=edge_id,
            evidence_id=evidence_id,
            evidence_source_id=evidence_source_id,
        )
        return legacy_cursor.rowcount > 0 or owner_inserted

    def _insert_claim_evidence(
        self,
        conn: sqlite3.Connection,
        claim_id: str,
        owner_source_id: str,
        node_id: str,
        evidence_id: str,
        *,
        evidence_source_id: str | None = None,
    ) -> bool:
        legacy_cursor = conn.execute(
            "INSERT OR IGNORE INTO analysis_graph_claim_evidence(claim_id, evidence_id) VALUES (?, ?)",
            (claim_id, evidence_id),
        )
        owner_inserted = self.store._insert_owner_evidence_link(
            conn,
            owner_kind="NODE",
            owner_source_id=owner_source_id,
            owner_node_id=node_id,
            owner_edge_id="",
            evidence_id=evidence_id,
            evidence_source_id=evidence_source_id,
        )
        return legacy_cursor.rowcount > 0 or owner_inserted

    def _claim_evidence_refs(self, conn: sqlite3.Connection, claim_id: str) -> list[tuple[str, str]]:
        rows = conn.execute(
            """
            SELECT DISTINCT owner.evidence_source_id, owner.evidence_id
            FROM analysis_graph_claims claim
            JOIN analysis_graph_claim_evidence link
              ON link.claim_id = claim.id
            JOIN analysis_graph_owner_evidence owner
              ON owner.owner_kind = 'NODE'
             AND owner.owner_source_id = claim.source_id
             AND owner.owner_node_id = claim.node_id
             AND owner.evidence_id = link.evidence_id
            WHERE claim.id = ?
            ORDER BY owner.evidence_source_id, owner.evidence_id
            """,
            (claim_id,),
        ).fetchall()
        if rows:
            return [(str(row["evidence_source_id"]), str(row["evidence_id"])) for row in rows]
        return [
            (str(row["evidence_source_id"]), str(row["evidence_id"]))
            for row in conn.execute(
                """
                SELECT ev.source_id AS evidence_source_id, link.evidence_id
                FROM analysis_graph_claim_evidence link
                JOIN analysis_graph_evidence ev ON ev.id = link.evidence_id
                WHERE link.claim_id = ?
                ORDER BY ev.source_id, link.evidence_id
                """,
                (claim_id,),
            ).fetchall()
        ]

    def _connector_edge_id(self, operation: sqlite3.Row, target: sqlite3.Row) -> str:
        return self._derived_id(
            "analysis-graph-edge",
            str(operation["source_id"]),
            str(operation["node_id"]),
            CONNECTOR_REASON,
            str(target["source_id"]),
            str(target["node_id"]),
        )

    def _normalize_route_template(self, value: Any) -> str:
        text = str(value or "").strip()
        if not text:
            return ""
        if not text.startswith("/"):
            text = "/" + text
        text = re.sub(r"/+", "/", text)
        text = re.sub(r"\{[^}/]+\}", "{}", text)
        text = re.sub(r":[A-Za-z_][A-Za-z0-9_]*", ":{}", text)
        return text.rstrip("/") or "/"

    def _derived_id(self, prefix: str, *parts: str) -> str:
        digest = hashlib.sha256("|".join(str(part) for part in parts).encode("utf-8")).hexdigest()[:24]
        return f"{prefix}:{digest}"
