from __future__ import annotations

import time
from collections import defaultdict, deque
from dataclasses import dataclass, replace
from enum import Enum
from typing import TYPE_CHECKING, Sequence

from knowledge_service.flow_graph_contract import (
    FlowEdgeKey,
    FlowGraphBundle,
    FlowGraphEdge,
    FlowGraphEvidence,
    FlowGraphNode,
    FlowNodeKey,
    dedupe_evidence,
)
from knowledge_service.knowledge_query_schema import (
    KnowledgeQueryDiagnostic,
    KnowledgeQueryFlow,
    KnowledgeQueryFlowBoundary,
    KnowledgeQueryFlowCoverage,
    KnowledgeQueryFlowEvidence,
    KnowledgeQueryFlowNode,
    KnowledgeQueryFlowOrigin,
    KnowledgeQueryFlowTransition,
    KnowledgeQueryMatchedNode,
)

if TYPE_CHECKING:
    from knowledge_service.knowledge_query_service import KnowledgeQueryPolicy


class EntrypointFlowOrigin(str, Enum):
    EXPLICIT_GRAPH_FACT = "EXPLICIT_GRAPH_FACT"
    INFERRED_ROOT = "INFERRED_ROOT"


@dataclass(frozen=True)
class EntrypointFlowKey:
    source_id: str
    graph_revision: str
    entrypoint_node_id: str


@dataclass(frozen=True)
class EntrypointFlowAnchor:
    node_id: str
    label: str
    score: float
    match_reasons: tuple[str, ...]
    distance: int


@dataclass(frozen=True)
class EntrypointFlowCoverage:
    node_count: int
    transition_count: int
    boundary_count: int
    anchor_count: int
    max_depth_reached: int
    cycle_detected: bool = False
    truncated: bool = False


@dataclass(frozen=True)
class EntrypointFlow:
    key: EntrypointFlowKey
    entrypoint: FlowGraphNode
    origin: EntrypointFlowOrigin
    anchors: tuple[EntrypointFlowAnchor, ...]
    nodes: tuple[FlowGraphNode, ...]
    transitions: tuple[FlowGraphEdge, ...]
    boundary_transitions: tuple[FlowGraphEdge, ...]
    evidence: tuple[FlowGraphEvidence, ...]
    complete: bool
    coverage: EntrypointFlowCoverage
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...]
    relevance_score: float


@dataclass(frozen=True)
class EntrypointFlowBuildResult:
    flows: tuple[EntrypointFlow, ...]
    public_flows: list[KnowledgeQueryFlow]
    diagnostics: list[KnowledgeQueryDiagnostic]
    truncated: bool
    discovered_entrypoint_count: int = 0
    stage_timings_ms: dict[str, float] | None = None


@dataclass(frozen=True)
class _Adjacency:
    nodes: dict[FlowNodeKey, FlowGraphNode]
    edges: dict[FlowEdgeKey, FlowGraphEdge]
    incoming: dict[FlowNodeKey, tuple[FlowEdgeKey, ...]]
    outgoing: dict[FlowNodeKey, tuple[FlowEdgeKey, ...]]
    evidence_by_id: dict[str, FlowGraphEvidence]


