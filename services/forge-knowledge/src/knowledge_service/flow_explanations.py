from __future__ import annotations

import re
from typing import Any, Dict, List, Mapping, Sequence

from knowledge_service.entrypoint_kinds import tree_kind_for_entrypoint, trigger_kind_for_entrypoint
from knowledge_service.entrypoint_flow_engine import EntrypointFlow
from knowledge_service.flow_family import FlowFamily
from knowledge_service.flow_narrative import FlowNarrativeGap, FlowNarrativePartKind, FlowNarrativePlan
from knowledge_service.flow_boundary_classifier import FlowBoundaryClassifier, FLOW_BOUNDARY_CLASSIFIER
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode
from knowledge_service.operation_facts import AvailableOperationFact, normalize_http_method, normalize_route, normalize_transport_kind
from knowledge_service.knowledge_query_schema import (
    FlowToolEvidence,
    FlowToolFlow,
    FlowToolGap,
    FlowToolPart,
    FlowToolSupportingRelation,
    FlowToolTransition,
    FlowToolTrigger,
    FlowToolTree,
    FlowToolTreeItem,
    KnowledgeQueryDiagnostic,
    KnowledgeQueryRequest,
    KnowledgeQueryToolContextResponse,
)

class FlowProjectionBuilder:
    def __init__(self, boundary_classifier: FlowBoundaryClassifier | None = None) -> None:
        self.boundary_classifier = boundary_classifier or FLOW_BOUNDARY_CLASSIFIER

    def to_tool_response(self, request: KnowledgeQueryRequest, execution: Any) -> KnowledgeQueryToolContextResponse:
        narrative_plans = tuple(getattr(execution, "narrative_plans", ()) or ())
        if not narrative_plans:
            return KnowledgeQueryToolContextResponse(
                queryText=request.queryText,
                flows=[
                    FlowToolFlow(
                        source=str(flow.key.source_id or ""),
                        entrypoint=self._symbol(flow.entrypoint),
                        parts=[FlowToolPart(kind=FlowNarrativePartKind.VERIFIED_FRAGMENT.value, tree=self._tree(flow))],
                        complete=bool(flow.complete),
                        diagnostics=list(flow.diagnostics),
                    )
                    for flow in tuple(getattr(execution, "flows", ()) or ())
                ],
                diagnostics=self._diagnostics(execution),
            )
        return KnowledgeQueryToolContextResponse(
            queryText=request.queryText,
            flows=[self._tool_flow(plan) for plan in narrative_plans],
            diagnostics=self._diagnostics(execution),
        )

    def flow_answer_identity(self, flow: EntrypointFlow | FlowFamily | FlowNarrativePlan) -> tuple[str, str]:
        if isinstance(flow, FlowNarrativePlan):
            fragments = flow.fragments
            if not fragments:
                return "", ""
            return str(fragments[0].source_id or ""), self._symbol(fragments[0].root)
        return str(flow.key.source_id or ""), self._symbol(flow.entrypoint)

    def _tool_flow(self, plan: FlowNarrativePlan) -> FlowToolFlow:
        fragments = plan.fragments
        first = fragments[0] if fragments else None
        parts: list[FlowToolPart] = []
        for part in plan.parts:
            if part.kind is FlowNarrativePartKind.VERIFIED_FRAGMENT and part.fragment is not None:
                parts.append(FlowToolPart(kind=part.kind.value, tree=self._tree(part.fragment.family, part.fragment.operation_facts)))
            elif part.gap is not None:
                parts.append(FlowToolPart(kind=part.kind.value, gap=self._tool_gap(part.gap)))
        return FlowToolFlow(
            source=first.source_id if first is not None else None,
            entrypoint=self._symbol(first.root) if first is not None else None,
            parts=parts,
            complete=bool(plan.complete),
            diagnostics=list(plan.diagnostics),
        )

    def _tool_gap(self, gap: FlowNarrativeGap) -> FlowToolGap:
        return FlowToolGap(
            kind=gap.kind,
            verificationStatus=gap.verification_status.value,
            fromSource=gap.from_source,
            fromSymbol=gap.from_symbol,
            toSource=gap.to_source,
            toSymbol=gap.to_symbol,
            transportKind=gap.transport_kind,
            method=gap.method,
            route=gap.route,
            operationIdentity=gap.operation_identity,
            reason=gap.reason,
        )

    def _tree_item_dict(self, item: FlowToolTreeItem) -> Dict[str, Any]:
        data = item.dict(exclude_none=True)
        children = [
            self._tree_item_dict(child)
            for child in item.children
        ]
        data["children"] = children
        return data

    def _tree(
        self,
        flow: EntrypointFlow | FlowFamily,
        operation_facts: Sequence[AvailableOperationFact] = (),
    ) -> FlowToolTree:
        node_by_key = {self._node_key(node): node for node in flow.nodes}
        operation_facts_by_node = self._operation_facts_by_node(operation_facts)
        evidence_by_node: Dict[tuple[str, str], List[FlowGraphEvidence]] = {}
        evidence_by_edge: Dict[tuple[str, str], List[FlowGraphEvidence]] = {}
        for item in flow.evidence:
            if item.edge_id:
                evidence_by_edge.setdefault((item.source_id, item.edge_id), []).append(item)
            elif item.node_id:
                evidence_by_node.setdefault((item.source_id, item.node_id), []).append(item)
        outgoing: Dict[tuple[str, str, str], List[FlowGraphEdge]] = {}
        supporting_edges = tuple(getattr(flow, "supporting_transitions", ()) or ())
        for edge in sorted(flow.transitions, key=lambda item: self._edge_sort_key(item, evidence_by_edge)):
            outgoing.setdefault(self._from_key(edge), []).append(edge)
        boundaries: Dict[tuple[str, str, str], List[FlowGraphEdge]] = {}
        for edge in sorted(flow.boundary_transitions, key=lambda item: self._edge_sort_key(item, evidence_by_edge)):
            boundaries.setdefault(self._from_key(edge), []).append(edge)

        root_key = self._node_key(flow.entrypoint)
        root_source = flow.entrypoint.source_id
        root = self._node_item(
            flow.entrypoint,
            evidence_by_node.get((flow.entrypoint.source_id, flow.entrypoint.node_id), []),
            root_source=root_source,
            operation_facts=operation_facts_by_node.get(root_key, ()),
        )
        rendered = {root_key}
        stack: List[Dict[str, Any]] = [
            {
                "node": flow.entrypoint,
                "node_key": root_key,
                "item": root,
                "entries": self._sorted_child_edges(root_key, outgoing, boundaries, evidence_by_edge),
                "index": 0,
                "ancestry": {root_key},
            }
        ]
        while stack:
            frame = stack[-1]
            if frame["index"] >= len(frame["entries"]):
                stack.pop()
                continue
            entry = frame["entries"][frame["index"]]
            frame["index"] += 1
            edge_key = self._edge_key(entry)
            if entry in boundaries.get(frame["node_key"], []):
                frame["item"].children.append(self._boundary_item(entry, evidence_by_edge.get(edge_key, [])))
                continue
            target_key = self._to_key(entry)
            target = node_by_key.get(target_key) if target_key is not None else None
            if target is None:
                frame["item"].children.append(self._boundary_item(replace_edge_boundary(entry), evidence_by_edge.get(edge_key, [])))
                continue
            child_evidence = [*evidence_by_node.get((target.source_id, target.node_id), []), *evidence_by_edge.get(edge_key, [])]
            if target_key in frame["ancestry"]:
                frame["item"].children.append(
                    self._node_item(
                        target,
                        child_evidence,
                        root_source=root_source,
                        transition=entry,
                        cycle=True,
                        operation_facts=operation_facts_by_node.get(target_key, ()),
                    )
                )
                continue
            if target_key in rendered:
                frame["item"].children.append(
                    self._node_item(
                        target,
                        child_evidence,
                        root_source=root_source,
                        transition=entry,
                        shared=True,
                        operation_facts=operation_facts_by_node.get(target_key, ()),
                    )
                )
                continue
            child = self._node_item(
                target,
                child_evidence,
                root_source=root_source,
                transition=entry,
                operation_facts=operation_facts_by_node.get(target_key, ()),
            )
            frame["item"].children.append(child)
            rendered.add(target_key)
            stack.append(
                {
                    "node": target,
                    "node_key": target_key,
                    "item": child,
                    "entries": self._sorted_child_edges(target_key, outgoing, boundaries, evidence_by_edge),
                    "index": 0,
                    "ancestry": {*frame["ancestry"], target_key},
                }
            )
        for fact in self._external_operation_facts(operation_facts, node_by_key):
            root.children.append(self._operation_item(fact, root_source=root_source))
        return FlowToolTree(
            source=str(flow.key.source_id or ""),
            entrypoint=root,
            supportingRelations=self._supporting_relation_items(supporting_edges, node_by_key, evidence_by_edge, root_source=root_source),
        )

    def _sorted_child_edges(
        self,
        node_key: tuple[str, str, str],
        outgoing: Mapping[tuple[str, str, str], Sequence[FlowGraphEdge]],
        boundaries: Mapping[tuple[str, str, str], Sequence[FlowGraphEdge]],
        evidence_by_edge: Mapping[tuple[str, str], Sequence[FlowGraphEvidence]],
    ) -> List[FlowGraphEdge]:
        return sorted(
            [*outgoing.get(node_key, ()), *boundaries.get(node_key, ())],
            key=lambda item: self._edge_sort_key(item, evidence_by_edge),
        )

    def _supporting_relation_items(
        self,
        supporting_edges: Sequence[FlowGraphEdge],
        node_by_key: Mapping[tuple[str, str, str], FlowGraphNode],
        evidence_by_edge: Mapping[tuple[str, str], Sequence[FlowGraphEvidence]],
        *,
        root_source: str,
    ) -> list[FlowToolSupportingRelation]:
        items: list[FlowToolSupportingRelation] = []
        for edge in sorted(supporting_edges, key=lambda item: self._edge_sort_key(item, evidence_by_edge)):
            from_node = node_by_key.get(self._from_key(edge))
            to_key = self._to_key(edge)
            to_node = node_by_key.get(to_key) if to_key is not None else None
            symbol = self._symbol(to_node) if to_node is not None else (edge.to_node_id or edge.edge_id)
            source = from_node.source_id if from_node is not None else edge.source_id
            target_source = to_node.source_id if to_node is not None else (edge.to_source_id or edge.source_id)
            items.append(
                FlowToolSupportingRelation(
                    relation=edge.edge_type,
                    source=source if source != root_source else None,
                    targetSource=target_source if target_source != root_source or target_source != source else None,
                    symbol=symbol,
                    path=to_node.relative_path if to_node is not None else None,
                    lineStart=to_node.line_start if to_node is not None else None,
                    lineEnd=to_node.line_end if to_node is not None else None,
                    description=to_node.summary if to_node is not None else None,
                    evidence=[self._evidence(item) for item in evidence_by_edge.get(self._edge_key(edge), [])],
                )
            )
        return items

    def _node_item(
        self,
        node: FlowGraphNode,
        evidence: Sequence[FlowGraphEvidence],
        *,
        root_source: str | None = None,
        transition: FlowGraphEdge | None = None,
        cycle: bool = False,
        shared: bool = False,
        operation_facts: Sequence[AvailableOperationFact] = (),
    ) -> FlowToolTreeItem:
        return FlowToolTreeItem(
            source=node.source_id if root_source and node.source_id != root_source else None,
            symbol=self._symbol(node),
            kind=self._node_kind(node),
            trigger=self._trigger(node, operation_facts),
            transition=self._tool_transition(transition, node) if transition is not None else None,
            path=node.relative_path,
            lineStart=node.line_start,
            lineEnd=node.line_end,
            description=node.summary,
            evidence=[self._evidence(item) for item in evidence],
            children=[],
            cycle=True if cycle else None,
            shared=True if shared else None,
        )

    def _operation_item(self, fact: AvailableOperationFact, *, root_source: str) -> FlowToolTreeItem:
        return FlowToolTreeItem(
            source=fact.owner_source_id if fact.owner_source_id != root_source else None,
            symbol=self._operation_symbol(fact),
            kind="OPERATION",
            trigger=self._operation_trigger((fact,)),
            path=fact.owner_relative_path,
            evidence=[self._operation_evidence(item) for item in fact.evidence],
            children=[],
        )

    def _tool_transition(self, edge: FlowGraphEdge, target: FlowGraphNode | None = None) -> FlowToolTransition:
        metadata = edge.metadata if isinstance(edge.metadata, dict) else {}
        from_source = edge.source_id
        to_source = target.source_id if target is not None else (edge.to_source_id or edge.source_id)
        connector_kind = self._clean(metadata.get("connectorKind") if isinstance(metadata.get("connectorKind"), str) else None)
        http_method = self._clean(metadata.get("httpMethod") if isinstance(metadata.get("httpMethod"), str) else None)
        route = self._clean(metadata.get("routeTemplate") if isinstance(metadata.get("routeTemplate"), str) else None)
        return FlowToolTransition(
            edgeType=edge.edge_type,
            resolutionStatus=edge.resolution_status,
            crossSource=True if from_source != to_source else None,
            connectorKind=connector_kind,
            method=http_method,
            route=route,
        )

    def _boundary_item(self, edge: FlowGraphEdge, evidence: Sequence[FlowGraphEvidence]) -> FlowToolTreeItem:
        projection = self.boundary_classifier.project(edge)
        kind = "EXTERNAL_CALL" if projection.kind.value == "EXTERNAL" else "UNRESOLVED_CALL"
        symbol = self._boundary_symbol(edge, projection.target) or (
            "External call" if kind == "EXTERNAL_CALL" else "Unresolved call"
        )
        description = "Calls an external client boundary." if kind == "EXTERNAL_CALL" else None
        if edge.boundary_reason == "CURRENT_TARGET_NODE_MISSING":
            symbol = self._boundary_symbol(edge, projection.target) or "Target missing from current graph"
            description = "Target is missing from the current graph."
        return FlowToolTreeItem(
            symbol=symbol,
            kind=kind,
            path=None,
            lineStart=None,
            lineEnd=None,
            description=description,
            evidence=[self._evidence(item) for item in evidence],
            children=[],
        )

    def _boundary_symbol(self, edge: FlowGraphEdge, projected_target: str | None) -> str | None:
        target = edge.unresolved_target or {}
        if not isinstance(target, dict):
            return self._compact_symbol(projected_target)
        for key in ("qualifiedName", "target", "displayName", "label", "symbol"):
            value = self._clean(target.get(key) if isinstance(target.get(key), str) else None)
            if value:
                return self._compact_symbol(value)
        name = self._clean(target.get("name") if isinstance(target.get("name"), str) else None)
        for key in ("interfaceType", "receiverTypeHint", "targetTypeText"):
            owner = self._clean(target.get(key) if isinstance(target.get(key), str) else None)
            if owner and name and owner != name and self._looks_like_symbol(owner):
                return f"{self._compact_symbol(owner)}.{name}"
        return self._compact_symbol(projected_target or name)

    def _compact_symbol(self, value: str | None) -> str | None:
        normalized = self._clean(value)
        if not normalized:
            return None
        parts = [part for part in normalized.split(".") if part]
        if len(parts) >= 2:
            return ".".join(parts[-2:])
        return normalized

    def _looks_like_symbol(self, value: str) -> bool:
        return re.match(r"^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*$", value) is not None

    def _evidence(self, item: FlowGraphEvidence) -> FlowToolEvidence:
        return FlowToolEvidence(
            path=item.relative_path,
            lineStart=item.line_start,
            lineEnd=item.line_end,
            excerpt=item.text,
        )

    def _symbol(self, node: FlowGraphNode) -> str:
        qualified = str(node.qualified_name or "").strip()
        if qualified:
            parts = [part for part in qualified.split(".") if part]
            if node.node_kind == "CALLABLE" and len(parts) >= 2:
                return ".".join(parts[-2:])
            if node.node_kind == "CALLABLE":
                return parts[-1] if parts else qualified
            return qualified
        return str(node.label or node.node_id)

    def _node_kind(self, node: FlowGraphNode) -> str:
        if node.entrypoint:
            return self._entrypoint_kind(node.entrypoint_kind)
        if node.node_kind == "CALLABLE":
            return "METHOD"
        return node.node_kind

    def _entrypoint_kind(self, value: str | None) -> str:
        return tree_kind_for_entrypoint(value)

    def _trigger(
        self,
        node: FlowGraphNode,
        operation_facts: Sequence[AvailableOperationFact] = (),
    ) -> FlowToolTrigger | None:
        operation_trigger = self._operation_trigger(operation_facts)
        if operation_trigger is not None and not node.entrypoint:
            return operation_trigger
        if not node.entrypoint:
            return None
        trigger_kind = trigger_kind_for_entrypoint(node.entrypoint_kind)
        if trigger_kind is None:
            return operation_trigger
        return FlowToolTrigger(
            kind=trigger_kind,
            method=self._clean(node.entrypoint_http_method),
            route=self._clean(node.entrypoint_route),
            topic=self._clean(node.entrypoint_topic),
            schedule=self._clean(node.entrypoint_schedule),
            interfaceMethod=self._clean(node.entrypoint_interface_method),
        )

    def _operation_facts_by_node(
        self,
        operation_facts: Sequence[AvailableOperationFact],
    ) -> Dict[tuple[str, str, str], tuple[AvailableOperationFact, ...]]:
        grouped: Dict[tuple[str, str, str], List[AvailableOperationFact]] = {}
        for fact in operation_facts:
            grouped.setdefault(fact.owner_key, []).append(fact)
        return {
            key: tuple(sorted(values, key=self._operation_fact_sort_key))
            for key, values in grouped.items()
        }

    def _external_operation_facts(
        self,
        operation_facts: Sequence[AvailableOperationFact],
        node_by_key: Mapping[tuple[str, str, str], FlowGraphNode],
    ) -> tuple[AvailableOperationFact, ...]:
        return tuple(
            sorted(
                (
                    fact
                    for fact in operation_facts
                    if fact.owner_key not in node_by_key
                    and str(fact.direction_role or "") == "OUTBOUND"
                ),
                key=self._operation_fact_sort_key,
            )
        )

    def _operation_symbol(self, fact: AvailableOperationFact) -> str:
        qualified = str(fact.owner_qualified_name or "").strip()
        if qualified:
            parts = [part for part in qualified.split(".") if part]
            if len(parts) >= 2:
                return ".".join(parts[-2:])
            return parts[-1] if parts else qualified
        identity = fact.interface_identity or fact.operation_identity
        if identity:
            return str(identity)
        method = normalize_http_method(fact.method)
        route = normalize_route(fact.normalized_route)
        return " ".join(part for part in (method, route) if part) or fact.owner_node_id

    def _operation_evidence(self, item: Any) -> FlowToolEvidence:
        return FlowToolEvidence(
            path=getattr(item, "relative_path", None),
            lineStart=getattr(item, "line_start", None),
            lineEnd=getattr(item, "line_end", None),
            excerpt=getattr(item, "excerpt", None),
        )

    def _operation_trigger(self, operation_facts: Sequence[AvailableOperationFact]) -> FlowToolTrigger | None:
        for fact in sorted(operation_facts, key=self._operation_fact_sort_key):
            transport = normalize_transport_kind(fact.transport_kind)
            if not transport:
                continue
            return FlowToolTrigger(
                kind=transport,
                method=normalize_http_method(fact.method),
                route=normalize_route(fact.normalized_route),
                topic=self._clean(fact.topic),
                schedule=self._clean(fact.schedule),
                interfaceMethod=self._clean(fact.interface_identity or fact.operation_identity),
            )
        return None

    def _operation_fact_sort_key(self, fact: AvailableOperationFact) -> tuple[int, str, str, str, str, str]:
        direction_rank = {"OUTBOUND": 0, "INBOUND": 1, "SUPPORTING": 2}.get(str(fact.direction_role or ""), 3)
        return (
            direction_rank,
            normalize_transport_kind(fact.transport_kind) or "",
            normalize_http_method(fact.method) or "",
            normalize_route(fact.normalized_route) or "",
            fact.operation_identity or fact.interface_identity or "",
            fact.structural_owner,
        )

    def _unresolved_target_name(self, edge: FlowGraphEdge) -> str | None:
        target = edge.unresolved_target or {}
        for key in ("name", "qualifiedName", "targetTypeText", "receiverTypeHint"):
            value = target.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        return None

    def _edge_sort_key(
        self,
        edge: FlowGraphEdge,
        evidence_by_edge: Mapping[tuple[str, str], Sequence[FlowGraphEvidence]] | None = None,
    ) -> tuple[str, int, int, str, str, str]:
        line_starts = [
            item.line_start
            for item in (evidence_by_edge or {}).get(self._edge_key(edge), ())
            if item.line_start is not None
        ]
        first_line = min(line_starts) if line_starts else 1_000_000_000
        return (edge.from_node_id, first_line, 0 if line_starts else 1, edge.to_node_id or "", edge.edge_id, edge.resolution_status)

    def _node_key(self, node: FlowGraphNode) -> tuple[str, str, str]:
        return (node.source_id, node.graph_revision or node.graph_id, node.node_id)

    def _edge_key(self, edge: FlowGraphEdge) -> tuple[str, str]:
        return (edge.source_id, edge.edge_id)

    def _from_key(self, edge: FlowGraphEdge) -> tuple[str, str, str]:
        return (edge.source_id, edge.graph_revision or edge.graph_id, edge.from_node_id)

    def _to_key(self, edge: FlowGraphEdge) -> tuple[str, str, str] | None:
        if not edge.to_node_id:
            return None
        return (
            edge.to_source_id or edge.source_id,
            edge.to_graph_revision or edge.to_graph_id or edge.graph_revision or edge.graph_id,
            edge.to_node_id,
        )

    def _clean(self, value: str | None) -> str | None:
        normalized = str(value or "").strip()
        return normalized or None

    def _without_none(self, value: Dict[str, Any]) -> Dict[str, Any]:
        result: Dict[str, Any] = {}
        for key, item in value.items():
            if item is None:
                continue
            if isinstance(item, dict):
                nested = self._without_none(item)
                if nested:
                    result[key] = nested
            elif isinstance(item, list) and not item:
                continue
            else:
                result[key] = item
        return result

    def _diagnostics(self, execution: Any) -> List[KnowledgeQueryDiagnostic]:
        response = getattr(execution, "response", None)
        diagnostics = list(getattr(response, "diagnostics", []) or [])
        for flow in tuple(getattr(execution, "flows", ()) or ()):
            diagnostics.extend(flow.diagnostics)
        for narrative_plan in tuple(getattr(execution, "narrative_plans", ()) or ()):
            diagnostics.extend(narrative_plan.diagnostics)
        return [
            self._compact_diagnostic(item)
            for item in diagnostics
            if not str(item.code).startswith("SEMANTIC_") and item.code != "ENTRYPOINT_FLOW_TIMINGS"
        ]

    def _compact_diagnostic(self, diagnostic: KnowledgeQueryDiagnostic) -> KnowledgeQueryDiagnostic:
        return KnowledgeQueryDiagnostic(
            code=diagnostic.code,
            message=diagnostic.message,
            severity=diagnostic.severity,
            sourceId=diagnostic.sourceId,
            metadata={},
        )


def replace_edge_boundary(edge: FlowGraphEdge) -> FlowGraphEdge:
    from dataclasses import replace

    return replace(edge, boundary_reason="CURRENT_TARGET_NODE_MISSING")
