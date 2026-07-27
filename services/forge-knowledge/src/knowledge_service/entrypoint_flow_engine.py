from __future__ import annotations

import hashlib
import json
import time
from collections import defaultdict
from dataclasses import dataclass, field, replace
from enum import Enum
from typing import Any, Mapping, Protocol, Sequence

from knowledge_service.boundary_contract import LocalBoundaryDescriptor as LocalBoundaryDescriptor
from knowledge_service.boundary_contract import LocalBoundaryFact as LocalBoundaryFact
from knowledge_service.flow_boundary_classifier import FLOW_BOUNDARY_CLASSIFIER, FlowBoundaryClassifier
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
from knowledge_service.graph_relation_semantics import GraphRelationSemantics, graph_relation_semantics
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

_MAX_FRONTIER_ROUNDS = 256
_MAX_UNIT_NODES = 1500
_MAX_UNIT_TRANSITIONS = 3000
_MAX_UNIT_BOUNDARIES = 1000


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
    original_stable_key: str | None = None
    original_node_kind: str | None = None
    expanded_seed_node_id: str | None = None
    expanded_seed_stable_key: str | None = None
    anchor_to_seed_reasons: tuple[str, ...] = ()
    query_provenance: tuple[str, ...] = ()


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
class EntrypointFlowSeedProvenance:
    original_anchor: KnowledgeQueryMatchedNode
    expanded_seed: KnowledgeQueryMatchedNode
    anchor_to_seed_reasons: tuple[str, ...] = ()


@dataclass(frozen=True)
class LocalFlowRoot:
    node: FlowGraphNode
    origin: EntrypointFlowOrigin
    distance_to_nearest_seed: int


@dataclass(frozen=True)
class LocalFlowAnchorProvenance:
    original_anchor: KnowledgeQueryMatchedNode
    expanded_seed: FlowGraphNode
    anchor_to_seed_reasons: tuple[str, ...]
    query_provenance: tuple[str, ...]
    distance_to_nearest_root: int


@dataclass(frozen=True)
class LocalFlowCoverage:
    node_count: int
    transition_count: int
    generic_boundary_count: int
    topology_boundary_count: int
    anchor_count: int
    root_count: int
    max_depth_reached: int
    cycle_detected: bool = False
    truncated: bool = False


@dataclass(frozen=True)
class LocalFlowUnit:
    unit_id: str
    source_id: str
    graph_revision: str
    roots: tuple[LocalFlowRoot, ...]
    anchors: tuple[LocalFlowAnchorProvenance, ...]
    execution_nodes: tuple[FlowGraphNode, ...]
    execution_transitions: tuple[FlowGraphEdge, ...]
    generic_boundaries: tuple[LocalBoundaryFact, ...]
    topology_boundaries: tuple[FlowGraphEdge, ...]
    supporting_context: tuple[FlowGraphNode, ...]
    evidence: tuple[FlowGraphEvidence, ...]
    complete: bool
    coverage: LocalFlowCoverage
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...]


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
    local_unit_id: str | None = None
    root_nodes: tuple[FlowGraphNode, ...] = ()
    generic_boundaries: tuple[LocalBoundaryFact, ...] = ()


@dataclass(frozen=True)
class EntrypointFlowBuildResult:
    flows: tuple[EntrypointFlow, ...]
    public_flows: list[KnowledgeQueryFlow]
    diagnostics: list[KnowledgeQueryDiagnostic]
    truncated: bool
    discovered_entrypoint_count: int = 0
    stage_timings_ms: dict[str, float] | None = None
    traversal_stats: dict[str, int] | None = None
    local_units: tuple[LocalFlowUnit, ...] = ()


class EntrypointFlowGraphRepository(Protocol):
    def load_nodes(self, node_keys: set[FlowNodeKey], *, include_tests: bool) -> dict[FlowNodeKey, FlowGraphNode]: ...

    def load_incoming_calls(
        self,
        target_keys: set[FlowNodeKey],
        *,
        include_tests: bool,
    ) -> dict[FlowNodeKey, tuple[FlowGraphEdge, ...]]: ...

    def load_outgoing_calls(
        self,
        source_keys: set[FlowNodeKey],
        *,
        include_tests: bool,
    ) -> dict[FlowNodeKey, tuple[FlowGraphEdge, ...]]: ...

    def load_boundaries(
        self,
        node_keys: set[FlowNodeKey],
        *,
        include_tests: bool,
    ) -> dict[FlowNodeKey, tuple[LocalBoundaryFact, ...]]: ...

    def hydrate_evidence(self, flows: Sequence[EntrypointFlow]) -> tuple[EntrypointFlow, ...]: ...

    def load_supporting_relations(
        self,
        node_keys: set[FlowNodeKey],
        *,
        include_tests: bool,
    ) -> tuple[dict[FlowNodeKey, FlowGraphNode], tuple[FlowGraphEdge, ...]]: ...

    def metrics(self) -> dict[str, int]: ...


@dataclass
class _SeedSpec:
    original_anchor: KnowledgeQueryMatchedNode
    expanded_seed: KnowledgeQueryMatchedNode
    anchor_to_seed_reasons: tuple[str, ...]


@dataclass
class _ExploredSeed:
    seed_key: FlowNodeKey
    seed_node: FlowGraphNode
    anchors: list[LocalFlowAnchorProvenance]
    roots: dict[FlowNodeKey, LocalFlowRoot]
    nodes: dict[FlowNodeKey, FlowGraphNode]
    upstream_transitions: dict[FlowEdgeKey, FlowGraphEdge]
    downstream_transitions: dict[FlowEdgeKey, FlowGraphEdge]
    topology_boundaries: dict[FlowEdgeKey, FlowGraphEdge]
    supporting_context: dict[FlowNodeKey, FlowGraphNode]
    root_distance_by_seed: dict[FlowNodeKey, int]
    diagnostics: list[KnowledgeQueryDiagnostic] = field(default_factory=list)
    reverse_rounds: int = 0
    forward_rounds: int = 0
    cycle_detected: bool = False
    truncated: bool = False
    missing_resolved_target: bool = False


