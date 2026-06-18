from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from knowledge_service.graph_schema import (
    GraphClaimKind,
    GraphDiagnosticCode,
    GraphDiagnosticSeverity,
    GraphDiagnosticStage,
    GraphEdgeType,
    GraphEvidenceKind,
    GraphFactOrigin,
    GraphFactStatus,
    GraphFlowDomain,
    GraphNodeKind,
    GraphResolutionStatus,
)


@dataclass(frozen=True)
class GraphDiagnostic:
    source_id: str
    inventory_file_id: int
    analysis_file_id: int
    relative_path: str
    stage: GraphDiagnosticStage
    code: GraphDiagnosticCode
    severity: GraphDiagnosticSeverity
    message: str
    job_id: Optional[str] = None
    candidate_id: Optional[str] = None
    line_start: Optional[int] = None
    line_end: Optional[int] = None
    metadata: Dict[str, Any] = field(default_factory=dict)
    fact_origin: GraphFactOrigin = GraphFactOrigin.UNKNOWN
    flow_domain: GraphFlowDomain = GraphFlowDomain.UNKNOWN

    def to_record(self) -> Dict[str, Any]:
        record = {
            "jobId": self.job_id,
            "sourceId": self.source_id,
            "inventoryFileId": self.inventory_file_id,
            "analysisFileId": self.analysis_file_id,
            "relativePath": self.relative_path,
            "stage": self.stage.value,
            "code": self.code.value,
            "severity": self.severity.value,
            "message": self.message,
            "candidateId": self.candidate_id,
            "lineStart": self.line_start,
            "lineEnd": self.line_end,
            "metadata": self.metadata,
            "factOrigin": self.fact_origin.value,
            "flowDomain": self.flow_domain.value,
        }
        validation_error = self.metadata.get("validationError") if isinstance(self.metadata, dict) else None
        if isinstance(validation_error, dict):
            record["validationError"] = validation_error
            for key in ("path", "expected", "actual", "allowedValues", "repairHint"):
                if key in validation_error:
                    record[key] = validation_error[key]
            if validation_error.get("code"):
                record["validationCode"] = validation_error["code"]
        return record


@dataclass(frozen=True)
class GraphEvidenceFact:
    id: str
    job_id: str
    source_id: str
    inventory_file_id: int
    analysis_file_id: int
    content_hash: str
    line_start: int
    line_end: int
    excerpt_hash: str
    evidence_kind: GraphEvidenceKind
    fact_origin: GraphFactOrigin
    flow_domain: GraphFlowDomain
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_record(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "job_id": self.job_id,
            "source_id": self.source_id,
            "inventory_file_id": self.inventory_file_id,
            "analysis_file_id": self.analysis_file_id,
            "content_hash": self.content_hash,
            "line_start": self.line_start,
            "line_end": self.line_end,
            "excerpt_hash": self.excerpt_hash,
            "evidence_kind": self.evidence_kind.value,
            "fact_origin": self.fact_origin.value,
            "flow_domain": self.flow_domain.value,
            "metadata": self.metadata,
        }


@dataclass(frozen=True)
class GraphNodeFact:
    id: str
    job_id: str
    source_id: str
    inventory_file_id: int
    analysis_file_id: int
    stable_key: str
    node_kind: GraphNodeKind
    language: str
    name: str
    qualified_name: str
    display_name: str
    parent_node_id: Optional[str]
    line_start: Optional[int]
    line_end: Optional[int]
    confidence: float
    status: GraphFactStatus
    fact_origin: GraphFactOrigin
    flow_domain: GraphFlowDomain
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_record(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "job_id": self.job_id,
            "source_id": self.source_id,
            "inventory_file_id": self.inventory_file_id,
            "analysis_file_id": self.analysis_file_id,
            "stable_key": self.stable_key,
            "node_kind": self.node_kind.value,
            "language": self.language,
            "name": self.name,
            "qualified_name": self.qualified_name,
            "display_name": self.display_name,
            "parent_node_id": self.parent_node_id,
            "line_start": self.line_start,
            "line_end": self.line_end,
            "confidence": self.confidence,
            "status": self.status.value,
            "fact_origin": self.fact_origin.value,
            "flow_domain": self.flow_domain.value,
            "metadata": self.metadata,
        }


