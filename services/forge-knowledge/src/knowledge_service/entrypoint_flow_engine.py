from __future__ import annotations

import time
from collections import defaultdict
from dataclasses import dataclass, field, replace
from enum import Enum
from typing import Protocol, Sequence

from knowledge_service.flow_graph_contract import (
    FlowEdgeKey,
    FlowGraphEdge,
    FlowGraphEvidence,
    FlowGraphEvidenceKey,
    FlowGraphNode,
    FlowNodeKey,
    dedupe_evidence,
    evidence_key,
)
from knowledge_service.flow_boundary_classifier import FlowBoundaryClassifier, FLOW_BOUNDARY_CLASSIFIER
from knowledge_service.knowledge_query_schema import (
    KnowledgeQueryDiagnostic,
    KnowledgeQueryEntrypointOrigin,
    KnowledgeQueryFlow,
    KnowledgeQueryFlowBoundary,
    KnowledgeQueryFlowCoverage,
    KnowledgeQueryFlowEvidence,
    KnowledgeQueryFlowNode,
    KnowledgeQueryFlowOrigin,
    KnowledgeQueryFlowTransition,
    KnowledgeQueryMatchedNode,
)


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
    traversal_stats: dict[str, int] | None = None


class EntrypointFlowGraphRepository(Protocol):
    def load_nodes(self, node_keys: set[FlowNodeKey], *, include_tests: bool) -> dict[FlowNodeKey, FlowGraphNode]:
        ...

    def load_incoming_calls(
        self,
        target_keys: set[FlowNodeKey],
        *,
        include_tests: bool,
    ) -> dict[FlowNodeKey, tuple[FlowGraphEdge, ...]]:
        ...

    def load_outgoing_calls(
        self,
        source_keys: set[FlowNodeKey],
        *,
        include_tests: bool,
    ) -> dict[FlowNodeKey, tuple[FlowGraphEdge, ...]]:
        ...

    def hydrate_evidence(self, flows: Sequence[EntrypointFlow]) -> tuple[EntrypointFlow, ...]:
        ...

    def metrics(self) -> dict[str, int]:
        ...


@dataclass
class _FlowCollectionState:
    candidate_key: tuple[EntrypointFlowKey, EntrypointFlowOrigin]
    anchors: tuple[EntrypointFlowAnchor, ...]
    root_key: FlowNodeKey
    nodes: dict[FlowNodeKey, FlowGraphNode]
    frontier: set[FlowNodeKey]
    expanded: set[FlowNodeKey] = field(default_factory=set)
    transitions: dict[FlowEdgeKey, FlowGraphEdge] = field(default_factory=dict)
    boundaries: dict[FlowEdgeKey, FlowGraphEdge] = field(default_factory=dict)
    depth_by_node: dict[FlowNodeKey, int] = field(default_factory=dict)
    cycle_detected: bool = False
    missing_resolved_target: bool = False


