from __future__ import annotations

import time
from collections import defaultdict
from dataclasses import dataclass, replace
from enum import Enum
from typing import TYPE_CHECKING, Sequence, Tuple

from knowledge_service.knowledge_query_schema import (
    KnowledgeQueryDiagnostic,
    KnowledgeQueryFlowPath,
    KnowledgeQueryMatchedNode,
)

if TYPE_CHECKING:
    from knowledge_service.knowledge_query_service import KnowledgeQueryPolicy


FlowNodeKey = Tuple[str, str, str]
FlowEdgeKey = Tuple[str, str, str]


@dataclass(frozen=True)
class FlowGraphSourceScope:
    source_id: str
    graph_id: str
    graph_revision: str | None = None
    node_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class FlowGraphNode:
    source_id: str
    graph_id: str
    graph_revision: str | None
    node_id: str
    stable_key: str
    node_kind: str
    label: str
    qualified_name: str | None = None
    relative_path: str | None = None
    line_start: int | None = None
    line_end: int | None = None
    summary: str | None = None
    entrypoint: bool = False


@dataclass(frozen=True)
class FlowGraphEdge:
    source_id: str
    graph_id: str
    graph_revision: str | None
    edge_id: str
    edge_type: str
    from_node_id: str
    to_node_id: str | None
    resolution_status: str
    external: bool = False
    unresolved_target: dict[str, object] | None = None
    evidence_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class FlowGraphEvidence:
    source_id: str
    graph_id: str
    graph_revision: str | None
    evidence_id: str
    node_id: str | None
    edge_id: str | None
    relative_path: str | None
    line_start: int | None
    line_end: int | None
    text: str | None


@dataclass(frozen=True)
class FlowGraphBundle:
    nodes: tuple[FlowGraphNode, ...] = ()
    edges: tuple[FlowGraphEdge, ...] = ()
    evidence: tuple[FlowGraphEvidence, ...] = ()
    truncated: bool = False


class FlowStopReason(str, Enum):
    TERMINAL_NODE = "TERMINAL_NODE"
    ENTRYPOINT_REACHED = "ENTRYPOINT_REACHED"
    INFERRED_ROOT = "INFERRED_ROOT"
    EXTERNAL_BOUNDARY = "EXTERNAL_BOUNDARY"
    UNRESOLVED_BOUNDARY = "UNRESOLVED_BOUNDARY"
    CYCLE_DETECTED = "CYCLE_DETECTED"
    RESULT_LIMIT_REACHED = "RESULT_LIMIT_REACHED"
    GRAPH_TRUNCATED = "GRAPH_TRUNCATED"


@dataclass(frozen=True)
class FlowUnitKey:
    source_id: str
    graph_id: str
    node_ids: tuple[str, ...]
    edge_ids: tuple[str, ...]
    boundary_edge_ids: tuple[str, ...]
    stop_reason: str


@dataclass(frozen=True)
class FlowUnitOrigin:
    matched_node_id: str
    source_id: str
    graph_id: str
    score: float
    match_reasons: tuple[str, ...]


@dataclass(frozen=True)
class FlowUnit:
    key: FlowUnitKey
    origins: tuple[FlowUnitOrigin, ...]
    node_ids: tuple[str, ...]
    edge_ids: tuple[str, ...]
    boundary_edge_ids: tuple[str, ...]
    nodes: tuple[FlowGraphNode, ...]
    edges: tuple[FlowGraphEdge, ...]
    boundary_edges: tuple[FlowGraphEdge, ...]
    evidence: tuple[FlowGraphEvidence, ...]
    complete: bool
    stop_reason: FlowStopReason
    root_stop_reason: FlowStopReason
    root_node_id: str | None
    seed_node_ids: tuple[str, ...]
    score: float


@dataclass(frozen=True)
class FlowBuildResult:
    flow_paths: list[KnowledgeQueryFlowPath]
    diagnostics: list[KnowledgeQueryDiagnostic]
    truncated: bool
    flow_units: tuple[FlowUnit, ...] = ()
    exact_duplicate_merge_count: int = 0


@dataclass(frozen=True)
class _TraversalPath:
    node_keys: tuple[FlowNodeKey, ...]
    edge_keys: tuple[FlowEdgeKey, ...]
    boundary_edge_keys: tuple[FlowEdgeKey, ...]
    stop_reason: FlowStopReason
    complete: bool
    upstream_stop_reason: FlowStopReason


@dataclass(frozen=True)
class _BuiltFlowPath:
    path: _TraversalPath
    origins: tuple[FlowUnitOrigin, ...]


