from __future__ import annotations

import hashlib
import json
from typing import Any, Dict, List, Optional, Set

from knowledge_service.graph_call_intelligence import classify_call_metadata
from knowledge_service.graph_schema import GraphAnalysisResult, GraphEdge, GraphEvidenceRef


TRUSTED_CONFIDENCE_THRESHOLD = 0.70
EDGE_METADATA_ALLOWLIST = {
    "callKind",
    "callTargetCategory",
    "methodName",
    "relationKind",
    "receiverText",
    "receiverTypeHint",
    "resolutionReason",
    "sliceDefaultVisibility",
    "targetTypeHint",
    "targetTypeText",
    "unresolvedReason",
}
CLAIM_METADATA_ALLOWLIST = {
    "entrypointKind",
    "exceptionType",
    "httpMethod",
    "route",
    "schedule",
    "topic",
}


def confidence_status(confidence: Optional[float]) -> str:
    value = 0.0 if confidence is None else float(confidence)
    if value >= TRUSTED_CONFIDENCE_THRESHOLD:
        return "TRUSTED"
    return "CANDIDATE"


class GraphAnalysisEngine:
    def materialize(
        self, row: Dict[str, Any], job_id: str, analyzer_name: str, analyzer_version: str, result: GraphAnalysisResult, lines: List[str]
    ) -> Dict[str, List[Dict[str, Any]]]:
        local_to_node_id: Dict[str, str] = {}
        nodes: List[Dict[str, Any]] = []
        edges: List[Dict[str, Any]] = []
        claims: List[Dict[str, Any]] = []
        evidence: List[Dict[str, Any]] = []
        diagnostics: List[Dict[str, Any]] = []
        used_evidence_ids: Set[str] = set()
        parent_by_local_id = self._parents_from_edges(result.edges)

        for node in result.nodes:
            node_id = self._stable_id("analysis-graph-node", row["source_id"], row["relative_path"], node.localId, node.name, str(node.lineStart or 0))
            local_to_node_id[node.localId] = node_id
            metadata = dict(node.metadata or {})
            metadata.setdefault("sourceKind", metadata.get("sourceKind") or node.nodeKind)
            metadata.setdefault("analyzerName", analyzer_name)
            metadata.setdefault("analyzerVersion", analyzer_version)
            fact_origin = self._fact_origin(metadata)
            flow_domain = self._flow_domain(row, metadata)
            nodes.append(
                {
                    "id": node_id,
                    "job_id": job_id,
                    "source_id": row["source_id"],
                    "inventory_file_id": row["id"],
                    "analysis_file_id": row["id"],
                    "stable_key": metadata.get("stableKey") or node_id,
                    "node_kind": node.nodeKind,
                    "language": node.language or metadata.get("language"),
                    "name": node.name,
                    "qualified_name": node.qualifiedName,
                    "display_name": node.displayName,
                    "parent_node_id": None,
                    "parent_local_id": node.parentLocalId or parent_by_local_id.get(node.localId),
                    "parameter_count": node.parameter_count,
                    "line_start": node.lineStart,
                    "line_end": node.lineEnd,
                    "confidence": node.confidence,
                    "status": metadata.get("status") or confidence_status(node.confidence),
                    "metadata": {},
                    "fact_origin": fact_origin,
                    "flow_domain": flow_domain,
                }
            )

        for node in nodes:
            parent_local_id = node.pop("parent_local_id", None)
            if parent_local_id:
                node["parent_node_id"] = local_to_node_id.get(parent_local_id)

        for claim_index, claim in enumerate(result.claims, start=1):
            node_id = local_to_node_id.get(claim.nodeLocalId)
            if not node_id:
                continue
            node = next((item for item in nodes if item["id"] == node_id), None)
            if node is None:
                continue
            claim_id = self._stable_id(
                "analysis-graph-claim",
                row["source_id"],
                row["relative_path"],
                str(claim_index),
                claim.localId,
                node_id,
                claim.claimKind,
            )
            claim_evidence_ids: List[str] = []
            for index, item in enumerate(claim.evidence, start=1):
                evidence_row = self._evidence(
                    row,
                    job_id,
                    lines,
                    item,
                    owner_kind="CLAIM",
                    owner_local_id=claim.localId,
                    owner_id=claim_id,
                    owner_index=claim_index,
                    fact_origin=node["fact_origin"],
                    flow_domain=node["flow_domain"],
                    index=index,
                    owner_metadata={"claimKind": claim.claimKind, "nodeId": node_id},
                    used_ids=used_evidence_ids,
                )
                evidence.append(evidence_row)
                claim_evidence_ids.append(evidence_row["id"])
            metadata = dict(claim.metadata or {})
            status = metadata.get("status") or confidence_status(claim.confidence)
            rejection_reason = None
            fact_origin = self._fact_origin(metadata) if metadata.get("factOrigin") else node["fact_origin"]
            flow_domain = metadata.get("flowDomain") or node["flow_domain"]
            if metadata.get("rejectionReason") and status == "CANDIDATE":
                rejection_reason = str(metadata.get("rejectionReason"))
            claims.append(
                {
                    "id": claim_id,
                    "job_id": job_id,
                    "source_id": row["source_id"],
                    "node_id": node_id,
                    "claim_kind": claim.claimKind,
                    "summary": claim.summary,
                    "confidence": claim.confidence,
                    "status": status,
                    "evidence_ids": claim_evidence_ids,
                    "metadata": self._allowlisted_metadata(metadata, CLAIM_METADATA_ALLOWLIST),
                    "rejection_reason": rejection_reason,
                    "fact_origin": fact_origin,
                    "flow_domain": str(flow_domain or "CODE").upper(),
                }
            )

        for edge_index, edge in enumerate(result.edges, start=1):
            from_node_id = local_to_node_id.get(edge.fromNodeLocalId)
            if not from_node_id:
                continue
            to_node_id = local_to_node_id.get(edge.toNodeLocalId) if edge.toNodeLocalId else None
            from_node = next((item for item in nodes if item["id"] == from_node_id), None)
            metadata = dict(edge.metadata or {})
            fact_origin = self._fact_origin(metadata) if metadata.get("factOrigin") else (from_node or {}).get("fact_origin") or "LLM"
            flow_domain = metadata.get("flowDomain") or (from_node or {}).get("flow_domain") or self._flow_domain(row, metadata)
            resolution_status = self._resolution_status(edge, to_node_id)
            if edge.edgeType == "CALLS":
                metadata = classify_call_metadata(
                    metadata,
                    flow_domain,
                    resolution_status,
                    edge.unresolvedTarget,
                )
            edge_id = self._stable_id(
                "analysis-graph-edge",
                row["source_id"],
                row["relative_path"],
                str(edge_index),
                edge.localId,
                from_node_id,
                to_node_id or "",
                edge.edgeType,
            )
            edge_evidence_ids: List[str] = []
            if edge.evidence:
                evidence_row = self._evidence(
                    row,
                    job_id,
                    lines,
                    edge.evidence[0],
                    owner_kind="EDGE",
                    owner_local_id=edge.localId,
                    owner_id=edge_id,
                    owner_index=edge_index,
                    fact_origin=fact_origin,
                    flow_domain=flow_domain,
                    index=1,
                    owner_metadata={"edgeType": edge.edgeType, "fromNodeId": from_node_id, "toNodeId": to_node_id},
                    used_ids=used_evidence_ids,
                )
                evidence.append(evidence_row)
                edge_evidence_ids.append(evidence_row["id"])
            edges.append(
                {
                    "id": edge_id,
                    "job_id": job_id,
                    "source_id": row["source_id"],
                    "inventory_file_id": row["id"],
                    "analysis_file_id": row["id"],
                    "from_node_id": from_node_id,
                    "to_node_id": to_node_id,
                    "edge_type": edge.edgeType,
                    "resolution_status": resolution_status,
                    "argument_count": edge.argument_count,
                    "confidence": edge.confidence,
                    "evidence_ids": edge_evidence_ids,
                    "unresolved_target": edge.unresolvedTarget,
                    "metadata": self._allowlisted_metadata(metadata, EDGE_METADATA_ALLOWLIST),
                    "status": metadata.get("status") or confidence_status(edge.confidence),
                    "fact_origin": fact_origin,
                    "flow_domain": str(flow_domain).upper(),
                }
            )

        for index, diagnostic in enumerate(result.diagnostics or [], start=1):
            diagnostic_metadata = self._diagnostic_metadata(diagnostic)
            diagnostic_fact_origin = str(diagnostic.get("factOrigin") or diagnostic_metadata.get("factOrigin") or "LLM").upper()
            diagnostic_flow_domain = str(diagnostic.get("flowDomain") or diagnostic_metadata.get("flowDomain") or self._flow_domain(row, {})).upper()
            diagnostics.append(
                {
                    "id": self._stable_id(
                        "analysis-graph-diagnostic", row["source_id"], row["relative_path"], str(index), diagnostic.get("code") or "DIAGNOSTIC"
                    ),
                    "job_id": job_id,
                    "source_id": row["source_id"],
                    "inventory_file_id": row["id"],
                    "analysis_file_id": row["id"],
                    "severity": diagnostic.get("severity") or "WARN",
                    "stage": diagnostic.get("stage") or "ANALYSIS",
                    "code": diagnostic.get("code") or "DIAGNOSTIC",
                    "message": diagnostic.get("message") or "-",
                    "candidate_id": diagnostic.get("candidateId") or diagnostic.get("candidate_id"),
                    "line_start": diagnostic.get("lineStart") or diagnostic.get("line_start"),
                    "line_end": diagnostic.get("lineEnd") or diagnostic.get("line_end"),
                    "metadata": diagnostic_metadata,
                    "fact_origin": diagnostic_fact_origin,
                    "flow_domain": diagnostic_flow_domain,
                }
            )

        return {"nodes": nodes, "edges": edges, "claims": claims, "evidence": evidence, "diagnostics": diagnostics}

    def _parents_from_edges(self, edges: List[GraphEdge]) -> Dict[str, str]:
        parents: Dict[str, str] = {}
        for edge in edges:
            if edge.edgeType in {"DECLARES", "CONTAINS"} and edge.toNodeLocalId:
                parents.setdefault(edge.toNodeLocalId, edge.fromNodeLocalId)
        return parents

    def _evidence(
        self,
        row: Dict[str, Any],
        job_id: str,
        lines: List[str],
        item: GraphEvidenceRef,
        owner_kind: str,
        owner_local_id: str,
        owner_id: str,
        owner_index: int,
        fact_origin: str,
        flow_domain: str,
        index: int,
        owner_metadata: Dict[str, Any],
        used_ids: Set[str],
    ) -> Dict[str, Any]:
        excerpt = self._excerpt(lines, item.lineStart, item.lineEnd)
        metadata = dict(item.metadata or {})
        stored_evidence_kind = str(metadata.pop("evidenceKind", owner_kind) or owner_kind).upper()
        identity_metadata = {
            key: value
            for key, value in {
                **owner_metadata,
                "evidenceMetadata": metadata,
            }.items()
            if value is not None
        }
        evidence_id = self._stable_id(
            "analysis-graph-evidence",
            row["source_id"],
            row["relative_path"],
            row["content_hash"],
            owner_kind,
            owner_local_id,
            owner_id,
            str(owner_index),
            str(index),
            stored_evidence_kind,
            str(item.lineStart),
            str(item.lineEnd),
            hashlib.sha256(excerpt.encode("utf-8")).hexdigest(),
            json.dumps(identity_metadata, sort_keys=True, separators=(",", ":")),
        )
        if evidence_id in used_ids:
            suffix = 2
            base_id = evidence_id
            while evidence_id in used_ids:
                evidence_id = self._stable_id("analysis-graph-evidence", base_id, "duplicate", str(suffix))
                suffix += 1
        used_ids.add(evidence_id)
        return {
            "id": evidence_id,
            "job_id": job_id,
            "source_id": row["source_id"],
            "inventory_file_id": row["id"],
            "analysis_file_id": row["id"],
            "content_hash": row["content_hash"],
            "line_start": item.lineStart,
            "line_end": item.lineEnd,
            "excerpt": item.text or excerpt,
            "excerpt_hash": hashlib.sha256(excerpt.encode("utf-8")).hexdigest(),
            "evidence_kind": stored_evidence_kind,
            "metadata": {},
            "fact_origin": str(fact_origin or "LLM").upper(),
            "flow_domain": str(flow_domain or "CODE").upper(),
        }

    def _excerpt(self, lines: List[str], line_start: int, line_end: int) -> str:
        start = max(line_start - 1, 0)
        end = min(line_end, len(lines))
        return "\n".join(lines[start:end])

    def _fact_origin(self, metadata: Dict[str, Any]) -> str:
        return str(metadata.get("factOrigin") or "LLM").upper()

    def _resolution_status(self, edge: GraphEdge, to_node_id: Optional[str]) -> str:
        return str(edge.resolutionStatus or ("RESOLVED" if to_node_id else "UNRESOLVED")).upper()

    def _diagnostic_metadata(self, diagnostic: Dict[str, Any]) -> Dict[str, Any]:
        metadata = {key: value for key, value in diagnostic.items() if key not in {"severity", "stage", "code", "message", "factOrigin", "flowDomain"}}
        nested = metadata.pop("metadata", None)
        if isinstance(nested, dict):
            metadata.update(nested)
        return metadata

    def _allowlisted_metadata(self, metadata: Dict[str, Any], allowlist: Set[str]) -> Dict[str, Any]:
        return {key: value for key, value in (metadata or {}).items() if key in allowlist and value is not None}

    def _flow_domain(self, row: Dict[str, Any], metadata: Dict[str, Any]) -> str:
        explicit = metadata.get("flowDomain")
        explicit_value = str(explicit or "").strip().upper()
        if explicit_value and explicit_value != "UNKNOWN":
            return explicit_value
        row_flow_domain = str(row.get("flow_domain") or "").strip().upper()
        if row_flow_domain and row_flow_domain != "UNKNOWN":
            return row_flow_domain
        return "CODE"

    def _stable_id(self, *parts: str) -> str:
        digest = hashlib.sha256("\0".join(str(part) for part in parts).encode("utf-8")).hexdigest()[:24]
        return f"{parts[0]}:{digest}"
