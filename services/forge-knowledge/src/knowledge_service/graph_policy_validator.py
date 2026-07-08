from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, Iterable, List, Mapping, Optional

from knowledge_service.analysis_graph_contract import AnalysisGraphContract, ResolutionStatusContract
from knowledge_service.analysis_policy import AnalysisPolicy, ExtractorDefinition
from knowledge_service.errors import KnowledgeError
from knowledge_service.graph_schema import GraphAnalysisResult, GraphClaim, GraphEdge, GraphEvidenceRef, GraphNode


EXTRACTOR_OUTPUT_INVALID = "ANALYSIS_EXTRACTOR_OUTPUT_INVALID"
GRAPH_POLICY_VALIDATION_FAILED = "ANALYSIS_GRAPH_POLICY_VALIDATION_FAILED"


@dataclass(frozen=True)
class GraphPolicyValidationIssue:
    message: str
    stage: str
    entity_type: str
    entity_id: Optional[str]
    field: str
    actual: Any = None
    allowed_values: Optional[List[str]] = None
    path: Optional[str] = None
    severity: str = "ERROR"

    def to_dict(self) -> Dict[str, Any]:
        payload: Dict[str, Any] = {
            "message": self.message,
            "stage": self.stage,
            "entityType": self.entity_type,
            "field": self.field,
            "severity": self.severity,
        }
        if self.entity_id is not None:
            payload["entityId"] = self.entity_id
        if self.actual is not None:
            payload["actual"] = self.actual
        if self.allowed_values is not None:
            payload["allowedValues"] = list(self.allowed_values)
        if self.path is not None:
            payload["path"] = self.path
        return payload