@dataclass
class _TraversalState:
    policy: "KnowledgeQueryPolicy"
    started_at: float
    expanded: int = 0
    truncated: bool = False
    cycle_detected: bool = False

    def allow_step(self) -> bool:
        elapsed_ms = (time.monotonic() - self.started_at) * 1000
        if elapsed_ms > int(getattr(self.policy, "max_execution_ms", 250) or 250):
            self.truncated = True
            return False
        if self.expanded >= int(getattr(self.policy, "max_traversal_nodes", 80) or 80):
            self.truncated = True
            return False
        self.expanded += 1
        return True

    def mark_truncated(self) -> None:
        self.truncated = True

    def mark_cycle(self) -> None:
        self.cycle_detected = True


@dataclass(frozen=True)
class _FlowAdjacency:
    nodes_by_key: dict[FlowNodeKey, FlowGraphNode]
    edges_by_key: dict[FlowEdgeKey, FlowGraphEdge]
    incoming: dict[FlowNodeKey, list[FlowEdgeKey]]
    outgoing: dict[FlowNodeKey, list[FlowEdgeKey]]
    evidence: tuple[FlowGraphEvidence, ...]
    evidence_by_id: dict[str, FlowGraphEvidence]


class FlowBuilder:
    def build(
        self,
        bundle: FlowGraphBundle,
        flow_seed_nodes: Sequence[KnowledgeQueryMatchedNode],
        entrypoint_candidate_node_ids: set[FlowNodeKey],
        policy: "KnowledgeQueryPolicy",
    ) -> FlowBuildResult:
        callable_seeds = [seed for seed in flow_seed_nodes if self._node_kind(seed.nodeKind) == "CALLABLE"]
        if not callable_seeds:
            return FlowBuildResult(
                flow_paths=[],
                diagnostics=[
                    KnowledgeQueryDiagnostic(
                        code="FLOW_BUILDER_NO_FLOW_SEEDS",
                        message="FlowBuilder did not receive callable flow seeds.",
                        severity="INFO",
                    )
                ],
                truncated=False,
            )

        adjacency = self._build_adjacency(bundle)
        state = _TraversalState(policy=policy, started_at=time.monotonic())
        flow_units_by_key: dict[FlowUnitKey, FlowUnit] = {}
        exact_duplicate_merge_count = 0
        max_paths = max(1, int(getattr(policy, "max_flow_paths", 25) or 25))

        for seed in self._ordered_seeds(callable_seeds):
            seed_key = self._seed_key(seed, adjacency.nodes_by_key)
            if seed_key is None:
                continue
            origin = self._origin(seed, seed_key)
            upstream_paths = self._upstream_paths(seed_key, adjacency, entrypoint_candidate_node_ids, state, policy, max_paths)
            for upstream_path in upstream_paths:
                if not upstream_path.complete:
                    exact_duplicate_merge_count += self._remember_flow_unit(
                        flow_units_by_key,
                        self._flow_unit(_BuiltFlowPath(upstream_path, (origin,)), adjacency, policy),
                    )
                    continue
                downstream_paths = self._downstream_paths(seed_key, upstream_path, adjacency, state, policy, max_paths)
                for downstream_path in downstream_paths:
                    combined = self._combine_paths(upstream_path, downstream_path)
                    if not combined.edge_keys and not combined.boundary_edge_keys:
                        continue
                    exact_duplicate_merge_count += self._remember_flow_unit(
                        flow_units_by_key,
                        self._flow_unit(_BuiltFlowPath(combined, (origin,)), adjacency, policy),
                    )

        all_flow_units = sorted(flow_units_by_key.values(), key=self._flow_unit_sort_key)
        if len(all_flow_units) > max_paths:
            state.mark_truncated()
        flow_units = tuple(all_flow_units[:max_paths])
        flow_paths = [self._public_flow_path(index, item) for index, item in enumerate(flow_units, start=1)]
        diagnostics = self._diagnostics(flow_paths, bundle, state, policy)
        return FlowBuildResult(
            flow_paths=flow_paths,
            diagnostics=diagnostics,
            truncated=bundle.truncated or state.truncated,
            flow_units=flow_units,
            exact_duplicate_merge_count=exact_duplicate_merge_count,
        )

    def _build_adjacency(self, bundle: FlowGraphBundle) -> _FlowAdjacency:
        nodes_by_key = {self._node_key(node): node for node in bundle.nodes}
        evidence_by_id = {item.evidence_id: item for item in bundle.evidence if item.evidence_id}
        edges_by_key: dict[FlowEdgeKey, FlowGraphEdge] = {}
        incoming: dict[FlowNodeKey, list[FlowEdgeKey]] = defaultdict(list)
        outgoing: dict[FlowNodeKey, list[FlowEdgeKey]] = defaultdict(list)

        for edge in bundle.edges:
            if self._edge_type(edge) != "CALLS":
                continue
            from_key = self._edge_from_key(edge)
            if from_key not in nodes_by_key:
                continue
            edge_key = self._edge_key(edge)
            edges_by_key[edge_key] = edge
            outgoing[from_key].append(edge_key)
            target_key = self._resolved_target_key(edge, nodes_by_key)
            if target_key is not None:
                incoming[target_key].append(edge_key)

        def sort_key(edge_key: FlowEdgeKey) -> tuple[int, int, str, str, str]:
            edge = edges_by_key[edge_key]
            evidence_order = self._edge_evidence_order(edge, evidence_by_id)
            return (evidence_order[0], evidence_order[1], edge.edge_id, edge.from_node_id, edge.to_node_id or "")

        for values in incoming.values():
            values.sort(key=sort_key)
        for values in outgoing.values():
            values.sort(key=sort_key)
        return _FlowAdjacency(
            nodes_by_key=nodes_by_key,
            edges_by_key=edges_by_key,
            incoming=dict(incoming),
            outgoing=dict(outgoing),
            evidence=tuple(bundle.evidence),
            evidence_by_id=evidence_by_id,
        )

    def _upstream_paths(
        self,
        seed_key: FlowNodeKey,
        adjacency: _FlowAdjacency,
        entrypoint_candidate_node_ids: set[FlowNodeKey],
        state: _TraversalState,
        policy: "KnowledgeQueryPolicy",
        max_paths: int,
    ) -> list[_TraversalPath]:
        results: list[_TraversalPath] = []
        max_depth = max(0, int(getattr(policy, "max_flow_upstream_depth", 8) or 8))

        def visit(
            current_key: FlowNodeKey,
            node_keys_reversed: tuple[FlowNodeKey, ...],
            edge_keys_reversed: tuple[FlowEdgeKey, ...],
            visited: set[FlowNodeKey],
            depth: int,
        ) -> None:
            if len(results) >= max_paths:
                state.mark_truncated()
                return
            if not state.allow_step():
                results.append(
                    _TraversalPath(
                        node_keys=node_keys_reversed,
                        edge_keys=edge_keys_reversed,
                        boundary_edge_keys=(),
                        stop_reason=FlowStopReason.RESULT_LIMIT_REACHED,
                        complete=False,
                        upstream_stop_reason=FlowStopReason.RESULT_LIMIT_REACHED,
                    )
                )
                return
            if self._is_entrypoint(current_key, adjacency, entrypoint_candidate_node_ids):
                results.append(
                    _TraversalPath(
                        node_keys=node_keys_reversed,
                        edge_keys=edge_keys_reversed,
                        boundary_edge_keys=(),
                        stop_reason=FlowStopReason.ENTRYPOINT_REACHED,
                        complete=True,
                        upstream_stop_reason=FlowStopReason.ENTRYPOINT_REACHED,
                    )
                )
                return
            if depth >= max_depth:
                state.mark_truncated()
                results.append(
                    _TraversalPath(
                        node_keys=node_keys_reversed,
                        edge_keys=edge_keys_reversed,
                        boundary_edge_keys=(),
                        stop_reason=FlowStopReason.RESULT_LIMIT_REACHED,
                        complete=False,
                        upstream_stop_reason=FlowStopReason.RESULT_LIMIT_REACHED,
                    )
                )
                return
            incoming_edges = self._limited_edges(adjacency.incoming.get(current_key, []), policy, state)
            if not incoming_edges:
                results.append(
                    _TraversalPath(
                        node_keys=node_keys_reversed,
                        edge_keys=edge_keys_reversed,
                        boundary_edge_keys=(),
                        stop_reason=FlowStopReason.INFERRED_ROOT,
                        complete=True,
                        upstream_stop_reason=FlowStopReason.INFERRED_ROOT,
                    )
                )
                return
            for edge_key in incoming_edges:
                edge = adjacency.edges_by_key[edge_key]
                source_key = self._edge_from_key(edge)
                if source_key in visited:
                    state.mark_cycle()
                    results.append(
                        _TraversalPath(
                            node_keys=(source_key, *node_keys_reversed),
                            edge_keys=(edge_key, *edge_keys_reversed),
                            boundary_edge_keys=(),
                            stop_reason=FlowStopReason.CYCLE_DETECTED,
                            complete=False,
                            upstream_stop_reason=FlowStopReason.CYCLE_DETECTED,
                        )
                    )
                    continue
                visit(source_key, (source_key, *node_keys_reversed), (edge_key, *edge_keys_reversed), {*visited, source_key}, depth + 1)

        visit(seed_key, (seed_key,), (), {seed_key}, 0)
        return sorted(results, key=self._upstream_sort_key)

    def _downstream_paths(
        self,
        seed_key: FlowNodeKey,
        upstream_path: _TraversalPath,
        adjacency: _FlowAdjacency,
        state: _TraversalState,
        policy: "KnowledgeQueryPolicy",
        max_paths: int,
    ) -> list[_TraversalPath]:
        results: list[_TraversalPath] = []
        max_depth = max(0, int(getattr(policy, "max_flow_downstream_depth", 12) or 12))

        def visit(
            current_key: FlowNodeKey,
            node_keys: tuple[FlowNodeKey, ...],
            edge_keys: tuple[FlowEdgeKey, ...],
            visited: set[FlowNodeKey],
            depth: int,
        ) -> None:
            if len(results) >= max_paths:
                state.mark_truncated()
                return
            if not state.allow_step():
                results.append(
                    _TraversalPath(
                        node_keys=node_keys,
                        edge_keys=edge_keys,
                        boundary_edge_keys=(),
                        stop_reason=FlowStopReason.RESULT_LIMIT_REACHED,
                        complete=False,
                        upstream_stop_reason=upstream_path.upstream_stop_reason,
                    )
                )
                return
            if depth >= max_depth:
                state.mark_truncated()
                results.append(
                    _TraversalPath(
                        node_keys=node_keys,
                        edge_keys=edge_keys,
                        boundary_edge_keys=(),
                        stop_reason=FlowStopReason.RESULT_LIMIT_REACHED,
                        complete=False,
                        upstream_stop_reason=upstream_path.upstream_stop_reason,
                    )
                )
                return
            outgoing_edges = self._limited_edges(adjacency.outgoing.get(current_key, []), policy, state)
            if not outgoing_edges:
                results.append(
                    _TraversalPath(
                        node_keys=node_keys,
                        edge_keys=edge_keys,
                        boundary_edge_keys=(),
                        stop_reason=FlowStopReason.TERMINAL_NODE,
                        complete=True,
                        upstream_stop_reason=upstream_path.upstream_stop_reason,
                    )
                )
                return
            for edge_key in outgoing_edges:
                edge = adjacency.edges_by_key[edge_key]
                boundary_reason = self._boundary_reason(edge, adjacency.nodes_by_key)
                if boundary_reason is not None:
                    results.append(
                        _TraversalPath(
                            node_keys=node_keys,
                            edge_keys=edge_keys,
                            boundary_edge_keys=(edge_key,),
                            stop_reason=boundary_reason,
                            complete=False,
                            upstream_stop_reason=upstream_path.upstream_stop_reason,
                        )
                    )
                    continue
                target_key = self._edge_to_key(edge)
                if target_key in visited:
                    state.mark_cycle()
                    results.append(
                        _TraversalPath(
                            node_keys=(*node_keys, target_key),
                            edge_keys=(*edge_keys, edge_key),
                            boundary_edge_keys=(),
                            stop_reason=FlowStopReason.CYCLE_DETECTED,
                            complete=False,
                            upstream_stop_reason=upstream_path.upstream_stop_reason,
                        )
                    )
                    continue
                visit(target_key, (*node_keys, target_key), (*edge_keys, edge_key), {*visited, target_key}, depth + 1)

        visit(seed_key, (seed_key,), (), set(upstream_path.node_keys), 0)
        return sorted(results, key=self._downstream_sort_key)

    def _remember_flow_unit(
        self,
        flow_units_by_key: dict[FlowUnitKey, FlowUnit],
        flow_unit: FlowUnit,
    ) -> int:
        if not flow_unit.edge_ids and not flow_unit.boundary_edge_ids:
            return 0
        existing = flow_units_by_key.get(flow_unit.key)
        if existing is not None:
            flow_units_by_key[flow_unit.key] = self._merge_flow_units(existing, flow_unit)
            return 1
        flow_units_by_key[flow_unit.key] = flow_unit
        return 0

    def _combine_paths(self, upstream_path: _TraversalPath, downstream_path: _TraversalPath) -> _TraversalPath:
        return _TraversalPath(
            node_keys=(*upstream_path.node_keys, *downstream_path.node_keys[1:]),
            edge_keys=(*upstream_path.edge_keys, *downstream_path.edge_keys),
            boundary_edge_keys=(*upstream_path.boundary_edge_keys, *downstream_path.boundary_edge_keys),
            stop_reason=downstream_path.stop_reason,
            complete=upstream_path.complete and downstream_path.complete,
            upstream_stop_reason=upstream_path.upstream_stop_reason,
        )

    def _flow_unit(
        self,
        built_path: _BuiltFlowPath,
        adjacency: _FlowAdjacency,
        policy: "KnowledgeQueryPolicy",
    ) -> FlowUnit:
        path = built_path.path
        source_id = path.node_keys[0][0] if path.node_keys else ""
        graph_id = path.node_keys[0][1] if path.node_keys else ""
        node_ids = tuple(node_key[2] for node_key in path.node_keys)
        edge_ids = tuple(edge_key[2] for edge_key in path.edge_keys)
        boundary_edge_ids = tuple(edge_key[2] for edge_key in path.boundary_edge_keys)
        origins = self._merge_origins(built_path.origins)
        evidence = tuple(self._copy_evidence(item) for item in self._path_evidence(adjacency, path, policy))
        key = FlowUnitKey(
            source_id=source_id,
            graph_id=graph_id,
            node_ids=node_ids,
            edge_ids=edge_ids,
            boundary_edge_ids=boundary_edge_ids,
            stop_reason=path.stop_reason.value,
        )
        return FlowUnit(
            key=key,
            origins=origins,
            node_ids=node_ids,
            edge_ids=edge_ids,
            boundary_edge_ids=boundary_edge_ids,
            nodes=tuple(self._copy_node(adjacency.nodes_by_key[node_key]) for node_key in path.node_keys if node_key in adjacency.nodes_by_key),
            edges=tuple(self._copy_edge(adjacency.edges_by_key[edge_key]) for edge_key in path.edge_keys if edge_key in adjacency.edges_by_key),
            boundary_edges=tuple(
                self._copy_edge(adjacency.edges_by_key[edge_key]) for edge_key in path.boundary_edge_keys if edge_key in adjacency.edges_by_key
            ),
            evidence=evidence,
            complete=path.complete,
            stop_reason=path.stop_reason,
            root_stop_reason=path.upstream_stop_reason,
            root_node_id=node_ids[0] if node_ids else None,
            seed_node_ids=tuple(origin.matched_node_id for origin in origins),
            score=max((origin.score for origin in origins), default=0.0),
        )

    def _merge_flow_units(self, existing: FlowUnit, incoming: FlowUnit) -> FlowUnit:
        origins = self._merge_origins((*existing.origins, *incoming.origins))
        return replace(
            existing,
            origins=origins,
            seed_node_ids=tuple(origin.matched_node_id for origin in origins),
            score=max((origin.score for origin in origins), default=0.0),
        )

    def _merge_origins(self, origins: Sequence[FlowUnitOrigin]) -> tuple[FlowUnitOrigin, ...]:
        merged: dict[tuple[str, str, str], FlowUnitOrigin] = {}
        for origin in origins:
            key = (origin.source_id, origin.graph_id, origin.matched_node_id)
            existing = merged.get(key)
            if existing is None:
                merged[key] = FlowUnitOrigin(
                    matched_node_id=origin.matched_node_id,
                    source_id=origin.source_id,
                    graph_id=origin.graph_id,
                    score=origin.score,
                    match_reasons=tuple(sorted(set(origin.match_reasons))),
                )
                continue
            merged[key] = FlowUnitOrigin(
                matched_node_id=origin.matched_node_id,
                source_id=origin.source_id,
                graph_id=origin.graph_id,
                score=max(existing.score, origin.score),
                match_reasons=tuple(sorted({*existing.match_reasons, *origin.match_reasons})),
            )
        return tuple(
            sorted(
                merged.values(),
                key=lambda origin: (
                    -origin.score,
                    origin.source_id,
                    origin.graph_id,
                    origin.matched_node_id,
                ),
            )
        )

    def _public_flow_path(
        self,
        index: int,
        flow_unit: FlowUnit,
    ) -> KnowledgeQueryFlowPath:
        return KnowledgeQueryFlowPath(
            flowId=f"flow-{index}",
            sourceId=flow_unit.key.source_id or None,
            matchedNodeIds=list(flow_unit.seed_node_ids),
            nodeIds=list(flow_unit.node_ids),
            edgeIds=list(flow_unit.edge_ids),
            boundaryEdgeIds=list(flow_unit.boundary_edge_ids),
            evidenceIds=[item.evidence_id for item in flow_unit.evidence],
            nodes=[self._public_node(node) for node in flow_unit.nodes],
            edges=[self._public_edge(edge) for edge in flow_unit.edges],
            evidence=[self._public_evidence(item) for item in flow_unit.evidence],
            complete=flow_unit.complete,
            stopReason=flow_unit.stop_reason.value,
        )

    def _path_evidence(
        self,
        adjacency: _FlowAdjacency,
        path: _TraversalPath,
        policy: "KnowledgeQueryPolicy",
    ) -> list[FlowGraphEvidence]:
        max_refs = max(0, int(getattr(policy, "max_evidence_refs", 25) or 25))
        node_ids = {node_key[2] for node_key in path.node_keys}
        edge_ids = {edge_key[2] for edge_key in (*path.edge_keys, *path.boundary_edge_keys)}
        edge_evidence_ids = {
            evidence_id
            for edge_key in (*path.edge_keys, *path.boundary_edge_keys)
            for edge in [adjacency.edges_by_key.get(edge_key)]
            if edge is not None
            for evidence_id in edge.evidence_ids
        }
        selected: list[FlowGraphEvidence] = []
        seen: set[str] = set()
        source_ids = {node_key[0] for node_key in path.node_keys}
        graph_ids = {node_key[1] for node_key in path.node_keys}
        for item in adjacency.evidence:
            if item.evidence_id in seen:
                continue
            if item.source_id not in source_ids:
                continue
            if item.graph_id and item.graph_id not in graph_ids:
                continue
            linked_by_node = bool(item.node_id and item.node_id in node_ids)
            linked_by_edge = bool(item.edge_id and item.edge_id in edge_ids)
            linked_by_edge_evidence_id = item.evidence_id in edge_evidence_ids
            if not linked_by_node and not linked_by_edge and not linked_by_edge_evidence_id:
                continue
            selected.append(item)
            seen.add(item.evidence_id)
            if len(selected) >= max_refs:
                break
        return selected

    def _diagnostics(
        self,
        flow_paths: Sequence[KnowledgeQueryFlowPath],
        bundle: FlowGraphBundle,
        state: _TraversalState,
        policy: "KnowledgeQueryPolicy",
    ) -> list[KnowledgeQueryDiagnostic]:
        diagnostics: list[KnowledgeQueryDiagnostic] = []
        if not flow_paths:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="NO_CALLS_PATH",
                    message="Callable flow seeds were found, but no verified CALLS path could be built from current graph facts.",
                    severity="INFO",
                )
            )
        if bundle.truncated or state.truncated:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="RESULT_LIMIT_REACHED",
                    message="FlowBuilder reached an internal safety limit.",
                    severity="INFO",
                    metadata={
                        "maxFlowUpstreamDepth": int(getattr(policy, "max_flow_upstream_depth", 8) or 8),
                        "maxFlowDownstreamDepth": int(getattr(policy, "max_flow_downstream_depth", 12) or 12),
                        "maxFlowBranchingPerNode": int(getattr(policy, "max_flow_branching_per_node", 8) or 8),
                        "maxTraversalNodes": int(getattr(policy, "max_traversal_nodes", 80) or 80),
                        "maxFlowPaths": int(getattr(policy, "max_flow_paths", 25) or 25),
                        "maxEdgesPerTraversal": int(getattr(policy, "max_edges_per_traversal", 2000) or 2000),
                        "maxExecutionMs": int(getattr(policy, "max_execution_ms", 250) or 250),
                        "maxEvidenceRefs": int(getattr(policy, "max_evidence_refs", 25) or 25),
                    },
                )
            )
        if state.cycle_detected:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="CYCLE_DETECTED",
                    message="FlowBuilder stopped one or more paths at a CALLS cycle.",
                    severity="INFO",
                )
            )
        return diagnostics

    def _limited_edges(
        self,
        edge_keys: Sequence[FlowEdgeKey],
        policy: "KnowledgeQueryPolicy",
        state: _TraversalState,
    ) -> tuple[FlowEdgeKey, ...]:
        branch_limit = max(1, int(getattr(policy, "max_flow_branching_per_node", 8) or 8))
        ordered = tuple(edge_keys)
        if len(ordered) > branch_limit:
            state.mark_truncated()
        return ordered[:branch_limit]

    def _boundary_reason(
        self,
        edge: FlowGraphEdge,
        nodes_by_key: dict[FlowNodeKey, FlowGraphNode],
    ) -> FlowStopReason | None:
        if self._is_external_edge(edge):
            return FlowStopReason.EXTERNAL_BOUNDARY
        if self._is_unresolved_edge(edge):
            return FlowStopReason.UNRESOLVED_BOUNDARY
        target_key = self._edge_to_key(edge)
        if target_key not in nodes_by_key:
            return FlowStopReason.UNRESOLVED_BOUNDARY
        return None

    def _resolved_target_key(
        self,
        edge: FlowGraphEdge,
        nodes_by_key: dict[FlowNodeKey, FlowGraphNode],
    ) -> FlowNodeKey | None:
        if self._boundary_reason(edge, nodes_by_key) is not None:
            return None
        return self._edge_to_key(edge)

    def _is_entrypoint(
        self,
        node_key: FlowNodeKey,
        adjacency: _FlowAdjacency,
        entrypoint_candidate_node_ids: set[FlowNodeKey],
    ) -> bool:
        node = adjacency.nodes_by_key[node_key]
        return node.entrypoint or node_key in entrypoint_candidate_node_ids

    def _seed_key(
        self,
        seed: KnowledgeQueryMatchedNode,
        nodes_by_key: dict[FlowNodeKey, FlowGraphNode],
    ) -> FlowNodeKey | None:
        graph_id = str(seed.graphId or "")
        exact = (str(seed.sourceId), graph_id, str(seed.nodeId))
        if exact in nodes_by_key:
            return exact
        candidates = sorted(
            key
            for key in nodes_by_key
            if key[0] == seed.sourceId and key[2] == seed.nodeId and (not graph_id or key[1] == graph_id)
        )
        return candidates[0] if candidates else None

    def _ordered_seeds(self, seeds: Sequence[KnowledgeQueryMatchedNode]) -> list[KnowledgeQueryMatchedNode]:
        return sorted(
            seeds,
            key=lambda seed: (
                -float(seed.score),
                str(seed.sourceId),
                str(seed.graphId or ""),
                str(seed.nodeId),
            ),
        )

    def _origin(self, seed: KnowledgeQueryMatchedNode, seed_key: FlowNodeKey) -> FlowUnitOrigin:
        return FlowUnitOrigin(
            matched_node_id=str(seed.nodeId),
            source_id=seed_key[0],
            graph_id=seed_key[1],
            score=float(seed.score),
            match_reasons=tuple(sorted(str(reason) for reason in seed.matchReasons)),
        )

    def _upstream_sort_key(self, path: _TraversalPath) -> tuple[int, int, tuple[str, ...], tuple[str, ...]]:
        entry_rank = 0 if path.upstream_stop_reason == FlowStopReason.ENTRYPOINT_REACHED else 1
        return (entry_rank, len(path.edge_keys), tuple(edge_key[2] for edge_key in path.edge_keys), tuple(node_key[2] for node_key in path.node_keys))

    def _downstream_sort_key(self, path: _TraversalPath) -> tuple[tuple[str, ...], tuple[str, ...], str]:
        return (
            tuple(edge_key[2] for edge_key in path.edge_keys),
            tuple(edge_key[2] for edge_key in path.boundary_edge_keys),
            path.stop_reason.value,
        )

    def _flow_unit_sort_key(
        self,
        flow_unit: FlowUnit,
    ) -> tuple[int, int, float, int, int, int, tuple[tuple[int, int, str], ...], str, str, tuple[str, ...], tuple[str, ...], tuple[str, ...], str]:
        root_rank = self._root_rank(flow_unit.root_stop_reason)
        stop_rank = self._stop_rank(flow_unit)
        return (
            root_rank,
            stop_rank,
            -flow_unit.score,
            -self._origin_reason_count(flow_unit),
            -(len(flow_unit.edge_ids) + len(flow_unit.boundary_edge_ids)),
            -len(flow_unit.evidence),
            self._unit_callsite_order(flow_unit),
            flow_unit.key.source_id,
            flow_unit.key.graph_id,
            flow_unit.key.node_ids,
            flow_unit.key.edge_ids,
            flow_unit.key.boundary_edge_ids,
            flow_unit.key.stop_reason,
        )

    def _root_rank(self, stop_reason: FlowStopReason) -> int:
        if stop_reason == FlowStopReason.ENTRYPOINT_REACHED:
            return 0
        if stop_reason == FlowStopReason.INFERRED_ROOT:
            return 1
        if stop_reason == FlowStopReason.TERMINAL_NODE:
            return 2
        return 3

    def _stop_rank(self, flow_unit: FlowUnit) -> int:
        if flow_unit.complete:
            return 0
        if flow_unit.stop_reason in {FlowStopReason.EXTERNAL_BOUNDARY, FlowStopReason.UNRESOLVED_BOUNDARY}:
            return 1
        if flow_unit.stop_reason == FlowStopReason.CYCLE_DETECTED:
            return 2
        if flow_unit.stop_reason in {FlowStopReason.RESULT_LIMIT_REACHED, FlowStopReason.GRAPH_TRUNCATED}:
            return 3
        return 2

    def _origin_reason_count(self, flow_unit: FlowUnit) -> int:
        return len({reason for origin in flow_unit.origins for reason in origin.match_reasons})

    def _unit_callsite_order(self, flow_unit: FlowUnit) -> tuple[tuple[int, int, str], ...]:
        evidence_by_id = {item.evidence_id: item for item in flow_unit.evidence if item.evidence_id}
        return tuple(
            (*self._edge_evidence_order(edge, evidence_by_id), edge.edge_id)
            for edge in (*flow_unit.edges, *flow_unit.boundary_edges)
        )

    def _copy_node(self, node: FlowGraphNode) -> FlowGraphNode:
        return replace(node)

    def _copy_edge(self, edge: FlowGraphEdge) -> FlowGraphEdge:
        return replace(
            edge,
            unresolved_target=dict(edge.unresolved_target) if edge.unresolved_target is not None else None,
            evidence_ids=tuple(edge.evidence_ids),
        )

    def _copy_evidence(self, evidence: FlowGraphEvidence) -> FlowGraphEvidence:
        return replace(evidence)

    def _node_key(self, node: FlowGraphNode) -> FlowNodeKey:
        return (node.source_id, node.graph_id, node.node_id)

    def _edge_key(self, edge: FlowGraphEdge) -> FlowEdgeKey:
        return (edge.source_id, edge.graph_id, edge.edge_id)

    def _edge_from_key(self, edge: FlowGraphEdge) -> FlowNodeKey:
        return (edge.source_id, edge.graph_id, edge.from_node_id)

    def _edge_to_key(self, edge: FlowGraphEdge) -> FlowNodeKey:
        return (edge.source_id, edge.graph_id, str(edge.to_node_id or ""))

    def _edge_type(self, edge: FlowGraphEdge) -> str:
        return str(edge.edge_type or "").upper()

    def _node_kind(self, value: str) -> str:
        return str(value or "").upper()

    def _is_external_edge(self, edge: FlowGraphEdge) -> bool:
        return edge.external or str(edge.resolution_status or "").upper() == "EXTERNAL_TARGET"

    def _is_unresolved_edge(self, edge: FlowGraphEdge) -> bool:
        status = str(edge.resolution_status or "").upper()
        if status in {"UNRESOLVED", "DYNAMIC_TARGET", "MULTIPLE_CANDIDATES", "INTERFACE_TARGET", "AMBIGUOUS"}:
            return True
        return edge.to_node_id is None and not self._is_external_edge(edge)

    def _edge_evidence_order(
        self,
        edge: FlowGraphEdge,
        evidence_by_id: dict[str, FlowGraphEvidence],
    ) -> tuple[int, int]:
        line_numbers: list[int] = []
        for evidence_id in edge.evidence_ids:
            evidence = evidence_by_id.get(evidence_id)
            if evidence is not None and evidence.line_start is not None:
                line_numbers.append(evidence.line_start)
        if not line_numbers:
            return (1, 0)
        return (0, min(line_numbers))

    def _public_node(self, node: FlowGraphNode) -> dict[str, object | None]:
        return {
            "id": node.node_id,
            "sourceId": node.source_id,
            "graphId": node.graph_id,
            "graphRevision": node.graph_revision,
            "stableKey": node.stable_key,
            "nodeKind": node.node_kind,
            "label": node.label,
            "qualifiedName": node.qualified_name,
            "relativePath": node.relative_path,
            "lineStart": node.line_start,
            "lineEnd": node.line_end,
            "summary": node.summary,
            "entrypoint": node.entrypoint,
        }

    def _public_edge(self, edge: FlowGraphEdge) -> dict[str, object | None]:
        return {
            "id": edge.edge_id,
            "sourceId": edge.source_id,
            "graphId": edge.graph_id,
            "graphRevision": edge.graph_revision,
            "edgeType": edge.edge_type,
            "fromNodeId": edge.from_node_id,
            "toNodeId": edge.to_node_id,
            "resolutionStatus": edge.resolution_status,
            "external": edge.external,
            "unresolvedTarget": edge.unresolved_target,
            "evidenceIds": list(edge.evidence_ids),
        }

    def _public_evidence(self, evidence: FlowGraphEvidence) -> dict[str, object | None]:
        return {
            "id": evidence.evidence_id,
            "sourceId": evidence.source_id,
            "graphId": evidence.graph_id,
            "graphRevision": evidence.graph_revision,
            "nodeId": evidence.node_id,
            "edgeId": evidence.edge_id,
            "relativePath": evidence.relative_path,
            "lineStart": evidence.line_start,
            "lineEnd": evidence.line_end,
            "excerpt": evidence.text,
        }