class EntrypointFlowEngine:
    def build(
        self,
        bundle: FlowGraphBundle,
        anchors: Sequence[KnowledgeQueryMatchedNode],
        policy: "KnowledgeQueryPolicy",
        *,
        max_flows: int,
        include_tests: bool,
    ) -> EntrypointFlowBuildResult:
        callable_anchors = [
            item for item in anchors
            if str(item.nodeKind or "").upper() == "CALLABLE"
        ]
        adjacency = self._adjacency(bundle, include_tests)
        callable_anchors = [item for item in callable_anchors if self._anchor_key(item) in adjacency.nodes]
        if not callable_anchors:
            diagnostic = KnowledgeQueryDiagnostic(
                code="ENTRYPOINT_FLOW_NO_CALLABLE_ANCHORS",
                message="No eligible callable anchors were available for entrypoint discovery.",
                severity="INFO",
            )
            return EntrypointFlowBuildResult((), [], [diagnostic], bundle.truncated, stage_timings_ms={})

        started_at = time.monotonic()
        discovery_started = time.monotonic()
        candidates, discovery_diagnostics, discovery_truncated = self._discover_entrypoints(
            adjacency, callable_anchors, policy, started_at
        )
        discovery_ms = (time.monotonic() - discovery_started) * 1000
        ranked = sorted(candidates.items(), key=self._candidate_sort_key)
        discovered_count = len(ranked)
        selected = ranked[:max(1, max_flows)]
        result_truncated = bundle.truncated or discovery_truncated or len(ranked) > len(selected)
        diagnostics = list(discovery_diagnostics)
        if len(ranked) > len(selected):
            diagnostics.append(KnowledgeQueryDiagnostic(
                code="ENTRYPOINT_FLOW_MAX_FLOWS_REACHED",
                message="Distinct entrypoint flows were truncated by maxFlows.",
                severity="INFO",
                metadata={
                    "returnedFlowCount": len(selected),
                    "discoveredEntrypointCount": len(ranked),
                    "truncatedFlowCount": len(ranked) - len(selected),
                    "maxFlows": max_flows,
                },
            ))

        collection_started = time.monotonic()
        flows = tuple(
            self._collect_flow(adjacency, key, origins, policy, started_at, bundle.truncated)
            for key, origins in selected
        )
        collection_ms = (time.monotonic() - collection_started) * 1000
        public = self.public_flows(flows)
        return EntrypointFlowBuildResult(
            flows, public, diagnostics, result_truncated, discovered_count,
            {"entrypointDiscovery": round(discovery_ms, 3), "downstreamSliceCollection": round(collection_ms, 3)},
        )

    def public_flows(self, flows: Sequence[EntrypointFlow]) -> list[KnowledgeQueryFlow]:
        return [self._public_flow(index, flow) for index, flow in enumerate(flows, start=1)]

    def _discover_entrypoints(
        self,
        adjacency: _Adjacency,
        anchors: Sequence[KnowledgeQueryMatchedNode],
        policy: "KnowledgeQueryPolicy",
        started_at: float,
    ) -> tuple[dict[tuple[EntrypointFlowKey, EntrypointFlowOrigin], list[EntrypointFlowAnchor]], list[KnowledgeQueryDiagnostic], bool]:
        result: dict[tuple[EntrypointFlowKey, EntrypointFlowOrigin], list[EntrypointFlowAnchor]] = defaultdict(list)
        diagnostics: list[KnowledgeQueryDiagnostic] = []
        truncated = False
        max_depth = max(0, int(policy.max_reverse_depth))
        max_nodes = max(1, int(policy.max_traversal_nodes))
        max_entrypoints = max(1, int(policy.max_entrypoints_per_query))
        visited_total = 0

        for anchor in sorted(anchors, key=lambda item: (-item.score, item.sourceId, item.nodeId)):
            anchor_key = self._anchor_key(anchor)
            queue = deque([(anchor_key, 0)])
            visited = {anchor_key}
            explicit_found: list[tuple[FlowNodeKey, int]] = []
            inferred_found: list[tuple[FlowNodeKey, int]] = []
            while queue:
                node_key, distance = queue.popleft()
                if self._deadline_reached(policy, started_at) or visited_total >= max_nodes:
                    truncated = True
                    break
                visited_total += 1
                node = adjacency.nodes[node_key]
                if node.entrypoint:
                    explicit_found.append((node_key, distance))
                    continue
                incoming = adjacency.incoming.get(node_key, ())
                if not incoming:
                    inferred_found.append((node_key, distance))
                    continue
                if distance >= max_depth:
                    truncated = True
                    continue
                for edge_key in incoming[: max(1, int(policy.max_edges_per_node))]:
                    source_key = self._from_key(adjacency.edges[edge_key])
                    if source_key not in visited:
                        visited.add(source_key)
                        queue.append((source_key, distance + 1))

            found = explicit_found or inferred_found
            origin_kind = EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT if explicit_found else EntrypointFlowOrigin.INFERRED_ROOT
            if not found:
                diagnostics.append(KnowledgeQueryDiagnostic(
                    code="ENTRYPOINT_FLOW_ROOT_NOT_FOUND",
                    message="No explicit entrypoint or bounded topology root was reachable from an anchor.",
                    severity="WARN",
                    sourceId=anchor.sourceId,
                ))
                continue
            for node_key, distance in sorted(set(found), key=lambda item: (item[1], item[0])):
                node = adjacency.nodes[node_key]
                key = EntrypointFlowKey(node.source_id, node.graph_revision or node.graph_id, node.node_id)
                result[(key, origin_kind)].append(EntrypointFlowAnchor(
                    node_id=anchor.nodeId,
                    label=anchor.label,
                    score=anchor.score,
                    match_reasons=tuple(sorted(set(anchor.matchReasons))),
                    distance=distance,
                ))
                if len(result) >= max_entrypoints:
                    truncated = True
                    break
        return result, diagnostics, truncated

    def _collect_flow(
        self,
        adjacency: _Adjacency,
        candidate_key: tuple[EntrypointFlowKey, EntrypointFlowOrigin],
        raw_anchors: Sequence[EntrypointFlowAnchor],
        policy: "KnowledgeQueryPolicy",
        started_at: float,
        graph_truncated: bool,
    ) -> EntrypointFlow:
        key, origin = candidate_key
        root_key = (key.source_id, key.graph_revision, key.entrypoint_node_id)
        if root_key not in adjacency.nodes:
            root_key = next(item for item in adjacency.nodes if item[0] == key.source_id and item[2] == key.entrypoint_node_id)
        queue = deque([(root_key, 0)])
        visited = {root_key}
        transition_keys: set[FlowEdgeKey] = set()
        boundary_keys: set[FlowEdgeKey] = set()
        max_depth_reached = 0
        cycle = False
        truncated = graph_truncated
        max_depth = max(0, int(policy.max_downstream_depth))
        max_nodes = max(1, int(policy.max_traversal_nodes))
        max_edges = max(1, int(policy.max_edges_per_traversal))

        while queue:
            node_key, depth = queue.popleft()
            max_depth_reached = max(max_depth_reached, depth)
            if self._deadline_reached(policy, started_at) or len(visited) >= max_nodes:
                truncated = True
                break
            outgoing = adjacency.outgoing.get(node_key, ())
            branch_limit = max(1, int(policy.max_edges_per_node))
            if len(outgoing) > branch_limit:
                truncated = True
            for edge_key in outgoing[:branch_limit]:
                if len(transition_keys) + len(boundary_keys) >= max_edges:
                    truncated = True
                    break
                edge = adjacency.edges[edge_key]
                target_key = self._resolved_target_key(edge, adjacency.nodes)
                if target_key is None:
                    boundary_keys.add(edge_key)
                    continue
                transition_keys.add(edge_key)
                if target_key in visited:
                    cycle = True
                    continue
                if depth >= max_depth:
                    truncated = True
                    continue
                visited.add(target_key)
                queue.append((target_key, depth + 1))

        nodes = tuple(sorted((adjacency.nodes[item] for item in visited), key=self._node_sort_key))
        transitions = tuple(sorted((adjacency.edges[item] for item in transition_keys), key=lambda edge: self._edge_sort_key(edge, adjacency)))
        boundaries = tuple(sorted((adjacency.edges[item] for item in boundary_keys), key=lambda edge: self._edge_sort_key(edge, adjacency)))
        evidence_ids = {evidence_id for edge in (*transitions, *boundaries) for evidence_id in edge.evidence_ids}
        evidence_ids.update(
            item.evidence_id for item in adjacency.evidence_by_id.values()
            if item.node_id in {node.node_id for node in nodes}
        )
        evidence = dedupe_evidence([adjacency.evidence_by_id[item] for item in sorted(evidence_ids) if item in adjacency.evidence_by_id])
        diagnostics: list[KnowledgeQueryDiagnostic] = []
        if origin is EntrypointFlowOrigin.INFERRED_ROOT:
            diagnostics.append(KnowledgeQueryDiagnostic(
                code="ENTRYPOINT_FLOW_INFERRED_ROOT",
                message="No explicit entrypoint fact was reachable; the flow uses a topology root.",
                severity="INFO",
                sourceId=key.source_id,
            ))
        if cycle:
            diagnostics.append(KnowledgeQueryDiagnostic(
                code="ENTRYPOINT_FLOW_CYCLE_DETECTED",
                message="A downstream CALLS cycle was retained without repeated expansion.",
                severity="INFO",
                sourceId=key.source_id,
            ))
        if truncated:
            diagnostics.append(KnowledgeQueryDiagnostic(
                code="ENTRYPOINT_FLOW_SLICE_TRUNCATED",
                message="The bounded downstream CALLS slice reached an engine limit.",
                severity="WARN",
                sourceId=key.source_id,
            ))
        anchors = self._merge_anchors(raw_anchors)
        coverage = EntrypointFlowCoverage(
            node_count=len(nodes), transition_count=len(transitions), boundary_count=len(boundaries),
            anchor_count=len(anchors), max_depth_reached=max_depth_reached,
            cycle_detected=cycle, truncated=truncated,
        )
        relevance = self._relevance(origin, anchors)
        return EntrypointFlow(
            key=key, entrypoint=adjacency.nodes[root_key], origin=origin, anchors=anchors,
            nodes=nodes, transitions=transitions, boundary_transitions=boundaries,
            evidence=evidence, complete=not truncated, coverage=coverage,
            diagnostics=tuple(diagnostics), relevance_score=relevance,
        )

    def _adjacency(self, bundle: FlowGraphBundle, include_tests: bool) -> _Adjacency:
        nodes = {
            self._node_key(node): node for node in bundle.nodes
            if include_tests or str(node.flow_domain or "").upper() != "TEST"
        }
        edges: dict[FlowEdgeKey, FlowGraphEdge] = {}
        incoming: dict[FlowNodeKey, list[FlowEdgeKey]] = defaultdict(list)
        outgoing: dict[FlowNodeKey, list[FlowEdgeKey]] = defaultdict(list)
        evidence_by_id = {item.evidence_id: item for item in bundle.evidence}
        for edge in bundle.edges:
            if str(edge.edge_type or "").upper() != "CALLS":
                continue
            if not include_tests and str(edge.flow_domain or "").upper() == "TEST":
                continue
            from_key = self._from_key(edge)
            if from_key not in nodes:
                continue
            edge_key = self._edge_key(edge)
            edges[edge_key] = edge
            outgoing[from_key].append(edge_key)
            target_key = self._resolved_target_key(edge, nodes)
            if target_key is not None:
                incoming[target_key].append(edge_key)
        sort_key = lambda item: self._edge_sort_key(edges[item], _Adjacency(nodes, edges, {}, {}, evidence_by_id))
        return _Adjacency(
            nodes, edges,
            {key: tuple(sorted(value, key=sort_key)) for key, value in incoming.items()},
            {key: tuple(sorted(value, key=sort_key)) for key, value in outgoing.items()},
            evidence_by_id,
        )

    def _public_flow(self, index: int, flow: EntrypointFlow) -> KnowledgeQueryFlow:
        node_ref_by_id = {node.node_id: f"n{position}" for position, node in enumerate(flow.nodes, start=1)}
        transition_ref_by_id = {edge.edge_id: f"t{position}" for position, edge in enumerate(flow.transitions, start=1)}
        boundary_ref_by_id = {edge.edge_id: f"b{position}" for position, edge in enumerate(flow.boundary_transitions, start=1)}
        evidence_ref_by_id = {item.evidence_id: f"e{position}" for position, item in enumerate(flow.evidence, start=1)}
        public_nodes = [self._public_node(node, node_ref_by_id[node.node_id]) for node in flow.nodes]
        return KnowledgeQueryFlow(
            flowIndex=index,
            source=flow.key.source_id,
            entrypoint=self._public_node(flow.entrypoint, node_ref_by_id[flow.entrypoint.node_id]),
            entrypointOrigin=flow.origin.value,
            matchedAnchors=[KnowledgeQueryFlowOrigin(
                anchorRef=node_ref_by_id.get(item.node_id, "matched-anchor"), label=item.label,
                score=round(item.score, 4), distance=item.distance, matchReasons=list(item.match_reasons),
            ) for item in flow.anchors],
            nodes=public_nodes,
            transitions=[KnowledgeQueryFlowTransition(
                transitionRef=transition_ref_by_id[edge.edge_id],
                fromNodeRef=node_ref_by_id[edge.from_node_id],
                toNodeRef=node_ref_by_id[edge.to_node_id or ""],
                evidenceRefs=[evidence_ref_by_id[item] for item in edge.evidence_ids if item in evidence_ref_by_id],
            ) for edge in flow.transitions],
            boundaries=[KnowledgeQueryFlowBoundary(
                boundaryRef=boundary_ref_by_id[edge.edge_id],
                fromNodeRef=node_ref_by_id[edge.from_node_id], kind=self._boundary_kind(edge),
                resolutionStatus=edge.resolution_status, target=self._boundary_target(edge),
                evidenceRefs=[evidence_ref_by_id[item] for item in edge.evidence_ids if item in evidence_ref_by_id],
            ) for edge in flow.boundary_transitions],
            evidence=[KnowledgeQueryFlowEvidence(
                evidenceRef=evidence_ref_by_id[item.evidence_id],
                ownerRef=(transition_ref_by_id.get(item.edge_id or "") or boundary_ref_by_id.get(item.edge_id or "") or node_ref_by_id.get(item.node_id or "") or "flow"),
                relativePath=item.relative_path, lineStart=item.line_start, lineEnd=item.line_end, excerpt=item.text,
            ) for item in flow.evidence],
            complete=flow.complete,
            coverage=KnowledgeQueryFlowCoverage(**{
                "nodeCount": flow.coverage.node_count,
                "transitionCount": flow.coverage.transition_count,
                "boundaryCount": flow.coverage.boundary_count,
                "anchorCount": flow.coverage.anchor_count,
                "maxDepthReached": flow.coverage.max_depth_reached,
                "cycleDetected": flow.coverage.cycle_detected,
                "truncated": flow.coverage.truncated,
            }),
            diagnostics=list(flow.diagnostics),
        )

    def _public_node(self, node: FlowGraphNode, ref: str) -> KnowledgeQueryFlowNode:
        return KnowledgeQueryFlowNode(
            nodeRef=ref, label=node.label, kind=node.node_kind, qualifiedName=node.qualified_name,
            relativePath=node.relative_path, lineStart=node.line_start, lineEnd=node.line_end,
        )

    def _candidate_sort_key(self, item):
        (key, origin), anchors = item
        return (-self._relevance(origin, self._merge_anchors(anchors)), key.source_id, key.graph_revision, key.entrypoint_node_id)

    def _relevance(self, origin: EntrypointFlowOrigin, anchors: Sequence[EntrypointFlowAnchor]) -> float:
        best = max((item.score for item in anchors), default=0.0)
        shortest = min((item.distance for item in anchors), default=999)
        return best + min(len(anchors), 10) * 0.01 + (0.02 if origin is EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT else 0.0) + 0.01 / (1 + shortest)

    def _merge_anchors(self, anchors: Sequence[EntrypointFlowAnchor]) -> tuple[EntrypointFlowAnchor, ...]:
        merged: dict[str, EntrypointFlowAnchor] = {}
        for item in anchors:
            current = merged.get(item.node_id)
            if current is None:
                merged[item.node_id] = item
            else:
                merged[item.node_id] = EntrypointFlowAnchor(
                    item.node_id, current.label if current.score >= item.score else item.label,
                    max(current.score, item.score), tuple(sorted(set(current.match_reasons) | set(item.match_reasons))),
                    min(current.distance, item.distance),
                )
        return tuple(sorted(merged.values(), key=lambda item: (-item.score, item.distance, item.node_id)))

    def _edge_sort_key(self, edge: FlowGraphEdge, adjacency: _Adjacency) -> tuple[str, int, int, str]:
        evidence = [adjacency.evidence_by_id[item] for item in edge.evidence_ids if item in adjacency.evidence_by_id]
        first = min(evidence, key=lambda item: (item.relative_path or "", item.line_start or 0, item.line_end or 0, item.evidence_id), default=None)
        return (first.relative_path if first else "", first.line_start or 0 if first else 0, first.line_end or 0 if first else 0, edge.edge_id)

    def _node_sort_key(self, node: FlowGraphNode) -> tuple[str, int, int, str]:
        return (node.relative_path or "", node.line_start or 0, node.line_end or 0, node.node_id)

    def _deadline_reached(self, policy: "KnowledgeQueryPolicy", started_at: float) -> bool:
        return (time.monotonic() - started_at) * 1000 >= max(1, int(policy.max_execution_ms))

    def _anchor_key(self, item: KnowledgeQueryMatchedNode) -> FlowNodeKey:
        return (item.sourceId, item.graphRevision or item.graphId or "", item.nodeId)

    def _node_key(self, node: FlowGraphNode) -> FlowNodeKey:
        return (node.source_id, node.graph_revision or node.graph_id, node.node_id)

    def _edge_key(self, edge: FlowGraphEdge) -> FlowEdgeKey:
        return (edge.source_id, edge.graph_revision or edge.graph_id, edge.edge_id)

    def _from_key(self, edge: FlowGraphEdge) -> FlowNodeKey:
        return (edge.source_id, edge.graph_revision or edge.graph_id, edge.from_node_id)

    def _resolved_target_key(self, edge: FlowGraphEdge, nodes: dict[FlowNodeKey, FlowGraphNode]) -> FlowNodeKey | None:
        if not edge.to_node_id or edge.external or str(edge.resolution_status or "").upper() != "RESOLVED":
            return None
        key = (edge.source_id, edge.graph_revision or edge.graph_id, edge.to_node_id)
        return key if key in nodes else None

    def _boundary_kind(self, edge: FlowGraphEdge) -> str:
        return "EXTERNAL" if edge.external or str(edge.resolution_status).upper() == "EXTERNAL_TARGET" else "UNRESOLVED"

    def _boundary_target(self, edge: FlowGraphEdge) -> str | None:
        target = edge.unresolved_target or {}
        for key in ("name", "qualifiedName", "target", "kindHint", "displayName", "label", "symbol"):
            value = target.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        return None
