from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple

import sqlite3

from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.errors import KnowledgeError


UNLIMITED_GRAPH_ITEMS = 1_000_000


@dataclass(frozen=True)
class GraphSliceRequest:
    source_id: Optional[str] = None
    root_graph_node_id: Optional[str] = None
    stable_key: Optional[str] = None
    flow_domain: str = "CODE"
    direction: str = "OUTBOUND"
    depth: int = 2
    max_nodes: int = 80
    max_edges: int = 120
    include_external: str = "collapsed"
    include_unresolved: bool = True
    include_tests: bool = False
    include_workflow: bool = False
    edge_types: Optional[Set[str]] = None
    node_kinds: Optional[Set[str]] = None
    include_evidence: bool = False
    include_claims: bool = True
    include_isolated: bool = False


class GraphSliceService:
    def __init__(self, store: AnalysisStore) -> None:
        self.store = store

    def slice(self, request: GraphSliceRequest) -> Dict[str, Any]:
        self.store.init()
        req = self._normalize(request)
        with self.store._connect() as conn:
            root_row = self._root_row(conn, req)
            diagnostics: List[Dict[str, Any]] = []
            if (req.root_graph_node_id or req.stable_key) and root_row is None:
                if not req.source_id:
                    raise KnowledgeError("GRAPH_NODE_NOT_FOUND", "Selected graph node was not found")
                diagnostics.append(self._missing_root_diagnostic(req))
            overview_metrics: Dict[str, Any] = {}
            if root_row is not None:
                node_rows, edge_rows, groups, uncertainties, truncated = self._slice_from_root(conn, req, root_row)
            else:
                node_rows, edge_rows, groups, uncertainties, truncated, overview_metrics = self._overview(conn, req)
            deduped_node_rows = self._dedupe_rows(node_rows, "id")
            node_rows = deduped_node_rows[: req.max_nodes]
            node_ids = {row["id"] for row in node_rows}
            deduped_edge_rows = self._dedupe_rows(edge_rows, "id")
            closed_edge_rows = [row for row in deduped_edge_rows if row["from_node_id"] in node_ids and row.get("to_node_id") in node_ids]
            skipped_missing_endpoint_count = len(deduped_edge_rows) - len(closed_edge_rows)
            skipped_by_limit_count = max(0, len(closed_edge_rows) - req.max_edges)
            edge_rows = closed_edge_rows[: req.max_edges]
            truncated = truncated or len(deduped_node_rows) > req.max_nodes or skipped_missing_endpoint_count > 0 or skipped_by_limit_count > 0
            node_views = [self.store._fact_node(conn, row, req.include_evidence, False, req.include_claims) for row in node_rows]
            node_by_id = {node["id"]: node for node in node_views}
            edge_views = [self.store._fact_edge(conn, row, node_by_id, req.include_evidence) for row in edge_rows]
            root_view = self.store._fact_node(conn, root_row, req.include_evidence, False, req.include_claims) if root_row is not None else None
            calls_taxonomy = self._calls_taxonomy(conn, req.source_id)
            return {
                "sourceId": req.source_id,
                "sourceName": self.store._graph_source_name(conn, req.source_id),
                "root": root_view,
                "status": self.store._graph_status(conn, req.source_id),
                "request": {
                    "flowDomain": req.flow_domain,
                    "direction": req.direction,
                    "depth": req.depth,
                    "maxNodes": req.max_nodes,
                    "maxEdges": req.max_edges,
                    "includeExternal": req.include_external,
                    "includeUnresolved": req.include_unresolved,
                    "includeTests": req.include_tests,
                    "includeWorkflow": req.include_workflow,
                    "includeIsolated": req.include_isolated,
                    "edgeTypes": sorted(req.edge_types or []),
                    "nodeKinds": sorted(req.node_kinds or []),
                    "includeClaims": req.include_claims,
                },
                "filters": {
                    "flowDomain": req.flow_domain,
                    "depth": req.depth,
                    "limit": req.max_nodes,
                    "includeEvidence": req.include_evidence,
                    "includeDiagnostics": True,
                },
                "nodes": node_views,
                "edges": edge_views,
                "claims": self.store._fact_claims(node_views) if req.include_claims else [],
                "evidence": self.store._fact_evidence(node_views, edge_views) if req.include_evidence else [],
                "selected": {"node": root_view, "edge": None},
                "groups": groups,
                "uncertainties": uncertainties,
                "diagnostics": [*diagnostics, *self.store._graph_source_diagnostics(conn, req.source_id)[:50]],
                "metrics": {
                    "totalNodesAvailable": self.store._fact_total_nodes(conn, req.source_id),
                    "totalEdgesAvailable": self.store._fact_total_edges(conn, req.source_id),
                    "sliceNodeCount": len(node_views),
                    "sliceEdgeCount": len(edge_views),
                    "collapsedGroupCount": len(groups),
                    "unresolvedCount": len(uncertainties),
                    "skippedEdgeCount": skipped_missing_endpoint_count + skipped_by_limit_count,
                    "skippedMissingEndpointCount": skipped_missing_endpoint_count,
                    "skippedByLimitCount": skipped_by_limit_count,
                    "truncationReason": self._truncation_reason(
                        len(deduped_node_rows) > req.max_nodes,
                        skipped_missing_endpoint_count,
                        skipped_by_limit_count,
                    ),
                    "truncated": truncated,
                    "callsTaxonomy": calls_taxonomy,
                    **overview_metrics,
                },
                "meta": {
                    "truncated": truncated,
                    "totalNodeCount": self.store._fact_total_nodes(conn, req.source_id),
                    "totalEdgeCount": self.store._fact_total_edges(conn, req.source_id),
                    "returnedNodeCount": len(node_views),
                    "returnedEdgeCount": len(edge_views),
                    "skippedEdgeCount": skipped_missing_endpoint_count + skipped_by_limit_count,
                    "skippedMissingEndpointCount": skipped_missing_endpoint_count,
                    "skippedByLimitCount": skipped_by_limit_count,
                    "truncationReason": self._truncation_reason(
                        len(deduped_node_rows) > req.max_nodes,
                        skipped_missing_endpoint_count,
                        skipped_by_limit_count,
                    ),
                    "maxNodeLimit": req.max_nodes,
                    "maxEdgeLimit": req.max_edges,
                    **overview_metrics,
                },
            }

    def _normalize(self, request: GraphSliceRequest) -> GraphSliceRequest:
        requested_max_nodes = int(request.max_nodes if request.max_nodes is not None else 80)
        requested_max_edges = int(request.max_edges if request.max_edges is not None else 120)
        return GraphSliceRequest(
            source_id=request.source_id,
            root_graph_node_id=request.root_graph_node_id,
            stable_key=request.stable_key,
            flow_domain=str(request.flow_domain or "CODE").upper(),
            direction=str(request.direction or "OUTBOUND").upper(),
            depth=max(0, min(int(request.depth or 2), 4)),
            max_nodes=UNLIMITED_GRAPH_ITEMS if requested_max_nodes <= 0 else max(5, requested_max_nodes),
            max_edges=UNLIMITED_GRAPH_ITEMS if requested_max_edges <= 0 else max(5, requested_max_edges),
            include_external=str(request.include_external or "collapsed").lower(),
            include_unresolved=bool(request.include_unresolved),
            include_tests=bool(request.include_tests),
            include_workflow=bool(request.include_workflow),
            edge_types={item.upper() for item in request.edge_types or set()},
            node_kinds={item.upper() for item in request.node_kinds or set()},
            include_evidence=bool(request.include_evidence),
            include_claims=bool(request.include_claims),
            include_isolated=bool(request.include_isolated),
        )

    def _root_row(self, conn: sqlite3.Connection, request: GraphSliceRequest) -> Optional[Dict[str, Any]]:
        if request.root_graph_node_id:
            return self.store._fact_node_by_id(conn, request.root_graph_node_id, request.source_id)
        if request.stable_key:
            clauses = [self.store._current_graph_node_clause("n"), "n.stable_key = ?"]
            params: List[Any] = [request.stable_key]
            if request.source_id:
                clauses.append("n.source_id = ?")
                params.append(request.source_id)
            row = conn.execute(f"SELECT n.*, 0 AS graph_degree FROM analysis_graph_nodes n WHERE {' AND '.join(clauses)} LIMIT 1", params).fetchone()
            return self.store._row_dict(row) if row else None
        return None

    def _missing_root_diagnostic(self, request: GraphSliceRequest) -> Dict[str, Any]:
        return {
            "severity": "WARN",
            "stage": "GRAPH_SLICE",
            "code": "GRAPH_SLICE_ROOT_NOT_FOUND",
            "message": "Selected graph node was not found. Showing source overview instead.",
            "metadata": {
                "rootGraphNodeId": request.root_graph_node_id,
                "stableKey": request.stable_key,
                "fallback": "SOURCE_OVERVIEW",
            },
        }

    def _slice_from_root(
        self, conn: sqlite3.Connection, request: GraphSliceRequest, root: Dict[str, Any]
    ) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]], List[Dict[str, Any]], List[Dict[str, Any]], bool]:
        node_ids: Set[str] = {root["id"]}
        edge_rows: Dict[str, Dict[str, Any]] = {}
        groups: Dict[str, Dict[str, Any]] = {}
        uncertainties: Dict[str, Dict[str, Any]] = {}
        self._include_hierarchy(conn, root, node_ids, edge_rows)
        frontier: Set[str] = {root["id"]}
        truncated = False
        for _ in range(request.depth):
            if not frontier:
                break
            candidates = self._candidate_edges(conn, request, frontier)
            candidates = sorted(candidates, key=self._edge_sort_key, reverse=True)
            next_frontier: Set[str] = set()
            for edge in candidates:
                if len(edge_rows) >= request.max_edges or len(node_ids) >= request.max_nodes:
                    truncated = True
                    break
                metadata = self._metadata(edge)
                target_id = edge.get("to_node_id")
                if edge.get("edge_type") == "CALLS" and not target_id:
                    self._record_unresolved(edge, metadata, groups, uncertainties, request)
                    continue
                if not target_id:
                    continue
                adjacent_id = target_id if edge["from_node_id"] in frontier else edge["from_node_id"]
                adjacent = self.store._fact_node_by_id(conn, adjacent_id, request.source_id)
                if adjacent is None or not self._node_allowed(adjacent, request, is_root=False):
                    continue
                visibility = str(metadata.get("sliceDefaultVisibility") or "SHOW").upper()
                category = str(metadata.get("callTargetCategory") or "")
                if edge.get("edge_type") == "CALLS" and not self._call_visible(visibility, category, request):
                    self._record_group(edge, metadata, groups, "Collapsed calls")
                    continue
                edge_rows.setdefault(edge["id"], edge)
                if adjacent_id not in node_ids:
                    node_ids.add(adjacent_id)
                    next_frontier.add(adjacent_id)
            frontier = next_frontier
        node_rows = self.store._fact_nodes_by_ids(conn, node_ids)
        return node_rows, list(edge_rows.values()), list(groups.values()), list(uncertainties.values()), truncated

    def _overview(
        self, conn: sqlite3.Connection, request: GraphSliceRequest
    ) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]], List[Dict[str, Any]], List[Dict[str, Any]], bool, Dict[str, Any]]:
        clauses = [self.store._current_graph_node_clause("n")]
        params: List[Any] = []
        if request.source_id:
            clauses.append("n.source_id = ?")
            params.append(request.source_id)
        if request.node_kinds:
            placeholders = ",".join("?" for _ in request.node_kinds)
            clauses.append(f"n.node_kind IN ({placeholders})")
            params.extend(sorted(request.node_kinds))
        if request.flow_domain and request.flow_domain != "ALL":
            clauses.append("n.flow_domain = ?")
            params.append(request.flow_domain)
        candidate_limit = max(2000, request.max_nodes)
        rows = conn.execute(
            f"""
            SELECT n.*,
                   COALESCE(out_degree.count, 0) + COALESCE(in_degree.count, 0) AS graph_degree,
                   CASE WHEN entry.id IS NULL THEN 0 ELSE 1 END AS is_entrypoint,
                   COALESCE(claim_count.count, 0) AS claim_count,
                   COALESCE(diagnostic_count.count, 0) AS diagnostic_count
            FROM analysis_graph_nodes n
            LEFT JOIN (
                SELECT from_node_id AS node_id, COUNT(*) AS count
                FROM analysis_graph_edges
                GROUP BY from_node_id
            ) out_degree ON out_degree.node_id = n.id
            LEFT JOIN (
                SELECT to_node_id AS node_id, COUNT(*) AS count
                FROM analysis_graph_edges
                GROUP BY to_node_id
            ) in_degree ON in_degree.node_id = n.id
            LEFT JOIN analysis_graph_claims entry
              ON entry.node_id = n.id
             AND entry.claim_kind = 'ENTRYPOINT_HINT'
             AND entry.status IN ('TRUSTED', 'LOW_CONFIDENCE')
            LEFT JOIN (
                SELECT node_id, COUNT(*) AS count
                FROM analysis_graph_claims
                WHERE status IN ('TRUSTED', 'LOW_CONFIDENCE')
                GROUP BY node_id
            ) claim_count ON claim_count.node_id = n.id
            LEFT JOIN (
                SELECT analysis_file_id, COUNT(*) AS count
                FROM analysis_graph_diagnostics
                GROUP BY analysis_file_id
            ) diagnostic_count ON diagnostic_count.analysis_file_id = n.analysis_file_id
            WHERE {" AND ".join(clauses)}
            ORDER BY is_entrypoint DESC, graph_degree DESC, n.node_kind = 'CALLABLE' DESC, n.confidence DESC
            LIMIT ?
        """,
            [*params, candidate_limit],
        ).fetchall()
        candidates = [self.store._row_dict(row) for row in rows]
        node_rows = [row for row in candidates if self._node_allowed(row, request, is_root=False)]
        node_by_id = {row["id"]: row for row in node_rows}
        visible_edges = self._visible_edges_for_nodes(conn, request, set(node_by_id))
        visible_edges = [row for row in visible_edges if row.get("from_node_id") in node_by_id and row.get("to_node_id") in node_by_id]
        components = self._overview_components(node_by_id, visible_edges)
        connected_components = [component for component in components if component["edge_ids"]]
        isolated_components = [component for component in components if not component["edge_ids"]]
        selected_ids: Set[str] = set()
        for component in connected_components:
            self._select_component_nodes(component, node_by_id, visible_edges, selected_ids, request.max_nodes)
            if len(selected_ids) >= request.max_nodes:
                break
        show_isolated = request.include_isolated or not selected_ids
        hidden_isolated_count = 0
        if show_isolated:
            for component in isolated_components:
                if len(selected_ids) >= request.max_nodes:
                    break
                selected_ids.update(component["node_ids"])
        else:
            hidden_isolated_count = sum(len(component["node_ids"]) for component in isolated_components)
        selected_nodes = [node_by_id[node_id] for node_id in selected_ids if node_id in node_by_id]
        selected_nodes.sort(key=lambda row: self._node_sort_key(row), reverse=True)
        selected_node_ids = {row["id"] for row in selected_nodes[: request.max_nodes]}
        selected_edges_all = [
            row
            for row in sorted(visible_edges, key=self._edge_sort_key, reverse=True)
            if row.get("from_node_id") in selected_node_ids and row.get("to_node_id") in selected_node_ids
        ]
        selected_edges = selected_edges_all[: request.max_edges]
        groups: List[Dict[str, Any]] = []
        if hidden_isolated_count > 0:
            groups.append(
                {
                    "id": "slice-group:ISOLATED_NODES",
                    "groupType": "ISOLATED_NODES",
                    "label": f"Isolated nodes ({hidden_isolated_count})",
                    "count": hidden_isolated_count,
                    "examples": [
                        node_by_id[next(iter(component["node_ids"]))].get("display_name")
                        or node_by_id[next(iter(component["node_ids"]))].get("name")
                        or next(iter(component["node_ids"]))
                        for component in isolated_components[:5]
                    ],
                    "reason": "Hidden from source overview because no drawable edges connect these nodes.",
                }
            )
        largest_component = max(
            connected_components, key=lambda item: (len(item["node_ids"]), len(item["edge_ids"])), default={"node_ids": set(), "edge_ids": set()}
        )
        metrics = {
            "hiddenIsolatedCount": hidden_isolated_count,
            "largestComponentNodeCount": len(largest_component["node_ids"]),
            "largestComponentEdgeCount": len(largest_component["edge_ids"]),
            "connectedComponentCount": len(connected_components),
            "overviewSelectionReason": "CONNECTED_COMPONENTS_FIRST",
        }
        truncated = len(selected_nodes) > request.max_nodes or len(selected_edges_all) > request.max_edges or hidden_isolated_count > 0
        return selected_nodes[: request.max_nodes], selected_edges, groups, [], truncated, metrics

    def _overview_components(self, node_by_id: Dict[str, Dict[str, Any]], edges: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        adjacency: Dict[str, Set[str]] = {node_id: set() for node_id in node_by_id}
        edge_ids_by_component_key: Dict[Tuple[str, str], Set[str]] = {}
        edge_by_id: Dict[str, Dict[str, Any]] = {}
        for edge in edges:
            from_id = edge.get("from_node_id")
            to_id = edge.get("to_node_id")
            if from_id not in node_by_id or to_id not in node_by_id:
                continue
            adjacency[from_id].add(to_id)
            adjacency[to_id].add(from_id)
            key = tuple(sorted((from_id, to_id)))
            edge_ids_by_component_key.setdefault(key, set()).add(edge["id"])
            edge_by_id[edge["id"]] = edge

        seen: Set[str] = set()
        components: List[Dict[str, Any]] = []
        for node_id in node_by_id:
            if node_id in seen:
                continue
            stack = [node_id]
            seen.add(node_id)
            component_nodes: Set[str] = set()
            while stack:
                current = stack.pop()
                component_nodes.add(current)
                for adjacent in adjacency.get(current, set()):
                    if adjacent not in seen:
                        seen.add(adjacent)
                        stack.append(adjacent)
            component_edge_ids: Set[str] = set()
            for left in component_nodes:
                for right in adjacency.get(left, set()):
                    if right in component_nodes:
                        component_edge_ids.update(edge_ids_by_component_key.get(tuple(sorted((left, right))), set()))
            components.append(
                {
                    "node_ids": component_nodes,
                    "edge_ids": component_edge_ids,
                    "score": self._component_score(component_nodes, component_edge_ids, node_by_id, edge_by_id),
                }
            )
        components.sort(
            key=lambda component: (
                float(component.get("score") or 0.0),
                len(component["edge_ids"]),
                len(component["node_ids"]),
            ),
            reverse=True,
        )
        return components

    def _component_score(self, node_ids: Set[str], edge_ids: Set[str], node_by_id: Dict[str, Dict[str, Any]], edge_by_id: Dict[str, Dict[str, Any]]) -> float:
        score = 0.0
        for node_id in node_ids:
            score += float(sum(self._node_sort_key(node_by_id[node_id])))
        for edge_id in edge_ids:
            edge = edge_by_id.get(edge_id) or {}
            edge_type = str(edge.get("edge_type") or "").upper()
            resolution_status = str(edge.get("resolution_status") or "").upper()
            if edge_type == "CALLS" and resolution_status == "RESOLVED":
                score += 100.0
            elif edge_type == "CALLS":
                score += 45.0
            elif edge_type in {"DECLARES", "CONTAINS"}:
                score += 25.0
            else:
                score += 5.0
        if not edge_ids:
            score -= 100.0
        return score

    def _select_component_nodes(
        self, component: Dict[str, Any], node_by_id: Dict[str, Dict[str, Any]], edges: List[Dict[str, Any]], selected_ids: Set[str], max_nodes: int
    ) -> None:
        if len(selected_ids) >= max_nodes:
            return
        component_node_ids: Set[str] = set(component["node_ids"])
        if len(selected_ids) + len(component_node_ids - selected_ids) <= max_nodes:
            selected_ids.update(component_node_ids)
            return

        component_edge_ids: Set[str] = set(component["edge_ids"])
        component_edges = [edge for edge in edges if edge["id"] in component_edge_ids]
        for edge in sorted(component_edges, key=self._edge_sort_key, reverse=True):
            needed = {edge.get("from_node_id"), edge.get("to_node_id")} - selected_ids
            needed.discard(None)
            if not needed:
                continue
            if len(selected_ids) + len(needed) <= max_nodes:
                selected_ids.update(needed)
        for node_id in sorted(component_node_ids, key=lambda item: self._node_sort_key(node_by_id[item]), reverse=True):
            if len(selected_ids) >= max_nodes:
                break
            selected_ids.add(node_id)

    def _node_sort_key(self, row: Dict[str, Any]) -> Tuple[float, float, float, float, float]:
        kind = str(row.get("node_kind") or "").upper()
        entrypoint = 1.0 if row.get("is_entrypoint") else 0.0
        kind_score = 45.0 if kind == "CALLABLE" else 35.0 if kind == "TYPE" else 20.0 if kind == "FILE" else 10.0
        degree_score = min(80.0, float(row.get("graph_degree") or 0) * 6.0)
        diagnostic_score = 25.0 if int(row.get("diagnostic_count") or 0) > 0 else 0.0
        confidence = float(row.get("confidence") or 0.0)
        return (entrypoint * 200.0, degree_score, kind_score, diagnostic_score, confidence)

    def _include_hierarchy(self, conn: sqlite3.Connection, root: Dict[str, Any], node_ids: Set[str], edge_rows: Dict[str, Dict[str, Any]]) -> None:
        current = root
        while current.get("parent_node_id"):
            parent = self.store._fact_node_by_id(conn, current["parent_node_id"], current.get("source_id"))
            if parent is None:
                break
            node_ids.add(parent["id"])
            for edge in self._edges_between(conn, parent["id"], current["id"]):
                edge_rows.setdefault(edge["id"], edge)
            current = parent
        file_row = conn.execute(
            """
            SELECT n.*, 0 AS graph_degree
            FROM analysis_graph_nodes n
            WHERE n.analysis_file_id = ?
              AND n.node_kind = 'FILE'
            LIMIT 1
        """,
            (root.get("analysis_file_id"),),
        ).fetchone()
        if file_row:
            file_node = self.store._row_dict(file_row)
            node_ids.add(file_node["id"])
            for edge in self._edges_between(conn, file_node["id"], root["id"]):
                edge_rows.setdefault(edge["id"], edge)

    def _candidate_edges(self, conn: sqlite3.Connection, request: GraphSliceRequest, frontier: Set[str]) -> List[Dict[str, Any]]:
        if not frontier:
            return []
        placeholders = ",".join("?" for _ in frontier)
        clauses = [self.store._current_graph_edge_clause("e")]
        params: List[Any] = []
        if request.direction == "INBOUND":
            clauses.append(f"e.to_node_id IN ({placeholders})")
            params.extend(frontier)
        elif request.direction in {"BOTH", "CALLERS", "DEPENDENCIES"}:
            clauses.append(f"(e.from_node_id IN ({placeholders}) OR e.to_node_id IN ({placeholders}))")
            params.extend([*frontier, *frontier])
        else:
            clauses.append(f"e.from_node_id IN ({placeholders})")
            params.extend(frontier)
        if request.source_id:
            clauses.append("e.source_id = ?")
            params.append(request.source_id)
        if request.edge_types:
            edge_placeholders = ",".join("?" for _ in request.edge_types)
            clauses.append(f"e.edge_type IN ({edge_placeholders})")
            params.extend(sorted(request.edge_types))
        else:
            clauses.append("e.edge_type IN ('CALLS', 'DECLARES', 'CONTAINS')")
        rows = conn.execute(
            f"""
            SELECT e.*
            FROM analysis_graph_edges e
            WHERE {" AND ".join(clauses)}
            LIMIT ?
        """,
            [*params, request.max_edges * 4],
        ).fetchall()
        return [self.store._row_dict(row) for row in rows if self._edge_allowed(self.store._row_dict(row), request)]

    def _visible_edges_for_nodes(self, conn: sqlite3.Connection, request: GraphSliceRequest, node_ids: Set[str]) -> List[Dict[str, Any]]:
        rows = self.store._fact_edges_for_nodes(conn, node_ids, request.source_id, max(request.max_edges * 20, 1000), None)
        result = []
        for row in sorted(rows, key=self._edge_sort_key, reverse=True):
            metadata = self._metadata(row)
            if row.get("edge_type") == "CALLS" and not self._call_visible(
                str(metadata.get("sliceDefaultVisibility") or "SHOW"), str(metadata.get("callTargetCategory") or ""), request
            ):
                continue
            if self._edge_allowed(row, request):
                result.append(row)
        return result

    def _edges_between(self, conn: sqlite3.Connection, from_id: str, to_id: str) -> List[Dict[str, Any]]:
        rows = conn.execute(
            f"""
            SELECT e.*
            FROM analysis_graph_edges e
            WHERE {self.store._current_graph_edge_clause("e")}
              AND e.from_node_id = ?
              AND e.to_node_id = ?
              AND e.edge_type IN ('DECLARES', 'CONTAINS')
        """,
            (from_id, to_id),
        ).fetchall()
        return [self.store._row_dict(row) for row in rows]

    def _node_allowed(self, row: Dict[str, Any], request: GraphSliceRequest, is_root: bool) -> bool:
        if is_root:
            return True
        domain = str(row.get("flow_domain") or "UNKNOWN").upper()
        if request.node_kinds and str(row.get("node_kind") or "").upper() not in request.node_kinds:
            return False
        if not request.include_tests and domain == "TEST":
            return False
        if not request.include_workflow and domain in {"WORKFLOW", "CONFIG", "BUILD"}:
            return False
        if request.flow_domain and request.flow_domain != "ALL" and domain != request.flow_domain:
            return False
        return True

    def _edge_allowed(self, row: Dict[str, Any], request: GraphSliceRequest) -> bool:
        domain = str(row.get("flow_domain") or "UNKNOWN").upper()
        if request.edge_types and str(row.get("edge_type") or "").upper() not in request.edge_types:
            return False
        if not request.include_tests and domain == "TEST":
            return False
        if not request.include_workflow and domain in {"WORKFLOW", "CONFIG", "BUILD"}:
            return False
        if request.flow_domain and request.flow_domain != "ALL" and domain != request.flow_domain:
            return row.get("edge_type") in {"DECLARES", "CONTAINS"}
        return True

    def _call_visible(self, visibility: str, category: str, request: GraphSliceRequest) -> bool:
        external = category.startswith("EXTERNAL_")
        if external and request.include_external == "hide":
            return False
        if external and request.include_external == "collapsed":
            return False
        if visibility == "HIDE_BY_DEFAULT":
            return False
        if visibility == "COLLAPSE":
            return request.include_external == "show"
        return True

    def _record_unresolved(
        self,
        edge: Dict[str, Any],
        metadata: Dict[str, Any],
        groups: Dict[str, Dict[str, Any]],
        uncertainties: Dict[str, Dict[str, Any]],
        request: GraphSliceRequest,
    ) -> None:
        category = str(metadata.get("callTargetCategory") or "")
        if category.startswith("EXTERNAL_"):
            self._record_group(edge, metadata, groups, "External calls")
            return
        if not request.include_unresolved:
            self._record_group(edge, metadata, groups, "Hidden unresolved calls")
            return
        visibility = str(metadata.get("sliceDefaultVisibility") or "SHOW_AS_UNCERTAINTY")
        if visibility == "HIDE_BY_DEFAULT":
            self._record_group(edge, metadata, groups, "Unresolved low-value calls")
            return
        uncertainties.setdefault(
            edge["id"],
            {
                "id": edge["id"],
                "edgeId": edge["id"],
                "kind": "UNRESOLVED_CALL",
                "from": edge["from_node_id"],
                "target": self._unresolved_target(edge),
                "methodName": metadata.get("methodName"),
                "receiverText": metadata.get("receiverText"),
                "receiverTypeHint": metadata.get("receiverTypeHint"),
                "unresolvedReason": metadata.get("unresolvedReason") or "UNKNOWN",
                "resolutionStatus": edge.get("resolution_status"),
                "flowUsefulness": metadata.get("flowUsefulness"),
                "lineStart": self._edge_line(edge),
                "message": self._uncertainty_message(metadata),
            },
        )

    def _record_group(self, edge: Dict[str, Any], metadata: Dict[str, Any], groups: Dict[str, Dict[str, Any]], default_label: str) -> None:
        category = str(metadata.get("callTargetCategory") or metadata.get("noiseCategory") or "COLLAPSED")
        group_type = category if category != "UNKNOWN" else str(metadata.get("noiseCategory") or "COLLAPSED")
        group = groups.setdefault(
            group_type,
            {
                "id": f"slice-group:{group_type}",
                "groupType": group_type,
                "label": self._group_label(group_type, default_label),
                "count": 0,
                "examples": [],
                "reason": metadata.get("noiseCategory") or metadata.get("unresolvedReason") or default_label,
            },
        )
        group["count"] += 1
        example = metadata.get("rawCallText") or metadata.get("methodName") or self._unresolved_target(edge)
        if example and len(group["examples"]) < 5:
            group["examples"].append(example)

    def _calls_taxonomy(self, conn: sqlite3.Connection, source_id: Optional[str]) -> Dict[str, Dict[str, int]]:
        clauses = [self.store._current_graph_edge_clause("e"), "e.edge_type = 'CALLS'"]
        params: List[Any] = []
        if source_id:
            clauses.append("e.source_id = ?")
            params.append(source_id)
        rows = conn.execute(
            f"SELECT resolution_status, flow_domain, metadata_json FROM analysis_graph_edges e WHERE {' AND '.join(clauses)}", params
        ).fetchall()
        result: Dict[str, Dict[str, int]] = {
            "resolutionStatus": {},
            "unresolvedReason": {},
            "callKind": {},
            "callTargetCategory": {},
            "flowDomain": {},
        }
        for row in rows:
            metadata = self._json_dict(row["metadata_json"])
            self._increment(result["resolutionStatus"], row["resolution_status"] or "UNKNOWN")
            self._increment(result["flowDomain"], row["flow_domain"] or "UNKNOWN")
            self._increment(result["unresolvedReason"], metadata.get("unresolvedReason") or "NONE")
            self._increment(result["callKind"], metadata.get("callKind") or "UNKNOWN")
            self._increment(result["callTargetCategory"], metadata.get("callTargetCategory") or "UNKNOWN")
        return result

    def _edge_sort_key(self, row: Dict[str, Any]) -> Tuple[float, float, float]:
        metadata = self._metadata(row)
        return (
            float(metadata.get("expansionScore") or 0.0),
            float(metadata.get("flowScore") or 0.0),
            float(row.get("confidence") or 0.0),
        )

    def _truncation_reason(self, node_limited: bool, skipped_missing_endpoint_count: int, skipped_by_limit_count: int) -> Optional[str]:
        reasons: List[str] = []
        if node_limited:
            reasons.append("NODE_LIMIT")
        if skipped_missing_endpoint_count > 0:
            reasons.append("EDGE_ENDPOINT_NOT_RETURNED")
        if skipped_by_limit_count > 0:
            reasons.append("EDGE_LIMIT")
        return ",".join(reasons) if reasons else None

    def _metadata(self, row: Dict[str, Any]) -> Dict[str, Any]:
        return self._json_dict(row.get("metadata_json"))

    def _json_dict(self, value: Any) -> Dict[str, Any]:
        if isinstance(value, dict):
            return value
        if not value:
            return {}
        try:
            parsed = json.loads(value)
        except (TypeError, json.JSONDecodeError):
            return {}
        return parsed if isinstance(parsed, dict) else {}

    def _dedupe_rows(self, rows: Iterable[Dict[str, Any]], key: str) -> List[Dict[str, Any]]:
        seen: Set[Any] = set()
        result: List[Dict[str, Any]] = []
        for row in rows:
            value = row.get(key)
            if value in seen:
                continue
            seen.add(value)
            result.append(row)
        return result

    def _unresolved_target(self, edge: Dict[str, Any]) -> Any:
        if not edge.get("unresolved_target_json"):
            return None
        try:
            return json.loads(edge.get("unresolved_target_json"))
        except (TypeError, json.JSONDecodeError):
            return edge.get("unresolved_target_json")

    def _edge_line(self, edge: Dict[str, Any]) -> Optional[int]:
        metadata = self._metadata(edge)
        return metadata.get("callsiteLineStart") or metadata.get("lineStart")

    def _uncertainty_message(self, metadata: Dict[str, Any]) -> str:
        reason = metadata.get("unresolvedReason") or "UNKNOWN"
        receiver = metadata.get("receiverText")
        receiver_type = metadata.get("receiverTypeHint")
        if receiver_type:
            return f"{reason}: receiver '{receiver}' has type hint '{receiver_type}', but no safe target was selected."
        return f"{reason}: call target could not be resolved safely."

    def _group_label(self, group_type: str, default_label: str) -> str:
        labels = {
            "EXTERNAL_JDK": "External JDK calls",
            "EXTERNAL_FRAMEWORK": "Framework calls",
            "EXTERNAL_LIBRARY": "External library calls",
            "EXTERNAL_SERVICE": "External service calls",
            "JDK_UTILITY": "JDK utility calls",
            "FRAMEWORK_CALL": "Framework calls",
            "TEST_CALL": "Test calls",
        }
        return labels.get(group_type, default_label)

    def _increment(self, bucket: Dict[str, int], key: Any) -> None:
        text = str(key or "UNKNOWN")
        bucket[text] = bucket.get(text, 0) + 1