@dataclass(frozen=True)
class GraphEdgeFact:
    id: str
    job_id: str
    source_id: str
    inventory_file_id: int
    analysis_file_id: int
    from_node_id: str
    to_node_id: Optional[str]
    edge_type: GraphEdgeType
    resolution_status: GraphResolutionStatus
    confidence: float
    evidence_id: Optional[str]
    unresolved_target: Optional[Dict[str, Any]]
    metadata: Dict[str, Any]
    status: GraphFactStatus
    fact_origin: GraphFactOrigin
    flow_domain: GraphFlowDomain

    def to_record(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "job_id": self.job_id,
            "source_id": self.source_id,
            "inventory_file_id": self.inventory_file_id,
            "analysis_file_id": self.analysis_file_id,
            "from_node_id": self.from_node_id,
            "to_node_id": self.to_node_id,
            "edge_type": self.edge_type.value,
            "resolution_status": self.resolution_status.value,
            "confidence": self.confidence,
            "evidence_id": self.evidence_id,
            "unresolved_target": self.unresolved_target,
            "metadata": self.metadata,
            "status": self.status.value,
            "fact_origin": self.fact_origin.value,
            "flow_domain": self.flow_domain.value,
        }


@dataclass(frozen=True)
class GraphClaimFact:
    id: str
    job_id: str
    source_id: str
    node_id: str
    claim_kind: GraphClaimKind
    summary: str
    confidence: float
    status: GraphFactStatus
    evidence_ids: List[str]
    fact_origin: GraphFactOrigin
    flow_domain: GraphFlowDomain
    metadata: Dict[str, Any] = field(default_factory=dict)
    rejection_reason: Optional[str] = None

    def to_record(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "job_id": self.job_id,
            "source_id": self.source_id,
            "node_id": self.node_id,
            "claim_kind": self.claim_kind.value,
            "summary": self.summary,
            "confidence": self.confidence,
            "status": self.status.value,
            "evidence_ids": self.evidence_ids,
            "fact_origin": self.fact_origin.value,
            "flow_domain": self.flow_domain.value,
            "metadata": self.metadata,
            "rejection_reason": self.rejection_reason,
        }


@dataclass
class GraphMaterialization:
    nodes: List[GraphNodeFact] = field(default_factory=list)
    edges: List[GraphEdgeFact] = field(default_factory=list)
    evidence: List[GraphEvidenceFact] = field(default_factory=list)
    claims: List[GraphClaimFact] = field(default_factory=list)
    diagnostics: List[GraphDiagnostic] = field(default_factory=list)

    @property
    def projected_symbol_count(self) -> int:
        return len([
            node
            for node in self.nodes
            if node.status in {GraphFactStatus.TRUSTED, GraphFactStatus.DERIVED}
            and node.node_kind != GraphNodeKind.FILE
        ])

    @property
    def projected_relation_count(self) -> int:
        return len([
            edge
            for edge in self.edges
            if edge.status in {GraphFactStatus.TRUSTED, GraphFactStatus.DERIVED}
            and edge.to_node_id is not None
        ])

    def to_store_payload(self) -> Dict[str, List[Dict[str, Any]]]:
        return {
            "nodes": [node.to_record() for node in self.nodes],
            "edges": [edge.to_record() for edge in self.edges],
            "evidence": [evidence.to_record() for evidence in self.evidence],
            "claims": [claim.to_record() for claim in self.claims],
            "diagnostics": [diagnostic.to_record() for diagnostic in self.diagnostics],
        }