def flow_graph_bundle_to_public_bundle(bundle: FlowGraphBundle) -> dict[str, object]:
    builder = FlowBuilder()
    nodes = [builder._public_node(node) for node in bundle.nodes]
    edges = [builder._public_edge(edge) for edge in bundle.edges]
    evidence = [builder._public_evidence(item) for item in bundle.evidence]
    unresolved = [builder._public_edge(edge) for edge in bundle.edges if builder._is_unresolved_edge(edge) or builder._is_external_edge(edge)]
    external = [builder._public_edge(edge) for edge in bundle.edges if builder._is_external_edge(edge)]
    return {
        "nodes": nodes,
        "edges": edges,
        "evidence": evidence,
        "unresolved": unresolved,
        "external": external,
        "verifiedPaths": _verified_paths_from_flow_evidence(bundle.evidence),
        "truncated": bundle.truncated,
    }


def _verified_paths_from_flow_evidence(evidence: Sequence[FlowGraphEvidence]) -> list[dict[str, object | None]]:
    paths: list[dict[str, object | None]] = []
    seen: set[tuple[str, str]] = set()
    for item in evidence:
        if not item.relative_path:
            continue
        key = (item.source_id, item.relative_path)
        if key in seen:
            continue
        seen.add(key)
        paths.append(
            {
                "sourceId": item.source_id,
                "relativePath": item.relative_path,
                "lineStart": item.line_start,
                "lineEnd": item.line_end,
            }
        )
    return paths
