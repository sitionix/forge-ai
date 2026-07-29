from __future__ import annotations

import re
from typing import Any, Dict, List, Optional

from knowledge_service.entrypoint_kinds import EntrypointExecutionKind, EntrypointKind, entrypoint_kind_for_annotation
from knowledge_service.graph_schema import BoundaryDescriptor, BoundaryFact, GraphAnalysisResult, GraphClaim, GraphEdge, GraphEvidenceRef, GraphNode
from knowledge_service.graph_call_intelligence import classify_call_metadata
from knowledge_service.java_parser_adapter import JavaParserAdapter
from knowledge_service.structural_model import (
    StructuralAnnotation,
    StructuralCallsite,
    StructuralFileMetadata,
    StructuralParseDiagnostic,
    StructuralParseResult,
    StructuralType,
)

class ParserAdapterRegistry:
    def __init__(self) -> None:
        self._adapters = {
            "java": JavaParserAdapter(),
        }

    def adapter_for(self, language: str):
        return self._adapters.get(str(language or "").lower())


class StructuralAnalysisEngine:
    def __init__(self, registry: Optional[ParserAdapterRegistry] = None) -> None:
        self.registry = registry or ParserAdapterRegistry()

    def parse(self, row: Dict[str, Any], lines: List[str]) -> StructuralParseResult:
        language = self._language(row)
        flow_domain = self._flow_domain(row)
        metadata = StructuralFileMetadata(
            source_id=row["source_id"],
            inventory_file_id=int(row["id"]),
            relative_path=row["relative_path"],
            language=language,
            flow_domain=flow_domain,
            content_hash=row["content_hash"],
            line_count=len(lines),
            decode_policy=row["decode_policy"] if "decode_policy" in row.keys() else None,
        )
        adapter = self.registry.adapter_for(language)
        if adapter is None:
            return StructuralParseResult(
                file=metadata,
                diagnostics=[
                    StructuralParseDiagnostic(
                        code="STRUCTURAL_PARSER_NOT_AVAILABLE",
                        message=f"No structural parser adapter is available for language '{language}'.",
                        severity="WARN",
                        metadata={"language": language},
                    )
                ],
            )
        try:
            return adapter.parse("\n".join(lines), metadata)
        except Exception as exc:
            return StructuralParseResult(
                file=metadata,
                diagnostics=[
                    StructuralParseDiagnostic(
                        code="STRUCTURAL_PARSER_FAILED",
                        message=f"Structural parser failed: {type(exc).__name__}: {exc}",
                        severity="ERROR",
                        metadata={"language": language},
                    )
                ],
            )

    def _language(self, row: Dict[str, Any]) -> str:
        configured = str(row.get("language") or "").strip().lower()
        if configured and configured != "unknown":
            return configured
        extension = str(row["extension"] or "").lower().lstrip(".")
        return {"yml": "yaml", "md": "markdown", "adoc": "markdown"}.get(extension, extension or "unknown")

    def _flow_domain(self, row: Dict[str, Any]) -> str:
        configured = str(row.get("flow_domain") or "").strip().upper()
        if configured and configured != "UNKNOWN":
            return configured
        return "CODE"