class GraphPolicyValidator:
    def __init__(self, policy: AnalysisPolicy) -> None:
        self.policy = policy

    def validate_extractor_output(
        self,
        graph_result: GraphAnalysisResult,
        contract: AnalysisGraphContract,
        extractor: ExtractorDefinition,
        line_count: int,
        *,
        relative_path: str,
        extractor_id: Optional[str] = None,
        implementation: Optional[str] = None,
        used_fallback: bool = False,
    ) -> None:
        issues = self._validate_graph(
            graph_result,
            contract,
            line_count,
            stage="STATIC_EXTRACTION",
            extractor=extractor,
        )
        if issues:
            self._raise(
                EXTRACTOR_OUTPUT_INVALID,
                "Static extractor output violates the analysis graph policy.",
                issues,
                relative_path=relative_path,
                stage="STATIC_EXTRACTION",
                extractor_id=extractor_id or extractor.id,
                implementation=implementation or extractor.implementation,
                used_fallback=used_fallback,
            )

    def validate_llm_enrichment(
        self,
        graph_result: GraphAnalysisResult,
        contract: AnalysisGraphContract,
        line_count: int,
        *,
        relative_path: str,
        static_graph: GraphAnalysisResult,
    ) -> None:
        topology_issues = [
            GraphPolicyValidationIssue(
                message="LLM enrichment must not emit graph edges; graph topology is static/backend-owned.",
                stage="LLM_ENRICHMENT",
                entity_type="edge",
                entity_id=edge.localId or None,
                field="edges",
                actual=edge.edgeType,
                allowed_values=[],
                path=f"$.edges[{index}]",
            )
            for index, edge in enumerate(graph_result.edges)
        ]
        if topology_issues:
            self._raise(
                GRAPH_POLICY_VALIDATION_FAILED,
                "LLM graph enrichment violates the analysis graph policy.",
                topology_issues,
                relative_path=relative_path,
                stage="LLM_ENRICHMENT",
            )
        issues = self._validate_graph(
            graph_result,
            contract,
            line_count,
            stage="LLM_ENRICHMENT",
            known_nodes=list(static_graph.nodes),
        )
        if issues:
            self._raise(
                GRAPH_POLICY_VALIDATION_FAILED,
                "LLM graph enrichment violates the analysis graph policy.",
                issues,
                relative_path=relative_path,
                stage="LLM_ENRICHMENT",
            )

    def validate_final_graph(
        self,
        graph_result: GraphAnalysisResult,
        contract: AnalysisGraphContract,
        line_count: int,
        *,
        relative_path: str,
    ) -> None:
        issues = self._validate_graph(
            graph_result,
            contract,
            line_count,
            stage="GRAPH_VALIDATION",
        )
        if issues:
            self._raise(
                GRAPH_POLICY_VALIDATION_FAILED,
                "Final graph analysis result violates the analysis graph policy.",
                issues,
                relative_path=relative_path,
                stage="GRAPH_VALIDATION",
            )

    def _validate_graph(
        self,
        graph_result: GraphAnalysisResult,
        contract: AnalysisGraphContract,
        line_count: int,
        *,
        stage: str,
        extractor: Optional[ExtractorDefinition] = None,
        known_nodes: Optional[List[GraphNode]] = None,
    ) -> List[GraphPolicyValidationIssue]:
        issues: List[GraphPolicyValidationIssue] = []
        nodes_by_id = self._nodes_by_id([*(known_nodes or []), *graph_result.nodes])
        result_node_ids = {node.localId for node in graph_result.nodes}
        self._validate_nodes(graph_result.nodes, contract, line_count, stage, extractor, nodes_by_id, result_node_ids, issues)
        self._validate_edges(graph_result.edges, contract, line_count, stage, extractor, nodes_by_id, issues)
        self._validate_claims(graph_result.claims, contract, line_count, stage, extractor, nodes_by_id, issues)
        self._validate_diagnostics(graph_result.diagnostics, stage, issues)
        return issues

    def _validate_nodes(
        self,
        nodes: List[GraphNode],
        contract: AnalysisGraphContract,
        line_count: int,
        stage: str,
        extractor: Optional[ExtractorDefinition],
        nodes_by_id: Mapping[str, GraphNode],
        result_node_ids: set[str],
        issues: List[GraphPolicyValidationIssue],
    ) -> None:
        for index, node in enumerate(nodes):
            path = f"$.nodes[{index}]"
            entity_id = node.localId or None
            self._require_non_empty(node.localId, "node", entity_id, "localId", stage, path, issues)
            self._require_non_empty(node.name, "node", entity_id, "name", stage, f"{path}.name", issues)
            self._validate_kind(
                node.nodeKind,
                "node",
                entity_id,
                "nodeKind",
                stage,
                f"{path}.nodeKind",
                declared=self.policy.graph.nodes,
                allowed=contract.allowed_node_kinds,
                produced=extractor.produces.nodes if extractor is not None else None,
                issues=issues,
            )
            self._validate_optional_line_range(
                node.lineStart,
                node.lineEnd,
                line_count,
                "node",
                entity_id,
                "lineStart",
                stage,
                f"{path}.lineStart",
                issues,
            )
            self._validate_metadata_contract(
                node.metadata,
                contract,
                "node",
                entity_id,
                stage,
                f"{path}.metadata",
                issues,
            )
            if node.parentLocalId and node.parentLocalId not in nodes_by_id:
                issues.append(
                    GraphPolicyValidationIssue(
                        message="Graph node references an unknown parentLocalId.",
                        stage=stage,
                        entity_type="node",
                        entity_id=entity_id,
                        field="parentLocalId",
                        actual=node.parentLocalId,
                        allowed_values=sorted(nodes_by_id.keys()),
                        path=f"{path}.parentLocalId",
                    )
                )
            if node.parentLocalId and node.parentLocalId not in result_node_ids and stage != "LLM_ENRICHMENT":
                issues.append(
                    GraphPolicyValidationIssue(
                        message="Graph node parentLocalId must be present in the validated graph.",
                        stage=stage,
                        entity_type="node",
                        entity_id=entity_id,
                        field="parentLocalId",
                        actual=node.parentLocalId,
                        allowed_values=sorted(result_node_ids),
                        path=f"{path}.parentLocalId",
                    )
                )

    def _validate_edges(
        self,
        edges: List[GraphEdge],
        contract: AnalysisGraphContract,
        line_count: int,
        stage: str,
        extractor: Optional[ExtractorDefinition],
        nodes_by_id: Mapping[str, GraphNode],
        issues: List[GraphPolicyValidationIssue],
    ) -> None:
        for index, edge in enumerate(edges):
            path = f"$.edges[{index}]"
            entity_id = edge.localId or None
            self._require_non_empty(edge.localId, "edge", entity_id, "localId", stage, path, issues)
            self._require_non_empty(edge.fromNodeLocalId, "edge", entity_id, "fromNodeLocalId", stage, f"{path}.fromNodeLocalId", issues)
            self._validate_kind(
                edge.edgeType,
                "edge",
                entity_id,
                "edgeType",
                stage,
                f"{path}.edgeType",
                declared=self.policy.graph.edges,
                allowed=contract.allowed_edge_types,
                produced=extractor.produces.edges if extractor is not None else None,
                issues=issues,
            )
            from_node = nodes_by_id.get(edge.fromNodeLocalId)
            to_node = nodes_by_id.get(edge.toNodeLocalId) if edge.toNodeLocalId else None
            if from_node is None:
                issues.append(
                    GraphPolicyValidationIssue(
                        message="Graph edge references an unknown fromNodeLocalId.",
                        stage=stage,
                        entity_type="edge",
                        entity_id=entity_id,
                        field="fromNodeLocalId",
                        actual=edge.fromNodeLocalId,
                        allowed_values=sorted(nodes_by_id.keys()),
                        path=f"{path}.fromNodeLocalId",
                    )
                )
            if edge.toNodeLocalId and to_node is None:
                issues.append(
                    GraphPolicyValidationIssue(
                        message="Graph edge references an unknown toNodeLocalId.",
                        stage=stage,
                        entity_type="edge",
                        entity_id=entity_id,
                        field="toNodeLocalId",
                        actual=edge.toNodeLocalId,
                        allowed_values=sorted(nodes_by_id.keys()),
                        path=f"{path}.toNodeLocalId",
                    )
                )
            if edge.edgeType in self.policy.graph.edges and edge.edgeType in contract.allowed_edge_types:
                self._validate_edge_endpoint(edge, from_node, to_node, contract, stage, path, issues)
            self._validate_edge_resolution_status(edge, contract, stage, path, issues)
            self._validate_metadata_contract(
                edge.metadata,
                contract,
                "edge",
                entity_id,
                stage,
                f"{path}.metadata",
                issues,
            )
            for evidence_index, evidence in enumerate(edge.evidence):
                self._validate_evidence_range(
                    evidence,
                    line_count,
                    "edge",
                    entity_id,
                    "evidence",
                    stage,
                    f"{path}.evidence[{evidence_index}]",
                    issues,
                )
                self._validate_metadata_contract(
                    evidence.metadata,
                    contract,
                    "evidence",
                    self._evidence_entity_id(entity_id, evidence_index),
                    stage,
                    f"{path}.evidence[{evidence_index}].metadata",
                    issues,
                )

    def _validate_claims(
        self,
        claims: List[GraphClaim],
        contract: AnalysisGraphContract,
        line_count: int,
        stage: str,
        extractor: Optional[ExtractorDefinition],
        nodes_by_id: Mapping[str, GraphNode],
        issues: List[GraphPolicyValidationIssue],
    ) -> None:
        for index, claim in enumerate(claims):
            path = f"$.claims[{index}]"
            entity_id = claim.localId or None
            self._require_non_empty(claim.localId, "claim", entity_id, "localId", stage, path, issues)
            self._require_non_empty(claim.nodeLocalId, "claim", entity_id, "nodeLocalId", stage, f"{path}.nodeLocalId", issues)
            self._require_non_empty(claim.summary, "claim", entity_id, "summary", stage, f"{path}.summary", issues)
            self._validate_kind(
                claim.claimKind,
                "claim",
                entity_id,
                "claimKind",
                stage,
                f"{path}.claimKind",
                declared=self.policy.graph.claims,
                allowed=contract.allowed_claim_kinds,
                produced=extractor.produces.claims if extractor is not None else None,
                issues=issues,
            )
            if claim.nodeLocalId not in nodes_by_id:
                issues.append(
                    GraphPolicyValidationIssue(
                        message="Graph claim references an unknown nodeLocalId.",
                        stage=stage,
                        entity_type="claim",
                        entity_id=entity_id,
                        field="nodeLocalId",
                        actual=claim.nodeLocalId,
                        allowed_values=sorted(nodes_by_id.keys()),
                        path=f"{path}.nodeLocalId",
                    )
                )
            if self._claim_requires_evidence(claim.claimKind, contract) and not claim.evidence:
                issues.append(
                    GraphPolicyValidationIssue(
                        message="Graph claim evidence is required by the analysis graph contract.",
                        stage=stage,
                        entity_type="claim",
                        entity_id=entity_id,
                        field="evidence",
                        actual=[],
                        path=f"{path}.evidence",
                    )
                )
            self._validate_metadata_contract(
                claim.metadata,
                contract,
                "claim",
                entity_id,
                stage,
                f"{path}.metadata",
                issues,
            )
            for evidence_index, evidence in enumerate(claim.evidence):
                self._validate_evidence_range(
                    evidence,
                    line_count,
                    "claim",
                    entity_id,
                    "evidence",
                    stage,
                    f"{path}.evidence[{evidence_index}]",
                    issues,
                )
                self._validate_metadata_contract(
                    evidence.metadata,
                    contract,
                    "evidence",
                    self._evidence_entity_id(entity_id, evidence_index),
                    stage,
                    f"{path}.evidence[{evidence_index}].metadata",
                    issues,
                )

    def _validate_diagnostics(self, diagnostics: Iterable[Mapping[str, Any]], stage: str, issues: List[GraphPolicyValidationIssue]) -> None:
        for index, diagnostic in enumerate(diagnostics or []):
            path = f"$.diagnostics[{index}]"
            if not isinstance(diagnostic, Mapping):
                issues.append(
                    GraphPolicyValidationIssue(
                        message="Graph diagnostic must be a mapping.",
                        stage=stage,
                        entity_type="diagnostic",
                        entity_id=None,
                        field="diagnostic",
                        actual=type(diagnostic).__name__,
                        path=path,
                    )
                )
                continue
            entity_id = str(diagnostic.get("code") or index)
            for field_name in ("code", "message", "severity", "stage"):
                self._require_non_empty(
                    diagnostic.get(field_name),
                    "diagnostic",
                    entity_id,
                    field_name,
                    stage,
                    f"{path}.{field_name}",
                    issues,
                )

    def _validate_kind(
        self,
        value: str,
        entity_type: str,
        entity_id: Optional[str],
        field: str,
        stage: str,
        path: str,
        *,
        declared: Mapping[str, Any],
        allowed: Iterable[str],
        produced: Optional[Iterable[str]],
        issues: List[GraphPolicyValidationIssue],
    ) -> None:
        if not value:
            self._require_non_empty(value, entity_type, entity_id, field, stage, path, issues)
            return
        declared_values = tuple(declared.keys())
        if value not in declared:
            issues.append(
                GraphPolicyValidationIssue(
                    message=f"{field} is not declared by the YAML graph contract.",
                    stage=stage,
                    entity_type=entity_type,
                    entity_id=entity_id,
                    field=field,
                    actual=value,
                    allowed_values=sorted(declared_values),
                    path=path,
                )
            )
            return
        if produced is not None and value not in set(produced):
            issues.append(
                GraphPolicyValidationIssue(
                    message=f"{field} is not listed in extractor.produces.",
                    stage=stage,
                    entity_type=entity_type,
                    entity_id=entity_id,
                    field=field,
                    actual=value,
                    allowed_values=list(produced),
                    path=path,
                )
            )
            return
        allowed_values = tuple(allowed)
        if value not in allowed_values:
            issues.append(
                GraphPolicyValidationIssue(
                    message=f"{field} is not allowed by the effective analysis graph profiles.",
                    stage=stage,
                    entity_type=entity_type,
                    entity_id=entity_id,
                    field=field,
                    actual=value,
                    allowed_values=list(allowed_values),
                    path=path,
                )
            )

    def _validate_metadata_contract(
        self,
        metadata: Mapping[str, Any],
        contract: AnalysisGraphContract,
        entity_type: str,
        entity_id: Optional[str],
        stage: str,
        path: str,
        issues: List[GraphPolicyValidationIssue],
    ) -> None:
        if metadata is None:
            return
        if not isinstance(metadata, Mapping):
            issues.append(
                GraphPolicyValidationIssue(
                    message="Graph metadata must be a mapping.",
                    stage=stage,
                    entity_type=entity_type,
                    entity_id=entity_id,
                    field="metadata",
                    actual=type(metadata).__name__,
                    path=path,
                )
            )
            return
        self._validate_metadata_value(
            metadata.get("status"),
            field="metadata.status",
            path=f"{path}.status",
            allowed=contract.allowed_statuses,
            message="metadata.status is not declared by the analysis policy.",
            entity_type=entity_type,
            entity_id=entity_id,
            stage=stage,
            issues=issues,
        )
        self._validate_metadata_value(
            metadata.get("factOrigin"),
            field="metadata.factOrigin",
            path=f"{path}.factOrigin",
            allowed=contract.allowed_origins,
            message="metadata.factOrigin is not declared by the analysis policy.",
            entity_type=entity_type,
            entity_id=entity_id,
            stage=stage,
            issues=issues,
        )
        self._validate_metadata_value(
            metadata.get("evidenceKind"),
            field="metadata.evidenceKind",
            path=f"{path}.evidenceKind",
            allowed=contract.allowed_evidence_kinds,
            message="metadata.evidenceKind is not declared by the analysis policy.",
            entity_type=entity_type,
            entity_id=entity_id,
            stage=stage,
            issues=issues,
        )

    def _validate_metadata_value(
        self,
        value: Any,
        *,
        field: str,
        path: str,
        allowed: Iterable[str],
        message: str,
        entity_type: str,
        entity_id: Optional[str],
        stage: str,
        issues: List[GraphPolicyValidationIssue],
    ) -> None:
        if value is None:
            return
        allowed_values = tuple(allowed)
        if str(value) not in allowed_values:
            issues.append(
                GraphPolicyValidationIssue(
                    message=message,
                    stage=stage,
                    entity_type=entity_type,
                    entity_id=entity_id,
                    field=field,
                    actual=value,
                    allowed_values=list(allowed_values),
                    path=path,
                )
            )

    def _validate_edge_endpoint(
        self,
        edge: GraphEdge,
        from_node: Optional[GraphNode],
        to_node: Optional[GraphNode],
        contract: AnalysisGraphContract,
        stage: str,
        path: str,
        issues: List[GraphPolicyValidationIssue],
    ) -> None:
        allowed_from = tuple(contract.edge_from_kinds.get(edge.edgeType, ()))
        allowed_to = tuple(contract.edge_to_kinds.get(edge.edgeType, ()))
        if from_node is not None and allowed_from and from_node.nodeKind not in allowed_from:
            issues.append(
                GraphPolicyValidationIssue(
                    message="Graph edge source node kind violates the YAML endpoint rule.",
                    stage=stage,
                    entity_type="edge",
                    entity_id=edge.localId,
                    field="fromNodeLocalId",
                    actual=from_node.nodeKind,
                    allowed_values=list(allowed_from),
                    path=f"{path}.fromNodeLocalId",
                )
            )
        if to_node is not None and allowed_to and to_node.nodeKind not in allowed_to:
            issues.append(
                GraphPolicyValidationIssue(
                    message="Graph edge target node kind violates the YAML endpoint rule.",
                    stage=stage,
                    entity_type="edge",
                    entity_id=edge.localId,
                    field="toNodeLocalId",
                    actual=to_node.nodeKind,
                    allowed_values=list(allowed_to),
                    path=f"{path}.toNodeLocalId",
                )
            )
        if edge.toNodeLocalId and not allowed_to:
            issues.append(
                GraphPolicyValidationIssue(
                    message="Graph edge target node is not allowed for this edge type by the YAML endpoint rule.",
                    stage=stage,
                    entity_type="edge",
                    entity_id=edge.localId,
                    field="toNodeLocalId",
                    actual=edge.toNodeLocalId,
                    allowed_values=[],
                    path=f"{path}.toNodeLocalId",
                )
            )

    def _validate_edge_resolution_status(
        self,
        edge: GraphEdge,
        contract: AnalysisGraphContract,
        stage: str,
        path: str,
        issues: List[GraphPolicyValidationIssue],
    ) -> None:
        value = edge.resolutionStatus
        if value is None:
            return
        allowed_values = tuple(contract.allowed_resolution_statuses)
        status = str(value)
        if status not in allowed_values:
            issues.append(
                GraphPolicyValidationIssue(
                    message="resolutionStatus is not declared by the analysis policy.",
                    stage=stage,
                    entity_type="edge",
                    entity_id=edge.localId,
                    field="resolutionStatus",
                    actual=value,
                    allowed_values=list(allowed_values),
                    path=f"{path}.resolutionStatus",
                )
            )
            return

        resolution_rules = ResolutionStatusContract.from_graph_contract(contract)
        if resolution_rules.requires_to_ref(status) and not edge.toNodeLocalId:
            issues.append(
                GraphPolicyValidationIssue(
                    message="Edge resolution requires toNodeLocalId.",
                    stage=stage,
                    entity_type="edge",
                    entity_id=edge.localId,
                    field="resolutionStatus",
                    actual=status,
                    path=f"{path}.resolutionStatus",
                )
            )
        if resolution_rules.forbids_to_ref(status) and edge.toNodeLocalId:
            issues.append(
                GraphPolicyValidationIssue(
                    message="Edge resolution must not have toNodeLocalId.",
                    stage=stage,
                    entity_type="edge",
                    entity_id=edge.localId,
                    field="resolutionStatus",
                    actual=status,
                    path=f"{path}.resolutionStatus",
                )
            )
        if resolution_rules.requires_unresolved_target(status) and not edge.unresolvedTarget:
            issues.append(
                GraphPolicyValidationIssue(
                    message="Edge resolution requires unresolvedTarget.",
                    stage=stage,
                    entity_type="edge",
                    entity_id=edge.localId,
                    field="unresolvedTarget",
                    actual=edge.unresolvedTarget,
                    path=f"{path}.unresolvedTarget",
                )
            )
        if edge.unresolvedTarget and not resolution_rules.allows_unresolved_target(status):
            issues.append(
                GraphPolicyValidationIssue(
                    message="Edge resolution must not include unresolvedTarget.",
                    stage=stage,
                    entity_type="edge",
                    entity_id=edge.localId,
                    field="unresolvedTarget",
                    actual=edge.unresolvedTarget,
                    path=f"{path}.unresolvedTarget",
                )
            )

    def _validate_optional_line_range(
        self,
        line_start: Optional[int],
        line_end: Optional[int],
        line_count: int,
        entity_type: str,
        entity_id: Optional[str],
        field: str,
        stage: str,
        path: str,
        issues: List[GraphPolicyValidationIssue],
    ) -> None:
        if line_start is None and line_end is None:
            return
        if line_start is None or line_end is None:
            issues.append(
                GraphPolicyValidationIssue(
                    message="Line range must include both start and end.",
                    stage=stage,
                    entity_type=entity_type,
                    entity_id=entity_id,
                    field=field,
                    actual={"lineStart": line_start, "lineEnd": line_end},
                    path=path,
                )
            )
            return
        self._validate_range(line_start, line_end, line_count, entity_type, entity_id, field, stage, path, issues)

    def _validate_evidence_range(
        self,
        evidence: GraphEvidenceRef,
        line_count: int,
        entity_type: str,
        entity_id: Optional[str],
        field: str,
        stage: str,
        path: str,
        issues: List[GraphPolicyValidationIssue],
    ) -> None:
        self._validate_range(evidence.lineStart, evidence.lineEnd, line_count, entity_type, entity_id, field, stage, path, issues)

    def _validate_range(
        self,
        line_start: int,
        line_end: int,
        line_count: int,
        entity_type: str,
        entity_id: Optional[str],
        field: str,
        stage: str,
        path: str,
        issues: List[GraphPolicyValidationIssue],
    ) -> None:
        if line_start < 1 or line_end < line_start or line_end > max(line_count, 1):
            issues.append(
                GraphPolicyValidationIssue(
                    message="Line range outside file.",
                    stage=stage,
                    entity_type=entity_type,
                    entity_id=entity_id,
                    field=field,
                    actual={"lineStart": line_start, "lineEnd": line_end, "lineCount": line_count},
                    path=path,
                )
            )

    def _evidence_entity_id(self, owner_id: Optional[str], evidence_index: int) -> Optional[str]:
        if owner_id is None:
            return None
        return f"{owner_id}:evidence:{evidence_index + 1}"

    def _require_non_empty(
        self,
        value: Any,
        entity_type: str,
        entity_id: Optional[str],
        field: str,
        stage: str,
        path: str,
        issues: List[GraphPolicyValidationIssue],
    ) -> None:
        if isinstance(value, str) and value.strip():
            return
        if value is not None and not isinstance(value, str):
            return
        issues.append(
            GraphPolicyValidationIssue(
                message=f"{field} is required.",
                stage=stage,
                entity_type=entity_type,
                entity_id=entity_id,
                field=field,
                actual=value,
                path=path,
            )
        )

    def _claim_requires_evidence(self, claim_kind: str, contract: AnalysisGraphContract) -> bool:
        claim_definition = self.policy.graph.claims.get(claim_kind)
        if claim_definition is None:
            return contract.evidence_required
        return bool(contract.evidence_required or claim_definition.evidence_required or claim_definition.material_support_required)

    def _nodes_by_id(self, nodes: Iterable[GraphNode]) -> Dict[str, GraphNode]:
        result: Dict[str, GraphNode] = {}
        for node in nodes:
            if node.localId:
                result[node.localId] = node
        return result

    def _raise(
        self,
        code: str,
        message: str,
        issues: List[GraphPolicyValidationIssue],
        *,
        relative_path: str,
        stage: str,
        extractor_id: Optional[str] = None,
        implementation: Optional[str] = None,
        used_fallback: bool = False,
    ) -> None:
        first = issues[0]
        details: Dict[str, Any] = {
            "stage": stage,
            "severity": first.severity,
            "relativePath": relative_path,
            "entityType": first.entity_type,
            "entityId": first.entity_id,
            "field": first.field,
            "actual": first.actual,
            "allowedValues": first.allowed_values or [],
            "validationErrors": [issue.to_dict() for issue in issues[:25]],
        }
        if extractor_id is not None:
            details["extractorId"] = extractor_id
        if implementation is not None:
            details["implementation"] = implementation
        if used_fallback:
            details["extractorFallbackUsed"] = True
        raise KnowledgeError(code, f"{message} {first.message}", **details)