class EntrypointFlowEngine:
    def __init__(
        self,
        repository: EntrypointFlowGraphRepository | None = None,
        boundary_classifier: FlowBoundaryClassifier | None = None,
        semantics: GraphRelationSemantics | None = None,
    ) -> None:
        self.repository = repository
        self.boundary_classifier = boundary_classifier or FLOW_BOUNDARY_CLASSIFIER
        self.semantics = semantics or graph_relation_semantics()

    def build(
        self,
        anchors: Sequence[KnowledgeQueryMatchedNode],
        *,
        max_flows: int,
        include_tests: bool,
        anchor_seed_provenance: Sequence[EntrypointFlowSeedProvenance] = (),
    ) -> EntrypointFlowBuildResult:
        del max_flows
        if self.repository is None:
            raise RuntimeError("EntrypointFlowEngine requires an EntrypointFlowGraphRepository")

        started_at = time.monotonic()
        specs = self._seed_specs(anchors, anchor_seed_provenance)
        if not specs:
            diagnostic = KnowledgeQueryDiagnostic(
                code="LOCAL_FLOW_NO_TRAVERSAL_SEEDS",
                message="No traversal seeds were available for local flow exploration.",
                severity="INFO",
            )
            return EntrypointFlowBuildResult((), [], [diagnostic], False, stage_timings_ms={}, traversal_stats=self.repository.metrics())

        seed_keys = {self._anchor_lookup_key(spec.expanded_seed) for spec in specs}
        original_keys = {self._anchor_lookup_key(spec.original_anchor) for spec in specs}
        load_started = time.monotonic()
        loaded_nodes = self.repository.load_nodes(seed_keys | original_keys, include_tests=include_tests)
        load_ms = (time.monotonic() - load_started) * 1000

        grouped_specs: dict[FlowNodeKey, list[_SeedSpec]] = defaultdict(list)
        diagnostics: list[KnowledgeQueryDiagnostic] = []
        for spec in sorted(specs, key=self._seed_spec_sort_key):
            requested_key = self._anchor_lookup_key(spec.expanded_seed)
            seed_node = self._find_node_by_id(loaded_nodes, requested_key)
            if seed_node is None:
                diagnostics.append(
                    KnowledgeQueryDiagnostic(
                        code="LOCAL_FLOW_SEED_NOT_CURRENT",
                        message="A selected traversal seed was not present in the current graph and was skipped.",
                        severity="WARN",
                        sourceId=spec.expanded_seed.sourceId,
                        metadata={"nodeId": spec.expanded_seed.nodeId},
                    )
                )
                continue
            grouped_specs[self._node_key(seed_node)].append(spec)

        if not grouped_specs:
            diagnostic = KnowledgeQueryDiagnostic(
                code="LOCAL_FLOW_NO_CURRENT_SEEDS",
                message="No selected traversal seeds were current in the source graph.",
                severity="INFO",
            )
            return EntrypointFlowBuildResult(
                (),
                [],
                [*diagnostics, diagnostic],
                False,
                stage_timings_ms={"nodeResolution": round(load_ms, 3)},
                traversal_stats=self.repository.metrics(),
            )

        exploration_started = time.monotonic()
        explored = tuple(self._explore_seed(seed_key, raw_specs, loaded_nodes, include_tests) for seed_key, raw_specs in sorted(grouped_specs.items()))
        exploration_ms = (time.monotonic() - exploration_started) * 1000

        merge_started = time.monotonic()
        local_units = self._merge_seed_regions(explored, include_tests)
        merge_ms = (time.monotonic() - merge_started) * 1000
        flows = self._project_local_units(local_units)

        hydration_started = time.monotonic()
        flows = self.repository.hydrate_evidence(flows)
        hydration_ms = (time.monotonic() - hydration_started) * 1000
        local_units = self._hydrate_local_units_from_flows(local_units, flows)
        public = self.public_flows(flows)

        stats = dict(self.repository.metrics())
        stats.update(
            {
                "reverseFrontierRounds": sum(item.reverse_rounds for item in explored),
                "downstreamFrontierRounds": sum(item.forward_rounds for item in explored),
                "localUnitCount": len(local_units),
                "returnedFlowCount": len(flows),
                "discoveredEntrypointCount": sum(len(unit.roots) for unit in local_units),
            }
        )
        return EntrypointFlowBuildResult(
            flows=flows,
            public_flows=public,
            diagnostics=diagnostics,
            truncated=any(unit.coverage.truncated for unit in local_units),
            discovered_entrypoint_count=sum(len(unit.roots) for unit in local_units),
            stage_timings_ms={
                "nodeResolution": round(load_ms, 3),
                "localFlowExploration": round(exploration_ms, 3),
                "localUnitAssembly": round(merge_ms, 3),
                "evidenceHydration": round(hydration_ms, 3),
                "engineTotal": round((time.monotonic() - started_at) * 1000, 3),
            },
            traversal_stats=stats,
            local_units=local_units,
        )

    def public_flows(self, flows: Sequence[EntrypointFlow]) -> list[KnowledgeQueryFlow]:
        return [self._public_flow(index, flow) for index, flow in enumerate(flows, start=1)]

    def _seed_specs(
        self,
        anchors: Sequence[KnowledgeQueryMatchedNode],
        provenance: Sequence[EntrypointFlowSeedProvenance],
    ) -> tuple[_SeedSpec, ...]:
        specs: list[_SeedSpec] = []
        if provenance:
            for item in provenance:
                specs.append(
                    _SeedSpec(
                        original_anchor=item.original_anchor,
                        expanded_seed=item.expanded_seed,
                        anchor_to_seed_reasons=tuple(sorted(set(item.anchor_to_seed_reasons))),
                    )
                )
            return tuple(sorted(specs, key=self._seed_spec_sort_key))
        for anchor in anchors:
            specs.append(
                _SeedSpec(
                    original_anchor=anchor,
                    expanded_seed=anchor,
                    anchor_to_seed_reasons=("ORIGINAL_MATCH",),
                )
            )
        return tuple(sorted(specs, key=self._seed_spec_sort_key))

    def _explore_seed(
        self,
        seed_key: FlowNodeKey,
        raw_specs: Sequence[_SeedSpec],
        loaded_nodes: Mapping[FlowNodeKey, FlowGraphNode],
        include_tests: bool,
    ) -> _ExploredSeed:
        seed_node = self._find_node_by_id(loaded_nodes, seed_key)
        if seed_node is None:
            raise RuntimeError("Current seed nodes must be resolved before exploration")
        actual_seed_key = self._node_key(seed_node)
        nodes = {actual_seed_key: seed_node}
        supporting_context: dict[FlowNodeKey, FlowGraphNode] = {}
        for spec in raw_specs:
            original_key = self._anchor_lookup_key(spec.original_anchor)
            original_node = self._find_node_by_id(loaded_nodes, original_key)
            if original_node is not None and self._node_key(original_node) != actual_seed_key:
                supporting_context[self._node_key(original_node)] = original_node
        explored = _ExploredSeed(
            seed_key=actual_seed_key,
            seed_node=seed_node,
            anchors=[],
            roots={},
            nodes=nodes,
            upstream_transitions={},
            downstream_transitions={},
            topology_boundaries={},
            supporting_context=supporting_context,
            root_distance_by_seed={},
        )
        self._reverse_explore(explored, include_tests)
        self._forward_explore(explored, include_tests)

        nearest_root_distance = min(explored.root_distance_by_seed.values(), default=0)
        for spec in sorted(raw_specs, key=self._seed_spec_sort_key):
            explored.anchors.append(
                LocalFlowAnchorProvenance(
                    original_anchor=spec.original_anchor,
                    expanded_seed=seed_node,
                    anchor_to_seed_reasons=tuple(sorted(set(spec.anchor_to_seed_reasons))),
                    query_provenance=self._query_provenance(spec.original_anchor),
                    distance_to_nearest_root=nearest_root_distance,
                )
            )
        return explored

    def _reverse_explore(self, state: _ExploredSeed, include_tests: bool) -> None:
        frontier: dict[FlowNodeKey, int] = {state.seed_key: 0}
        visited: set[FlowNodeKey] = {state.seed_key}
        while frontier:
            state.reverse_rounds += 1
            if state.reverse_rounds > _MAX_FRONTIER_ROUNDS:
                self._mark_truncated(state, "LOCAL_FLOW_REVERSE_TRUNCATED", "Reverse local flow exploration reached the internal frontier-round limit.")
                break
            query_frontier = {node_key for node_key in frontier if not self._is_explicit_executable_root(state.nodes.get(node_key))}
            incoming_by_target = self.repository.load_incoming_calls(query_frontier, include_tests=include_tests) if query_frontier else {}
            incoming_source_keys = {
                self._from_key(edge)
                for edges in incoming_by_target.values()
                for edge in edges
                if self._is_local_resolved_execution_edge(edge, state.seed_key[0])
            }
            missing_source_keys = incoming_source_keys - set(state.nodes)
            state.nodes.update(self.repository.load_nodes(missing_source_keys, include_tests=include_tests))

            next_frontier: dict[FlowNodeKey, int] = {}
            for node_key, distance in sorted(frontier.items()):
                node = state.nodes.get(node_key)
                if node is None:
                    continue
                if self._is_explicit_executable_root(node):
                    self._add_root(state, node, EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT, distance)
                    continue
                eligible_edges = [
                    edge
                    for edge in incoming_by_target.get(node_key, ())
                    if self._is_local_resolved_execution_edge(edge, state.seed_key[0]) and self._from_key(edge) in state.nodes
                ]
                if not eligible_edges:
                    self._add_root(state, node, EntrypointFlowOrigin.INFERRED_ROOT, distance)
                    continue
                for edge in sorted(eligible_edges, key=self._edge_sort_key):
                    state.upstream_transitions.setdefault(self._edge_key(edge), edge)
                    source_key = self._from_key(edge)
                    if not self._within_unit_limits(state, next_node=source_key, next_edge=self._edge_key(edge)):
                        continue
                    if source_key in visited:
                        state.cycle_detected = True
                        continue
                    visited.add(source_key)
                    next_frontier[source_key] = min(next_frontier.get(source_key, distance + 1), distance + 1)
            frontier = next_frontier

        if not state.roots:
            self._add_root(state, state.seed_node, EntrypointFlowOrigin.INFERRED_ROOT, 0)
            state.diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="LOCAL_FLOW_ROOT_FALLBACK_TO_SEED",
                    message="No acyclic local predecessor root was retained; the seed was kept as an inferred local root.",
                    severity="INFO",
                    sourceId=state.seed_key[0],
                )
            )

    def _forward_explore(self, state: _ExploredSeed, include_tests: bool) -> None:
        frontier: set[FlowNodeKey] = {state.seed_key}
        expanded: set[FlowNodeKey] = set()
        while frontier:
            state.forward_rounds += 1
            if state.forward_rounds > _MAX_FRONTIER_ROUNDS:
                self._mark_truncated(state, "LOCAL_FLOW_FORWARD_TRUNCATED", "Forward local flow exploration reached the internal frontier-round limit.")
                break
            query_keys = {node_key for node_key in frontier if node_key not in expanded}
            outgoing_by_source = self.repository.load_outgoing_calls(query_keys, include_tests=include_tests) if query_keys else {}
            local_target_keys = {
                self._to_key(edge)
                for edges in outgoing_by_source.values()
                for edge in edges
                if self._is_local_resolved_execution_edge(edge, state.seed_key[0]) and self._to_key(edge) is not None
            }
            target_nodes = self.repository.load_nodes({key for key in local_target_keys if key is not None}, include_tests=include_tests)

            next_frontier: set[FlowNodeKey] = set()
            for node_key in sorted(frontier):
                if node_key in expanded:
                    continue
                expanded.add(node_key)
                for edge in outgoing_by_source.get(node_key, ()):
                    if not self.semantics.is_execution_continuation(edge):
                        continue
                    edge_key = self._edge_key(edge)
                    target_key = self._to_key(edge)
                    if not self._is_resolved(edge) or target_key is None:
                        self._add_topology_boundary(state, edge)
                        continue
                    if not self._edge_target_is_same_source(edge, state.seed_key[0]):
                        self._add_topology_boundary(state, replace(edge, boundary_reason="CROSS_SOURCE_TARGET"))
                        continue
                    target_node = target_nodes.get(target_key) or state.nodes.get(target_key)
                    if target_node is None:
                        state.missing_resolved_target = True
                        self._add_topology_boundary(state, replace(edge, boundary_reason="CURRENT_TARGET_NODE_MISSING"))
                        continue
                    if not self._within_unit_limits(state, next_node=target_key, next_edge=edge_key):
                        continue
                    state.nodes[target_key] = target_node
                    state.downstream_transitions.setdefault(edge_key, edge)
                    if target_key in expanded or target_key == node_key:
                        state.cycle_detected = True
                        continue
                    next_frontier.add(target_key)
            frontier = next_frontier

    def _merge_seed_regions(self, explored: Sequence[_ExploredSeed], include_tests: bool) -> tuple[LocalFlowUnit, ...]:
        if not explored:
            return ()
        adjacency: dict[int, set[int]] = {index: set() for index, _item in enumerate(explored)}
        for left_index, left in enumerate(explored):
            for right_index in range(left_index + 1, len(explored)):
                right = explored[right_index]
                if self._regions_overlap(left, right):
                    adjacency[left_index].add(right_index)
                    adjacency[right_index].add(left_index)

        components: list[tuple[int, ...]] = []
        seen: set[int] = set()
        for index in range(len(explored)):
            if index in seen:
                continue
            stack = [index]
            component: set[int] = set()
            while stack:
                item = stack.pop()
                if item in component:
                    continue
                component.add(item)
                stack.extend(sorted(adjacency[item] - component))
            seen.update(component)
            components.append(tuple(sorted(component)))
        components.sort(key=lambda indexes: min(self._seed_region_sort_key(explored[index]) for index in indexes))
        return tuple(self._unit_from_seed_regions(tuple(explored[index] for index in component), include_tests) for component in components)

    def _unit_from_seed_regions(self, regions: Sequence[_ExploredSeed], include_tests: bool) -> LocalFlowUnit:
        node_map = self._node_map(node for region in regions for node in region.nodes.values())
        supporting_map = self._node_map(node for region in regions for node in region.supporting_context.values())
        transition_map = self._edge_map(edge for region in regions for edge in (*region.upstream_transitions.values(), *region.downstream_transitions.values()))
        topology_map = self._edge_map(edge for region in regions for edge in region.topology_boundaries.values())
        root_by_key: dict[FlowNodeKey, LocalFlowRoot] = {}
        for region in regions:
            for root_key, root in region.roots.items():
                current = root_by_key.get(root_key)
                if (
                    current is None
                    or root.distance_to_nearest_seed < current.distance_to_nearest_seed
                    or current.origin is EntrypointFlowOrigin.INFERRED_ROOT
                    and root.origin is EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT
                ):
                    root_by_key[root_key] = root

        anchors = self._merge_local_anchors([anchor for region in regions for anchor in region.anchors])
        diagnostics = self._dedupe_diagnostics([item for region in regions for item in region.diagnostics])
        cycle_detected = any(region.cycle_detected for region in regions)
        truncated = any(region.truncated for region in regions)
        missing_resolved_target = any(region.missing_resolved_target for region in regions)
        if missing_resolved_target:
            diagnostics = (
                *diagnostics,
                KnowledgeQueryDiagnostic(
                    code="LOCAL_FLOW_CURRENT_TARGET_NODE_MISSING",
                    message="A resolved execution edge pointed to a target node outside the current graph and was exposed as a topology boundary.",
                    severity="WARN",
                    sourceId=regions[0].seed_key[0],
                ),
            )
        if cycle_detected:
            diagnostics = (
                *diagnostics,
                KnowledgeQueryDiagnostic(
                    code="LOCAL_FLOW_CYCLE_DETECTED",
                    message="A local execution cycle was retained without repeated expansion.",
                    severity="INFO",
                    sourceId=regions[0].seed_key[0],
                ),
            )

        boundary_node_keys = set(node_map) | set(supporting_map)
        generic_boundaries = self._load_unit_boundaries(boundary_node_keys, include_tests)
        if len(generic_boundaries) > _MAX_UNIT_BOUNDARIES:
            generic_boundaries = generic_boundaries[:_MAX_UNIT_BOUNDARIES]
            truncated = True
            diagnostics = (
                *diagnostics,
                KnowledgeQueryDiagnostic(
                    code="LOCAL_FLOW_BOUNDARIES_TRUNCATED",
                    message="Generic boundary facts reached the internal local unit limit.",
                    severity="WARN",
                    sourceId=regions[0].seed_key[0],
                ),
            )

        roots = tuple(sorted(root_by_key.values(), key=self._local_root_sort_key))
        execution_nodes = tuple(sorted(node_map.values(), key=lambda node: self._node_sort_key(node, roots[0].node if roots else None)))
        supporting_context = tuple(
            sorted(
                (node for key, node in supporting_map.items() if key not in node_map),
                key=lambda node: self._node_sort_key(node, roots[0].node if roots else None),
            )
        )
        execution_transitions = tuple(sorted(transition_map.values(), key=self._edge_sort_key))
        topology_boundaries = tuple(sorted(topology_map.values(), key=self._edge_sort_key))
        evidence = dedupe_evidence(
            [
                *(item for boundary in generic_boundaries for item in boundary.evidence),
                *(item for boundary in generic_boundaries for descriptor in boundary.descriptors for item in descriptor.evidence),
            ]
        )
        max_depth = max((root.distance_to_nearest_seed for root in roots), default=0)
        complete = not truncated
        unit_id = self._stable_unit_id(
            roots=roots,
            anchors=anchors,
            execution_nodes=execution_nodes,
            execution_transitions=execution_transitions,
            generic_boundaries=generic_boundaries,
            topology_boundaries=topology_boundaries,
        )
        coverage = LocalFlowCoverage(
            node_count=len(execution_nodes),
            transition_count=len(execution_transitions),
            generic_boundary_count=len(generic_boundaries),
            topology_boundary_count=len(topology_boundaries),
            anchor_count=len(anchors),
            root_count=len(roots),
            max_depth_reached=max_depth,
            cycle_detected=cycle_detected,
            truncated=truncated,
        )
        return LocalFlowUnit(
            unit_id=unit_id,
            source_id=regions[0].seed_key[0],
            graph_revision=regions[0].seed_key[1],
            roots=roots,
            anchors=anchors,
            execution_nodes=execution_nodes,
            execution_transitions=execution_transitions,
            generic_boundaries=generic_boundaries,
            topology_boundaries=topology_boundaries,
            supporting_context=supporting_context,
            evidence=evidence,
            complete=complete,
            coverage=coverage,
            diagnostics=self._dedupe_diagnostics(diagnostics),
        )

    def _project_local_units(self, local_units: Sequence[LocalFlowUnit]) -> tuple[EntrypointFlow, ...]:
        flows: list[EntrypointFlow] = []
        for unit in sorted(local_units, key=self._local_unit_sort_key):
            roots = unit.roots or ()
            for root in roots:
                flows.append(self._project_local_unit(unit, root))
        return tuple(sorted(flows, key=self._flow_sort_key))

    def _project_local_unit(self, unit: LocalFlowUnit, root: LocalFlowRoot) -> EntrypointFlow:
        root_key = self._node_key(root.node)
        reachable_node_keys, transitions = self._root_reachable_execution(unit, root_key)
        retained_anchors = tuple(
            sorted(
                (anchor for anchor in unit.anchors if self._node_key(anchor.expanded_seed) in reachable_node_keys),
                key=self._local_anchor_sort_key,
            )
        )
        retained_supporting_context = self._root_projection_supporting_context(unit, retained_anchors, reachable_node_keys)
        projected_node_keys = reachable_node_keys | {self._node_key(node) for node in retained_supporting_context}
        node_map = self._node_map(
            [
                *(node for node in unit.execution_nodes if self._node_key(node) in reachable_node_keys),
                *retained_supporting_context,
            ]
        )
        nodes = tuple(
            sorted(
                node_map.values(),
                key=lambda node: self._node_sort_key(node, root.node),
            )
        )
        topology_boundaries = tuple(
            sorted(
                (edge for edge in unit.topology_boundaries if self._from_key(edge) in projected_node_keys),
                key=self._edge_sort_key,
            )
        )
        generic_boundaries = tuple(
            sorted(
                (boundary for boundary in unit.generic_boundaries if boundary.owner_key in projected_node_keys),
                key=self._boundary_sort_key,
            )
        )
        evidence = self._projection_evidence(
            unit.evidence,
            node_keys=projected_node_keys,
            transitions=transitions,
            topology_boundaries=topology_boundaries,
            generic_boundaries=generic_boundaries,
        )
        diagnostics = list(unit.diagnostics)
        if root.origin is EntrypointFlowOrigin.INFERRED_ROOT:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="ENTRYPOINT_FLOW_INFERRED_ROOT",
                    message="No explicit execution root was reachable for this local unit projection; a topology root was used.",
                    severity="INFO",
                    sourceId=unit.source_id,
                )
            )
        if len(unit.roots) > 1:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="ENTRYPOINT_FLOW_COMPATIBILITY_MULTI_ROOT_PROJECTION",
                    message="A local flow unit has multiple roots; this legacy flow is one deterministic root projection.",
                    severity="INFO",
                    sourceId=unit.source_id,
                    metadata={"localUnitId": unit.unit_id, "rootCount": len(unit.roots)},
                )
            )
        if generic_boundaries:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="ENTRYPOINT_FLOW_GENERIC_BOUNDARY_FACTS_PRESENT",
                    message="Generic boundary facts are retained on the internal local flow unit and are not projected as topology boundaries.",
                    severity="INFO",
                    sourceId=unit.source_id,
                    metadata={"localUnitId": unit.unit_id, "genericBoundaryCount": len(generic_boundaries)},
                )
            )
        anchors = tuple(
            sorted(
                (self._legacy_anchor(anchor) for anchor in retained_anchors),
                key=lambda item: (-item.score, item.distance, item.node_id, item.expanded_seed_node_id or ""),
            )
        )
        coverage = EntrypointFlowCoverage(
            node_count=len(nodes),
            transition_count=len(transitions),
            boundary_count=len(topology_boundaries) + len(generic_boundaries),
            anchor_count=len(anchors),
            max_depth_reached=unit.coverage.max_depth_reached,
            cycle_detected=unit.coverage.cycle_detected,
            truncated=unit.coverage.truncated,
        )
        return EntrypointFlow(
            key=EntrypointFlowKey(root_key[0], root_key[1], root_key[2]),
            entrypoint=root.node,
            origin=root.origin,
            anchors=self._merge_anchors(anchors),
            nodes=nodes,
            transitions=transitions,
            boundary_transitions=topology_boundaries,
            evidence=evidence,
            complete=unit.complete,
            coverage=coverage,
            diagnostics=self._dedupe_diagnostics(diagnostics),
            relevance_score=self._relevance(root.origin, anchors),
            local_unit_id=unit.unit_id,
            root_nodes=(root.node,),
            generic_boundaries=generic_boundaries,
        )

    def _root_reachable_execution(
        self,
        unit: LocalFlowUnit,
        root_key: FlowNodeKey,
    ) -> tuple[set[FlowNodeKey], tuple[FlowGraphEdge, ...]]:
        unit_node_keys = {self._node_key(node) for node in unit.execution_nodes}
        if root_key not in unit_node_keys:
            return {root_key}, ()
        outgoing: dict[FlowNodeKey, list[FlowGraphEdge]] = defaultdict(list)
        for edge in unit.execution_transitions:
            source_key = self._from_key(edge)
            target_key = self._to_key(edge)
            if source_key in unit_node_keys and target_key in unit_node_keys:
                outgoing[source_key].append(edge)
        for values in outgoing.values():
            values.sort(key=self._edge_sort_key)

        reachable: set[FlowNodeKey] = {root_key}
        retained_edges: dict[FlowEdgeKey, FlowGraphEdge] = {}
        expanded: set[FlowNodeKey] = set()
        frontier: list[FlowNodeKey] = [root_key]
        while frontier:
            current = frontier.pop(0)
            if current in expanded:
                continue
            expanded.add(current)
            for edge in outgoing.get(current, ()):
                target_key = self._to_key(edge)
                if target_key is None:
                    continue
                retained_edges.setdefault(self._edge_key(edge), edge)
                if target_key not in reachable:
                    reachable.add(target_key)
                    frontier.append(target_key)
        return reachable, tuple(sorted(retained_edges.values(), key=self._edge_sort_key))

    def _root_projection_supporting_context(
        self,
        unit: LocalFlowUnit,
        retained_anchors: Sequence[LocalFlowAnchorProvenance],
        reachable_node_keys: set[FlowNodeKey],
    ) -> tuple[FlowGraphNode, ...]:
        candidates = self._node_map(unit.supporting_context)
        retained: dict[FlowNodeKey, FlowGraphNode] = {}
        for anchor in retained_anchors:
            original_key = self._anchor_lookup_key(anchor.original_anchor)
            original_node = self._find_node_by_id(candidates, original_key)
            if original_node is None:
                continue
            key = self._node_key(original_node)
            if key not in reachable_node_keys:
                retained[key] = original_node
        return tuple(sorted(retained.values(), key=lambda node: self._node_sort_key(node, None)))

    def _projection_evidence(
        self,
        evidence: Sequence[FlowGraphEvidence],
        *,
        node_keys: set[FlowNodeKey],
        transitions: Sequence[FlowGraphEdge],
        topology_boundaries: Sequence[FlowGraphEdge],
        generic_boundaries: Sequence[LocalBoundaryFact],
    ) -> tuple[FlowGraphEvidence, ...]:
        node_source_ids = {(source_id, node_id) for source_id, _revision, node_id in node_keys}
        edge_ids = {edge.edge_id for edge in (*transitions, *topology_boundaries)}
        boundary_owner_nodes = {(boundary.source_id, boundary.owner_node_id) for boundary in generic_boundaries}
        return dedupe_evidence(
            [
                item
                for item in evidence
                if item.edge_id in edge_ids
                or item.node_id is not None
                and (item.source_id, item.node_id) in node_source_ids
                or str(item.owner_kind or "").upper() in {"BOUNDARY", "BOUNDARY_DESCRIPTOR"}
                and item.owner_node_id is not None
                and (item.owner_source_id or item.source_id, item.owner_node_id) in boundary_owner_nodes
            ]
        )

    def _public_flow(self, index: int, flow: EntrypointFlow) -> KnowledgeQueryFlow:
        node_ref_by_key = {self._node_key(node): f"n{position}" for position, node in enumerate(flow.nodes, start=1)}
        node_ref_by_id = {node.node_id: node_ref_by_key[self._node_key(node)] for node in flow.nodes}
        transition_ref_by_key = {self._edge_key(edge): f"t{position}" for position, edge in enumerate(flow.transitions, start=1)}
        boundary_ref_by_key = {self._edge_key(edge): f"b{position}" for position, edge in enumerate(flow.boundary_transitions, start=1)}
        transition_ref_by_id = {edge.edge_id: transition_ref_by_key[self._edge_key(edge)] for edge in flow.transitions}
        boundary_ref_by_id = {edge.edge_id: boundary_ref_by_key[self._edge_key(edge)] for edge in flow.boundary_transitions}
        evidence_ref_by_key = {evidence_key(item): f"e{position}" for position, item in enumerate(flow.evidence, start=1)}
        public_nodes = [self._public_node(node, node_ref_by_key[self._node_key(node)]) for node in flow.nodes]
        public_evidence: list[KnowledgeQueryFlowEvidence] = []
        for item in flow.evidence:
            owner_ref = transition_ref_by_id.get(item.edge_id or "") or boundary_ref_by_id.get(item.edge_id or "") or node_ref_by_id.get(item.node_id or "")
            if not owner_ref:
                continue
            public_evidence.append(
                KnowledgeQueryFlowEvidence(
                    evidenceRef=evidence_ref_by_key[evidence_key(item)],
                    ownerRef=owner_ref,
                    relativePath=item.relative_path,
                    lineStart=item.line_start,
                    lineEnd=item.line_end,
                    excerpt=item.text,
                )
            )
        return KnowledgeQueryFlow(
            flowIndex=index,
            source=flow.key.source_id,
            entrypoint=self._public_node(flow.entrypoint, node_ref_by_key[self._node_key(flow.entrypoint)]),
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
                    transitionRef=transition_ref_by_key[self._edge_key(edge)],
                    fromNodeRef=node_ref_by_key[self._from_key(edge)],
                    toNodeRef=node_ref_by_key[self._to_key(edge)],
                    evidenceRefs=self._public_edge_evidence_refs(edge, flow.evidence, evidence_ref_by_key),
                )
                for edge in flow.transitions
                if self._from_key(edge) in node_ref_by_key and self._to_key(edge) in node_ref_by_key
            ],
            boundaries=[
                self._public_boundary(edge, boundary_ref_by_key[self._edge_key(edge)], node_ref_by_key, flow.evidence, evidence_ref_by_key)
                for edge in flow.boundary_transitions
                if self._from_key(edge) in node_ref_by_key
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
        node_ref_by_key: dict[FlowNodeKey, str],
        evidence: Sequence[FlowGraphEvidence],
        evidence_ref_by_key: dict[FlowGraphEvidenceKey, str],
    ) -> KnowledgeQueryFlowBoundary:
        projection = self.boundary_classifier.project(edge)
        return KnowledgeQueryFlowBoundary(
            boundaryRef=boundary_ref,
            fromNodeRef=node_ref_by_key[self._from_key(edge)],
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

    def _load_unit_boundaries(self, node_keys: set[FlowNodeKey], include_tests: bool) -> tuple[LocalBoundaryFact, ...]:
        if not node_keys or not hasattr(self.repository, "load_boundaries"):
            return ()
        loaded = self.repository.load_boundaries(node_keys, include_tests=include_tests)
        facts = [fact for key in sorted(loaded) for fact in loaded[key]]
        return tuple(sorted(facts, key=self._boundary_sort_key))

    def _hydrate_local_units_from_flows(
        self,
        units: Sequence[LocalFlowUnit],
        flows: Sequence[EntrypointFlow],
    ) -> tuple[LocalFlowUnit, ...]:
        evidence_by_unit: dict[str, list[FlowGraphEvidence]] = defaultdict(list)
        for flow in flows:
            if flow.local_unit_id:
                evidence_by_unit[flow.local_unit_id].extend(flow.evidence)
        hydrated: list[LocalFlowUnit] = []
        for unit in units:
            evidence = dedupe_evidence([*unit.evidence, *evidence_by_unit.get(unit.unit_id, [])])
            hydrated.append(replace(unit, evidence=evidence))
        return tuple(hydrated)

    def _regions_overlap(self, left: _ExploredSeed, right: _ExploredSeed) -> bool:
        if left.seed_key == right.seed_key:
            return True
        left_edges = set(left.upstream_transitions) | set(left.downstream_transitions)
        right_edges = set(right.upstream_transitions) | set(right.downstream_transitions)
        if left_edges & right_edges:
            return True
        if left.seed_key in right.nodes or right.seed_key in left.nodes:
            return True
        left_roots = set(left.roots)
        right_roots = set(right.roots)
        shared_non_root_nodes = (set(left.nodes) & set(right.nodes)) - left_roots - right_roots
        return bool(shared_non_root_nodes)

    def _add_root(self, state: _ExploredSeed, node: FlowGraphNode, origin: EntrypointFlowOrigin, distance: int) -> None:
        key = self._node_key(node)
        current = state.roots.get(key)
        root = LocalFlowRoot(node=node, origin=origin, distance_to_nearest_seed=distance)
        if (
            current is None
            or distance < current.distance_to_nearest_seed
            or current.origin is EntrypointFlowOrigin.INFERRED_ROOT
            and origin is EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT
        ):
            state.roots[key] = root
        state.root_distance_by_seed[key] = min(state.root_distance_by_seed.get(key, distance), distance)

    def _add_topology_boundary(self, state: _ExploredSeed, edge: FlowGraphEdge) -> None:
        if len(state.topology_boundaries) >= _MAX_UNIT_BOUNDARIES:
            self._mark_truncated(state, "LOCAL_FLOW_TOPOLOGY_BOUNDARIES_TRUNCATED", "Topology boundaries reached the internal local unit limit.")
            return
        state.topology_boundaries.setdefault(self._edge_key(edge), edge)

    def _within_unit_limits(
        self,
        state: _ExploredSeed,
        *,
        next_node: FlowNodeKey | None = None,
        next_edge: FlowEdgeKey | None = None,
    ) -> bool:
        if next_node is not None and next_node not in state.nodes and len(state.nodes) >= _MAX_UNIT_NODES:
            self._mark_truncated(state, "LOCAL_FLOW_NODE_LIMIT_REACHED", "Local flow exploration reached the internal node limit.")
            return False
        edge_count = len(state.upstream_transitions) + len(state.downstream_transitions)
        if (
            next_edge is not None
            and next_edge not in state.upstream_transitions
            and next_edge not in state.downstream_transitions
            and edge_count >= _MAX_UNIT_TRANSITIONS
        ):
            self._mark_truncated(state, "LOCAL_FLOW_TRANSITION_LIMIT_REACHED", "Local flow exploration reached the internal transition limit.")
            return False
        return True

    def _mark_truncated(self, state: _ExploredSeed, code: str, message: str) -> None:
        if not state.truncated:
            state.diagnostics.append(KnowledgeQueryDiagnostic(code=code, message=message, severity="WARN", sourceId=state.seed_key[0]))
        state.truncated = True

    def _legacy_anchor(self, anchor: LocalFlowAnchorProvenance) -> EntrypointFlowAnchor:
        original = anchor.original_anchor
        seed = anchor.expanded_seed
        match_reasons = tuple(sorted(set(original.matchReasons or ())))
        return EntrypointFlowAnchor(
            node_id=original.nodeId,
            label=original.label,
            score=original.score,
            match_reasons=match_reasons,
            distance=anchor.distance_to_nearest_root,
            original_stable_key=original.stableKey,
            original_node_kind=original.nodeKind,
            expanded_seed_node_id=seed.node_id,
            expanded_seed_stable_key=seed.stable_key,
            anchor_to_seed_reasons=anchor.anchor_to_seed_reasons,
            query_provenance=anchor.query_provenance,
        )

    def _merge_anchors(self, anchors: Sequence[EntrypointFlowAnchor]) -> tuple[EntrypointFlowAnchor, ...]:
        merged: dict[tuple[str, str | None, str | None], EntrypointFlowAnchor] = {}
        for item in anchors:
            key = (item.node_id, item.original_stable_key, item.expanded_seed_node_id)
            current = merged.get(key)
            if current is None:
                merged[key] = item
            else:
                merged[key] = EntrypointFlowAnchor(
                    node_id=item.node_id,
                    label=current.label if current.score >= item.score else item.label,
                    score=max(current.score, item.score),
                    match_reasons=tuple(sorted(set(current.match_reasons) | set(item.match_reasons))),
                    distance=min(current.distance, item.distance),
                    original_stable_key=current.original_stable_key or item.original_stable_key,
                    original_node_kind=current.original_node_kind or item.original_node_kind,
                    expanded_seed_node_id=current.expanded_seed_node_id or item.expanded_seed_node_id,
                    expanded_seed_stable_key=current.expanded_seed_stable_key or item.expanded_seed_stable_key,
                    anchor_to_seed_reasons=tuple(sorted(set(current.anchor_to_seed_reasons) | set(item.anchor_to_seed_reasons))),
                    query_provenance=tuple(sorted(set(current.query_provenance) | set(item.query_provenance))),
                )
        return tuple(sorted(merged.values(), key=lambda item: (-item.score, item.distance, item.node_id, item.expanded_seed_node_id or "")))

    def _merge_local_anchors(self, anchors: Sequence[LocalFlowAnchorProvenance]) -> tuple[LocalFlowAnchorProvenance, ...]:
        by_key: dict[tuple[str, str, str], LocalFlowAnchorProvenance] = {}
        for item in anchors:
            key = (item.original_anchor.sourceId, item.original_anchor.stableKey, item.expanded_seed.node_id)
            current = by_key.get(key)
            if current is None:
                by_key[key] = item
                continue
            reasons = tuple(sorted(set(current.anchor_to_seed_reasons) | set(item.anchor_to_seed_reasons)))
            query_provenance = tuple(sorted(set(current.query_provenance) | set(item.query_provenance)))
            selected = current if current.original_anchor.score >= item.original_anchor.score else item
            by_key[key] = replace(
                selected,
                anchor_to_seed_reasons=reasons,
                query_provenance=query_provenance,
                distance_to_nearest_root=min(current.distance_to_nearest_root, item.distance_to_nearest_root),
            )
        return tuple(sorted(by_key.values(), key=self._local_anchor_sort_key))

    def _query_provenance(self, anchor: KnowledgeQueryMatchedNode) -> tuple[str, ...]:
        return tuple(sorted(reason for reason in set(anchor.matchReasons or ()) if str(reason).startswith("QUERY_")))

    def _stable_unit_id(
        self,
        *,
        roots: Sequence[LocalFlowRoot],
        anchors: Sequence[LocalFlowAnchorProvenance],
        execution_nodes: Sequence[FlowGraphNode],
        execution_transitions: Sequence[FlowGraphEdge],
        generic_boundaries: Sequence[LocalBoundaryFact],
        topology_boundaries: Sequence[FlowGraphEdge],
    ) -> str:
        payload = {
            "roots": sorted(self._node_identity(root.node) for root in roots),
            "anchors": sorted((anchor.original_anchor.stableKey, anchor.expanded_seed.stable_key) for anchor in anchors),
            "nodes": sorted(self._node_identity(node) for node in execution_nodes),
            "transitions": sorted(self._edge_identity(edge) for edge in execution_transitions),
            "genericBoundaries": sorted(self._boundary_identity(boundary) for boundary in generic_boundaries),
            "topologyBoundaries": sorted(self._edge_identity(edge) for edge in topology_boundaries),
        }
        raw = json.dumps(payload, sort_keys=True, separators=(",", ":"), default=str)
        return "lfu_" + hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]

    def _is_explicit_executable_root(self, node: FlowGraphNode | None) -> bool:
        return bool(node and self.semantics.may_root_family(node))

    def _is_resolved(self, edge: FlowGraphEdge) -> bool:
        return bool(edge.to_node_id) and not edge.external and str(edge.resolution_status or "").upper() == "RESOLVED"

    def _is_local_resolved_execution_edge(self, edge: FlowGraphEdge, source_id: str) -> bool:
        return self.semantics.is_execution_continuation(edge) and self._is_resolved(edge) and self._edge_target_is_same_source(edge, source_id)

    def _edge_target_is_same_source(self, edge: FlowGraphEdge, source_id: str) -> bool:
        target_key = self._to_key(edge)
        if target_key is None:
            return False
        return edge.source_id == source_id and target_key[0] == source_id

    def _relevance(self, origin: EntrypointFlowOrigin, anchors: Sequence[EntrypointFlowAnchor]) -> float:
        best = max((item.score for item in anchors), default=0.0)
        shortest = min((item.distance for item in anchors), default=999)
        return best + min(len(anchors), 10) * 0.01 + (0.02 if origin is EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT else 0.0) + 0.01 / (1 + shortest)

    def _dedupe_diagnostics(self, diagnostics: Sequence[KnowledgeQueryDiagnostic]) -> tuple[KnowledgeQueryDiagnostic, ...]:
        result: list[KnowledgeQueryDiagnostic] = []
        seen: set[tuple[str, str, str | None, str]] = set()
        for item in diagnostics:
            metadata = json.dumps(item.metadata or {}, sort_keys=True, default=str)
            key = (item.code, item.message, item.sourceId, metadata)
            if key in seen:
                continue
            seen.add(key)
            result.append(item)
        return tuple(result)

    def _node_map(self, nodes: Sequence[FlowGraphNode] | Any) -> dict[FlowNodeKey, FlowGraphNode]:
        result: dict[FlowNodeKey, FlowGraphNode] = {}
        for node in nodes:
            result.setdefault(self._node_key(node), node)
        return result

    def _edge_map(self, edges: Sequence[FlowGraphEdge] | Any) -> dict[FlowEdgeKey, FlowGraphEdge]:
        result: dict[FlowEdgeKey, FlowGraphEdge] = {}
        for edge in edges:
            result.setdefault(self._edge_key(edge), edge)
        return result

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
        return (
            edge.to_source_id or edge.source_id,
            edge.to_graph_revision or edge.to_graph_id or edge.graph_revision or edge.graph_id,
            edge.to_node_id,
        )

    def _find_node_by_id(self, nodes: Mapping[FlowNodeKey, FlowGraphNode], key: FlowNodeKey) -> FlowGraphNode | None:
        for node_key, node in nodes.items():
            if node_key[0] == key[0] and node_key[2] == key[2]:
                expected_revision = key[1]
                if not expected_revision or expected_revision in {node.graph_id, node.graph_revision or ""}:
                    return node
        return None

    def _node_identity(self, node: FlowGraphNode) -> tuple[str, str, str]:
        return (node.source_id, node.graph_revision or node.graph_id, node.stable_key or node.node_id)

    def _edge_identity(self, edge: FlowGraphEdge) -> tuple[str, str, str]:
        return (edge.source_id, edge.graph_revision or edge.graph_id, edge.edge_id)

    def _boundary_identity(self, boundary: LocalBoundaryFact) -> tuple[str, str, str]:
        return (boundary.source_id, boundary.graph_revision or boundary.graph_id, boundary.stable_key or boundary.boundary_id)

    def _seed_spec_sort_key(self, spec: _SeedSpec) -> tuple[str, str, str, str, str]:
        return (
            spec.expanded_seed.sourceId,
            spec.expanded_seed.graphRevision or spec.expanded_seed.graphId or "",
            spec.expanded_seed.nodeId,
            spec.original_anchor.stableKey,
            spec.original_anchor.nodeId,
        )

    def _seed_region_sort_key(self, region: _ExploredSeed) -> tuple[str, str, str]:
        return region.seed_key

    def _flow_sort_key(self, flow: EntrypointFlow) -> tuple[float, str, str, str, str]:
        return (
            -float(flow.relevance_score or 0.0),
            flow.local_unit_id or "",
            flow.key.source_id,
            flow.key.graph_revision,
            flow.key.entrypoint_node_id,
        )

    def _local_unit_sort_key(self, unit: LocalFlowUnit) -> tuple[str, str, str]:
        return (unit.source_id, unit.graph_revision, unit.unit_id)

    def _local_root_sort_key(self, root: LocalFlowRoot) -> tuple[int, int, str, str, str]:
        return (
            0 if root.origin is EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT else 1,
            root.distance_to_nearest_seed,
            root.node.source_id,
            root.node.graph_revision or root.node.graph_id,
            root.node.stable_key or root.node.node_id,
        )

    def _local_anchor_sort_key(self, anchor: LocalFlowAnchorProvenance) -> tuple[float, str, str, str, str]:
        return (
            -float(anchor.original_anchor.score or 0.0),
            anchor.original_anchor.sourceId,
            anchor.original_anchor.stableKey,
            anchor.expanded_seed.stable_key,
            anchor.original_anchor.nodeId,
        )

    def _boundary_sort_key(self, boundary: LocalBoundaryFact) -> tuple[str, str, str, str, str]:
        return (
            boundary.source_id,
            boundary.graph_revision or boundary.graph_id,
            boundary.owner_node_id,
            boundary.role,
            boundary.stable_key or boundary.boundary_id,
        )

    def _edge_sort_key(self, edge: FlowGraphEdge) -> tuple[str, str, str, str, str]:
        return (
            edge.source_id,
            edge.graph_revision or edge.graph_id,
            edge.from_node_id,
            edge.to_node_id or "",
            edge.edge_id,
        )

    def _node_sort_key(self, node: FlowGraphNode, root: FlowGraphNode | None) -> tuple[int, str, str, int, int, str]:
        root_key = self._node_key(root) if root is not None else None
        return (
            0 if root_key is not None and self._node_key(node) == root_key else 1,
            node.source_id,
            node.relative_path or "",
            node.line_start or 0,
            node.line_end or 0,
            node.stable_key or node.node_id,
        )