class StaticGraphMaterializer:
    HTTP_METHODS = {
        "GetMapping": "GET",
        "PostMapping": "POST",
        "PutMapping": "PUT",
        "DeleteMapping": "DELETE",
        "PatchMapping": "PATCH",
    }
    ENTRYPOINT_ANNOTATIONS = {
        "GetMapping",
        "PostMapping",
        "PutMapping",
        "DeleteMapping",
        "PatchMapping",
        "RequestMapping",
        "ExceptionHandler",
        "KafkaListener",
        "Scheduled",
        "MessageMapping",
        "PostConstruct",
        "Bean",
        "Test",
    }

    def to_graph(self, result: StructuralParseResult) -> GraphAnalysisResult:
        nodes: List[GraphNode] = []
        edges: List[GraphEdge] = []
        claims: List[GraphClaim] = []
        boundaries: list[BoundaryFact] = []
        diagnostics: List[Dict[str, Any]] = []
        file_local_id = result.file_stable_key
        callables_by_id = {item.local_id: item for item in result.callables}
        types_by_id = {item.local_id: item for item in result.types}
        fields_by_id = {item.local_id: item for item in result.fields}
        fields_by_owner: Dict[str, List[Any]] = {}
        for item in result.fields:
            fields_by_owner.setdefault(item.owner_type_local_id, []).append(item)
        field_usages: Dict[tuple[str, str], Dict[str, Any]] = {}
        type_lookup = self._type_lookup(result)
        nodes.append(
            GraphNode(
                localId=file_local_id,
                nodeKind="FILE",
                name=result.file.relative_path.rsplit("/", 1)[-1],
                language=result.file.language,
                qualifiedName=None,
                displayName=result.file.relative_path.rsplit("/", 1)[-1],
                parentLocalId=None,
                lineStart=1,
                lineEnd=max(result.file.line_count, 1),
                confidence=1.0,
                metadata=self._metadata(result, "FILE", file_local_id, {"packageName": result.package_name}),
            )
        )
        for item in result.imports:
            imported_simple = item.imported_name.rsplit(".", 1)[-1]
            unresolved_target = {
                "name": imported_simple,
                "qualifiedName": item.imported_name,
                "kindHint": "IMPORT",
                "isStatic": item.is_static,
                "isWildcard": item.is_wildcard,
            }
            edges.append(
                GraphEdge(
                    localId=self._stable_key(result, "IMPORT_EDGE", item.stable_key),
                    fromNodeLocalId=file_local_id,
                    toNodeLocalId=None,
                    edgeType="IMPORTS",
                    resolutionStatus="EXTERNAL_TARGET",
                    confidence=1.0,
                    evidence=[self._evidence(item.line_start, item.line_end, f"import {item.imported_name}")],
                    unresolvedTarget=unresolved_target,
                    metadata=self._metadata(
                        result,
                        "IMPORT",
                        item.stable_key,
                        {
                            "unresolvedReason": "EXTERNAL_NOT_MODELED",
                            "resolutionReason": "IMPORT_REFERENCE",
                        },
                    ),
                )
            )
            edges.append(
                GraphEdge(
                    localId=self._stable_key(result, "REFERENCES_IMPORT", item.stable_key),
                    fromNodeLocalId=file_local_id,
                    toNodeLocalId=None,
                    edgeType="REFERENCES",
                    resolutionStatus="EXTERNAL_TARGET",
                    confidence=1.0,
                    evidence=[self._evidence(item.line_start, item.line_end, f"import {item.imported_name}")],
                    unresolvedTarget=unresolved_target,
                    metadata=self._metadata(
                        result,
                        "IMPORT_REFERENCE",
                        item.stable_key,
                        {
                            "unresolvedReason": "EXTERNAL_NOT_MODELED",
                            "resolutionReason": "IMPORT_REFERENCE",
                        },
                    ),
                )
            )
        for item in result.types:
            nodes.append(
                GraphNode(
                    localId=item.local_id,
                    nodeKind="TYPE",
                    name=item.name,
                    language=result.file.language,
                    qualifiedName=item.qualified_name,
                    displayName=item.name,
                    parentLocalId=item.parent_type_local_id or file_local_id,
                    lineStart=item.line_start,
                    lineEnd=item.line_end,
                    confidence=1.0,
                    metadata=self._metadata(
                        result,
                        item.type_kind,
                        item.stable_key,
                        {
                            "bodyLineStart": item.body_line_start,
                            "bodyLineEnd": item.body_line_end,
                            "annotations": [self._annotation_metadata(annotation) for annotation in item.annotations],
                        },
                    ),
                )
            )
            edges.append(
                GraphEdge(
                    localId=self._stable_key(result, "DECLARES", item.local_id),
                    fromNodeLocalId=item.parent_type_local_id or file_local_id,
                    toNodeLocalId=item.local_id,
                    edgeType="DECLARES",
                    resolutionStatus="RESOLVED",
                    confidence=1.0,
                    evidence=[self._evidence(item.line_start, item.line_end, f"{item.type_kind.lower()} {item.name}")],
                    unresolvedTarget=None,
                    metadata=self._metadata(result, "DECLARATION", item.stable_key),
                )
            )
            edges.extend(self._type_relation_edges(result, item, type_lookup))
        for item in result.callables:
            nodes.append(
                GraphNode(
                    localId=item.local_id,
                    nodeKind="CALLABLE",
                    name=item.name,
                    language=result.file.language,
                    qualifiedName=item.qualified_name,
                    displayName=item.name,
                    parentLocalId=item.owner_type_local_id,
                    parameter_count=len(item.parameters),
                    parameterTypes=list(item.parameters),
                    lineStart=item.line_start,
                    lineEnd=item.line_end,
                    confidence=1.0,
                    metadata=self._metadata(
                        result,
                        item.callable_kind,
                        item.stable_key,
                        {
                            "signature": item.signature,
                            "returnType": item.return_type,
                            "visibility": item.visibility,
                            "static": item.is_static,
                            "bodyLineStart": item.body_line_start,
                            "bodyLineEnd": item.body_line_end,
                            "annotations": [self._annotation_metadata(annotation) for annotation in item.annotations],
                        },
                    ),
                )
            )
            edges.append(
                GraphEdge(
                    localId=self._stable_key(result, "DECLARES", item.local_id),
                    fromNodeLocalId=item.owner_type_local_id or file_local_id,
                    toNodeLocalId=item.local_id,
                    edgeType="DECLARES",
                    resolutionStatus="RESOLVED",
                    confidence=1.0,
                    evidence=[self._evidence(item.line_start, item.line_end, item.signature)],
                    unresolvedTarget=None,
                    metadata=self._metadata(result, "DECLARATION", item.stable_key),
                )
            )
            owner_annotations = self._type_annotations(result, item.owner_type_local_id)
            claims.extend(self._entrypoint_claims(result, item.local_id, item.annotations, owner_annotations))
            boundaries.extend(self._entrypoint_boundaries(result, item.local_id, item.annotations, owner_annotations))
            main_claim = self._main_entrypoint_claim(result, item.local_id, item, owner_annotations)
            if main_claim:
                claims.append(main_claim)
                main_boundary = self._main_entrypoint_boundary(result, item.local_id, item, owner_annotations)
                if main_boundary:
                    boundaries.append(main_boundary)
        for item in result.fields:
            nodes.append(
                GraphNode(
                    localId=item.local_id,
                    nodeKind="FIELD",
                    name=item.name,
                    language=result.file.language,
                    qualifiedName=item.qualified_name,
                    displayName=item.name,
                    parentLocalId=item.owner_type_local_id,
                    lineStart=item.line_start,
                    lineEnd=item.line_end,
                    confidence=1.0,
                    metadata=self._metadata(
                        result,
                        "FIELD",
                        item.stable_key,
                        {
                            "typeName": item.type_name,
                            "visibility": item.visibility,
                            "annotations": [self._annotation_metadata(annotation) for annotation in item.annotations],
                        },
                    ),
                )
            )
            edges.append(
                GraphEdge(
                    localId=self._stable_key(result, "DECLARES", item.local_id),
                    fromNodeLocalId=item.owner_type_local_id,
                    toNodeLocalId=item.local_id,
                    edgeType="DECLARES",
                    resolutionStatus="RESOLVED",
                    confidence=1.0,
                    evidence=[self._evidence(item.line_start, item.line_end, item.name)],
                    unresolvedTarget=None,
                    metadata=self._metadata(result, "DECLARATION", item.stable_key),
                )
            )
            claims.extend(self._field_config_claims(result, item.local_id, item.annotations))
        for callsite in result.callsites:
            unresolved = None
            edge_resolution_status = "RESOLVED" if callsite.target_callable_local_id else callsite.resolution_status
            if callsite.target_callable_local_id is None:
                unresolved = {
                    "name": callsite.method_name,
                    "receiverText": callsite.receiver_text,
                    "receiverTypeHint": callsite.receiver_type_hint,
                    "targetTypeText": callsite.target_type_text,
                    "kindHint": "CALLABLE",
                }
            resolution_reason = callsite.resolution_reason
            if edge_resolution_status == "RESOLVED" and resolution_reason == "NOT_RESOLVED":
                resolution_reason = None
            call_metadata_extra = {
                "receiverText": callsite.receiver_text,
                "methodName": callsite.method_name,
                "callKind": callsite.call_kind,
                "unresolvedReason": None if edge_resolution_status == "RESOLVED" else callsite.unresolved_reason,
                "resolutionReason": resolution_reason,
            }
            if "METHOD_REFERENCE" in str(callsite.call_kind):
                call_metadata_extra.update(
                    {
                        "receiverTypeHint": callsite.receiver_type_hint,
                        "targetTypeText": callsite.target_type_text,
                    }
                )
            call_metadata = self._metadata(
                result,
                "CALLSITE",
                callsite.stable_key,
                call_metadata_extra,
            )
            call_metadata = classify_call_metadata(
                call_metadata,
                result.file.flow_domain,
                edge_resolution_status,
                unresolved,
            )
            edges.append(
                GraphEdge(
                    localId=callsite.local_id,
                    fromNodeLocalId=callsite.caller_callable_local_id,
                    toNodeLocalId=callsite.target_callable_local_id,
                    edgeType="CALLS",
                    resolutionStatus=edge_resolution_status,
                    argument_count=callsite.argument_count,
                    confidence=1.0 if edge_resolution_status == "RESOLVED" else 0.72,
                    evidence=[self._evidence(callsite.line_start, callsite.line_end, callsite.raw_text, {"evidenceKind": "CALLSITE"})],
                    unresolvedTarget=unresolved,
                    metadata=call_metadata,
                )
            )
            claims.extend(self._call_boundary_claims(result, callsite))
            boundaries.extend(self._call_required_boundaries(result, callsite, call_metadata, edge_resolution_status, unresolved))
            self._collect_field_usage(result, callsite, callables_by_id, fields_by_id, fields_by_owner, field_usages)
        for (caller_local_id, field_local_id), usage in field_usages.items():
            evidence = usage["evidence"]
            edges.append(
                GraphEdge(
                    localId=self._stable_key(result, "USES_FIELD", caller_local_id, field_local_id),
                    fromNodeLocalId=caller_local_id,
                    toNodeLocalId=field_local_id,
                    edgeType="USES_FIELD",
                    resolutionStatus="RESOLVED",
                    confidence=1.0,
                    evidence=evidence,
                    unresolvedTarget=None,
                    metadata=self._metadata(
                        result,
                        "FIELD_USAGE",
                        self._stable_key(result, "USES_FIELD", caller_local_id, field_local_id),
                        {
                            "fieldName": usage.get("fieldName"),
                            "usageLineCount": len(evidence),
                        },
                    ),
                )
            )
        for diagnostic in result.diagnostics:
            diagnostics.append(
                {
                    "severity": diagnostic.severity,
                    "stage": diagnostic.stage,
                    "code": diagnostic.code,
                    "message": diagnostic.message,
                    "lineStart": diagnostic.line_start,
                    "lineEnd": diagnostic.line_end,
                    "metadata": {
                        **(diagnostic.metadata or {}),
                        "factOrigin": "STATIC",
                        "flowDomain": result.file.flow_domain,
                    },
                    "factOrigin": "STATIC",
                    "flowDomain": result.file.flow_domain,
                }
            )
        return GraphAnalysisResult(nodes=nodes, edges=edges, claims=claims, boundaries=boundaries, diagnostics=diagnostics)

    def _collect_field_usage(
        self,
        result: StructuralParseResult,
        callsite: StructuralCallsite,
        callables_by_id: Dict[str, Any],
        fields_by_id: Dict[str, Any],
        fields_by_owner: Dict[str, List[Any]],
        field_usages: Dict[tuple[str, str], Dict[str, Any]],
    ) -> None:
        caller = callables_by_id.get(callsite.caller_callable_local_id)
        if caller is None:
            return
        if callsite.field_receiver_local_id in fields_by_id:
            self._record_field_usage(result, field_usages, callsite, fields_by_id[callsite.field_receiver_local_id])
        for field in fields_by_owner.get(caller.owner_type_local_id or "", []):
            if self._contains_explicit_this_field(callsite.receiver_text, field.name) or self._contains_explicit_this_field(callsite.raw_text, field.name):
                self._record_field_usage(result, field_usages, callsite, field)

    def _record_field_usage(
        self,
        result: StructuralParseResult,
        field_usages: Dict[tuple[str, str], Dict[str, Any]],
        callsite: StructuralCallsite,
        field: Any,
    ) -> None:
        key = (callsite.caller_callable_local_id, field.local_id)
        usage = field_usages.setdefault(
            key,
            {
                "fieldName": field.name,
                "evidence": [],
                "evidenceKeys": set(),
            },
        )
        evidence_key = (callsite.line_start, callsite.line_end)
        if evidence_key in usage["evidenceKeys"]:
            return
        usage["evidenceKeys"].add(evidence_key)
        usage["evidence"].append(
            self._evidence(
                callsite.line_start,
                callsite.line_end,
                callsite.raw_text,
                {
                    "evidenceKind": "CALLSITE",
                    "fieldName": field.name,
                },
            )
        )

    def _contains_explicit_this_field(self, value: Optional[str], field_name: str) -> bool:
        if not value:
            return False
        return re.search(r"(?<![\w$])this\." + re.escape(field_name) + r"\b", value) is not None

    def _type_relation_edges(
        self,
        result: StructuralParseResult,
        item: StructuralType,
        type_lookup: Dict[str, StructuralType],
    ) -> List[GraphEdge]:
        edges: List[GraphEdge] = []
        for target_name in item.implemented_interfaces:
            edges.append(self._type_relation_edge(result, item, target_name, "IMPLEMENTS", "IMPLEMENTED_INTERFACE", type_lookup))
        for target_name in item.extended_classes:
            edges.append(self._type_relation_edge(result, item, target_name, "EXTENDS", "EXTENDED_CLASS", type_lookup))
        for target_name in item.extended_interfaces:
            edges.append(self._type_relation_edge(result, item, target_name, "EXTENDS", "EXTENDED_INTERFACE", type_lookup))
        return edges

    def _type_relation_edge(
        self,
        result: StructuralParseResult,
        source_type: StructuralType,
        target_name: str,
        edge_type: str,
        relation_kind: str,
        type_lookup: Dict[str, StructuralType],
    ) -> GraphEdge:
        resolved_name = self._resolve_type_reference(result, target_name)
        target_type = type_lookup.get(resolved_name) or type_lookup.get(self._simple_type(resolved_name))
        unresolved = None
        resolution_status = "RESOLVED" if target_type is not None else "UNRESOLVED"
        if target_type is None:
            unresolved = {
                "name": self._simple_type(resolved_name),
                "qualifiedName": resolved_name if "." in resolved_name else None,
                "targetTypeText": resolved_name,
                "kindHint": "TYPE",
            }
        stable_key = self._stable_key(result, edge_type, source_type.local_id, relation_kind, resolved_name)
        return GraphEdge(
            localId=stable_key,
            fromNodeLocalId=source_type.local_id,
            toNodeLocalId=target_type.local_id if target_type is not None else None,
            edgeType=edge_type,
            resolutionStatus=resolution_status,
            confidence=1.0,
            evidence=[self._evidence(source_type.line_start, source_type.line_end)],
            unresolvedTarget=unresolved,
            metadata=self._metadata(
                result,
                relation_kind,
                stable_key,
                {
                    "relationKind": relation_kind,
                    "targetTypeText": resolved_name,
                    "resolutionReason": "SAME_FILE_TYPE" if target_type is not None else "NOT_RESOLVED",
                    "unresolvedReason": None if target_type is not None else "TARGET_NOT_ANALYZED",
                },
            ),
        )

    def _type_lookup(self, result: StructuralParseResult) -> Dict[str, StructuralType]:
        lookup: Dict[str, StructuralType] = {}
        for item in result.types:
            lookup[item.name] = item
            lookup[item.qualified_name] = item
        return lookup

    def _resolve_type_reference(self, result: StructuralParseResult, type_name: str) -> str:
        value = str(type_name or "").strip()
        if not value:
            return value
        simple = self._simple_type(value)
        for item in result.imports:
            if item.is_wildcard:
                continue
            if item.imported_name.rsplit(".", 1)[-1] == simple:
                return item.imported_name
        if "." in value:
            return value
        if result.package_name:
            return f"{result.package_name}.{value}"
        return value

    def _simple_type(self, value: str) -> str:
        value = str(value or "").strip()
        value = value.split("<", 1)[0]
        return value.rsplit(".", 1)[-1]

    def _entrypoint_claims(
        self, result: StructuralParseResult, target_local_id: str, annotations: List[StructuralAnnotation], owner_annotations: List[StructuralAnnotation]
    ) -> List[GraphClaim]:
        claims: List[GraphClaim] = []
        owner_route = self._route_from_annotations(owner_annotations)
        interface_method = self._interface_method_name(result, target_local_id)
        execution_kind = EntrypointExecutionKind.CONTRACT_DECLARATION.value if interface_method else EntrypointExecutionKind.EXECUTABLE.value
        for annotation in annotations:
            simple = annotation.name.rsplit(".", 1)[-1]
            if simple not in self.ENTRYPOINT_ANNOTATIONS:
                continue
            route = self._join_routes(owner_route, self._annotation_route(annotation))
            http_method = self._http_method_from_annotation(simple, annotation)
            metadata = self._metadata(
                result,
                "ENTRYPOINT_HINT",
                self._stable_key(result, "ENTRYPOINT", target_local_id, simple, str(annotation.line_start)),
                {
                    "entrypointKind": self._entrypoint_kind(simple),
                    "entrypointExecutionKind": execution_kind,
                    "origin": "STATIC",
                    "annotation": simple,
                    "annotationName": simple,
                    "httpMethod": http_method,
                    "route": route,
                    "interfaceMethod": interface_method if http_method else None,
                    "exceptionType": self._annotation_first_identifier(annotation) if simple == "ExceptionHandler" else None,
                    "topic": self._annotation_route(annotation) if simple == "KafkaListener" else None,
                    "schedule": annotation.arguments_raw if simple == "Scheduled" else None,
                    "sourceAnnotationLine": annotation.line_start,
                },
            )
            claims.append(
                GraphClaim(
                    localId=metadata["stableKey"],
                    nodeLocalId=target_local_id,
                    claimKind="ENTRYPOINT_HINT",
                    summary=self._entrypoint_summary(simple, http_method, route),
                    evidence=[self._evidence(annotation.line_start, annotation.line_end, f"@{simple}")],
                    confidence=1.0,
                    metadata=metadata,
                )
            )
        return claims

    def _entrypoint_boundaries(
        self, result: StructuralParseResult, target_local_id: str, annotations: list[StructuralAnnotation], owner_annotations: list[StructuralAnnotation]
    ) -> list[BoundaryFact]:
        boundaries: list[BoundaryFact] = []
        owner_route = self._route_from_annotations(owner_annotations)
        interface_method = self._interface_method_name(result, target_local_id)
        execution_kind = EntrypointExecutionKind.CONTRACT_DECLARATION.value if interface_method else EntrypointExecutionKind.EXECUTABLE.value
        for annotation in annotations:
            simple = annotation.name.rsplit(".", 1)[-1]
            if simple not in self.ENTRYPOINT_ANNOTATIONS:
                continue
            route = self._join_routes(owner_route, self._annotation_route(annotation))
            http_method = self._http_method_from_annotation(simple, annotation)
            evidence = self._evidence(annotation.line_start, annotation.line_end, f"@{simple}", {"evidenceKind": "BOUNDARY"})
            descriptors = self._provided_boundary_descriptors(
                annotation,
                simple,
                execution_kind,
                http_method,
                route,
                interface_method,
                evidence,
            )
            boundaries.append(
                BoundaryFact(
                    localId=self._stable_key(result, "BOUNDARY", "PROVIDED", target_local_id, simple, str(annotation.line_start)),
                    nodeLocalId=target_local_id,
                    role="PROVIDED",
                    descriptors=descriptors,
                    evidence=[evidence],
                    origin="STATIC",
                    confidence=1.0,
                    flowDomain=result.file.flow_domain,
                    metadata=self._metadata(
                        result,
                        "BOUNDARY",
                        self._stable_key(result, "BOUNDARY", "PROVIDED", target_local_id, simple, str(annotation.line_start)),
                    ),
                )
            )
        return boundaries

    def _interface_method_name(self, result: StructuralParseResult, target_local_id: str) -> Optional[str]:
        target = next((item for item in result.callables if item.local_id == target_local_id), None)
        if target is None or not target.owner_type_local_id:
            return None
        owner = next((item for item in result.types if item.local_id == target.owner_type_local_id), None)
        if owner is None or owner.type_kind != "INTERFACE":
            return None
        return target.qualified_name

    def _main_entrypoint_claim(
        self, result: StructuralParseResult, target_local_id: str, callable_item, owner_annotations: List[StructuralAnnotation]
    ) -> Optional[GraphClaim]:
        owner_annotation_names = {annotation.name.rsplit(".", 1)[-1] for annotation in owner_annotations}
        if callable_item.name != "main" or "String[]" not in ",".join(callable_item.parameters):
            return None
        if "SpringBootApplication" not in owner_annotation_names and callable_item.visibility != "PUBLIC":
            return None
        metadata = self._metadata(
            result,
            "ENTRYPOINT_HINT",
            self._stable_key(result, "ENTRYPOINT", target_local_id, "MAIN", str(callable_item.line_start)),
            {
                "entrypointKind": EntrypointKind.BOOTSTRAP.value,
                "entrypointExecutionKind": EntrypointExecutionKind.EXECUTABLE.value,
                "annotation": "SpringBootApplication" if "SpringBootApplication" in owner_annotation_names else None,
                "sourceAnnotationLine": callable_item.line_start,
            },
        )
        return GraphClaim(
            localId=metadata["stableKey"],
            nodeLocalId=target_local_id,
            claimKind="ENTRYPOINT_HINT",
            summary="Starts the application bootstrap flow.",
            evidence=[self._evidence(callable_item.line_start, callable_item.line_end, callable_item.signature)],
            confidence=1.0 if "SpringBootApplication" in owner_annotation_names else 0.7,
            metadata=metadata,
        )

    def _main_entrypoint_boundary(
        self, result: StructuralParseResult, target_local_id: str, callable_item, owner_annotations: list[StructuralAnnotation]
    ) -> BoundaryFact | None:
        owner_annotation_names = {annotation.name.rsplit(".", 1)[-1] for annotation in owner_annotations}
        if callable_item.name != "main" or "String[]" not in ",".join(callable_item.parameters):
            return None
        if "SpringBootApplication" not in owner_annotation_names and callable_item.visibility != "PUBLIC":
            return None
        evidence = self._evidence(callable_item.line_start, callable_item.line_end, callable_item.signature, {"evidenceKind": "BOUNDARY"})
        local_id = self._stable_key(result, "BOUNDARY", "PROVIDED", target_local_id, "MAIN", str(callable_item.line_start))
        return BoundaryFact(
            localId=local_id,
            nodeLocalId=target_local_id,
            role="PROVIDED",
            descriptors=[
                self._descriptor("provided.kind", EntrypointKind.BOOTSTRAP.value, evidence=evidence),
                self._descriptor("provided.executionKind", EntrypointExecutionKind.EXECUTABLE.value, evidence=evidence),
                self._descriptor("callable.name", callable_item.name, evidence=evidence),
                self._descriptor("callable.signature", callable_item.signature, evidence=evidence),
            ],
            evidence=[evidence],
            origin="STATIC",
            confidence=1.0 if "SpringBootApplication" in owner_annotation_names else 0.7,
            flowDomain=result.file.flow_domain,
            metadata=self._metadata(result, "BOUNDARY", local_id),
        )

    def _call_boundary_claims(self, result: StructuralParseResult, callsite: StructuralCallsite) -> List[GraphClaim]:
        return []

    def _call_required_boundaries(
        self,
        result: StructuralParseResult,
        callsite: StructuralCallsite,
        call_metadata: dict[str, Any],
        edge_resolution_status: str,
        unresolved: dict[str, Any] | None,
    ) -> list[BoundaryFact]:
        status = str(edge_resolution_status or "").upper()
        if status == "RESOLVED":
            return []
        if not self._material_required_call_boundary(call_metadata, status):
            return []
        evidence = self._evidence(callsite.line_start, callsite.line_end, callsite.raw_text, {"evidenceKind": "BOUNDARY"})
        descriptors = [
            self._descriptor("call.method", callsite.method_name, evidence=evidence, confidence=0.82),
            self._descriptor("call.kind", call_metadata.get("callKind"), evidence=evidence, confidence=0.82),
            self._descriptor("call.resolutionStatus", status, evidence=evidence, confidence=0.82),
            self._descriptor("call.argumentCount", callsite.argument_count, evidence=evidence, confidence=0.82),
            self._descriptor("call.targetCategory", call_metadata.get("callTargetCategory"), evidence=evidence, confidence=0.72),
            self._descriptor("call.receiver", call_metadata.get("receiverText") or callsite.receiver_text, evidence=evidence, confidence=0.82),
            self._descriptor("call.receiverTypeHint", call_metadata.get("receiverTypeHint") or callsite.receiver_type_hint, evidence=evidence, confidence=0.72),
            self._descriptor("call.targetTypeHint", call_metadata.get("targetTypeHint") or callsite.target_type_text, evidence=evidence, confidence=0.72),
            self._descriptor("call.visibility", call_metadata.get("sliceDefaultVisibility"), evidence=evidence, confidence=0.72),
        ]
        for key, path in (
            ("transportKind", "transport.kind"),
            ("connectorKind", "connector.kind"),
            ("httpMethod", "http.method"),
            ("method", "operation.method"),
            ("route", "operation.route"),
            ("routeTemplate", "operation.routeTemplate"),
            ("operationIdentity", "operation.identity"),
            ("interfaceIdentity", "interface.identity"),
            ("interfaceMethod", "interface.method"),
            ("requestContractIdentity", "contract.request"),
            ("responseContractIdentity", "contract.response"),
            ("targetServiceIdentity", "target.serviceIdentity"),
            ("targetEntrypoint", "target.entrypoint"),
        ):
            descriptors.append(self._descriptor(path, call_metadata.get(key), evidence=evidence, confidence=0.82))
        if unresolved:
            descriptors.append(self._descriptor("unresolvedTarget", unresolved, evidence=evidence, confidence=0.72))
            for key in sorted(unresolved):
                descriptors.append(self._descriptor(f"unresolvedTarget.{key}", unresolved.get(key), evidence=evidence, confidence=0.72))
        descriptors = [item for item in descriptors if item is not None]
        if not descriptors:
            return []
        local_id = self._stable_key(result, "BOUNDARY", "REQUIRED", callsite.local_id)
        return [
            BoundaryFact(
                localId=local_id,
                nodeLocalId=callsite.caller_callable_local_id,
                role="REQUIRED",
                descriptors=descriptors,
                evidence=[evidence],
                origin="STATIC",
                confidence=0.72 if status in {"UNRESOLVED", "MULTIPLE_CANDIDATES"} else 0.82,
                flowDomain=result.file.flow_domain,
                metadata=self._metadata(result, "BOUNDARY", local_id),
            )
        ]

    def _material_required_call_boundary(self, metadata: dict[str, Any], status: str) -> bool:
        category = str(metadata.get("callTargetCategory") or "").upper()
        if status in {"EXTERNAL_TARGET", "DYNAMIC_TARGET", "INTERFACE_TARGET"}:
            return category not in {"EXTERNAL_JDK", "EXTERNAL_FRAMEWORK"}
        if status in {"UNRESOLVED", "MULTIPLE_CANDIDATES", "AMBIGUOUS"}:
            return str(metadata.get("sliceDefaultVisibility") or "").upper() in {"SHOW", "SHOW_AS_UNCERTAINTY"}
        return False

    def _provided_boundary_descriptors(
        self,
        annotation: StructuralAnnotation,
        annotation_name: str,
        execution_kind: str,
        http_method: str | None,
        route: str | None,
        interface_method: str | None,
        evidence: GraphEvidenceRef,
    ) -> list[BoundaryDescriptor]:
        descriptors: list[BoundaryDescriptor | None] = [
            self._descriptor("annotation.name", annotation_name, evidence=evidence),
            self._descriptor("annotation.qualifiedName", annotation.name, evidence=evidence),
            self._descriptor("provided.kind", self._entrypoint_kind(annotation_name), evidence=evidence),
            self._descriptor("provided.executionKind", execution_kind, evidence=evidence),
            self._descriptor("annotation.argumentsRaw", annotation.arguments_raw, evidence=evidence),
            self._descriptor("source.annotationLine", annotation.line_start, evidence=evidence),
            self._descriptor("interface.method", interface_method, evidence=evidence),
        ]
        if http_method:
            descriptors.append(self._descriptor("http.method", http_method, evidence=evidence))
        if self._entrypoint_kind(annotation_name) == EntrypointKind.HTTP.value and route:
            descriptors.append(self._descriptor("http.route", route, evidence=evidence))
        if annotation_name == "KafkaListener":
            descriptors.append(self._descriptor("messaging.topic", self._annotation_route(annotation), evidence=evidence))
        if annotation_name == "Scheduled":
            descriptors.append(self._descriptor("schedule.expression", annotation.arguments_raw, evidence=evidence))
        if annotation_name == "ExceptionHandler":
            descriptors.append(self._descriptor("exception.type", self._annotation_first_identifier(annotation), evidence=evidence))
        return [item for item in descriptors if item is not None]

    def _field_config_claims(self, result: StructuralParseResult, target_local_id: str, annotations: List[StructuralAnnotation]) -> List[GraphClaim]:
        claims: List[GraphClaim] = []
        for annotation in annotations:
            simple = annotation.name.rsplit(".", 1)[-1]
            if simple != "Value":
                continue
            metadata = self._metadata(
                result,
                "CONFIG_REFERENCE",
                self._stable_key(result, "CONFIG", target_local_id, str(annotation.line_start)),
                {
                    "annotationName": simple,
                    "propertyExpression": self._annotation_route(annotation),
                },
            )
            claims.append(
                GraphClaim(
                    localId=metadata["stableKey"],
                    nodeLocalId=target_local_id,
                    claimKind="CONFIG_REFERENCE",
                    summary="References a configuration property value.",
                    evidence=[self._evidence(annotation.line_start, annotation.line_end, "@Value")],
                    confidence=1.0,
                    metadata=metadata,
                )
            )
        return claims

    def _type_annotations(self, result: StructuralParseResult, type_local_id: Optional[str]) -> List[StructuralAnnotation]:
        if not type_local_id:
            return []
        for item in result.types:
            if item.local_id == type_local_id:
                return item.annotations
        return []

    def _route_from_annotations(self, annotations: List[StructuralAnnotation]) -> Optional[str]:
        for annotation in annotations:
            simple = annotation.name.rsplit(".", 1)[-1]
            if simple in {"RequestMapping", *self.HTTP_METHODS.keys()}:
                return self._annotation_route(annotation)
        return None

    def _annotation_route(self, annotation: StructuralAnnotation) -> Optional[str]:
        raw = annotation.arguments_raw or ""
        match = re.search(r'"([^"]*)"', raw)
        return match.group(1) if match else None

    def _http_method_from_annotation(self, annotation_name: str, annotation: StructuralAnnotation) -> Optional[str]:
        method = self.HTTP_METHODS.get(annotation_name)
        if method is not None or annotation_name != "RequestMapping":
            return method
        raw = annotation.arguments_raw or ""
        match = re.search(r"\bRequestMethod\s*\.\s*(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|TRACE)\b", raw)
        return match.group(1) if match else None

    def _annotation_first_identifier(self, annotation: StructuralAnnotation) -> Optional[str]:
        raw = annotation.arguments_raw or ""
        match = re.search(r"([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.class", raw)
        return match.group(1) if match else None

    def _join_routes(self, prefix: Optional[str], route: Optional[str]) -> Optional[str]:
        if not prefix:
            return route
        if not route:
            return prefix
        return f"/{prefix.strip('/')}/{route.strip('/')}"

    def _entrypoint_summary(self, annotation_name: str, http_method: Optional[str], route: Optional[str]) -> str:
        if http_method:
            return f"Handles {http_method} requests" + (f" for {route}." if route else ".")
        if annotation_name == "ExceptionHandler":
            return "Handles exceptions for the application."
        if annotation_name == "KafkaListener":
            return "Consumes messages from a configured listener."
        if annotation_name == "Scheduled":
            return "Runs as a scheduled task."
        if annotation_name == "PostConstruct":
            return "Runs during component initialization."
        if annotation_name == "Bean":
            return "Creates a configured bean."
        if annotation_name == "Test":
            return "Runs as a test entrypoint."
        return f"Acts as an entrypoint via @{annotation_name}."

    def _entrypoint_kind(self, annotation_name: str) -> str:
        kind = entrypoint_kind_for_annotation(
            annotation_name,
            is_http=annotation_name in self.HTTP_METHODS or annotation_name == "RequestMapping",
        )
        return kind or EntrypointKind.MESSAGE.value

    def _metadata(self, result: StructuralParseResult, source_kind: str, stable_key: str, extra: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        metadata = {
            "sourceKind": source_kind,
            "stableKey": stable_key,
            "factOrigin": "STATIC",
            "flowDomain": result.file.flow_domain,
            "parser": "tree-sitter-java" if result.file.language == "java" else "static-file",
        }
        metadata.update({k: v for k, v in (extra or {}).items() if v is not None})
        return metadata

    def _annotation_metadata(self, annotation: StructuralAnnotation) -> Dict[str, Any]:
        return {
            "name": annotation.name,
            "argumentsRaw": annotation.arguments_raw,
            "lineStart": annotation.line_start,
            "lineEnd": annotation.line_end,
        }

    def _evidence(self, line_start: int, line_end: int, text: Optional[str] = None, metadata: Optional[Dict[str, Any]] = None) -> GraphEvidenceRef:
        return GraphEvidenceRef(
            lineStart=line_start,
            lineEnd=line_end,
            text=text,
            metadata=metadata or {},
        )

    def _descriptor(
        self,
        path: str,
        value: Any,
        *,
        evidence: GraphEvidenceRef,
        confidence: float = 1.0,
    ) -> BoundaryDescriptor | None:
        if value is None:
            return None
        if isinstance(value, str) and not value.strip():
            return None
        return BoundaryDescriptor(
            path=path,
            value=value,
            origin="STATIC",
            confidence=confidence,
            evidence=[evidence],
        )

    def _stable_key(self, result: StructuralParseResult, *parts: str) -> str:
        return "|".join([result.file.source_id, result.file.relative_path, *[str(part) for part in parts]])
