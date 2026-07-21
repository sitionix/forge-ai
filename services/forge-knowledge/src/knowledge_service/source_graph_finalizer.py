from __future__ import annotations

import hashlib
import json
import re
import sqlite3
from collections import defaultdict
from datetime import datetime, timezone
from typing import Any, Dict, List, Mapping, Optional, Sequence, Set

from knowledge_service.entrypoint_kinds import EntrypointExecutionKind, EntrypointKind
from knowledge_service.graph_call_intelligence import classify_call_metadata
from knowledge_service.graph_query_contract import graph_query_contract, sql_in_clause
from knowledge_service.graph_state_repository import GRAPH_STATE_FINALIZING, GraphStateRepository
from knowledge_service.overview_projection import refresh_overview_for_sources
from knowledge_service.semantic_index import SemanticIndexStore
from knowledge_service.transport_connectors import HttpTransportConnectorResolver

ENTRYPOINT_EXECUTION_EXECUTABLE = EntrypointExecutionKind.EXECUTABLE.value


def _chunks(values: Sequence[Any], size: int):
    for offset in range(0, len(values), max(1, size)):
        yield values[offset : offset + max(1, size)]


class CrossSourceGraphResolver:
    def __init__(self, store: Any) -> None:
        self.store = store

    def finalize_source(self, conn: sqlite3.Connection, source_id: str, created_at: str) -> None:
        self._resolve_source_type_relation_edges(conn, source_id)
        self._refresh_source_overrides_and_inherited_entrypoints(conn, source_id, created_at)
        self._resolve_source_call_edges(conn, source_id)
        self._expand_source_interface_dispatch_edges(conn, source_id)

    def refresh_global_transport_connectors(self, conn: sqlite3.Connection, created_at: str) -> set[str]:
        result = HttpTransportConnectorResolver(self.store).resolve(conn, created_at)
        return set(result.affected_source_ids)

    def _refresh_source_overrides_and_inherited_entrypoints(
        self,
        conn: sqlite3.Connection,
        source_id: str,
        created_at: str,
    ) -> None:
        contract = graph_query_contract()
        overrides_edge_type = contract.required_edge_type("OVERRIDES")
        self._delete_derived_override_facts(conn, source_id, overrides_edge_type)
        types = self._current_type_rows(conn, source_id)
        methods_by_type = self._current_callables_by_type(conn, source_id)
        if not types or not methods_by_type:
            return
        implementation_type_ids_by_interface = self._implementation_type_ids_by_interface(conn, source_id, types)
        if not implementation_type_ids_by_interface:
            return
        declaration_evidence = self._declaration_evidence_ids_by_node(conn, source_id)
        interface_entrypoint_claims = self._entrypoint_claims_by_node(conn, source_id)
        implementation_entrypoints = self._nodes_with_entrypoint_claims(conn, source_id)
        pending_claims_by_impl: Dict[str, List[sqlite3.Row]] = defaultdict(list)
        for interface_id, implementation_type_ids in sorted(implementation_type_ids_by_interface.items()):
            interface_methods = methods_by_type.get(interface_id, [])
            if not interface_methods:
                continue
            for interface_method in interface_methods:
                target_params = self.store._json_list(interface_method["parameter_types_json"])
                if not target_params and int(interface_method["parameter_count"] or 0) > 0:
                    continue
                for implementation_type_id in sorted(implementation_type_ids):
                    for implementation_method in self._matching_methods(
                        methods_by_type.get(implementation_type_id, []),
                        interface_method["name"],
                        target_params,
                    ):
                        self._insert_override_edge(
                            conn,
                            implementation_method,
                            interface_method,
                            declaration_evidence,
                            overrides_edge_type,
                            created_at,
                        )
                        for claim in interface_entrypoint_claims.get(interface_method["id"], []):
                            pending_claims_by_impl[implementation_method["id"]].append(claim)
        for implementation_node_id, claims in sorted(pending_claims_by_impl.items()):
            unique_claims = {claim["id"]: claim for claim in claims}
            if len(unique_claims) != 1 or implementation_node_id in implementation_entrypoints:
                continue
            implementation_method = self._callable_row_by_id(conn, source_id, implementation_node_id)
            if implementation_method is None:
                continue
            claim = next(iter(unique_claims.values()))
            self._insert_inherited_entrypoint_claim(
                conn,
                implementation_method,
                claim,
                declaration_evidence,
                created_at,
            )

    def _delete_derived_override_facts(self, conn: sqlite3.Connection, source_id: str, overrides_edge_type: str) -> None:
        contract = graph_query_contract()
        conn.execute(
            """
            DELETE FROM analysis_graph_edges
            WHERE source_id = ?
              AND edge_type = ?
              AND status = ?
            """,
            (source_id, overrides_edge_type, contract.derived_status),
        )
        conn.execute(
            """
            DELETE FROM analysis_graph_claims
            WHERE source_id = ?
              AND claim_kind = ?
              AND status = ?
              AND entrypoint_interface_method IS NOT NULL
            """,
            (source_id, contract.entrypoint_claim_kind, contract.derived_status),
        )

    def _current_type_rows(self, conn: sqlite3.Connection, source_id: str) -> Dict[str, sqlite3.Row]:
        contract = graph_query_contract()
        implements_edge_type = contract.required_edge_type("IMPLEMENTS")
        extends_edge_type = contract.required_edge_type("EXTENDS")
        rows = conn.execute(
            """
            SELECT id, source_id, name, qualified_name, type_kind
            FROM analysis_graph_nodes
            WHERE source_id = ?
              AND node_kind = ?
              AND status = ?
            """,
            (source_id, contract.type_node_kind, contract.trusted_status),
        ).fetchall()
        result = {row["id"]: row for row in rows}
        target_rows = conn.execute(
            """
            SELECT DISTINCT target.id, target.source_id, target.name, target.qualified_name, target.type_kind
            FROM analysis_graph_edges edge
            JOIN analysis_graph_nodes target
              ON target.source_id = COALESCE(edge.to_source_id, edge.source_id)
             AND target.id = edge.to_node_id
            WHERE edge.source_id = ?
              AND edge.edge_type IN (?, ?)
              AND edge.resolution_status = ?
              AND edge.status = ?
              AND target.node_kind = ?
              AND target.status = ?
            """,
            (
                source_id,
                implements_edge_type,
                extends_edge_type,
                contract.resolved_status,
                contract.trusted_status,
                contract.type_node_kind,
                contract.trusted_status,
            ),
        ).fetchall()
        for row in target_rows:
            result[row["id"]] = row
        return result

    def _current_callables_by_type(self, conn: sqlite3.Connection, source_id: str) -> Dict[str, List[sqlite3.Row]]:
        contract = graph_query_contract()
        type_ids = sorted(self._current_type_rows(conn, source_id).keys())
        if not type_ids:
            return {}
        rows: List[sqlite3.Row] = []
        for batch in _chunks(type_ids, 400):
            type_sql, type_params = sql_in_clause(batch)
            rows.extend(
                conn.execute(
                    f"""
                    SELECT *
                    FROM analysis_graph_nodes
                    WHERE parent_node_id IN ({type_sql})
                      AND node_kind = ?
                      AND status = ?
                      AND parent_node_id IS NOT NULL
                    ORDER BY parent_node_id, name, signature, id
                    """,
                    (*type_params, contract.callable_node_kind, contract.trusted_status),
                ).fetchall()
            )
        grouped: Dict[str, List[sqlite3.Row]] = defaultdict(list)
        for row in rows:
            grouped[row["parent_node_id"]].append(row)
        return grouped

    def _implementation_type_ids_by_interface(
        self,
        conn: sqlite3.Connection,
        source_id: str,
        types: Dict[str, sqlite3.Row],
    ) -> Dict[str, Set[str]]:
        contract = graph_query_contract()
        implements_edge_type = contract.required_edge_type("IMPLEMENTS")
        extends_edge_type = contract.required_edge_type("EXTENDS")
        rows = conn.execute(
            """
            SELECT from_node_id, to_node_id, edge_type
            FROM analysis_graph_edges
            WHERE source_id = ?
              AND edge_type IN (?, ?)
              AND resolution_status = ?
              AND status = ?
              AND to_node_id IS NOT NULL
            """,
            (source_id, implements_edge_type, extends_edge_type, contract.resolved_status, contract.trusted_status),
        ).fetchall()
        reverse_edges: Dict[str, List[tuple[str, str]]] = defaultdict(list)
        for row in rows:
            reverse_edges[row["to_node_id"]].append((row["from_node_id"], row["edge_type"]))
        result: Dict[str, Set[str]] = defaultdict(set)
        for interface_id, interface_row in types.items():
            if str(interface_row["type_kind"] or "").upper() != "INTERFACE":
                continue
            frontier: List[tuple[str, bool, int]] = [
                (from_id, edge_type == implements_edge_type, 1)
                for from_id, edge_type in reverse_edges.get(interface_id, [])
            ]
            seen: set[tuple[str, bool]] = set()
            while frontier:
                type_id, has_implements, depth = frontier.pop(0)
                state = (type_id, has_implements)
                if state in seen or depth > 8:
                    continue
                seen.add(state)
                type_row = types.get(type_id)
                if has_implements and type_row is not None and str(type_row["type_kind"] or "").upper() != "INTERFACE":
                    result[interface_id].add(type_id)
                for next_type_id, edge_type in reverse_edges.get(type_id, []):
                    frontier.append((next_type_id, has_implements or edge_type == implements_edge_type, depth + 1))
        return result

    def _matching_methods(
        self,
        candidates: Sequence[sqlite3.Row],
        method_name: str,
        parameter_types: Sequence[str],
    ) -> List[sqlite3.Row]:
        normalized_params = self._normalized_parameter_types(parameter_types)
        if not normalized_params and parameter_types:
            return []
        result: List[sqlite3.Row] = []
        for candidate in candidates:
            if candidate["name"] != method_name:
                continue
            candidate_params = self._normalized_parameter_types(self.store._json_list(candidate["parameter_types_json"]))
            if not parameter_types and int(candidate["parameter_count"] or 0) > 0:
                continue
            if not candidate_params and normalized_params:
                continue
            if candidate_params != normalized_params:
                continue
            result.append(candidate)
        return result

    def _declaration_evidence_ids_by_node(self, conn: sqlite3.Connection, source_id: str) -> Dict[str, List[str]]:
        rows = conn.execute(
            """
            SELECT e.to_node_id AS node_id, ev.id AS evidence_id
            FROM analysis_graph_edges e
            JOIN analysis_graph_edge_evidence link ON link.edge_id = e.id
            JOIN analysis_graph_evidence ev ON ev.id = link.evidence_id
            WHERE e.edge_type = 'DECLARES'
              AND e.to_node_id IS NOT NULL
            ORDER BY e.to_node_id, ev.line_start, ev.id
            """,
        ).fetchall()
        grouped: Dict[str, List[str]] = defaultdict(list)
        for row in rows:
            grouped[row["node_id"]].append(row["evidence_id"])
        return grouped

    def _entrypoint_claims_by_node(self, conn: sqlite3.Connection, source_id: str) -> Dict[str, List[sqlite3.Row]]:
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
        rows = conn.execute(
            f"""
            SELECT *
            FROM analysis_graph_claims
            WHERE claim_kind = ?
              AND status IN ({current_status_sql})
              AND entrypoint_kind = ?
              AND entrypoint_http_method IS NOT NULL
              AND entrypoint_route IS NOT NULL
            ORDER BY node_id, confidence DESC, id
            """,
            (contract.entrypoint_claim_kind, *current_status_params, EntrypointKind.HTTP.value),
        ).fetchall()
        grouped: Dict[str, List[sqlite3.Row]] = defaultdict(list)
        for row in rows:
            grouped[row["node_id"]].append(row)
        return grouped

    def _nodes_with_entrypoint_claims(self, conn: sqlite3.Connection, source_id: str) -> Set[str]:
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
        rows = conn.execute(
            f"""
            SELECT DISTINCT node_id
            FROM analysis_graph_claims
            WHERE claim_kind = ?
              AND status IN ({current_status_sql})
              AND COALESCE(entrypoint_execution_kind, ?) = ?
            """,
            (
                contract.entrypoint_claim_kind,
                *current_status_params,
                ENTRYPOINT_EXECUTION_EXECUTABLE,
                ENTRYPOINT_EXECUTION_EXECUTABLE,
            ),
        ).fetchall()
        return {row["node_id"] for row in rows}

    def _callable_row_by_id(self, conn: sqlite3.Connection, source_id: str, node_id: str) -> Optional[sqlite3.Row]:
        contract = graph_query_contract()
        return conn.execute(
            """
            SELECT *
            FROM analysis_graph_nodes
            WHERE source_id = ?
              AND id = ?
              AND node_kind = ?
              AND status = ?
            """,
            (source_id, node_id, contract.callable_node_kind, contract.trusted_status),
        ).fetchone()

    def _insert_override_edge(
        self,
        conn: sqlite3.Connection,
        implementation_method: sqlite3.Row,
        interface_method: sqlite3.Row,
        declaration_evidence: Mapping[str, List[str]],
        edge_type: str,
        created_at: str,
    ) -> None:
        contract = graph_query_contract()
        evidence_ids = self._dedupe_strings([
            *declaration_evidence.get(implementation_method["id"], []),
            *declaration_evidence.get(interface_method["id"], []),
        ])
        metadata = self.store._edge_metadata_for_storage(
            {
                "resolutionReason": "SIGNATURE_MATCHED_INTERFACE_METHOD",
                "overrideReason": "IMPLEMENTS_INTERFACE_METHOD",
                "interfaceMethod": interface_method["qualified_name"] or interface_method["name"],
                "methodName": implementation_method["name"],
            }
        )
        edge_id = self._derived_graph_id("analysis-graph-edge", implementation_method["id"], "OVERRIDES", interface_method["id"])
        conn.execute(
            """
            INSERT OR IGNORE INTO analysis_graph_edges(
                id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                from_node_id, to_node_id, to_source_id, edge_type, resolution_status, confidence,
                argument_count, unresolved_target_json, metadata_json, status, created_at, updated_at, fact_origin, flow_domain
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?)
            """,
            (
                edge_id,
                implementation_method["job_id"],
                implementation_method["source_id"],
                implementation_method["inventory_file_id"],
                implementation_method["analysis_file_id"],
                implementation_method["file_id"],
                implementation_method["relative_path"],
                implementation_method["content_hash"],
                implementation_method["id"],
                interface_method["id"],
                interface_method["source_id"],
                edge_type,
                contract.resolved_status,
                1.0,
                implementation_method["parameter_count"],
                json.dumps(metadata),
                contract.derived_status,
                created_at,
                created_at,
                implementation_method["fact_origin"],
                implementation_method["flow_domain"],
            ),
        )
        self._insert_edge_evidence_link_rows(conn, edge_id, evidence_ids)

    def _insert_inherited_entrypoint_claim(
        self,
        conn: sqlite3.Connection,
        implementation_method: sqlite3.Row,
        interface_claim: sqlite3.Row,
        declaration_evidence: Mapping[str, List[str]],
        created_at: str,
    ) -> None:
        contract = graph_query_contract()
        claim_id = self._derived_graph_id("analysis-graph-claim", implementation_method["id"], "INHERITED_ENTRYPOINT", interface_claim["id"])
        interface_method = interface_claim["entrypoint_interface_method"] or interface_claim["node_id"]
        conn.execute(
            """
            INSERT OR IGNORE INTO analysis_graph_claims(
                id, job_id, source_id, node_id, claim_kind, summary, confidence, status,
                rejection_reason, created_at, updated_at, entrypoint_kind,
                entrypoint_http_method, entrypoint_route, entrypoint_topic, entrypoint_schedule,
                entrypoint_interface_method, entrypoint_execution_kind, fact_origin, flow_domain
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                claim_id,
                implementation_method["job_id"],
                implementation_method["source_id"],
                implementation_method["id"],
                contract.entrypoint_claim_kind,
                interface_claim["summary"],
                min(1.0, float(interface_claim["confidence"] or 1.0)),
                contract.derived_status,
                created_at,
                created_at,
                interface_claim["entrypoint_kind"],
                interface_claim["entrypoint_http_method"],
                interface_claim["entrypoint_route"],
                interface_claim["entrypoint_topic"],
                interface_claim["entrypoint_schedule"],
                interface_method,
                ENTRYPOINT_EXECUTION_EXECUTABLE,
                implementation_method["fact_origin"],
                implementation_method["flow_domain"],
            ),
        )
        evidence_ids = self._dedupe_strings([
            *declaration_evidence.get(implementation_method["id"], []),
            *self._claim_evidence_ids(conn, interface_claim["id"]),
        ])
        self._insert_claim_evidence_link_rows(conn, claim_id, evidence_ids)

    def _claim_evidence_ids(self, conn: sqlite3.Connection, claim_id: str) -> List[str]:
        return [
            row["evidence_id"]
            for row in conn.execute(
                """
                SELECT evidence_id
                FROM analysis_graph_claim_evidence
                WHERE claim_id = ?
                ORDER BY evidence_id
                """,
                (claim_id,),
            ).fetchall()
        ]

    def _insert_edge_evidence_link_rows(self, conn: sqlite3.Connection, edge_id: str, evidence_ids: Sequence[str]) -> None:
        conn.executemany(
            """
            INSERT OR IGNORE INTO analysis_graph_edge_evidence(edge_id, evidence_id)
            VALUES (?, ?)
            """,
            [(edge_id, evidence_id) for evidence_id in evidence_ids],
        )
        row = conn.execute(
            "SELECT source_id FROM analysis_graph_edges WHERE id = ?",
            (edge_id,),
        ).fetchone()
        if row is None:
            return
        for evidence_id in evidence_ids:
            self.store._insert_owner_evidence_link(
                conn,
                owner_kind="EDGE",
                owner_source_id=str(row["source_id"]),
                owner_node_id="",
                owner_edge_id=edge_id,
                evidence_id=str(evidence_id),
            )

    def _insert_claim_evidence_link_rows(self, conn: sqlite3.Connection, claim_id: str, evidence_ids: Sequence[str]) -> None:
        conn.executemany(
            """
            INSERT OR IGNORE INTO analysis_graph_claim_evidence(claim_id, evidence_id)
            VALUES (?, ?)
            """,
            [(claim_id, evidence_id) for evidence_id in evidence_ids],
        )
        row = conn.execute(
            "SELECT source_id, node_id FROM analysis_graph_claims WHERE id = ?",
            (claim_id,),
        ).fetchone()
        if row is None:
            return
        for evidence_id in evidence_ids:
            self.store._insert_owner_evidence_link(
                conn,
                owner_kind="NODE",
                owner_source_id=str(row["source_id"]),
                owner_node_id=str(row["node_id"]),
                owner_edge_id="",
                evidence_id=str(evidence_id),
            )

    def _derived_graph_id(self, prefix: str, *parts: str) -> str:
        digest = hashlib.sha256("|".join(str(part) for part in parts).encode("utf-8")).hexdigest()[:24]
        return f"{prefix}:{digest}"

    def _dedupe_strings(self, values: Sequence[str]) -> List[str]:
        result: List[str] = []
        seen: set[str] = set()
        for value in values:
            normalized = str(value or "").strip()
            if not normalized or normalized in seen:
                continue
            seen.add(normalized)
            result.append(normalized)
        return result


    def _resolve_source_type_relation_edges(self, conn: sqlite3.Connection, source_id: str) -> None:
        contract = graph_query_contract()
        implements_edge_type = contract.required_edge_type("IMPLEMENTS")
        extends_edge_type = contract.required_edge_type("EXTENDS")
        pending_status_sql, pending_status_params = sql_in_clause(contract.resolver_pending_statuses())
        rows = conn.execute(
            f"""
            SELECT id, metadata_json, unresolved_target_json
            FROM analysis_graph_edges
            WHERE source_id = ?
              AND edge_type IN (?, ?)
              AND to_node_id IS NULL
              AND resolution_status IN ({pending_status_sql})
            """,
            (source_id, implements_edge_type, extends_edge_type, *pending_status_params),
        ).fetchall()
        if not rows:
            return
        types_by_simple, types_by_qualified = self._type_candidates_by_name(conn, source_id)
        for edge in rows:
            metadata = self.store._json_dict(edge["metadata_json"])
            unresolved_target = self.store._json_dict(edge["unresolved_target_json"])
            target_type = (
                unresolved_target.get("qualifiedName")
                or unresolved_target.get("targetTypeText")
                or unresolved_target.get("name")
            )
            if not target_type:
                continue
            target_text = str(target_type)
            type_candidates = (
                types_by_qualified.get(target_text, [])
                if "." in target_text
                else types_by_simple.get(target_text.rsplit(".", 1)[-1], [])
            )
            if len(type_candidates) == 1:
                metadata = dict(metadata)
                metadata.pop("unresolvedReason", None)
                metadata["resolutionReason"] = "TYPE_RELATION_TARGET_MATCH"
                metadata = self.store._edge_metadata_for_storage(metadata)
                conn.execute(
                    """
                    UPDATE analysis_graph_edges
                    SET to_node_id = ?,
                        to_source_id = ?,
                        resolution_status = ?,
                        unresolved_target_json = NULL,
                        metadata_json = ?
                    WHERE id = ?
                    """,
                    (type_candidates[0]["id"], type_candidates[0]["source_id"], contract.resolved_status, json.dumps(metadata), edge["id"]),
                )
            elif len(type_candidates) > 1:
                self._mark_edge_multiple(conn, edge["id"], metadata, len(type_candidates))

    def _type_candidates_by_name(
        self,
        conn: sqlite3.Connection,
        source_id: str,
    ) -> tuple[Dict[str, List[sqlite3.Row]], Dict[str, List[sqlite3.Row]]]:
        contract = graph_query_contract()
        local_type_rows = conn.execute(
            """
            SELECT id, source_id, name, qualified_name, type_kind
            FROM analysis_graph_nodes
            WHERE source_id = ?
              AND node_kind = ?
              AND status = ?
            """,
            (source_id, contract.type_node_kind, contract.trusted_status),
        ).fetchall()
        qualified_type_rows = conn.execute(
            """
            SELECT id, source_id, name, qualified_name, type_kind
            FROM analysis_graph_nodes
            WHERE node_kind = ?
              AND status = ?
              AND qualified_name IS NOT NULL
              AND qualified_name != ''
            """,
            (contract.type_node_kind, contract.trusted_status),
        ).fetchall()
        types_by_simple: Dict[str, List[sqlite3.Row]] = {}
        types_by_qualified: Dict[str, List[sqlite3.Row]] = {}
        for row in local_type_rows:
            types_by_simple.setdefault(row["name"], []).append(row)
        for row in qualified_type_rows:
            if row["qualified_name"]:
                types_by_qualified.setdefault(row["qualified_name"], []).append(row)
        return types_by_simple, types_by_qualified

    def _resolve_source_call_edges(self, conn: sqlite3.Connection, source_id: str) -> None:
        contract = graph_query_contract()
        pending_status_sql, pending_status_params = sql_in_clause(contract.resolver_pending_statuses())
        rows = conn.execute(
            f"""
            SELECT id, file_id, metadata_json, unresolved_target_json, argument_count
            FROM analysis_graph_edges
            WHERE source_id = ?
              AND edge_type = ?
              AND to_node_id IS NULL
              AND resolution_status IN ({pending_status_sql})
        """,
            (source_id, contract.calls_edge_type, *pending_status_params),
        ).fetchall()
        if not rows:
            return
        types_by_simple, types_by_qualified = self._type_candidates_by_name(conn, source_id)
        imported_qualified_types = self._imported_qualified_types_by_file(
            conn,
            source_id,
            {int(row["file_id"]) for row in rows if row["file_id"] is not None},
        )
        resolvable_edges: List[tuple[sqlite3.Row, Dict[str, Any], Dict[str, Any], str, Optional[int]]] = []
        callable_keys: Set[tuple[str, str, Optional[int]]] = set()
        for edge in rows:
            metadata = self.store._json_dict(edge["metadata_json"])
            unresolved_target = self.store._json_dict(edge["unresolved_target_json"])
            method_name = unresolved_target.get("name")
            type_hint = unresolved_target.get("receiverTypeHint") or unresolved_target.get("targetTypeText")
            if not method_name or not type_hint:
                continue
            type_text = str(type_hint)
            if "." not in type_text and edge["file_id"] is not None:
                type_text = imported_qualified_types.get((int(edge["file_id"]), type_text.rsplit(".", 1)[-1]), type_text)
            type_candidates = (
                types_by_qualified.get(type_text, [])
                if "." in type_text
                else types_by_simple.get(type_text.rsplit(".", 1)[-1], [])
            )
            if len(type_candidates) != 1:
                if len(type_candidates) > 1:
                    self._mark_call_edge_multiple(conn, edge["id"], metadata, len(type_candidates))
                continue
            type_id = str(type_candidates[0]["id"])
            method = str(method_name)
            argument_count = edge["argument_count"]
            resolvable_edges.append((edge, metadata, unresolved_target, type_id, argument_count))
            callable_keys.add((type_id, method, argument_count))
        candidates_by_key = self._callable_candidates_by_type_method(conn, callable_keys)
        for edge, metadata, unresolved_target, type_id, argument_count in resolvable_edges:
            method_name = str(unresolved_target.get("name"))
            callable_candidates = candidates_by_key.get((type_id, method_name, argument_count), [])
            if len(callable_candidates) == 1:
                metadata = self._resolved_call_metadata(metadata, unresolved_target)
                metadata = self.store._edge_metadata_for_storage(metadata)
                conn.execute(
                    """
                    UPDATE analysis_graph_edges
                    SET to_node_id = ?,
                        to_source_id = ?,
                        resolution_status = ?,
                        unresolved_target_json = NULL,
                        metadata_json = ?
                    WHERE id = ?
                """,
                    (callable_candidates[0]["id"], callable_candidates[0]["source_id"], contract.resolved_status, json.dumps(metadata), edge["id"]),
                )
            elif len(callable_candidates) > 1:
                self._mark_call_edge_multiple(conn, edge["id"], metadata, len(callable_candidates))

    def _imported_qualified_types_by_file(
        self,
        conn: sqlite3.Connection,
        source_id: str,
        file_ids: Set[int],
    ) -> Dict[tuple[int, str], str]:
        if not file_ids:
            return {}
        contract = graph_query_contract()
        values: Dict[tuple[int, str], Set[str]] = defaultdict(set)
        for batch in _chunks(sorted(str(file_id) for file_id in file_ids), 400):
            batch_sql, batch_params = sql_in_clause(batch)
            rows = conn.execute(
                f"""
                SELECT file_id, unresolved_target_json
                FROM analysis_graph_edges
                WHERE source_id = ?
                  AND edge_type = ?
                  AND file_id IN ({batch_sql})
                  AND status = ?
                """,
                (source_id, contract.required_edge_type("IMPORTS"), *batch_params, contract.trusted_status),
            ).fetchall()
            for row in rows:
                target = self.store._json_dict(row["unresolved_target_json"])
                qualified_name = str(target.get("qualifiedName") or "")
                if "." not in qualified_name:
                    continue
                values[(int(row["file_id"]), qualified_name.rsplit(".", 1)[-1])].add(qualified_name)
        return {key: next(iter(items)) for key, items in values.items() if len(items) == 1}

    def _callable_candidates_by_type_method(
        self,
        conn: sqlite3.Connection,
        keys: Set[tuple[str, str, Optional[int]]],
    ) -> Dict[tuple[str, str, Optional[int]], List[sqlite3.Row]]:
        if not keys:
            return {}
        contract = graph_query_contract()
        result: Dict[tuple[str, str, Optional[int]], List[sqlite3.Row]] = {key: [] for key in keys}
        argument_counts_by_lookup: Dict[tuple[str, str], Set[Optional[int]]] = {}
        for type_id, method_name, argument_count in keys:
            argument_counts_by_lookup.setdefault((type_id, method_name), set()).add(argument_count)
        type_ids = sorted({type_id for type_id, _, _ in keys})
        method_names = sorted({method_name for _, method_name, _ in keys})
        for type_batch in _chunks(type_ids, 400):
            type_sql, type_params = sql_in_clause(type_batch)
            for method_batch in _chunks(method_names, 400):
                method_sql, method_params = sql_in_clause(method_batch)
                rows = conn.execute(
                    f"""
                    SELECT id, source_id, parent_node_id, qualified_name, name, parameter_count, signature, parameter_types_json
                    FROM analysis_graph_nodes
                    WHERE parent_node_id IN ({type_sql})
                      AND node_kind = ?
                      AND name IN ({method_sql})
                      AND status = ?
                    ORDER BY qualified_name, id
                    """,
                    (*type_params, contract.callable_node_kind, *method_params, contract.trusted_status),
                ).fetchall()
                for row in rows:
                    lookup = (str(row["parent_node_id"]), str(row["name"]))
                    for argument_count in argument_counts_by_lookup.get(lookup, set()):
                        if argument_count is not None:
                            if row["parameter_count"] is None or int(row["parameter_count"]) != int(argument_count):
                                continue
                        result[(lookup[0], lookup[1], argument_count)].append(row)
        return result

    def _expand_source_interface_dispatch_edges(self, conn: sqlite3.Connection, source_id: str) -> None:
        contract = graph_query_contract()
        conn.execute(
            """
            DELETE FROM analysis_graph_edges
            WHERE source_id = ?
              AND edge_type = ?
              AND metadata_json LIKE '%interfaceDispatchCloneOf%'
            """,
            (source_id, contract.calls_edge_type),
        )
        rows = conn.execute(
            """
            SELECT e.*
            FROM analysis_graph_edges e
            WHERE e.source_id = ?
              AND e.edge_type = ?
              AND e.resolution_status IN (?, ?)
              AND e.status = ?
            ORDER BY e.id
            """,
            (source_id, contract.calls_edge_type, contract.resolved_status, contract.unresolved_status, contract.trusted_status),
        ).fetchall()
        resolved_interface_targets = self._interface_call_targets_for_resolved_edges(conn, source_id, rows)
        for edge in rows:
            interface_target = (
                resolved_interface_targets.get(edge["id"])
                if edge["to_node_id"]
                else self._unresolved_interface_call_target(conn, source_id, edge)
            )
            if interface_target is None:
                continue
            candidates = self._implementation_method_candidates_for_interface_target(conn, source_id, edge, interface_target)
            if not candidates:
                self._mark_interface_call_unresolved(conn, edge, interface_target)
                continue
            candidate_ids = [row["id"] for row in candidates]
            if edge["to_node_id"] in candidate_ids and len(candidate_ids) == 1:
                continue
            metadata = self.store._json_dict(edge["metadata_json"])
            metadata["resolutionReason"] = "INTERFACE_IMPLEMENTATION_DISPATCH"
            metadata["interfaceMethod"] = interface_target["target_qualified_name"] or interface_target["target_name"]
            metadata["candidateCount"] = len(candidate_ids)
            metadata = self.store._edge_metadata_for_storage(metadata)
            first_target_id = candidate_ids[0]
            if edge["to_node_id"] != first_target_id:
                conn.execute(
                    """
                    UPDATE analysis_graph_edges
                    SET to_node_id = ?,
                        to_source_id = ?,
                        resolution_status = ?,
                        unresolved_target_json = NULL,
                        metadata_json = ?
                    WHERE id = ?
                    """,
                    (first_target_id, candidates[0]["source_id"], contract.resolved_status, json.dumps(metadata), edge["id"]),
                )
            for target_id in candidate_ids[1:]:
                candidate_row = next(row for row in candidates if row["id"] == target_id)
                self._insert_interface_dispatch_clone(conn, edge, target_id, str(candidate_row["source_id"]), metadata)

    def _interface_call_targets_for_resolved_edges(
        self,
        conn: sqlite3.Connection,
        source_id: str,
        edges: Sequence[sqlite3.Row],
    ) -> Dict[str, sqlite3.Row]:
        resolved_edges = [edge for edge in edges if edge["to_node_id"]]
        if not resolved_edges:
            return {}
        contract = graph_query_contract()
        edge_ids_by_target_id: Dict[str, List[str]] = {}
        metadata_method_by_edge_id: Dict[str, str] = {}
        for edge in resolved_edges:
            edge_ids_by_target_id.setdefault(str(edge["to_node_id"]), []).append(str(edge["id"]))
            metadata = self.store._json_dict(edge["metadata_json"])
            interface_method = metadata.get("interfaceMethod")
            if interface_method:
                metadata_method_by_edge_id[str(edge["id"])] = str(interface_method)
        result: Dict[str, sqlite3.Row] = {}
        target_ids = sorted(edge_ids_by_target_id)
        for batch in _chunks(target_ids, 400):
            batch_sql, batch_params = sql_in_clause(batch)
            direct_rows = conn.execute(
                f"""
                SELECT target.id AS target_id,
                       target.source_id AS target_source_id,
                       target.name AS target_name,
                       target.qualified_name AS target_qualified_name,
                       parent.id AS interface_type_id,
                       parent.name AS interface_name,
                       parent.qualified_name AS interface_qualified_name,
                       target.signature AS target_signature,
                       target.parameter_types_json AS target_parameter_types_json
                FROM analysis_graph_nodes target
                JOIN analysis_graph_nodes parent
                  ON parent.source_id = target.source_id
                 AND parent.id = target.parent_node_id
                WHERE target.id IN ({batch_sql})
                  AND target.node_kind = ?
                  AND target.status = ?
                  AND parent.node_kind = ?
                  AND parent.type_kind = 'INTERFACE'
                  AND parent.status = ?
                ORDER BY target.qualified_name, target.id
                """,
                (*batch_params, contract.callable_node_kind, contract.trusted_status, contract.type_node_kind, contract.trusted_status),
            ).fetchall()
            for target in direct_rows:
                for edge_id in edge_ids_by_target_id.get(str(target["target_id"]), []):
                    result[edge_id] = target
            unresolved_target_ids = [target_id for target_id in batch if any(edge_id not in result for edge_id in edge_ids_by_target_id[target_id])]
            if not unresolved_target_ids:
                continue
            unresolved_sql, unresolved_params = sql_in_clause(unresolved_target_ids)
            override_rows = conn.execute(
                f"""
                SELECT override.from_node_id AS implementation_target_id,
                       target.source_id AS target_source_id,
                       target.id AS target_id,
                       target.name AS target_name,
                       target.qualified_name AS target_qualified_name,
                       parent.id AS interface_type_id,
                       parent.name AS interface_name,
                       parent.qualified_name AS interface_qualified_name,
                       target.signature AS target_signature,
                       target.parameter_types_json AS target_parameter_types_json
                FROM analysis_graph_edges override
                JOIN analysis_graph_nodes target
                  ON target.source_id = COALESCE(override.to_source_id, override.source_id)
                 AND target.id = override.to_node_id
                JOIN analysis_graph_nodes parent
                  ON parent.source_id = target.source_id
                 AND parent.id = target.parent_node_id
                WHERE override.source_id = ?
                  AND override.from_node_id IN ({unresolved_sql})
                  AND override.edge_type = ?
                  AND override.resolution_status = ?
                  AND override.status IN (?, ?)
                  AND target.node_kind = ?
                  AND target.status = ?
                  AND parent.node_kind = ?
                  AND parent.type_kind = 'INTERFACE'
                  AND parent.status = ?
                ORDER BY target.qualified_name, target.id
                """,
                (
                    source_id,
                    *unresolved_params,
                    contract.required_edge_type("OVERRIDES"),
                    contract.resolved_status,
                    contract.trusted_status,
                    contract.derived_status,
                    contract.callable_node_kind,
                    contract.trusted_status,
                    contract.type_node_kind,
                    contract.trusted_status,
                ),
            ).fetchall()
            seen_override_targets: Set[str] = set()
            for target in override_rows:
                implementation_target_id = str(target["implementation_target_id"])
                if implementation_target_id in seen_override_targets:
                    continue
                seen_override_targets.add(implementation_target_id)
                for edge_id in edge_ids_by_target_id.get(implementation_target_id, []):
                    result.setdefault(edge_id, target)
        missing_metadata_methods = sorted(
            {
                method
                for edge_id, method in metadata_method_by_edge_id.items()
                if edge_id not in result
            }
        )
        if missing_metadata_methods:
            method_sql, method_params = sql_in_clause(missing_metadata_methods)
            metadata_rows = conn.execute(
                f"""
                SELECT target.id AS target_id,
                       target.source_id AS target_source_id,
                       target.name AS target_name,
                       target.qualified_name AS target_qualified_name,
                       parent.id AS interface_type_id,
                       parent.name AS interface_name,
                       parent.qualified_name AS interface_qualified_name,
                       target.signature AS target_signature,
                       target.parameter_types_json AS target_parameter_types_json
                FROM analysis_graph_nodes target
                JOIN analysis_graph_nodes parent
                  ON parent.source_id = target.source_id
                 AND parent.id = target.parent_node_id
                WHERE target.qualified_name IN ({method_sql})
                  AND target.node_kind = ?
                  AND target.status = ?
                  AND parent.node_kind = ?
                  AND parent.type_kind = 'INTERFACE'
                  AND parent.status = ?
                ORDER BY target.qualified_name, target.id
                """,
                (*method_params, contract.callable_node_kind, contract.trusted_status, contract.type_node_kind, contract.trusted_status),
            ).fetchall()
            metadata_by_method = {str(row["target_qualified_name"]): row for row in metadata_rows}
            for edge_id, method in metadata_method_by_edge_id.items():
                if edge_id not in result and method in metadata_by_method:
                    result[edge_id] = metadata_by_method[method]
        return result

    def _interface_call_target(
        self,
        conn: sqlite3.Connection,
        source_id: str,
        edge: sqlite3.Row,
    ) -> Optional[sqlite3.Row]:
        contract = graph_query_contract()
        row = conn.execute(
            """
            SELECT target.id AS target_id,
                   target.source_id AS target_source_id,
                   target.name AS target_name,
                   target.qualified_name AS target_qualified_name,
                   parent.id AS interface_type_id,
                   parent.name AS interface_name,
                   parent.qualified_name AS interface_qualified_name,
                   target.signature AS target_signature,
                   target.parameter_types_json AS target_parameter_types_json
            FROM analysis_graph_nodes target
            JOIN analysis_graph_nodes parent
              ON parent.source_id = target.source_id
             AND parent.id = target.parent_node_id
            WHERE target.id = ?
              AND target.node_kind = ?
              AND target.status = ?
              AND parent.node_kind = ?
              AND parent.type_kind = 'INTERFACE'
              AND parent.status = ?
            """,
            (
                edge["to_node_id"],
                contract.callable_node_kind,
                contract.trusted_status,
                contract.type_node_kind,
                contract.trusted_status,
            ),
        ).fetchone()
        if row is not None:
            return row
        row = self._interface_call_target_from_override(conn, source_id, edge)
        if row is not None:
            return row
        return self._interface_call_target_from_metadata(conn, source_id, edge)

    def _interface_call_target_from_override(
        self,
        conn: sqlite3.Connection,
        source_id: str,
        edge: sqlite3.Row,
    ) -> Optional[sqlite3.Row]:
        contract = graph_query_contract()
        overrides_edge_type = contract.required_edge_type("OVERRIDES")
        return conn.execute(
            """
            SELECT target.id AS target_id,
                   target.source_id AS target_source_id,
                   target.name AS target_name,
                   target.qualified_name AS target_qualified_name,
                   parent.id AS interface_type_id,
                   parent.name AS interface_name,
                   parent.qualified_name AS interface_qualified_name,
                   target.signature AS target_signature,
                   target.parameter_types_json AS target_parameter_types_json
            FROM analysis_graph_edges override
            JOIN analysis_graph_nodes target
              ON target.source_id = COALESCE(override.to_source_id, override.source_id)
             AND target.id = override.to_node_id
            JOIN analysis_graph_nodes parent
              ON parent.source_id = target.source_id
             AND parent.id = target.parent_node_id
            WHERE override.source_id = ?
              AND override.from_node_id = ?
              AND override.edge_type = ?
              AND override.resolution_status = ?
              AND override.status IN (?, ?)
              AND target.node_kind = ?
              AND target.status = ?
              AND parent.node_kind = ?
              AND parent.type_kind = 'INTERFACE'
              AND parent.status = ?
            ORDER BY target.qualified_name, target.id
            LIMIT 1
            """,
            (
                source_id,
                edge["to_node_id"],
                overrides_edge_type,
                contract.resolved_status,
                contract.trusted_status,
                contract.derived_status,
                contract.callable_node_kind,
                contract.trusted_status,
                contract.type_node_kind,
                contract.trusted_status,
            ),
        ).fetchone()

    def _interface_call_target_from_metadata(
        self,
        conn: sqlite3.Connection,
        source_id: str,
        edge: sqlite3.Row,
    ) -> Optional[sqlite3.Row]:
        metadata = self.store._json_dict(edge["metadata_json"])
        interface_method = metadata.get("interfaceMethod")
        if not interface_method:
            return None
        contract = graph_query_contract()
        return conn.execute(
            """
            SELECT target.id AS target_id,
                   target.source_id AS target_source_id,
                   target.name AS target_name,
                   target.qualified_name AS target_qualified_name,
                   parent.id AS interface_type_id,
                   parent.name AS interface_name,
                   parent.qualified_name AS interface_qualified_name,
                   target.signature AS target_signature,
                   target.parameter_types_json AS target_parameter_types_json
            FROM analysis_graph_nodes target
            JOIN analysis_graph_nodes parent
              ON parent.source_id = target.source_id
             AND parent.id = target.parent_node_id
            WHERE target.qualified_name = ?
              AND target.node_kind = ?
              AND target.status = ?
              AND parent.node_kind = ?
              AND parent.type_kind = 'INTERFACE'
              AND parent.status = ?
            """,
            (
                str(interface_method),
                contract.callable_node_kind,
                contract.trusted_status,
                contract.type_node_kind,
                contract.trusted_status,
            ),
        ).fetchone()

    def _unresolved_interface_call_target(
        self,
        conn: sqlite3.Connection,
        source_id: str,
        edge: sqlite3.Row,
    ) -> Optional[sqlite3.Row]:
        contract = graph_query_contract()
        unresolved_target = self.store._json_dict(edge["unresolved_target_json"])
        metadata = self.store._json_dict(edge["metadata_json"])
        method_name = unresolved_target.get("name") or metadata.get("methodName")
        interface_type_name = (
            unresolved_target.get("interfaceType")
            or unresolved_target.get("targetTypeText")
            or metadata.get("targetTypeHint")
            or metadata.get("receiverTypeHint")
            or metadata.get("targetTypeText")
        )
        if not method_name or not interface_type_name:
            return None
        types_by_simple, types_by_qualified = self._type_candidates_by_name(conn, source_id)
        interface_type_text = str(interface_type_name)
        interface_types = (
            types_by_qualified.get(interface_type_text, [])
            if "." in interface_type_text
            else types_by_simple.get(interface_type_text.rsplit(".", 1)[-1], [])
        )
        if len(interface_types) != 1:
            return None
        target = self._callable_candidates_for_type(conn, interface_types[0]["id"], str(method_name), edge["argument_count"])
        if len(target) != 1:
            return None
        return conn.execute(
            """
            SELECT target.id AS target_id,
                   target.source_id AS target_source_id,
                   target.name AS target_name,
                   target.qualified_name AS target_qualified_name,
                   parent.id AS interface_type_id,
                   parent.name AS interface_name,
                   parent.qualified_name AS interface_qualified_name,
                   target.signature AS target_signature,
                   target.parameter_types_json AS target_parameter_types_json
            FROM analysis_graph_nodes target
            JOIN analysis_graph_nodes parent
              ON parent.source_id = target.source_id
             AND parent.id = target.parent_node_id
            WHERE target.source_id = ?
              AND target.id = ?
              AND target.node_kind = ?
              AND target.status = ?
              AND parent.node_kind = ?
              AND parent.type_kind = 'INTERFACE'
              AND parent.status = ?
            """,
            (
                source_id,
                target[0]["id"],
                contract.callable_node_kind,
                contract.trusted_status,
                contract.type_node_kind,
                contract.trusted_status,
            ),
        ).fetchone()

    def _mark_interface_call_unresolved(
        self,
        conn: sqlite3.Connection,
        edge: sqlite3.Row,
        target: sqlite3.Row,
    ) -> None:
        contract = graph_query_contract()
        metadata = self.store._json_dict(edge["metadata_json"])
        metadata["resolutionReason"] = "INTERFACE_IMPLEMENTATION_NOT_FOUND"
        metadata["unresolvedReason"] = "NO_ANALYZED_IMPLEMENTATION"
        interface_type = target["interface_qualified_name"] or target["interface_name"]
        if interface_type:
            metadata["targetTypeHint"] = interface_type
            metadata.setdefault("targetTypeText", interface_type)
        metadata = self.store._edge_metadata_for_storage(metadata)
        original_unresolved_target = self.store._json_dict(edge["unresolved_target_json"])
        unresolved_target = dict(original_unresolved_target)
        if not unresolved_target:
            unresolved_target = {
                "name": target["target_name"],
                "qualifiedName": target["target_qualified_name"],
            }
        if interface_type:
            unresolved_target.setdefault("interfaceType", interface_type)
        conn.execute(
            """
            UPDATE analysis_graph_edges
            SET to_node_id = NULL,
                to_source_id = NULL,
                resolution_status = ?,
                unresolved_target_json = ?,
                metadata_json = ?
            WHERE id = ?
            """,
            (
                contract.unresolved_status,
                json.dumps({key: value for key, value in unresolved_target.items() if value is not None}),
                json.dumps(metadata),
                edge["id"],
            ),
        )

    def _implementation_method_candidates_for_interface_target(
        self,
        conn: sqlite3.Connection,
        source_id: str,
        edge: sqlite3.Row,
        target: Any,
    ) -> List[sqlite3.Row]:
        interface_type_id = target["interface_type_id"]
        metadata = self.store._json_dict(edge["metadata_json"])
        type_hint = metadata.get("targetTypeHint") or metadata.get("receiverTypeHint") or metadata.get("targetTypeText")
        if type_hint:
            types_by_simple, types_by_qualified = self._type_candidates_by_name(conn, source_id)
            type_hint_text = str(type_hint)
            hinted_types = (
                types_by_qualified.get(type_hint_text, [])
                if "." in type_hint_text
                else types_by_simple.get(type_hint_text.rsplit(".", 1)[-1], [])
            )
            if len(hinted_types) == 1 and self._implementing_type_rows(conn, source_id, hinted_types[0]["id"]):
                interface_type_id = hinted_types[0]["id"]
        implementation_types = self._implementing_type_rows(conn, source_id, interface_type_id)
        candidates: List[sqlite3.Row] = []
        target_parameter_types = self._parameter_types_from_target(target)
        for implementation_type in implementation_types:
            candidates.extend(
                self._callable_candidates_for_type(
                    conn,
                    implementation_type["id"],
                    str(target["target_name"]),
                    edge["argument_count"],
                    target_parameter_types,
                )
            )
        deduped: Dict[str, sqlite3.Row] = {row["id"]: row for row in candidates}
        return sorted(
            deduped.values(),
            key=lambda row: (
                str(row["qualified_name"] or ""),
                str(row["id"] or ""),
            ),
        )

    def _implementing_type_rows(
        self,
        conn: sqlite3.Connection,
        source_id: str,
        interface_type_id: str,
    ) -> List[sqlite3.Row]:
        contract = graph_query_contract()
        implements_edge_type = contract.required_edge_type("IMPLEMENTS")
        extends_edge_type = contract.required_edge_type("EXTENDS")
        return conn.execute(
            """
            WITH RECURSIVE related(type_id, has_implements, depth) AS (
                SELECT e.from_node_id,
                       CASE WHEN e.edge_type = ? THEN 1 ELSE 0 END,
                       1
                FROM analysis_graph_edges e
                WHERE e.source_id = ?
                  AND e.edge_type IN (?, ?)
                  AND e.to_node_id = ?
                  AND e.resolution_status = ?
                  AND e.status = ?
                UNION
                SELECT e.from_node_id,
                       CASE WHEN e.edge_type = ? THEN 1 ELSE related.has_implements END,
                       related.depth + 1
                FROM analysis_graph_edges e
                JOIN related ON related.type_id = e.to_node_id
                WHERE e.source_id = ?
                  AND e.edge_type IN (?, ?)
                  AND e.resolution_status = ?
                  AND e.status = ?
                  AND related.depth < 8
            )
            SELECT DISTINCT n.id, n.qualified_name, n.name
            FROM related
            JOIN analysis_graph_nodes n
              ON n.source_id = ?
             AND n.id = related.type_id
            WHERE related.has_implements = 1
              AND n.node_kind = ?
              AND n.status = ?
            ORDER BY n.qualified_name, n.id
            """,
            (
                implements_edge_type,
                source_id,
                implements_edge_type,
                extends_edge_type,
                interface_type_id,
                contract.resolved_status,
                contract.trusted_status,
                implements_edge_type,
                source_id,
                implements_edge_type,
                extends_edge_type,
                contract.resolved_status,
                contract.trusted_status,
                source_id,
                contract.type_node_kind,
                contract.trusted_status,
            ),
        ).fetchall()

    def _insert_interface_dispatch_clone(
        self,
        conn: sqlite3.Connection,
        edge: sqlite3.Row,
        target_id: str,
        target_source_id: str,
        metadata: Dict[str, Any],
    ) -> None:
        clone_id = "analysis-graph-edge:" + hashlib.sha256(
            f"{edge['id']}|interface-dispatch|{target_id}".encode("utf-8")
        ).hexdigest()[:24]
        clone_metadata = self.store._edge_metadata_for_storage({**metadata, "interfaceDispatchCloneOf": edge["id"]})
        conn.execute(
            """
            INSERT OR IGNORE INTO analysis_graph_edges(
                id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                from_node_id, to_node_id, to_source_id, edge_type, resolution_status, confidence,
                argument_count, unresolved_target_json, metadata_json, status, created_at, updated_at, fact_origin, flow_domain
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?)
            """,
            (
                clone_id,
                edge["job_id"],
                edge["source_id"],
                edge["inventory_file_id"],
                edge["analysis_file_id"],
                edge["file_id"],
                edge["relative_path"],
                edge["content_hash"],
                edge["from_node_id"],
                target_id,
                target_source_id,
                edge["edge_type"],
                graph_query_contract().resolved_status,
                edge["confidence"],
                edge["argument_count"],
                json.dumps(clone_metadata),
                edge["status"],
                edge["created_at"],
                edge["updated_at"],
                edge["fact_origin"],
                edge["flow_domain"],
            ),
        )
        conn.execute(
            """
            INSERT OR IGNORE INTO analysis_graph_edge_evidence(edge_id, evidence_id)
            SELECT ?, evidence_id
            FROM analysis_graph_edge_evidence
            WHERE edge_id = ?
            """,
            (clone_id, edge["id"]),
        )

    def _resolved_call_metadata(self, metadata: Dict[str, Any], unresolved_target: Dict[str, Any]) -> Dict[str, Any]:
        result = dict(metadata or {})
        call_kind = str(result.get("callKind") or "")
        receiver_type_hint = unresolved_target.get("receiverTypeHint")
        target_type_text = unresolved_target.get("targetTypeText") or receiver_type_hint
        result.pop("unresolvedReason", None)
        result.pop("unresolvedTarget", None)
        result["resolutionReason"] = self._resolved_call_reason(call_kind, receiver_type_hint, target_type_text)
        if "METHOD_REFERENCE" in call_kind:
            if receiver_type_hint:
                result["receiverTypeHint"] = receiver_type_hint
            if target_type_text:
                result["targetTypeText"] = target_type_text
        return classify_call_metadata(result, result.get("flowDomain"), graph_query_contract().resolved_status, None)

    def _resolved_call_reason(self, call_kind: str, receiver_type_hint: Optional[str], target_type_text: Optional[str]) -> str:
        if call_kind in {"FIELD_RECEIVER", "FIELD_METHOD_REFERENCE"} and receiver_type_hint:
            return "FIELD_TYPE_HINT"
        if call_kind in {"PARAMETER_RECEIVER", "PARAMETER_METHOD_REFERENCE"} and receiver_type_hint:
            return "PARAMETER_TYPE_HINT"
        if call_kind in {"LOCAL_VARIABLE_RECEIVER", "LOCAL_VARIABLE_METHOD_REFERENCE"} and receiver_type_hint:
            return "LOCAL_VARIABLE_TYPE_HINT"
        if call_kind in {"STATIC_METHOD", "STATIC_METHOD_REFERENCE"} and target_type_text:
            return "QUALIFIED_NAME_MATCH"
        if call_kind in {"LOCAL_METHOD", "THIS_METHOD", "METHOD_REFERENCE"}:
            return "SAME_TYPE_METHOD"
        return "SAME_FILE_UNIQUE_METHOD"

    def _callable_candidates_for_type(
        self,
        conn: sqlite3.Connection,
        type_node_id: str,
        method_name: str,
        argument_count: Optional[int],
        parameter_types: Optional[List[str]] = None,
    ) -> List[sqlite3.Row]:
        contract = graph_query_contract()
        rows = conn.execute(
            """
            SELECT id, source_id, qualified_name, name, parameter_count, signature, parameter_types_json
            FROM analysis_graph_nodes
            WHERE parent_node_id = ?
              AND node_kind = ?
              AND name = ?
              AND status = ?
        """,
            (type_node_id, contract.callable_node_kind, method_name, contract.trusted_status),
        ).fetchall()
        matching = rows
        if argument_count is not None:
            matching = [row for row in matching if row["parameter_count"] is not None and int(row["parameter_count"]) == int(argument_count)]
        normalized_parameters = self._normalized_parameter_types(parameter_types or [])
        if normalized_parameters:
            signature_matches = [
                row for row in matching
                if self._normalized_parameter_types(self.store._json_list(row["parameter_types_json"])) == normalized_parameters
            ]
            return signature_matches
        return matching

    def _parameter_types_from_target(self, target: Any) -> List[str]:
        if isinstance(target, dict):
            value = target.get("target_parameter_types_json") or target.get("parameter_types_json") or target.get("parameterTypes")
        else:
            value = target["target_parameter_types_json"] if "target_parameter_types_json" in target.keys() else None
        return [str(item) for item in self.store._json_list(value)]

    def _normalized_parameter_types(self, parameter_types: Sequence[str]) -> List[str]:
        return [self._normalize_parameter_type(item) for item in parameter_types]

    def _normalize_parameter_type(self, value: str) -> str:
        text = str(value or "").strip()
        text = re.sub(r"@\w+(?:\([^)]*\))?\s*", "", text)
        text = re.sub(r"\s+", "", text)
        text = text.replace("...", "[]")
        return text

    def _mark_edge_multiple(self, conn: sqlite3.Connection, edge_id: str, metadata: Dict[str, Any], candidate_count: int) -> None:
        contract = graph_query_contract()
        next_metadata = dict(metadata)
        next_metadata["resolutionReason"] = "MULTIPLE_TYPE_CANDIDATES"
        next_metadata["unresolvedReason"] = "MULTIPLE_TYPES_MATCH"
        next_metadata["candidateCount"] = candidate_count
        next_metadata = self.store._edge_metadata_for_storage(next_metadata)
        conn.execute(
            """
            UPDATE analysis_graph_edges
            SET resolution_status = ?,
                metadata_json = ?
            WHERE id = ?
        """,
            (contract.multiple_candidates_status, json.dumps(next_metadata), edge_id),
        )

    def _mark_call_edge_multiple(self, conn: sqlite3.Connection, edge_id: str, metadata: Dict[str, Any], candidate_count: int) -> None:
        contract = graph_query_contract()
        metadata = classify_call_metadata(metadata, metadata.get("flowDomain"), contract.multiple_candidates_status, None)
        metadata = self.store._edge_metadata_for_storage(metadata)
        conn.execute(
            """
            UPDATE analysis_graph_edges
            SET resolution_status = ?,
                metadata_json = ?
            WHERE id = ?
        """,
            (contract.multiple_candidates_status, json.dumps(metadata), edge_id),
        )

class SourceGraphFinalizer:
    def __init__(
        self,
        store: Any,
        *,
        state_repository: GraphStateRepository | None = None,
        resolver: CrossSourceGraphResolver | None = None,
    ) -> None:
        self.store = store
        self.state_repository = state_repository or GraphStateRepository(store)
        self.resolver = resolver or CrossSourceGraphResolver(store)

    def finalize_source_graph(self, source_id: str) -> None:
        self.finalize_source_graphs([source_id])

    def finalize_source_graphs(self, source_ids: Sequence[str]) -> None:
        self.store.init()
        created_at = datetime.now(timezone.utc).isoformat()
        requested_source_ids = tuple(sorted({str(source_id) for source_id in source_ids if str(source_id or "").strip()}))
        if not requested_source_ids:
            return

        def write(conn: sqlite3.Connection) -> None:
            affected_source_ids = set(requested_source_ids)
            for source_id in requested_source_ids:
                self.state_repository.set_status_conn(conn, source_id, GRAPH_STATE_FINALIZING, created_at)
                self.resolver.finalize_source(conn, source_id, created_at)
            refresh_global = getattr(self.resolver, "refresh_global_transport_connectors", None)
            if callable(refresh_global):
                affected_source_ids.update(refresh_global(conn, created_at))
            refresh_graph_state = getattr(self.store, "_refresh_graph_state", None)
            if callable(refresh_graph_state):
                for source_id in sorted(affected_source_ids):
                    graph_id = refresh_graph_state(conn, source_id, created_at)
                    if graph_id:
                        SemanticIndexStore.mark_current_graph_pending_conn(conn, source_id)
                refresh_overview_for_sources(conn, sorted(affected_source_ids))

        try:
            self.store._write_with_busy_retry(write)
        except Exception as exc:
            for source_id in requested_source_ids:
                self.store.mark_source_graph_failed(source_id, exc)
            raise
