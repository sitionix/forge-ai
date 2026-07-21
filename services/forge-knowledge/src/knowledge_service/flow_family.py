from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, replace
from typing import Dict, Iterable, Mapping, Sequence

from knowledge_service.entrypoint_flow_engine import (
    EntrypointFlow,
    EntrypointFlowAnchor,
    EntrypointFlowCoverage,
    EntrypointFlowKey,
    EntrypointFlowOrigin,
)
from knowledge_service.flow_graph_contract import (
    FlowEdgeKey,
    FlowGraphEdge,
    FlowGraphEvidence,
    FlowGraphNode,
    FlowNodeKey,
    dedupe_evidence,
    evidence_key,
)
from knowledge_service.graph_relation_semantics import GraphRelationSemantics, graph_relation_semantics
from knowledge_service.knowledge_query_schema import KnowledgeQueryDiagnostic


@dataclass(frozen=True)
class FlowFamily:
    key: EntrypointFlowKey
    entrypoint: FlowGraphNode
    origin: EntrypointFlowOrigin
    anchors: tuple[EntrypointFlowAnchor, ...]
    nodes: tuple[FlowGraphNode, ...]
    transitions: tuple[FlowGraphEdge, ...]
    boundary_transitions: tuple[FlowGraphEdge, ...]
    supporting_transitions: tuple[FlowGraphEdge, ...]
    evidence: tuple[FlowGraphEvidence, ...]
    complete: bool
    coverage: EntrypointFlowCoverage
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...]
    relevance_score: float
    nested_entrypoints: tuple[FlowGraphNode, ...] = ()
    subordinate_entrypoint_count: int = 0
    raw_flow_keys: tuple[EntrypointFlowKey, ...] = ()


@dataclass(frozen=True)
class FlowFamilyAssemblyResult:
    families: tuple[FlowFamily, ...]
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...]
    raw_candidate_flow_count: int
    discovered_family_count: int
    root_reachability: Mapping[str, tuple[str, ...]]