class EntrypointFlowEngine:
    def __init__(
        self,
        repository: EntrypointFlowGraphRepository | None = None,
        boundary_classifier: FlowBoundaryClassifier | None = None,
    ) -> None:
        self.repository = repository
        self.boundary_classifier = boundary_classifier or FLOW_BOUNDARY_CLASSIFIER

    def build(
        self,
        anchors: Sequence[KnowledgeQueryMatchedNode],
        *,
        max_flows: int,
        include_tests: bool,
    ) -> EntrypointFlowBuildResult:
        if self.repository is None:
            raise RuntimeError("EntrypointFlowEngine requires an EntrypointFlowGraphRepository")

        callable_anchors = [
            item for item in anchors
            if str(item.nodeKind or "").upper() == "CALLABLE"
        ]
        if not callable_anchors:
            diagnostic = KnowledgeQueryDiagnostic(
                code="ENTRYPOINT_FLOW_NO_CALLABLE_ANCHORS",
                message="No eligible callable anchors were available for entrypoint discovery.",
                severity="INFO",
            )
            return EntrypointFlowBuildResult((), [], [diagnostic], False, stage_timings_ms={}, traversal_stats=self.repository.metrics())

        started_at = time.monotonic()
        anchor_nodes = self.repository.load_nodes(
            {self._anchor_lookup_key(item) for item in callable_anchors},
            include_tests=include_tests,
        )
        resolved_anchors = self._resolved_anchors(callable_anchors, anchor_nodes)
        if not resolved_anchors:
            diagnostic = KnowledgeQueryDiagnostic(
                code="ENTRYPOINT_FLOW_NO_CALLABLE_ANCHORS",
                message="No eligible callable anchors were present in the current graph.",
                severity="INFO",
            )
            return EntrypointFlowBuildResult((), [], [diagnostic], False, stage_timings_ms={}, traversal_stats=self.repository.metrics())

        discovery_started = time.monotonic()
        candidates, discovery_diagnostics, reverse_rounds = self._discover_entrypoints(resolved_anchors, dict(anchor_nodes), include_tests)
        discovery_ms = (time.monotonic() - discovery_started) * 1000
        ranked = sorted(candidates.items(), key=self._candidate_sort_key)
        explicit_ranked = [
            item for item in ranked
            if item[0][1] is EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT
        ]
        selectable = explicit_ranked or ranked
        discovered_count = len(selectable)
        selected = selectable[:max(1, int(max_flows or 1))]
        diagnostics = list(discovery_diagnostics)
        omitted_count = max(0, discovered_count - len(selected))
        if omitted_count:
            diagnostics.append(KnowledgeQueryDiagnostic(
                code="ENTRYPOINT_FLOW_MAX_FLOWS_REACHED",
                message="Distinct entrypoint flows were omitted by maxFlows.",
                severity="INFO",
                metadata={
                    "returnedFlowCount": len(selected),
                    "discoveredEntrypointCount": discovered_count,
                    "omittedFlowCount": omitted_count,
                    "maxFlows": int(max_flows or 1),
                },
            ))

        collection_started = time.monotonic()
        flows, downstream_rounds = self._collect_flows(selected, include_tests)
        collection_ms = (time.monotonic() - collection_started) * 1000
        hydration_started = time.monotonic()
        flows = self.repository.hydrate_evidence(flows)
        hydration_ms = (time.monotonic() - hydration_started) * 1000
        public = self.public_flows(flows)
        stats = dict(self.repository.metrics())
        stats.update({
            "reverseFrontierRounds": reverse_rounds,
            "downstreamFrontierRounds": downstream_rounds,
            "discoveredEntrypointCount": discovered_count,
            "returnedFlowCount": len(flows),
            "omittedFlowCount": omitted_count,
        })
        return EntrypointFlowBuildResult(
            flows,
            public,
            diagnostics,
            omitted_count > 0,
            discovered_count,
            {
                "entrypointDiscovery": round(discovery_ms, 3),
                "downstreamSliceCollection": round(collection_ms, 3),
                "evidenceHydration": round(hydration_ms, 3),
                "engineTotal": round((time.monotonic() - started_at) * 1000, 3),
            },
            stats,
        )

    def public_flows(self, flows: Sequence[EntrypointFlow]) -> list[KnowledgeQueryFlow]:
        return [self._public_flow(index, flow) for index, flow in enumerate(flows, start=1)]

    def _discover_entrypoints(
        self,
        anchors: Sequence[tuple[KnowledgeQueryMatchedNode, FlowNodeKey]],
        known_nodes: dict[FlowNodeKey, FlowGraphNode],
        include_tests: bool,
    ) -> tuple[dict[tuple[EntrypointFlowKey, EntrypointFlowOrigin], list[EntrypointFlowAnchor]], list[KnowledgeQueryDiagnostic], int]:
        candidates: dict[tuple[EntrypointFlowKey, EntrypointFlowOrigin], list[EntrypointFlowAnchor]] = defaultdict(list)
        diagnostics: list[KnowledgeQueryDiagnostic] = []
        frontier_by_anchor: dict[int, dict[FlowNodeKey, int]] = {
            index: {key: 0}
            for index, (_anchor, key) in enumerate(anchors)
        }
        visited_by_anchor: dict[int, set[FlowNodeKey]] = {
            index: {key}
            for index, (_anchor, key) in enumerate(anchors)
        }
        explicit_by_anchor: dict[int, list[tuple[FlowNodeKey, int]]] = defaultdict(list)
        inferred_by_anchor: dict[int, list[tuple[FlowNodeKey, int]]] = defaultdict(list)
        rounds = 0

        while any(frontier_by_anchor.values()):
            rounds += 1
            query_frontier: set[FlowNodeKey] = set()
            for frontier in frontier_by_anchor.values():
                for node_key in frontier:
                    node = known_nodes.get(node_key)
                    if node is not None and node.entrypoint:
                        continue
                    query_frontier.add(node_key)

            incoming_by_target = self.repository.load_incoming_calls(query_frontier, include_tests=include_tests) if query_frontier else {}
            incoming_source_keys = {
                self._from_key(edge)
                for edges in incoming_by_target.values()
                for edge in edges
            }
            known_nodes.update(self.repository.load_nodes(incoming_source_keys - set(known_nodes), include_tests=include_tests))

            next_frontier: dict[int, dict[FlowNodeKey, int]] = defaultdict(dict)
            for anchor_index, frontier in frontier_by_anchor.items():
                for node_key, distance in frontier.items():
                    node = known_nodes.get(node_key)
                    if node is None:
                        continue
                    if node.entrypoint:
                        explicit_by_anchor[anchor_index].append((node_key, distance))
                        continue

                    incoming = [
                        self._from_key(edge)
                        for edge in incoming_by_target.get(node_key, ())
                        if self._from_key(edge) in known_nodes
                    ]
                    if not incoming:
                        inferred_by_anchor[anchor_index].append((node_key, distance))
                        continue
                    for source_key in sorted(set(incoming)):
                        if source_key in visited_by_anchor[anchor_index]:
                            continue
                        visited_by_anchor[anchor_index].add(source_key)
                        next_frontier[anchor_index][source_key] = distance + 1
            frontier_by_anchor = next_frontier

        for index, (anchor, _anchor_key) in enumerate(anchors):
            explicit = self._unique_nodes_by_distance(explicit_by_anchor.get(index, ()))
            inferred = self._unique_nodes_by_distance(inferred_by_anchor.get(index, ()))
            found = explicit or inferred
            origin = EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT if explicit else EntrypointFlowOrigin.INFERRED_ROOT
            if not found:
                diagnostics.append(KnowledgeQueryDiagnostic(
                    code="ENTRYPOINT_FLOW_ROOT_NOT_FOUND",
                    message="No explicit entrypoint or topology root was reachable from an anchor.",
                    severity="WARN",
                    sourceId=anchor.sourceId,
                ))
                continue
            for node_key, distance in found:
                node = known_nodes[node_key]
                key = EntrypointFlowKey(node.source_id, node.graph_revision or node.graph_id, node.node_id)
                candidates[(key, origin)].append(EntrypointFlowAnchor(
                    node_id=anchor.nodeId,
                    label=anchor.label,
                    score=anchor.score,
                    match_reasons=tuple(sorted(set(anchor.matchReasons))),
                    distance=distance,
                ))
        return candidates, diagnostics, rounds

    def _collect_flows(
        self,
        selected: Sequence[tuple[tuple[EntrypointFlowKey, EntrypointFlowOrigin], Sequence[EntrypointFlowAnchor]]],
        include_tests: bool,
    ) -> tuple[tuple[EntrypointFlow, ...], int]:
        if not selected:
            return (), 0
        root_keys = {
            (key.source_id, key.graph_revision, key.entrypoint_node_id)
            for (key, _origin), _anchors in selected
        }
        roots = self.repository.load_nodes(root_keys, include_tests=include_tests)
        states: list[_FlowCollectionState] = []
        for candidate_key, raw_anchors in selected:
            key, _origin = candidate_key
            root_key = (key.source_id, key.graph_revision, key.entrypoint_node_id)
            root = roots.get(root_key) or self._find_node_by_id(roots, root_key)
            if root is None:
                continue
            actual_root_key = self._node_key(root)
            states.append(_FlowCollectionState(
                candidate_key=candidate_key,
                anchors=self._merge_anchors(raw_anchors),
                root_key=actual_root_key,
                nodes={actual_root_key: root},
                frontier={actual_root_key},
                depth_by_node={actual_root_key: 0},
            ))

        rounds = 0
        while any(state.frontier for state in states):
            rounds += 1
            query_keys = {
                node_key
                for state in states
                for node_key in state.frontier
                if node_key not in state.expanded
            }
            outgoing_by_source = self.repository.load_outgoing_calls(query_keys, include_tests=include_tests) if query_keys else {}
            target_keys = {
                self._to_key(edge)
                for edges in outgoing_by_source.values()
                for edge in edges
                if self._is_resolved_internal(edge) and self._to_key(edge) is not None
            }
            target_nodes = self.repository.load_nodes({key for key in target_keys if key is not None}, include_tests=include_tests)

            for state in states:
                next_frontier: set[FlowNodeKey] = set()
                for node_key in sorted(state.frontier):
                    if node_key in state.expanded:
                        continue
                    state.expanded.add(node_key)
                    source_depth = state.depth_by_node.get(node_key, 0)
                    for edge in outgoing_by_source.get(node_key, ()):
                        edge_key = self._edge_key(edge)
                        target_key = self._to_key(edge)
                        if not self._is_resolved_internal(edge) or target_key is None:
                            state.boundaries.setdefault(edge_key, edge)
                            continue
                        target_node = target_nodes.get(target_key) or state.nodes.get(target_key)
                        if target_node is None:
                            state.boundaries.setdefault(
                                edge_key,
                                replace(edge, boundary_reason="CURRENT_TARGET_NODE_MISSING"),
                            )
                            state.missing_resolved_target = True
                            continue
                        state.nodes[target_key] = target_node
                        state.transitions.setdefault(edge_key, edge)
                        next_depth = source_depth + 1
                        previous_depth = state.depth_by_node.get(target_key)
                        if previous_depth is None or next_depth < previous_depth:
                            state.depth_by_node[target_key] = next_depth
                        elif previous_depth <= source_depth:
                            state.cycle_detected = True
                        if target_key in state.expanded:
                            if state.depth_by_node.get(target_key, next_depth) <= source_depth:
                                state.cycle_detected = True
                            continue
                        if target_key not in state.frontier:
                            next_frontier.add(target_key)
                state.frontier = next_frontier

        return tuple(self._flow_from_state(state) for state in states), rounds

    def _flow_from_state(self, state: _FlowCollectionState) -> EntrypointFlow:
        key, origin = state.candidate_key
        nodes = tuple(sorted(state.nodes.values(), key=lambda node: self._node_sort_key(node, state.root_key)))
        transitions = tuple(sorted(state.transitions.values(), key=self._edge_sort_key))
        boundaries = tuple(sorted(state.boundaries.values(), key=self._edge_sort_key))
        diagnostics: list[KnowledgeQueryDiagnostic] = []
        if origin is EntrypointFlowOrigin.INFERRED_ROOT:
            diagnostics.append(KnowledgeQueryDiagnostic(
                code="ENTRYPOINT_FLOW_INFERRED_ROOT",
                message="No explicit entrypoint fact was reachable; the flow uses a topology root.",
                severity="INFO",
                sourceId=key.source_id,
            ))
        if state.cycle_detected:
            diagnostics.append(KnowledgeQueryDiagnostic(
                code="ENTRYPOINT_FLOW_CYCLE_DETECTED",
                message="A downstream CALLS cycle was retained without repeated expansion.",
                severity="INFO",
                sourceId=key.source_id,
            ))
        if state.missing_resolved_target:
            diagnostics.append(KnowledgeQueryDiagnostic(
                code="ENTRYPOINT_FLOW_CURRENT_TARGET_NODE_MISSING",
                message="A resolved CALLS edge pointed to a target node outside the current graph and was exposed as a boundary.",
                severity="WARN",
                sourceId=key.source_id,
            ))
        anchors = state.anchors
        max_depth = max(state.depth_by_node.values(), default=0)
        coverage = EntrypointFlowCoverage(
            node_count=len(nodes),
            transition_count=len(transitions),
            boundary_count=len(boundaries),
            anchor_count=len(anchors),
            max_depth_reached=max_depth,
            cycle_detected=state.cycle_detected,
            truncated=False,
        )
        return EntrypointFlow(
            key=key,
            entrypoint=state.nodes[state.root_key],
            origin=origin,
            anchors=anchors,
            nodes=nodes,
            transitions=transitions,
            boundary_transitions=boundaries,
            evidence=(),
            complete=True,
            coverage=coverage,
            diagnostics=tuple(diagnostics),
            relevance_score=self._relevance(origin, anchors),
        )

    def _public_flow(self, index: int, flow: EntrypointFlow) -> KnowledgeQueryFlow:
        node_ref_by_id = {node.node_id: f"n{position}" for position, node in enumerate(flow.nodes, start=1)}
        transition_ref_by_id = {edge.edge_id: f"t{position}" for position, edge in enumerate(flow.transitions, start=1)}
        boundary_ref_by_id = {edge.edge_id: f"b{position}" for position, edge in enumerate(flow.boundary_transitions, start=1)}
        evidence_ref_by_key = {evidence_key(item): f"e{position}" for position, item in enumerate(flow.evidence, start=1)}
        public_nodes = [self._public_node(node, node_ref_by_id[node.node_id]) for node in flow.nodes]
        public_evidence: list[KnowledgeQueryFlowEvidence] = []
        for item in flow.evidence:
            owner_ref = (
                transition_ref_by_id.get(item.edge_id or "")
                or boundary_ref_by_id.get(item.edge_id or "")
                or node_ref_by_id.get(item.node_id or "")
            )
            if not owner_ref:
                continue
            public_evidence.append(KnowledgeQueryFlowEvidence(
                evidenceRef=evidence_ref_by_key[evidence_key(item)],
                ownerRef=owner_ref,
                relativePath=item.relative_path,
                lineStart=item.line_start,
                lineEnd=item.line_end,
                excerpt=item.text,
            ))
        return KnowledgeQueryFlow(
            flowIndex=index,
            source=flow.key.source_id,
            entrypoint=self._public_node(flow.entrypoint, node_ref_by_id[flow.entrypoint.node_id]),
            entrypointOrigin=KnowledgeQueryEntrypointOrigin(flow.origin.value),
            matchedAnchors=[
                KnowledgeQueryFlowOrigin(
                    anchorRef=node_ref_by_id[item.node_id],
                    label=item.label,
                    score=round(item.score, 4),
                    distance=item.distance,
                    matchReasons=list(item.match_reasons),
                )
                for item in flow.anchors
                if item.node_id in node_ref_by_id
            ],
            nodes=public_nodes,
            transitions=[
                KnowledgeQueryFlowTransition(
                    transitionRef=transition_ref_by_id[edge.edge_id],
                    fromNodeRef=node_ref_by_id[edge.from_node_id],
                    toNodeRef=node_ref_by_id[edge.to_node_id or ""],
                    evidenceRefs=self._public_edge_evidence_refs(edge, flow.evidence, evidence_ref_by_key),
                )
                for edge in flow.transitions
                if edge.from_node_id in node_ref_by_id and (edge.to_node_id or "") in node_ref_by_id
            ],
            boundaries=[
                self._public_boundary(edge, boundary_ref_by_id[edge.edge_id], node_ref_by_id, flow.evidence, evidence_ref_by_key)
                for edge in flow.boundary_transitions
                if edge.from_node_id in node_ref_by_id
            ],
            evidence=public_evidence,
            complete=flow.complete,
            coverage=KnowledgeQueryFlowCoverage(
                nodeCount=flow.coverage.node_count,
                transitionCount=flow.coverage.transition_count,
                boundaryCount=flow.coverage.boundary_count,
                anchorCount=flow.coverage.anchor_count,
                maxDepthReached=flow.coverage.max_depth_reached,
                cycleDetected=flow.coverage.cycle_detected,
                truncated=flow.coverage.truncated,
            ),
            diagnostics=list(flow.diagnostics),
        )

    def _resolved_anchors(
        self,
        anchors: Sequence[KnowledgeQueryMatchedNode],
        loaded_nodes: dict[FlowNodeKey, FlowGraphNode],
    ) -> list[tuple[KnowledgeQueryMatchedNode, FlowNodeKey]]:
        resolved: list[tuple[KnowledgeQueryMatchedNode, FlowNodeKey]] = []
        for anchor in sorted(anchors, key=lambda item: (-item.score, item.sourceId, item.nodeId)):
            lookup = self._anchor_lookup_key(anchor)
            node = loaded_nodes.get(lookup) or self._find_node_by_id(loaded_nodes, lookup)
            if node is None:
                continue
            resolved.append((anchor, self._node_key(node)))
        return resolved

    def _unique_nodes_by_distance(self, items: Sequence[tuple[FlowNodeKey, int]]) -> tuple[tuple[FlowNodeKey, int], ...]:
        best: dict[FlowNodeKey, int] = {}
        for node_key, distance in items:
            if node_key not in best or distance < best[node_key]:
                best[node_key] = distance
        return tuple(sorted(best.items(), key=lambda item: (item[1], item[0])))

    def _public_node(self, node: FlowGraphNode, ref: str) -> KnowledgeQueryFlowNode:
        return KnowledgeQueryFlowNode(
            nodeRef=ref,
            label=node.label,
            kind=node.node_kind,
            qualifiedName=node.qualified_name,
            relativePath=node.relative_path,
            lineStart=node.line_start,
            lineEnd=node.line_end,
        )

    def _public_boundary(
        self,
        edge: FlowGraphEdge,
        boundary_ref: str,
        node_ref_by_id: dict[str, str],
        evidence: Sequence[FlowGraphEvidence],
        evidence_ref_by_key: dict[FlowGraphEvidenceKey, str],
    ) -> KnowledgeQueryFlowBoundary:
        projection = self.boundary_classifier.project(edge)
        return KnowledgeQueryFlowBoundary(
            boundaryRef=boundary_ref,
            fromNodeRef=node_ref_by_id[edge.from_node_id],
            kind=projection.kind.value,
            resolutionStatus=projection.resolution_status,
            target=projection.target,
            evidenceRefs=self._public_edge_evidence_refs(edge, evidence, evidence_ref_by_key),
        )

    def _public_edge_evidence_refs(
        self,
        edge: FlowGraphEdge,
        evidence: Sequence[FlowGraphEvidence],
        evidence_ref_by_key: dict[FlowGraphEvidenceKey, str],
    ) -> list[str]:
        refs: list[str] = []
        linked_evidence_ids = set(edge.evidence_ids)
        for item in evidence:
            if item.edge_id != edge.edge_id:
                continue
            if linked_evidence_ids and item.evidence_id not in linked_evidence_ids:
                continue
            ref = evidence_ref_by_key.get(evidence_key(item))
            if ref and ref not in refs:
                refs.append(ref)
        return refs

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
                    item.node_id,
                    current.label if current.score >= item.score else item.label,
                    max(current.score, item.score),
                    tuple(sorted(set(current.match_reasons) | set(item.match_reasons))),
                    min(current.distance, item.distance),
                )
        return tuple(sorted(merged.values(), key=lambda item: (-item.score, item.distance, item.node_id)))

    def _edge_sort_key(self, edge: FlowGraphEdge) -> tuple[str, str, str, str, str]:
        return (
            edge.source_id,
            edge.graph_revision or edge.graph_id,
            edge.from_node_id,
            edge.to_node_id or "",
            edge.edge_id,
        )

    def _node_sort_key(self, node: FlowGraphNode, root_key: FlowNodeKey) -> tuple[int, str, int, int, str]:
        return (
            0 if self._node_key(node) == root_key else 1,
            node.relative_path or "",
            node.line_start or 0,
            node.line_end or 0,
            node.node_id,
        )

    def _anchor_lookup_key(self, item: KnowledgeQueryMatchedNode) -> FlowNodeKey:
        return (item.sourceId, item.graphRevision or item.graphId or "", item.nodeId)

    def _node_key(self, node: FlowGraphNode) -> FlowNodeKey:
        return (node.source_id, node.graph_revision or node.graph_id, node.node_id)

    def _edge_key(self, edge: FlowGraphEdge) -> FlowEdgeKey:
        return (edge.source_id, edge.graph_revision or edge.graph_id, edge.edge_id)

    def _from_key(self, edge: FlowGraphEdge) -> FlowNodeKey:
        return (edge.source_id, edge.graph_revision or edge.graph_id, edge.from_node_id)

    def _to_key(self, edge: FlowGraphEdge) -> FlowNodeKey | None:
        if not edge.to_node_id:
            return None
        return (edge.source_id, edge.graph_revision or edge.graph_id, edge.to_node_id)

    def _is_resolved_internal(self, edge: FlowGraphEdge) -> bool:
        return bool(edge.to_node_id) and not edge.external and str(edge.resolution_status or "").upper() == "RESOLVED"

    def _find_node_by_id(self, nodes: dict[FlowNodeKey, FlowGraphNode], key: FlowNodeKey) -> FlowGraphNode | None:
        for node_key, node in nodes.items():
            if node_key[0] == key[0] and node_key[2] == key[2]:
                expected_revision = key[1]
                if not expected_revision or expected_revision in {node.graph_id, node.graph_revision or ""}:
                    return node
        return None
