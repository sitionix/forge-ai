from __future__ import annotations

import hashlib
import json
from typing import Any, Dict, List, Optional, Set

from knowledge_service.graph_call_intelligence import classify_call_metadata
from knowledge_service.graph_schema import BoundaryDescriptor, BoundaryFact, GraphAnalysisResult, GraphEdge, GraphEvidenceRef


TRUSTED_CONFIDENCE_THRESHOLD = 0.70
EDGE_METADATA_ALLOWLIST = {
    "callKind",
    "callTargetCategory",
    "connectorKind",
    "directionRole",
    "httpMethod",
    "interfaceIdentity",
    "interfaceMethod",
    "methodName",
    "method",
    "operationIdentity",
    "relationKind",
    "receiverText",
    "receiverTypeHint",
    "requestContractIdentity",
    "resolutionReason",
    "responseContractIdentity",
    "route",
    "routeTemplate",
    "sliceDefaultVisibility",
    "targetEntrypoint",
    "targetInterfaceMethod",
    "targetServiceIdentity",
    "targetSource",
    "targetTypeHint",
    "targetTypeText",
    "transportConnector",
    "transportKind",
    "unresolvedReason",
}
CLAIM_METADATA_ALLOWLIST = {
    "entrypointExecutionKind",
    "entrypointKind",
    "exceptionType",
    "httpMethod",
    "interfaceMethod",
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
        boundaries: list[dict[str, Any]] = []
        boundary_descriptors: list[dict[str, Any]] = []
        boundary_descriptor_index: list[dict[str, Any]] = []
        boundary_evidence_links: list[dict[str, Any]] = []
        boundary_descriptor_evidence_links: list[dict[str, Any]] = []
        evidence: List[Dict[str, Any]] = []
        diagnostics: List[Dict[str, Any]] = []
        used_evidence_ids: Set[str] = set()
        parent_by_local_id = self._parents_from_edges(result.edges)
        boundary_row_by_id: dict[str, dict[str, Any]] = {}
        boundary_descriptor_envelope_keys_by_id: dict[str, set[tuple[str, str, str]]] = {}
        materialized_descriptor_ids: set[str] = set()

        for node in result.nodes:
            node_id = self._stable_id("analysis-graph-node", row["source_id"], row["relative_path"], node.localId, node.name, str(node.lineStart or 0))
            local_to_node_id[node.localId] = node_id
            metadata = dict(node.metadata or {})
            metadata.setdefault("sourceKind", metadata.get("sourceKind") or node.nodeKind)
            metadata.setdefault("analyzerName", analyzer_name)
            metadata.setdefault("analyzerVersion", analyzer_version)
            source_kind = str(metadata.get("sourceKind") or "").upper()
            type_kind = source_kind if node.nodeKind == "TYPE" and source_kind in {"CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION"} else None
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
                    "type_kind": type_kind,
                    "language": node.language or metadata.get("language"),
                    "name": node.name,
                    "qualified_name": node.qualifiedName,
                    "display_name": node.displayName,
                    "parent_node_id": None,
                    "parent_local_id": node.parentLocalId or parent_by_local_id.get(node.localId),
                    "parameter_count": node.parameter_count,
                    "signature": metadata.get("signature"),
                    "parameter_types": list(node.parameterTypes or []),
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

        for boundary_index, boundary in enumerate(result.boundaries or [], start=1):
            node_id = local_to_node_id.get(boundary.nodeLocalId)
            if not node_id or not boundary.descriptors:
                continue
            node = next((item for item in nodes if item["id"] == node_id), None)
            if node is None:
                continue
            metadata = dict(boundary.metadata or {})
            fact_origin = self._boundary_fact_origin(boundary, metadata, node)
            flow_domain = str(node.get("flow_domain") or self._flow_domain(row, {})).upper()
            status = confidence_status(boundary.confidence)
            rejection_reason = str(metadata.get("rejectionReason")) if metadata.get("rejectionReason") and status == "CANDIDATE" else None
            boundary_identity = self._boundary_identity(boundary, metadata)
            boundary_id = self._stable_id(
                "analysis-graph-boundary",
                row["source_id"],
                row["relative_path"],
                node_id,
                boundary.role,
                boundary_identity,
            )
            boundary_evidence_ids: list[str] = []
            evidence_id_by_key: dict[str, str] = {}
            for index, item in enumerate(boundary.evidence, start=1):
                evidence_row = self._evidence(
                    row,
                    job_id,
                    lines,
                    item,
                    owner_kind="BOUNDARY",
                    owner_local_id=boundary.localId,
                    owner_id=boundary_id,
                    owner_index=boundary_identity,
                    fact_origin=fact_origin,
                    flow_domain=flow_domain,
                    index=index,
                    owner_metadata={"boundaryRole": boundary.role, "nodeId": node_id},
                    used_ids=used_evidence_ids,
                )
                evidence.append(evidence_row)
                boundary_evidence_ids.append(evidence_row["id"])
                evidence_id_by_key[self._evidence_key(item)] = evidence_row["id"]
            descriptor_rows, descriptor_indexes, descriptor_links = self._materialize_boundary_descriptors(
                row,
                job_id,
                lines,
                boundary,
                boundary_id,
                boundary_identity,
                fact_origin,
                flow_domain,
                boundary_evidence_ids,
                evidence_id_by_key,
                evidence,
                used_evidence_ids,
                materialized_descriptor_ids,
            )
            if not descriptor_rows and not descriptor_links:
                continue
            boundary_row = boundary_row_by_id.get(boundary_id)
            if boundary_row is None:
                boundary_metadata = self._boundary_metadata(metadata)
                boundary_metadata["boundaryIdentity"] = boundary_identity
                boundary_row = {
                    "id": boundary_id,
                    "job_id": job_id,
                    "source_id": row["source_id"],
                    "inventory_file_id": row["id"],
                    "analysis_file_id": row["id"],
                    "stable_key": boundary_identity,
                    "node_id": node_id,
                    "role": boundary.role,
                    "confidence": boundary.confidence,
                    "status": status,
                    "rejection_reason": rejection_reason,
                    "descriptor_json": [],
                    "metadata": boundary_metadata,
                    "fact_origin": fact_origin,
                    "flow_domain": flow_domain,
                    "evidence_ids": boundary_evidence_ids,
                }
                boundary_row_by_id[boundary_id] = boundary_row
                boundary_descriptor_envelope_keys_by_id[boundary_id] = set()
                boundaries.append(boundary_row)
            else:
                boundary_row["confidence"] = max(float(boundary_row["confidence"]), float(boundary.confidence))
                boundary_row["status"] = confidence_status(boundary_row["confidence"])
                boundary_row["fact_origin"] = self._stronger_fact_origin(str(boundary_row.get("fact_origin") or "LLM"), fact_origin)
                if boundary_row["status"] == "CANDIDATE" and not boundary_row.get("rejection_reason"):
                    boundary_row["rejection_reason"] = rejection_reason
                elif boundary_row["status"] != "CANDIDATE":
                    boundary_row["rejection_reason"] = None
                boundary_row["evidence_ids"] = list(dict.fromkeys([*boundary_row.get("evidence_ids", []), *boundary_evidence_ids]))
            envelope_seen = boundary_descriptor_envelope_keys_by_id.setdefault(boundary_id, set())
            for envelope_item in self._boundary_descriptor_envelope(boundary.descriptors, fact_origin):
                envelope_key = (
                    str(envelope_item["path"]),
                    self._json_dump(envelope_item["value"]),
                    str(envelope_item["origin"]),
                )
                if envelope_key in envelope_seen:
                    continue
                envelope_seen.add(envelope_key)
                boundary_row["descriptor_json"].append(envelope_item)
            boundary_evidence_links.extend({"boundary_id": boundary_id, "evidence_id": evidence_id} for evidence_id in boundary_evidence_ids)
            boundary_descriptors.extend(descriptor_rows)
            boundary_descriptor_index.extend(descriptor_indexes)
            boundary_descriptor_evidence_links.extend(descriptor_links)

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

        self._canonicalize_boundary_graph(
            boundaries,
            boundary_descriptors,
            boundary_descriptor_index,
            boundary_evidence_links,
            boundary_descriptor_evidence_links,
        )
        return {
            "nodes": nodes,
            "edges": edges,
            "claims": claims,
            "boundaries": boundaries,
            "boundary_descriptors": boundary_descriptors,
            "boundary_descriptor_index": boundary_descriptor_index,
            "boundary_evidence_links": boundary_evidence_links,
            "boundary_descriptor_evidence_links": boundary_descriptor_evidence_links,
            "evidence": evidence,
            "diagnostics": diagnostics,
        }

    def _canonicalize_boundary_graph(
        self,
        boundaries: list[dict[str, Any]],
        boundary_descriptors: list[dict[str, Any]],
        boundary_descriptor_index: list[dict[str, Any]],
        boundary_evidence_links: list[dict[str, Any]],
        boundary_descriptor_evidence_links: list[dict[str, Any]],
    ) -> None:
        for boundary in boundaries:
            boundary["descriptor_json"] = sorted(
                boundary.get("descriptor_json") or [],
                key=lambda item: (
                    str(item.get("path") or ""),
                    str(item.get("origin") or ""),
                    self._json_dump(item.get("value")),
                ),
            )
            boundary["evidence_ids"] = sorted(set(boundary.get("evidence_ids") or []))
        boundaries.sort(key=lambda item: str(item.get("id") or ""))
        boundary_descriptors.sort(key=lambda item: str(item.get("id") or ""))
        boundary_descriptor_index.sort(
            key=lambda item: (
                str(item.get("descriptor_id") or ""),
                str(item.get("descriptor_path") or ""),
                str(item.get("value_type") or ""),
                str(item.get("normalized_scalar_value") or ""),
            )
        )
        boundary_evidence_links.sort(key=lambda item: (str(item.get("boundary_id") or ""), str(item.get("evidence_id") or "")))
        boundary_descriptor_evidence_links.sort(key=lambda item: (str(item.get("descriptor_id") or ""), str(item.get("evidence_id") or "")))

    def _materialize_boundary_descriptors(
        self,
        row: dict[str, Any],
        job_id: str,
        lines: list[str],
        boundary: BoundaryFact,
        boundary_id: str,
        boundary_identity: str,
        fact_origin: str,
        flow_domain: str,
        boundary_evidence_ids: list[str],
        evidence_id_by_key: dict[str, str],
        evidence: list[dict[str, Any]],
        used_evidence_ids: set[str],
        materialized_descriptor_ids: set[str],
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
        descriptor_rows: list[dict[str, Any]] = []
        descriptor_indexes: list[dict[str, Any]] = []
        descriptor_links: list[dict[str, Any]] = []
        for descriptor_index, descriptor in enumerate(boundary.descriptors, start=1):
            value_json = self._json_dump(descriptor.value)
            descriptor_origin = self._descriptor_fact_origin(descriptor, fact_origin)
            descriptor_id = self._stable_id(
                "analysis-graph-boundary-descriptor",
                boundary_id,
                descriptor.path,
                value_json,
                descriptor_origin,
            )
            descriptor_evidence_ids: list[str] = []
            for index, item in enumerate(descriptor.evidence, start=1):
                evidence_key = self._evidence_key(item)
                existing_evidence_id = evidence_id_by_key.get(evidence_key)
                if existing_evidence_id:
                    descriptor_evidence_ids.append(existing_evidence_id)
                    continue
                evidence_row = self._evidence(
                    row,
                    job_id,
                    lines,
                    item,
                    owner_kind="BOUNDARY_DESCRIPTOR",
                    owner_local_id=f"{boundary.localId}:{descriptor.path}",
                    owner_id=descriptor_id,
                    owner_index=boundary_identity,
                    fact_origin=descriptor_origin,
                    flow_domain=flow_domain,
                    index=index,
                    owner_metadata={"boundaryId": boundary_id, "descriptorPath": descriptor.path},
                    used_ids=used_evidence_ids,
                )
                evidence.append(evidence_row)
                descriptor_evidence_ids.append(evidence_row["id"])
                evidence_id_by_key[evidence_key] = evidence_row["id"]
            if not descriptor_evidence_ids:
                descriptor_evidence_ids = list(boundary_evidence_ids)
            if not descriptor_evidence_ids:
                continue
            if descriptor_id not in materialized_descriptor_ids:
                materialized_descriptor_ids.add(descriptor_id)
                descriptor_rows.append(
                    {
                        "id": descriptor_id,
                        "boundary_id": boundary_id,
                        "descriptor_path": descriptor.path,
                        "value_type": self._json_value_type(descriptor.value),
                        "value_json": value_json,
                        "origin": descriptor_origin,
                        "confidence": descriptor.confidence,
                        "status": "TRUSTED" if descriptor.confidence is None else confidence_status(descriptor.confidence),
                    }
                )
                descriptor_indexes.extend(self._descriptor_index_rows(descriptor_id, boundary_id, descriptor.path, descriptor.value))
            descriptor_links.extend(
                {"descriptor_id": descriptor_id, "evidence_id": evidence_id}
                for evidence_id in descriptor_evidence_ids
            )
        return descriptor_rows, descriptor_indexes, descriptor_links

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
        owner_index: int | str,
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

    def _boundary_fact_origin(self, boundary: BoundaryFact, metadata: dict[str, Any], node: dict[str, Any]) -> str:
        return str(boundary.origin or metadata.get("factOrigin") or node.get("fact_origin") or "LLM").upper()

    def _boundary_identity(self, boundary: BoundaryFact, metadata: dict[str, Any]) -> str:
        return str(boundary.identity or metadata.get("boundaryIdentity") or metadata.get("stableKey") or boundary.localId)

    def _stronger_fact_origin(self, current: str, candidate: str) -> str:
        rank = {"LLM": 0, "STATIC": 1, "DERIVED": 2}
        current_normalized = str(current or "LLM").upper()
        candidate_normalized = str(candidate or "LLM").upper()
        if rank.get(candidate_normalized, 0) > rank.get(current_normalized, 0):
            return candidate_normalized
        return current_normalized

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

    def _boundary_metadata(self, metadata: dict[str, Any]) -> dict[str, Any]:
        return {
            key: value
            for key, value in (metadata or {}).items()
            if key in {"sourceKind", "stableKey", "boundaryIdentity", "parser", "derivation"} and value is not None
        }

    def _descriptor_fact_origin(self, descriptor: BoundaryDescriptor, boundary_fact_origin: str) -> str:
        normalized_boundary_origin = str(boundary_fact_origin or "LLM").upper()
        if normalized_boundary_origin in {"LLM", "DERIVED"}:
            return normalized_boundary_origin
        return str(descriptor.origin or normalized_boundary_origin).upper()

    def _boundary_descriptor_envelope(self, descriptors: list[BoundaryDescriptor], boundary_fact_origin: str) -> list[dict[str, Any]]:
        return [
            {
                "path": descriptor.path,
                "value": descriptor.value,
                "valueType": self._json_value_type(descriptor.value),
                "origin": self._descriptor_fact_origin(descriptor, boundary_fact_origin),
                "confidence": descriptor.confidence,
            }
            for descriptor in descriptors
        ]

    def _descriptor_index_rows(self, descriptor_id: str, boundary_id: str, descriptor_path: str, value: Any) -> list[dict[str, Any]]:
        return [
            {
                "descriptor_id": descriptor_id,
                "boundary_id": boundary_id,
                "descriptor_path": path,
                "value_type": value_type,
                "normalized_scalar_value": normalized_value,
            }
            for path, value_type, normalized_value in self._scalar_projections(descriptor_path, value)
        ]

    def _scalar_projections(self, path: str, value: Any) -> list[tuple[str, str, str]]:
        if value is None:
            return []
        value_type = self._json_value_type(value)
        if value_type in {"STRING", "NUMBER", "BOOLEAN"}:
            return [(path, value_type, self._normalize_scalar_value(value))]
        if isinstance(value, list):
            rows: list[tuple[str, str, str]] = []
            for index, item in enumerate(value):
                rows.extend(self._scalar_projections(f"{path}[{index}]", item))
            return rows
        if isinstance(value, dict):
            rows = []
            for key in sorted(value):
                rows.extend(self._scalar_projections(f"{path}.{key}", value[key]))
            return rows
        return []

    def _normalize_scalar_value(self, value: Any) -> str:
        if isinstance(value, bool):
            return "true" if value else "false"
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return json.dumps(value, separators=(",", ":"))
        return str(value or "").strip().lower()

    def _json_value_type(self, value: Any) -> str:
        if value is None:
            return "NULL"
        if isinstance(value, bool):
            return "BOOLEAN"
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return "NUMBER"
        if isinstance(value, str):
            return "STRING"
        if isinstance(value, list):
            return "LIST"
        if isinstance(value, dict):
            return "OBJECT"
        return "OBJECT"

    def _json_dump(self, value: Any) -> str:
        return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))

    def _evidence_key(self, item: GraphEvidenceRef) -> str:
        return self._json_dump(
            {
                "lineStart": item.lineStart,
                "lineEnd": item.lineEnd,
                "text": item.text,
                "metadata": item.metadata or {},
            }
        )

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