class FlowFamilyAssembler:
    def __init__(self, semantics: GraphRelationSemantics | None = None) -> None:
        self.semantics = semantics or graph_relation_semantics()

    def assemble(
        self,
        raw_flows: Sequence[EntrypointFlow],
        *,
        supporting_nodes: Mapping[FlowNodeKey, FlowGraphNode] | None = None,
        supporting_relations: Sequence[FlowGraphEdge] = (),
    ) -> FlowFamilyAssemblyResult:
        flows = tuple(raw_flows or ())
        if not flows:
            return FlowFamilyAssemblyResult((), (), 0, 0, {})

        node_by_key = self._node_map(
            [
                *(node for flow in flows for node in flow.nodes),
                *((supporting_nodes or {}).values()),
            ]
        )
        execution_edges = self._edge_map(
            edge
            for flow in flows
            for edge in flow.transitions
            if self.semantics.is_execution_continuation(edge)
        )
        boundary_edges = self._edge_map(edge for flow in flows for edge in flow.boundary_transitions)
        support_edges = self._edge_map(
            edge
            for edge in supporting_relations
            if self.semantics.is_supporting_relation(edge)
        )
        for edge in support_edges.values():
            for key in (self._from_key(edge), self._to_key(edge)):
                if key is not None and key in (supporting_nodes or {}):
                    node_by_key.setdefault(key, (supporting_nodes or {})[key])

        flow_by_root = {self._node_key(flow.entrypoint): flow for flow in flows}
        candidate_roots = tuple(flow_by_root.keys())
        adjacency = self._execution_adjacency(execution_edges.values())
        reachability = self._root_reachability(candidate_roots, adjacency)
        diagnostics = list(self._cycle_diagnostics(candidate_roots, reachability, flow_by_root))
        subordinate_by_root = self._subordinate_roots(candidate_roots, reachability)
        independent_roots = [
            root
            for root in candidate_roots
            if root not in subordinate_by_root and self._root_may_stand(flow_by_root[root], flows, support_edges.values())
        ]
        if not independent_roots:
            independent_roots = [
                root
                for root in candidate_roots
                if root not in subordinate_by_root
            ] or list(candidate_roots)

        families = tuple(
            self._family(
                root,
                flows=flows,
                flow_by_root=flow_by_root,
                reachability=reachability,
                node_by_key=node_by_key,
                execution_edges=execution_edges,
                boundary_edges=boundary_edges,
                support_edges=support_edges,
                diagnostics=diagnostics,
            )
            for root in sorted(independent_roots, key=lambda key: self._family_sort_key(flow_by_root[key]))
        )
        return FlowFamilyAssemblyResult(
            families=families,
            diagnostics=tuple(diagnostics),
            raw_candidate_flow_count=len(flows),
            discovered_family_count=len(families),
            root_reachability={
                self._public_root_key(root, flow_by_root): tuple(self._public_root_key(item, flow_by_root) for item in reachable)
                for root, reachable in reachability.items()
            },
        )

    def rank(self, families: Sequence[FlowFamily]) -> tuple[FlowFamily, ...]:
        return tuple(sorted(families, key=self._family_sort_key))

    def _family(
        self,
        root: FlowNodeKey,
        *,
        flows: Sequence[EntrypointFlow],
        flow_by_root: Mapping[FlowNodeKey, EntrypointFlow],
        reachability: Mapping[FlowNodeKey, set[FlowNodeKey]],
        node_by_key: Mapping[FlowNodeKey, FlowGraphNode],
        execution_edges: Mapping[FlowEdgeKey, FlowGraphEdge],
        boundary_edges: Mapping[FlowEdgeKey, FlowGraphEdge],
        support_edges: Mapping[FlowEdgeKey, FlowGraphEdge],
        diagnostics: Sequence[KnowledgeQueryDiagnostic],
    ) -> FlowFamily:
        root_flow = flow_by_root[root]
        included_roots = {root, *self._strict_subordinate_reachable_roots(root, reachability)}
        included_raw_flows = [flow_by_root[item] for item in included_roots if item in flow_by_root]
        family_node_keys: set[FlowNodeKey] = set()
        for flow in included_raw_flows:
            family_node_keys.update(self._node_key(node) for node in flow.nodes)
        family_execution_edges = {
            key: edge
            for key, edge in execution_edges.items()
            if self._from_key(edge) in family_node_keys and (self._to_key(edge) in family_node_keys if self._to_key(edge) is not None else False)
        }
        family_boundary_edges = {
            key: edge
            for key, edge in boundary_edges.items()
            if self._from_key(edge) in family_node_keys
        }
        family_support_edges = self._supporting_edges_for_nodes(family_node_keys, support_edges.values())
        for edge in family_support_edges.values():
            family_node_keys.add(self._from_key(edge))
            to_key = self._to_key(edge)
            if to_key is not None:
                family_node_keys.add(to_key)
        family_nodes = tuple(
            sorted(
                (node_by_key[key] for key in family_node_keys if key in node_by_key),
                key=lambda node: self._node_sort_key(node, root),
            )
        )
        family_evidence = dedupe_evidence([item for flow in included_raw_flows for item in flow.evidence])
        family_diagnostics = tuple(
            self._dedupe_diagnostics(
                [
                    *(item for flow in included_raw_flows for item in flow.diagnostics),
                    *diagnostics,
                ]
            )
        )
        nested_entrypoints = tuple(
            sorted(
                (
                    node
                    for node in family_nodes
                    if self._node_key(node) != root and self.semantics.may_root_family(node)
                ),
                key=lambda node: self._node_sort_key(node, root),
            )
        )
        coverage = EntrypointFlowCoverage(
            node_count=len(family_nodes),
            transition_count=len(family_execution_edges),
            boundary_count=len(family_boundary_edges),
            anchor_count=len(root_flow.anchors),
            max_depth_reached=max((flow.coverage.max_depth_reached for flow in included_raw_flows), default=0),
            cycle_detected=any(flow.coverage.cycle_detected for flow in included_raw_flows) or self._root_has_cycle(root, reachability),
            truncated=False,
        )
        return FlowFamily(
            key=root_flow.key,
            entrypoint=root_flow.entrypoint,
            origin=root_flow.origin,
            anchors=self._merge_anchors([anchor for flow in included_raw_flows for anchor in flow.anchors]),
            nodes=family_nodes,
            transitions=tuple(sorted(family_execution_edges.values(), key=self._edge_sort_key)),
            boundary_transitions=tuple(sorted(family_boundary_edges.values(), key=self._edge_sort_key)),
            supporting_transitions=tuple(sorted(family_support_edges.values(), key=self._edge_sort_key)),
            evidence=family_evidence,
            complete=all(flow.complete for flow in included_raw_flows),
            coverage=coverage,
            diagnostics=family_diagnostics,
            relevance_score=max((flow.relevance_score for flow in included_raw_flows), default=root_flow.relevance_score),
            nested_entrypoints=nested_entrypoints,
            subordinate_entrypoint_count=max(0, len(included_roots) - 1),
            raw_flow_keys=tuple(sorted((flow.key for flow in included_raw_flows), key=lambda key: (key.source_id, key.graph_revision, key.entrypoint_node_id))),
        )

    def _root_may_stand(
        self,
        flow: EntrypointFlow,
        flows: Sequence[EntrypointFlow],
        support_edges: Iterable[FlowGraphEdge],
    ) -> bool:
        if self.semantics.may_root_family(flow.entrypoint):
            return True
        if flow.origin is EntrypointFlowOrigin.INFERRED_ROOT:
            explicit_roots = [item for item in flows if self.semantics.may_root_family(item.entrypoint)]
            return not explicit_roots
        implementation_keys = {self._node_key(item.entrypoint) for item in flows if self.semantics.may_root_family(item.entrypoint)}
        root_key = self._node_key(flow.entrypoint)
        for edge in support_edges:
            if self._to_key(edge) == root_key and self._from_key(edge) in implementation_keys:
                return False
            if self._from_key(edge) == root_key and self._to_key(edge) in implementation_keys:
                return False
        return False

    def _strict_subordinate_reachable_roots(
        self,
        root: FlowNodeKey,
        reachability: Mapping[FlowNodeKey, set[FlowNodeKey]],
    ) -> set[FlowNodeKey]:
        return {
            candidate
            for candidate in reachability.get(root, set())
            if candidate != root and root not in reachability.get(candidate, set())
        }

    def _subordinate_roots(
        self,
        roots: Sequence[FlowNodeKey],
        reachability: Mapping[FlowNodeKey, set[FlowNodeKey]],
    ) -> dict[FlowNodeKey, set[FlowNodeKey]]:
        subordinate: dict[FlowNodeKey, set[FlowNodeKey]] = defaultdict(set)
        for root in roots:
            for candidate in roots:
                if root == candidate:
                    continue
                if candidate in reachability.get(root, set()) and root not in reachability.get(candidate, set()):
                    subordinate[candidate].add(root)
        return subordinate

    def _root_reachability(
        self,
        roots: Sequence[FlowNodeKey],
        adjacency: Mapping[FlowNodeKey, set[FlowNodeKey]],
    ) -> dict[FlowNodeKey, set[FlowNodeKey]]:
        root_set = set(roots)
        result: dict[FlowNodeKey, set[FlowNodeKey]] = {root: set() for root in roots}
        for root in roots:
            seen: set[FlowNodeKey] = set()
            stack = list(sorted(adjacency.get(root, set())))
            while stack:
                node_key = stack.pop()
                if node_key in seen:
                    continue
                seen.add(node_key)
                if node_key in root_set:
                    result[root].add(node_key)
                stack.extend(sorted(adjacency.get(node_key, set()) - seen))
        return result

    def _cycle_diagnostics(
        self,
        roots: Sequence[FlowNodeKey],
        reachability: Mapping[FlowNodeKey, set[FlowNodeKey]],
        flow_by_root: Mapping[FlowNodeKey, EntrypointFlow],
    ) -> tuple[KnowledgeQueryDiagnostic, ...]:
        diagnostics: list[KnowledgeQueryDiagnostic] = []
        seen_pairs: set[tuple[FlowNodeKey, FlowNodeKey]] = set()
        for root in roots:
            for other in reachability.get(root, set()):
                if root == other or root not in reachability.get(other, set()):
                    continue
                pair = tuple(sorted((root, other)))
                if pair in seen_pairs:
                    continue
                seen_pairs.add(pair)
                root_flow = flow_by_root[root]
                other_flow = flow_by_root[other]
                diagnostics.append(
                    KnowledgeQueryDiagnostic(
                        code="FLOW_FAMILY_ROOT_CYCLE",
                        message="Executable candidate roots are mutually reachable; both roots were preserved.",
                        severity="WARN",
                        sourceId=root_flow.key.source_id,
                        metadata={
                            "roots": [
                                self._symbol(root_flow.entrypoint),
                                self._symbol(other_flow.entrypoint),
                            ]
                        },
                    )
                )
        return tuple(diagnostics)

    def _root_has_cycle(self, root: FlowNodeKey, reachability: Mapping[FlowNodeKey, set[FlowNodeKey]]) -> bool:
        return any(root != other and root in reachability.get(other, set()) for other in reachability.get(root, set()))

    def _execution_adjacency(self, edges: Iterable[FlowGraphEdge]) -> dict[FlowNodeKey, set[FlowNodeKey]]:
        adjacency: dict[FlowNodeKey, set[FlowNodeKey]] = defaultdict(set)
        for edge in edges:
            to_key = self._to_key(edge)
            if to_key is None:
                continue
            adjacency[self._from_key(edge)].add(to_key)
        return adjacency

    def _supporting_edges_for_nodes(
        self,
        node_keys: set[FlowNodeKey],
        support_edges: Iterable[FlowGraphEdge],
    ) -> dict[FlowEdgeKey, FlowGraphEdge]:
        selected: dict[FlowEdgeKey, FlowGraphEdge] = {}
        for edge in support_edges:
            to_key = self._to_key(edge)
            if self._from_key(edge) in node_keys or (to_key is not None and to_key in node_keys):
                selected[self._edge_key(edge)] = edge
        return selected

    def _node_map(self, nodes: Iterable[FlowGraphNode]) -> dict[FlowNodeKey, FlowGraphNode]:
        result: dict[FlowNodeKey, FlowGraphNode] = {}
        for node in nodes:
            result.setdefault(self._node_key(node), node)
        return result

    def _edge_map(self, edges: Iterable[FlowGraphEdge]) -> dict[FlowEdgeKey, FlowGraphEdge]:
        result: dict[FlowEdgeKey, FlowGraphEdge] = {}
        for edge in edges:
            result.setdefault(self._edge_key(edge), edge)
        return result

    def _merge_anchors(self, anchors: Sequence[EntrypointFlowAnchor]) -> tuple[EntrypointFlowAnchor, ...]:
        merged: dict[str, EntrypointFlowAnchor] = {}
        for item in anchors:
            current = merged.get(item.node_id)
            if current is None:
                merged[item.node_id] = item
                continue
            merged[item.node_id] = EntrypointFlowAnchor(
                node_id=item.node_id,
                label=current.label if current.score >= item.score else item.label,
                score=max(current.score, item.score),
                match_reasons=tuple(sorted(set(current.match_reasons) | set(item.match_reasons))),
                distance=min(current.distance, item.distance),
            )
        return tuple(sorted(merged.values(), key=lambda item: (-item.score, item.distance, item.node_id)))

    def _dedupe_diagnostics(self, diagnostics: Sequence[KnowledgeQueryDiagnostic]) -> tuple[KnowledgeQueryDiagnostic, ...]:
        result: list[KnowledgeQueryDiagnostic] = []
        seen: set[tuple[str, str, str | None]] = set()
        for item in diagnostics:
            key = (item.code, item.message, item.sourceId)
            if key in seen:
                continue
            seen.add(key)
            result.append(item)
        return tuple(result)

    def _family_sort_key(self, family: EntrypointFlow | FlowFamily) -> tuple[float, str, str, str]:
        return (
            -float(family.relevance_score or 0.0),
            family.key.source_id,
            family.key.graph_revision,
            family.key.entrypoint_node_id,
        )

    def _node_sort_key(self, node: FlowGraphNode, root_key: FlowNodeKey) -> tuple[int, str, int, int, str, str]:
        key = self._node_key(node)
        return (
            0 if key == root_key else 1,
            node.source_id,
            node.relative_path or "",
            node.line_start or 0,
            node.line_end or 0,
            node.node_id,
        )

    def _edge_sort_key(self, edge: FlowGraphEdge) -> tuple[str, str, str, str, str]:
        return (
            edge.source_id,
            edge.graph_revision or edge.graph_id,
            edge.from_node_id,
            edge.to_node_id or "",
            edge.edge_id,
        )

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

    def _symbol(self, node: FlowGraphNode) -> str:
        qualified = str(node.qualified_name or "").strip()
        if qualified:
            parts = [part for part in qualified.split(".") if part]
            if node.node_kind == "CALLABLE" and len(parts) >= 2:
                return ".".join(parts[-2:])
            return parts[-1] if node.node_kind == "CALLABLE" and parts else qualified
        return str(node.label or node.node_id)

    def _public_root_key(self, root: FlowNodeKey, flow_by_root: Mapping[FlowNodeKey, EntrypointFlow]) -> str:
        flow = flow_by_root.get(root)
        return f"{root[0]}:{self._symbol(flow.entrypoint) if flow else root[2]}"


def replace_family_evidence(family: FlowFamily, evidence: Sequence[FlowGraphEvidence]) -> FlowFamily:
    return replace(family, evidence=dedupe_evidence(tuple(evidence)))
