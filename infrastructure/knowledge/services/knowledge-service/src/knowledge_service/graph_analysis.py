from __future__ import annotations

import ast
import hashlib
import json
import re
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence

from knowledge_service.graph_model import (
    GraphClaimFact,
    GraphDiagnostic,
    GraphEdgeFact,
    GraphEvidenceFact,
    GraphMaterialization,
    GraphNodeFact,
)
from knowledge_service.graph_schema import (
    MIN_TRUSTED_CONFIDENCE,
    GraphAnalysisFile,
    GraphAnalysisResponse,
    GraphClaimCandidate,
    GraphClaimKind,
    GraphDiagnosticCode,
    GraphDiagnosticSeverity,
    GraphDiagnosticStage,
    GraphEdgeCandidate,
    GraphEdgeType,
    GraphEvidenceKind,
    GraphEvidenceRange,
    GraphFactOrigin,
    GraphFactStatus,
    GraphFlowDomain,
    GraphNodeCandidate,
    GraphNodeKind,
    GraphResolutionStatus,
    classify_flow_domain,
    enum_values,
)
from knowledge_service.graph_validation import GraphValidationError, GraphValidationErrorCode, enum_validation_error
from knowledge_service.inventory_file_resolver import InventoryFileContent


class StaticGraphSeedExtractor:
    _JAVA_TYPE_RE = re.compile(r"\b(class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)")
    _JAVA_IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([A-Za-z_][A-Za-z0-9_.*]*);")
    _JAVA_PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*);")
    _JAVA_FIELD_RE = re.compile(
        r"^\s*(?:public|protected|private|static|final|transient|volatile|\s)*"
        r"(?P<type>[A-Za-z_][A-Za-z0-9_.$]*(?:<[^;=()]+>)?(?:\[\])?)\s+"
        r"(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*(?:=|;)"
    )
    _JAVA_METHOD_RE = re.compile(
        r"^\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|strictfp|default)\s+)*"
        r"(?:<[^>{;]+>\s*)?"
        r"(?:(?P<return>[A-Za-z_][A-Za-z0-9_.$<>\[\], ?]*?)\s+)?"
        r"(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*\((?P<params>[^()]*)\)\s*(?:throws\s+[^{;]+)?(?:[;{]|$)"
    )
    _JAVA_CONTROL_WORDS = {"if", "for", "while", "switch", "catch", "return", "throw", "new", "do", "else", "try", "synchronized"}

    def extract(self, context: Dict[str, Any]) -> tuple[List[GraphNodeCandidate], List[GraphEdgeCandidate]]:
        line_count = context["lineCount"]
        nodes = [
            GraphNodeCandidate(
                localId="__file__",
                nodeKind=GraphNodeKind.FILE.value,
                name=Path(context["relativePath"]).name,
                qualifiedName=context["relativePath"],
                displayName=context["relativePath"],
                lineStart=1 if line_count > 0 else None,
                lineEnd=line_count if line_count > 0 else None,
                confidence=1.0,
                metadata={"deterministic": True},
            )
        ]
        edges: List[GraphEdgeCandidate] = []
        language = context.get("language")
        if language == "java":
            self._extract_java(context, nodes, edges)
        elif language == "python":
            self._extract_python(context, nodes, edges)
        return nodes, edges

    def _extract_java(self, context: Dict[str, Any], nodes: List[GraphNodeCandidate], edges: List[GraphEdgeCandidate]) -> None:
        sanitized_lines = self._strip_java_comments_and_strings(context["lines"])
        package_name = ""
        depth = 0
        type_stack: List[Dict[str, Any]] = []
        seen_imports: set[str] = set()
        seen_types: set[str] = set()
        callable_counts: Dict[str, int] = {}
        for line_number, line in enumerate(sanitized_lines, start=1):
            stripped = line.strip()
            package_match = self._JAVA_PACKAGE_RE.match(stripped)
            if package_match:
                package_name = package_match.group(1)
            import_match = self._JAVA_IMPORT_RE.match(stripped)
            if import_match:
                import_name = import_match.group(1)
                if import_name not in seen_imports:
                    seen_imports.add(import_name)
                    import_id = self._local_id("static:import", import_name)
                    nodes.append(GraphNodeCandidate(
                        localId=import_id,
                        nodeKind=GraphNodeKind.EXTERNAL.value,
                        name=import_name.split(".")[-1] if import_name else import_name,
                        qualifiedName=import_name,
                        displayName=import_name,
                        parentLocalId=None,
                        lineStart=line_number,
                        lineEnd=line_number,
                        confidence=1.0,
                        metadata={"deterministic": True, "staticExtractor": "java", "import": True},
                    ))
                    edges.append(self._static_edge(
                        f"static:edge:import:{import_name}",
                        GraphEdgeType.IMPORTS,
                        "__file__",
                        import_id,
                        line_number,
                        {"deterministic": True, "staticExtractor": "java", "import": import_name},
                    ))
            self._pop_closed_types(type_stack, depth)
            type_match = self._JAVA_TYPE_RE.search(stripped)
            if type_match:
                type_kind = type_match.group(1)
                type_name = type_match.group(2)
                parent = type_stack[-1] if type_stack else None
                qualified_name = self._java_qualified_type(package_name, parent, type_name)
                if qualified_name not in seen_types:
                    seen_types.add(qualified_name)
                    type_id = self._local_id("static:type", qualified_name)
                    parent_id = parent["localId"] if parent else "__file__"
                    nodes.append(GraphNodeCandidate(
                        localId=type_id,
                        nodeKind=GraphNodeKind.TYPE.value,
                        name=type_name,
                        qualifiedName=qualified_name,
                        displayName=qualified_name,
                        parentLocalId=parent_id,
                        lineStart=line_number,
                        lineEnd=line_number,
                        confidence=1.0,
                        metadata={
                            "deterministic": True,
                            "staticExtractor": "java",
                            "declarationKind": type_kind,
                            "package": package_name or None,
                            "interface": type_kind == "interface",
                        },
                    ))
                    edges.append(self._static_edge(
                        f"static:edge:contains:{parent_id}:{type_id}",
                        GraphEdgeType.CONTAINS,
                        parent_id,
                        type_id,
                        line_number,
                        {"deterministic": True, "staticExtractor": "java"},
                    ))
                    if "{" in stripped:
                        type_stack.append({"name": type_name, "qualifiedName": qualified_name, "localId": type_id, "bodyDepth": depth + line.count("{")})
            elif type_stack and depth == type_stack[-1]["bodyDepth"]:
                self._extract_java_field_or_callable(line, line_number, type_stack[-1], nodes, edges, callable_counts)
            depth += self._brace_delta(line)
            self._pop_closed_types(type_stack, depth)

    def _extract_java_field_or_callable(
        self,
        line: str,
        line_number: int,
        parent: Dict[str, Any],
        nodes: List[GraphNodeCandidate],
        edges: List[GraphEdgeCandidate],
        callable_counts: Dict[str, int],
    ) -> None:
        stripped = line.strip()
        if not stripped or stripped.startswith("@"):
            return
        if "(" not in stripped:
            field_match = self._JAVA_FIELD_RE.match(stripped)
            if field_match:
                field_name = field_match.group("name")
                type_name = field_match.group("type").strip()
                qualified_name = f"{parent['qualifiedName']}.{field_name}"
                field_id = self._local_id("static:field", qualified_name)
                nodes.append(GraphNodeCandidate(
                    localId=field_id,
                    nodeKind=GraphNodeKind.FIELD.value,
                    name=field_name,
                    qualifiedName=qualified_name,
                    displayName=qualified_name,
                    parentLocalId=parent["localId"],
                    lineStart=line_number,
                    lineEnd=line_number,
                    confidence=1.0,
                    metadata={
                        "deterministic": True,
                        "staticExtractor": "java",
                        "typeName": type_name,
                        "receiverName": field_name,
                    },
                ))
                edges.append(self._static_edge(
                    f"static:edge:contains:{parent['localId']}:{field_id}",
                    GraphEdgeType.CONTAINS,
                    parent["localId"],
                    field_id,
                    line_number,
                    {"deterministic": True, "staticExtractor": "java"},
                ))
            return
        method_match = self._JAVA_METHOD_RE.match(stripped)
        if not method_match:
            return
        method_name = method_match.group("name")
        if method_name in self._JAVA_CONTROL_WORDS:
            return
        if stripped.endswith(";") and method_match.group("return") is None and method_name != parent["name"]:
            return
        params = " ".join((method_match.group("params") or "").split())
        signature = f"{method_name}({params})"
        base_qualified_name = f"{parent['qualifiedName']}.{method_name}"
        callable_counts[base_qualified_name] = callable_counts.get(base_qualified_name, 0) + 1
        qualified_name = base_qualified_name
        if callable_counts[base_qualified_name] > 1:
            qualified_name = f"{base_qualified_name}({self._param_type_fingerprint(params)})"
        callable_id = self._local_id("static:callable", f"{qualified_name}:{line_number}")
        nodes.append(GraphNodeCandidate(
            localId=callable_id,
            nodeKind=GraphNodeKind.CALLABLE.value,
            name=method_name,
            qualifiedName=qualified_name,
            displayName=qualified_name,
            parentLocalId=parent["localId"],
            lineStart=line_number,
            lineEnd=line_number,
            confidence=1.0,
            metadata={
                "deterministic": True,
                "staticExtractor": "java",
                "signature": signature,
                "constructor": method_name == parent["name"],
                "declaringType": parent["qualifiedName"],
            },
        ))
        edges.append(self._static_edge(
            f"static:edge:contains:{parent['localId']}:{callable_id}",
            GraphEdgeType.CONTAINS,
            parent["localId"],
            callable_id,
            line_number,
            {"deterministic": True, "staticExtractor": "java"},
        ))

    def _extract_python(self, context: Dict[str, Any], nodes: List[GraphNodeCandidate], edges: List[GraphEdgeCandidate]) -> None:
        try:
            tree = ast.parse("\n".join(context["lines"]))
        except SyntaxError:
            return
        module_name = self._module_name(context["relativePath"])
        seen_imports: set[str] = set()

        def add_import(import_name: str, line_number: int) -> None:
            if import_name in seen_imports:
                return
            seen_imports.add(import_name)
            import_id = self._local_id("static:import", import_name)
            nodes.append(GraphNodeCandidate(
                localId=import_id,
                nodeKind=GraphNodeKind.EXTERNAL.value,
                name=import_name.split(".")[-1],
                qualifiedName=import_name,
                displayName=import_name,
                lineStart=line_number,
                lineEnd=line_number,
                confidence=1.0,
                metadata={"deterministic": True, "staticExtractor": "python", "import": True},
            ))
            edges.append(self._static_edge(
                f"static:edge:import:{import_name}",
                GraphEdgeType.IMPORTS,
                "__file__",
                import_id,
                line_number,
                {"deterministic": True, "staticExtractor": "python", "import": import_name},
            ))

        def visit(body: List[ast.stmt], parent_id: str, parent_qualified_name: str, parent_kind: str) -> None:
            for item in body:
                if isinstance(item, ast.Import):
                    for alias in item.names:
                        add_import(alias.name, item.lineno)
                    continue
                if isinstance(item, ast.ImportFrom):
                    module = "." * item.level + (item.module or "")
                    for alias in item.names:
                        add_import(f"{module}.{alias.name}".strip("."), item.lineno)
                    continue
                if isinstance(item, ast.ClassDef):
                    qualified_name = f"{parent_qualified_name}.{item.name}" if parent_qualified_name else item.name
                    class_id = self._local_id("static:type", qualified_name)
                    nodes.append(GraphNodeCandidate(
                        localId=class_id,
                        nodeKind=GraphNodeKind.TYPE.value,
                        name=item.name,
                        qualifiedName=qualified_name,
                        displayName=qualified_name,
                        parentLocalId=parent_id,
                        lineStart=item.lineno,
                        lineEnd=getattr(item, "end_lineno", item.lineno),
                        confidence=1.0,
                        metadata={"deterministic": True, "staticExtractor": "python", "declarationKind": "class"},
                    ))
                    edges.append(self._static_edge(
                        f"static:edge:contains:{parent_id}:{class_id}",
                        GraphEdgeType.CONTAINS,
                        parent_id,
                        class_id,
                        item.lineno,
                        {"deterministic": True, "staticExtractor": "python"},
                    ))
                    visit(item.body, class_id, qualified_name, GraphNodeKind.TYPE.value)
                    continue
                if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    qualified_name = f"{parent_qualified_name}.{item.name}" if parent_qualified_name else item.name
                    callable_id = self._local_id("static:callable", f"{qualified_name}:{item.lineno}")
                    nodes.append(GraphNodeCandidate(
                        localId=callable_id,
                        nodeKind=GraphNodeKind.CALLABLE.value,
                        name=item.name,
                        qualifiedName=qualified_name,
                        displayName=qualified_name,
                        parentLocalId=parent_id,
                        lineStart=item.lineno,
                        lineEnd=getattr(item, "end_lineno", item.lineno),
                        confidence=1.0,
                        metadata={
                            "deterministic": True,
                            "staticExtractor": "python",
                            "async": isinstance(item, ast.AsyncFunctionDef),
                            "declaringType": parent_qualified_name if parent_kind == GraphNodeKind.TYPE.value else None,
                        },
                    ))
                    edges.append(self._static_edge(
                        f"static:edge:contains:{parent_id}:{callable_id}",
                        GraphEdgeType.CONTAINS,
                        parent_id,
                        callable_id,
                        item.lineno,
                        {"deterministic": True, "staticExtractor": "python"},
                    ))

        visit(tree.body, "__file__", module_name, GraphNodeKind.FILE.value)

    def _static_edge(
        self,
        local_id: str,
        edge_type: GraphEdgeType,
        from_local_id: str,
        to_local_id: str,
        line_number: int,
        metadata: Dict[str, Any],
    ) -> GraphEdgeCandidate:
        return GraphEdgeCandidate(
            localId=self._local_id("static:edge", local_id),
            edgeType=edge_type.value,
            fromLocalId=from_local_id,
            toLocalId=to_local_id,
            lineStart=line_number,
            lineEnd=line_number,
            confidence=1.0,
            metadata=metadata,
        )

    def _strip_java_comments_and_strings(self, lines: Sequence[str]) -> List[str]:
        result: List[str] = []
        in_block_comment = False
        for line in lines:
            output: List[str] = []
            index = 0
            while index < len(line):
                if in_block_comment:
                    end = line.find("*/", index)
                    if end == -1:
                        break
                    in_block_comment = False
                    index = end + 2
                    continue
                if line.startswith("//", index):
                    break
                if line.startswith("/*", index):
                    in_block_comment = True
                    index += 2
                    continue
                char = line[index]
                if char in {'"', "'"}:
                    quote = char
                    output.append(" ")
                    index += 1
                    while index < len(line):
                        if line[index] == "\\":
                            index += 2
                            continue
                        if line[index] == quote:
                            index += 1
                            break
                        index += 1
                    continue
                output.append(char)
                index += 1
            result.append("".join(output))
        return result

    def _java_qualified_type(self, package_name: str, parent: Optional[Dict[str, Any]], type_name: str) -> str:
        if parent:
            return f"{parent['qualifiedName']}.{type_name}"
        return f"{package_name}.{type_name}" if package_name else type_name

    def _brace_delta(self, line: str) -> int:
        return line.count("{") - line.count("}")

    def _pop_closed_types(self, type_stack: List[Dict[str, Any]], depth: int) -> None:
        while type_stack and depth < type_stack[-1]["bodyDepth"]:
            type_stack.pop()

    def _param_type_fingerprint(self, params: str) -> str:
        if not params:
            return ""
        result: List[str] = []
        for param in params.split(","):
            tokens = [token for token in param.strip().split() if token and not token.startswith("@")]
            if not tokens:
                continue
            result.append(tokens[0].replace("...", "[]"))
        return ",".join(result)

    def _module_name(self, relative_path: str) -> str:
        path = Path(relative_path)
        parts = list(path.with_suffix("").parts)
        return ".".join(part for part in parts if part and part != "__init__")

    def _local_id(self, prefix: str, value: str) -> str:
        digest = hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]
        return f"{prefix}:{digest}"


class GraphAnalysisEngine:
    def __init__(self) -> None:
        self.static_seed = StaticGraphSeedExtractor()

    def materialize(
        self,
        job_id: str,
        row: Any,
        file_content: InventoryFileContent,
        ai_result: Any,
        analyzer_name: str,
        analyzer_version: str,
    ) -> GraphMaterialization:
        context = self._context(row, file_content, analyzer_name, analyzer_version)
        response = self._coerce_response(ai_result)
        materialized = GraphMaterialization()
        static_nodes, static_edges = self.static_seed.extract(context)
        ai_nodes: List[GraphNodeCandidate] = []
        ai_edges: List[GraphEdgeCandidate] = []
        candidate_claims: List[GraphClaimCandidate] = []

        if self._file_identity_matches(response.file, context):
            ai_nodes.extend(response.nodes)
            ai_edges.extend(response.edges)
            candidate_claims.extend(response.claims)
            for diagnostic in response.diagnostics:
                materialized.diagnostics.append(self._diagnostic(
                    context,
                    GraphDiagnosticStage.AI_CALL,
                    GraphDiagnosticCode.AI_DIAGNOSTIC,
                    self._severity(diagnostic.get("severity")),
                    diagnostic.get("message") or "AI analyzer returned a diagnostic.",
                    metadata=diagnostic,
                ))
        else:
            validation_error = GraphValidationError(
                code=GraphValidationErrorCode.FILE_IDENTITY_MISMATCH,
                path="$.file",
                stage=GraphDiagnosticStage.CANDIDATE_VALIDATE,
                message="AI graph response file identity does not match the selected inventory file.",
                expected={
                    "sourceId": context["sourceId"],
                    "inventoryFileId": context["inventoryFileId"],
                    "relativePath": context["relativePath"],
                    "contentHash": context["contentHash"],
                    "lineCount": context["lineCount"],
                },
                actual=response.file.dict(),
                repair_hint="Use the exact file identity from the provided file metadata. Do not analyze a different file.",
            )
            materialized.diagnostics.append(self._diagnostic(
                context,
                GraphDiagnosticStage.CANDIDATE_VALIDATE,
                GraphDiagnosticCode.FILE_IDENTITY_MISMATCH,
                GraphDiagnosticSeverity.ERROR,
                "AI graph response file identity does not match the selected inventory file.",
                metadata={"responseFile": response.file.dict(), "validationError": validation_error.to_dict()},
            ))

        candidate_nodes, candidate_edges = self._merge_static_and_ai_candidates(static_nodes, static_edges, ai_nodes, ai_edges)
        node_facts, local_to_node, node_evidence = self._trusted_nodes(job_id, context, candidate_nodes, materialized.diagnostics)
        materialized.nodes.extend(node_facts)
        materialized.evidence.extend(node_evidence)

        edge_facts, edge_evidence = self._trusted_edges(job_id, context, candidate_edges, local_to_node, materialized.diagnostics)
        materialized.edges.extend(edge_facts)
        materialized.evidence.extend(edge_evidence)
        materialized.edges.extend(self._derived_contains_edges(job_id, context, materialized.nodes, materialized.edges))

        claim_facts, claim_evidence = self._trusted_claims(job_id, context, candidate_claims, local_to_node, materialized.diagnostics)
        materialized.claims.extend(claim_facts)
        materialized.evidence.extend(claim_evidence)
        materialized.claims.extend(self._derived_type_responsibilities(job_id, context, materialized.nodes, materialized.claims))
        return materialized

    def _context(self, row: Any, file_content: InventoryFileContent, analyzer_name: str, analyzer_version: str) -> Dict[str, Any]:
        return {
            "sourceId": row["source_id"],
            "inventoryFileId": row["id"],
            "analysisFileId": row["id"],
            "relativePath": row["relative_path"],
            "extension": row["extension"],
            "language": self._language(row),
            "flowDomain": self._flow_domain(row),
            "sizeBytes": row["size_bytes"],
            "contentHash": row["content_hash"],
            "lineCount": file_content.lineCount,
            "decodePolicy": file_content.decodePolicy,
            "lines": file_content.lines,
            "analyzerName": analyzer_name,
            "analyzerVersion": analyzer_version,
        }

    def _coerce_response(self, ai_result: Any) -> GraphAnalysisResponse:
        if isinstance(ai_result, GraphAnalysisResponse):
            return ai_result
        if isinstance(ai_result, dict):
            return GraphAnalysisResponse.parse_obj(ai_result)
        raise TypeError("Unsupported graph analysis result type")

    def _merge_static_and_ai_candidates(
        self,
        static_nodes: Sequence[GraphNodeCandidate],
        static_edges: Sequence[GraphEdgeCandidate],
        ai_nodes: Sequence[GraphNodeCandidate],
        ai_edges: Sequence[GraphEdgeCandidate],
    ) -> tuple[List[GraphNodeCandidate], List[GraphEdgeCandidate]]:
        ai_identity_to_local: Dict[str, str] = {}
        for node in ai_nodes:
            identity = self._node_identity(node)
            if identity and identity not in ai_identity_to_local:
                ai_identity_to_local[identity] = node.localId
        local_id_remap: Dict[str, str] = {}
        dropped_static_targets: set[str] = set()
        merged_static_nodes: List[GraphNodeCandidate] = []
        for node in static_nodes:
            identity = self._node_identity(node)
            replacement = ai_identity_to_local.get(identity) if node.nodeKind != GraphNodeKind.FILE.value else None
            if replacement:
                local_id_remap[node.localId] = replacement
                dropped_static_targets.add(node.localId)
                continue
            parent_local_id = self._remap_local_id(node.parentLocalId, local_id_remap)
            merged_static_nodes.append(node.copy(update={"parentLocalId": parent_local_id}) if parent_local_id != node.parentLocalId else node)
        merged_static_edges: List[GraphEdgeCandidate] = []
        for edge in static_edges:
            if edge.toLocalId in dropped_static_targets:
                continue
            from_local_id = self._remap_local_id(edge.fromLocalId, local_id_remap)
            to_local_id = self._remap_local_id(edge.toLocalId, local_id_remap)
            if from_local_id != edge.fromLocalId or to_local_id != edge.toLocalId:
                edge = edge.copy(update={"fromLocalId": from_local_id, "toLocalId": to_local_id})
            merged_static_edges.append(edge)
        return [*merged_static_nodes, *ai_nodes], [*merged_static_edges, *ai_edges]

    def _node_identity(self, node: GraphNodeCandidate) -> Optional[str]:
        name = node.qualifiedName or node.displayName or node.name
        if not name:
            return None
        return f"{node.nodeKind}:{name}"

    def _remap_local_id(self, value: Optional[str], remap: Dict[str, str]) -> Optional[str]:
        if value is None:
            return None
        return remap.get(value, value)

    def _trusted_nodes(
        self,
        job_id: str,
        context: Dict[str, Any],
        candidates: Sequence[GraphNodeCandidate],
        diagnostics: List[GraphDiagnostic],
    ) -> tuple[List[GraphNodeFact], Dict[str, GraphNodeFact], List[GraphEvidenceFact]]:
        facts: List[GraphNodeFact] = []
        evidence_facts: List[GraphEvidenceFact] = []
        local_to_node: Dict[str, GraphNodeFact] = {}
        seen_local_ids: set[str] = set()
        ai_index = -1
        static_index = -1
        for candidate in candidates:
            if (candidate.metadata or {}).get("deterministic"):
                static_index += 1
                path = f"$.__static.nodes[{static_index}]"
            else:
                ai_index += 1
                path = f"$.nodes[{ai_index}]"
            node_kind = self._node_kind(candidate.nodeKind)
            if candidate.localId in seen_local_ids:
                diagnostics.append(self._candidate_diag(
                    context,
                    GraphDiagnosticCode.DUPLICATE_NODE_LOCAL_ID,
                    GraphDiagnosticSeverity.ERROR,
                    "Node localId is duplicated.",
                    candidate.localId,
                    path=f"{path}.localId",
                    validation_error=GraphValidationError(
                        code=GraphValidationErrorCode.DUPLICATE_LOCAL_ID,
                        path=f"{path}.localId",
                        message="Node localId is duplicated.",
                        expected="A localId unique within the response nodes array.",
                        actual=candidate.localId,
                        candidate_id=candidate.localId,
                        repair_hint="Rename this localId and update references, or remove the duplicate candidate.",
                    ),
                ))
                continue
            seen_local_ids.add(candidate.localId)
            if node_kind is None:
                diagnostics.append(self._candidate_diag(
                    context,
                    GraphDiagnosticCode.UNSUPPORTED_NODE_KIND,
                    GraphDiagnosticSeverity.ERROR,
                    "Node kind is not allowed.",
                    candidate.localId,
                    {"nodeKind": candidate.nodeKind},
                    path=f"{path}.nodeKind",
                    validation_error=enum_validation_error(
                        path=f"{path}.nodeKind",
                        message="Unsupported nodeKind value.",
                        actual=candidate.nodeKind,
                        allowed_values=enum_values(GraphNodeKind),
                        candidate_id=candidate.localId,
                        repair_hint="Replace this with a generic node kind such as TYPE, CALLABLE, CONFIG, RESOURCE, EXTERNAL, or UNKNOWN.",
                    ),
                ))
                continue
            node_kind = self._domain_node_kind(context["flowDomain"], node_kind)
            if not self._confidence_valid(candidate.confidence):
                diagnostics.append(self._confidence_diag(context, GraphDiagnosticCode.CONFIDENCE_INVALID, "Node confidence must be between 0 and 1.", candidate.localId, f"{path}.confidence", candidate.confidence))
                continue
            if candidate.confidence < MIN_TRUSTED_CONFIDENCE:
                diagnostics.append(self._candidate_diag(
                    context,
                    GraphDiagnosticCode.CONFIDENCE_BELOW_THRESHOLD,
                    GraphDiagnosticSeverity.WARN,
                    "Node confidence is below the trusted fact threshold.",
                    candidate.localId,
                    {"confidence": candidate.confidence},
                    path=f"{path}.confidence",
                    validation_error=GraphValidationError(
                        code=GraphValidationErrorCode.CONFIDENCE_BELOW_THRESHOLD,
                        path=f"{path}.confidence",
                        message="Node confidence is below the trusted fact threshold.",
                        expected=f"Confidence >= {MIN_TRUSTED_CONFIDENCE}.",
                        actual=candidate.confidence,
                        candidate_id=candidate.localId,
                        repair_hint="Raise confidence only if evidence supports it; otherwise remove this candidate.",
                        severity=GraphDiagnosticSeverity.WARN,
                    ),
                ))
                continue
            line_error = self._line_error(candidate.lineStart, candidate.lineEnd, context["lineCount"])
            if line_error:
                diagnostics.append(self._line_diag(context, candidate.localId, path, line_error, candidate.lineStart, candidate.lineEnd))
                continue
            if node_kind not in {GraphNodeKind.EXTERNAL, GraphNodeKind.UNKNOWN} and (candidate.lineStart is None or candidate.lineEnd is None):
                diagnostics.append(self._missing_evidence_diag(context, GraphDiagnosticCode.NODE_EVIDENCE_MISSING, "Trusted source node requires line evidence.", candidate.localId, path))
                continue
            fact_origin = self._candidate_origin(candidate)
            stable_key = self._stable_key(
                "node",
                context["sourceId"],
                context["relativePath"],
                context["flowDomain"].value,
                node_kind.value,
                candidate.qualifiedName or candidate.name,
                str((candidate.metadata or {}).get("signature") or ""),
                str(candidate.lineStart or ""),
            )
            evidence_id = None
            if candidate.lineStart is not None and candidate.lineEnd is not None:
                evidence = self._evidence(job_id, context, candidate.lineStart, candidate.lineEnd, GraphEvidenceKind.NODE, candidate.localId, fact_origin)
                evidence_id = evidence.id
                evidence_facts.append(evidence)
            metadata = dict(candidate.metadata or {})
            metadata["localId"] = candidate.localId
            metadata["evidenceId"] = evidence_id
            if candidate.parentLocalId:
                metadata["parentLocalId"] = candidate.parentLocalId
            fact = GraphNodeFact(
                id=self._id("graph-node", stable_key),
                job_id=job_id,
                source_id=context["sourceId"],
                inventory_file_id=context["inventoryFileId"],
                analysis_file_id=context["analysisFileId"],
                stable_key=stable_key,
                node_kind=node_kind,
                language=context["language"],
                name=candidate.name,
                qualified_name=candidate.qualifiedName or candidate.name,
                display_name=candidate.displayName or candidate.qualifiedName or candidate.name,
                parent_node_id=None,
                line_start=candidate.lineStart,
                line_end=candidate.lineEnd,
                confidence=candidate.confidence,
                status=GraphFactStatus.TRUSTED,
                fact_origin=fact_origin,
                flow_domain=context["flowDomain"],
                metadata=metadata,
            )
            local_to_node[candidate.localId] = fact
            facts.append(fact)

        trusted_facts: List[GraphNodeFact] = []
        for fact in facts:
            parent_local_id = fact.metadata.get("parentLocalId")
            if parent_local_id:
                parent = local_to_node.get(parent_local_id)
                if parent is None:
                    diagnostics.append(self._unknown_reference_diag(
                        context,
                        GraphDiagnosticCode.NODE_PARENT_MISSING,
                        "Node parentLocalId does not reference a trusted node.",
                        fact.metadata["localId"],
                        "$.nodes[].parentLocalId",
                        "parentLocalId",
                        parent_local_id,
                    ))
                    local_to_node.pop(fact.metadata["localId"], None)
                    continue
                fact = GraphNodeFact(**{**fact.__dict__, "parent_node_id": parent.id})
                local_to_node[fact.metadata["localId"]] = fact
            trusted_facts.append(fact)
        return trusted_facts, local_to_node, evidence_facts

    def _trusted_edges(
        self,
        job_id: str,
        context: Dict[str, Any],
        candidates: Sequence[GraphEdgeCandidate],
        local_to_node: Dict[str, GraphNodeFact],
        diagnostics: List[GraphDiagnostic],
    ) -> tuple[List[GraphEdgeFact], List[GraphEvidenceFact]]:
        facts: List[GraphEdgeFact] = []
        evidence_facts: List[GraphEvidenceFact] = []
        seen_local_ids: set[str] = set()
        ai_index = -1
        static_index = -1
        for candidate in candidates:
            if (candidate.metadata or {}).get("deterministic"):
                static_index += 1
                path = f"$.__static.edges[{static_index}]"
            else:
                ai_index += 1
                path = f"$.edges[{ai_index}]"
            edge_type = self._edge_type(candidate.edgeType)
            if candidate.localId in seen_local_ids:
                diagnostics.append(self._candidate_diag(
                    context,
                    GraphDiagnosticCode.DUPLICATE_EDGE_LOCAL_ID,
                    GraphDiagnosticSeverity.ERROR,
                    "Edge localId is duplicated.",
                    candidate.localId,
                    path=f"{path}.localId",
                    validation_error=GraphValidationError(
                        code=GraphValidationErrorCode.DUPLICATE_LOCAL_ID,
                        path=f"{path}.localId",
                        message="Edge localId is duplicated.",
                        expected="A localId unique within the response edges array.",
                        actual=candidate.localId,
                        candidate_id=candidate.localId,
                        repair_hint="Rename this localId and update references, or remove the duplicate edge.",
                    ),
                ))
                continue
            seen_local_ids.add(candidate.localId)
            if edge_type is None:
                diagnostics.append(self._candidate_diag(
                    context,
                    GraphDiagnosticCode.UNSUPPORTED_EDGE_TYPE,
                    GraphDiagnosticSeverity.ERROR,
                    "Edge type is not allowed.",
                    candidate.localId,
                    {"edgeType": candidate.edgeType},
                    path=f"{path}.edgeType",
                    validation_error=enum_validation_error(
                        path=f"{path}.edgeType",
                        message="Unsupported edgeType value.",
                        actual=candidate.edgeType,
                        allowed_values=enum_values(GraphEdgeType),
                        candidate_id=candidate.localId,
                        repair_hint=f"Replace {candidate.edgeType} with a generic edge type. Use CALLS for callable invocation or DEPENDS_ON for broad dependency.",
                    ),
                ))
                continue
            edge_type = self._domain_edge_type(context["flowDomain"], edge_type)
            if not self._confidence_valid(candidate.confidence):
                diagnostics.append(self._confidence_diag(context, GraphDiagnosticCode.CONFIDENCE_INVALID, "Edge confidence must be between 0 and 1.", candidate.localId, f"{path}.confidence", candidate.confidence))
                continue
            if candidate.confidence < MIN_TRUSTED_CONFIDENCE:
                diagnostics.append(self._candidate_diag(
                    context,
                    GraphDiagnosticCode.CONFIDENCE_BELOW_THRESHOLD,
                    GraphDiagnosticSeverity.WARN,
                    "Edge confidence is below the trusted fact threshold.",
                    candidate.localId,
                    {"confidence": candidate.confidence},
                    path=f"{path}.confidence",
                    validation_error=GraphValidationError(
                        code=GraphValidationErrorCode.CONFIDENCE_BELOW_THRESHOLD,
                        path=f"{path}.confidence",
                        message="Edge confidence is below the trusted fact threshold.",
                        expected=f"Confidence >= {MIN_TRUSTED_CONFIDENCE}.",
                        actual=candidate.confidence,
                        candidate_id=candidate.localId,
                        repair_hint="Raise confidence only if evidence supports it; otherwise remove this candidate.",
                        severity=GraphDiagnosticSeverity.WARN,
                    ),
                ))
                continue
            from_node = local_to_node.get(candidate.fromLocalId)
            if from_node is None:
                diagnostics.append(self._unknown_reference_diag(
                    context,
                    GraphDiagnosticCode.EDGE_SOURCE_MISSING,
                    "Edge source localId does not reference a trusted node.",
                    candidate.localId,
                    f"{path}.fromLocalId",
                    "fromLocalId",
                    candidate.fromLocalId,
                ))
                continue
            to_node = local_to_node.get(candidate.toLocalId) if candidate.toLocalId else None
            if candidate.toLocalId and to_node is None:
                diagnostics.append(self._unknown_reference_diag(
                    context,
                    GraphDiagnosticCode.EDGE_TARGET_MISSING,
                    "Edge target localId does not reference a trusted node.",
                    candidate.localId,
                    f"{path}.toLocalId",
                    "toLocalId",
                    candidate.toLocalId,
                ))
                continue
            line_error = self._line_error(candidate.lineStart, candidate.lineEnd, context["lineCount"])
            if line_error:
                diagnostics.append(self._line_diag(context, candidate.localId, path, line_error, candidate.lineStart, candidate.lineEnd))
                continue
            if candidate.lineStart is None or candidate.lineEnd is None:
                diagnostics.append(self._missing_evidence_diag(context, GraphDiagnosticCode.EDGE_EVIDENCE_MISSING, "Trusted edge requires line evidence.", candidate.localId, path))
                continue
            fact_origin = self._candidate_origin(candidate)
            evidence = self._evidence(job_id, context, candidate.lineStart, candidate.lineEnd, GraphEvidenceKind.EDGE, candidate.localId, fact_origin)
            evidence_facts.append(evidence)
            unresolved_target = candidate.unresolvedTarget if candidate.unresolvedTarget is not None else None
            metadata = dict(candidate.metadata or {})
            metadata["localId"] = candidate.localId
            if unresolved_target:
                metadata["unresolvedTarget"] = unresolved_target
            stable_key = self._stable_key(
                "edge",
                context["sourceId"],
                context["flowDomain"].value,
                from_node.stable_key,
                to_node.stable_key if to_node else json.dumps(unresolved_target or {}, sort_keys=True),
                edge_type.value,
                str(candidate.lineStart),
            )
            facts.append(GraphEdgeFact(
                id=self._id("graph-edge", stable_key),
                job_id=job_id,
                source_id=context["sourceId"],
                inventory_file_id=context["inventoryFileId"],
                analysis_file_id=context["analysisFileId"],
                from_node_id=from_node.id,
                to_node_id=to_node.id if to_node else None,
                edge_type=edge_type,
                resolution_status=GraphResolutionStatus.RESOLVED if to_node else self._initial_resolution_status(unresolved_target, metadata),
                confidence=candidate.confidence,
                evidence_id=evidence.id,
                unresolved_target=unresolved_target,
                metadata=metadata,
                status=GraphFactStatus.TRUSTED,
                fact_origin=fact_origin,
                flow_domain=context["flowDomain"],
            ))
        return facts, evidence_facts

    def _trusted_claims(
        self,
        job_id: str,
        context: Dict[str, Any],
        candidates: Sequence[GraphClaimCandidate],
        local_to_node: Dict[str, GraphNodeFact],
        diagnostics: List[GraphDiagnostic],
    ) -> tuple[List[GraphClaimFact], List[GraphEvidenceFact]]:
        facts: List[GraphClaimFact] = []
        evidence_facts: List[GraphEvidenceFact] = []
        seen_local_ids: set[str] = set()
        for index, candidate in enumerate(candidates):
            path = f"$.claims[{index}]"
            claim_kind = self._claim_kind(candidate.claimKind)
            if candidate.localId in seen_local_ids:
                diagnostics.append(self._candidate_diag(
                    context,
                    GraphDiagnosticCode.DUPLICATE_CLAIM_LOCAL_ID,
                    GraphDiagnosticSeverity.ERROR,
                    "Claim localId is duplicated.",
                    candidate.localId,
                    path=f"{path}.localId",
                    validation_error=GraphValidationError(
                        code=GraphValidationErrorCode.DUPLICATE_LOCAL_ID,
                        path=f"{path}.localId",
                        message="Claim localId is duplicated.",
                        expected="A localId unique within the response claims array.",
                        actual=candidate.localId,
                        candidate_id=candidate.localId,
                        repair_hint="Rename this localId and update references, or remove the duplicate claim.",
                    ),
                ))
                continue
            seen_local_ids.add(candidate.localId)
            if claim_kind is None:
                diagnostics.append(self._candidate_diag(
                    context,
                    GraphDiagnosticCode.UNSUPPORTED_CLAIM_KIND,
                    GraphDiagnosticSeverity.ERROR,
                    "Claim kind is not allowed.",
                    candidate.localId,
                    {"claimKind": candidate.claimKind},
                    path=f"{path}.claimKind",
                    validation_error=enum_validation_error(
                        path=f"{path}.claimKind",
                        message="Unsupported claimKind value.",
                        actual=candidate.claimKind,
                        allowed_values=enum_values(GraphClaimKind),
                        candidate_id=candidate.localId,
                        repair_hint="Replace this with a generic claim kind such as RESPONSIBILITY, ROLE, SIDE_EFFECT, ENTRYPOINT_HINT, DATA_ACCESS_HINT, EXTERNAL_BOUNDARY_HINT, TEST_HINT, or UNKNOWN.",
                    ),
                ))
                continue
            if not self._confidence_valid(candidate.confidence):
                diagnostics.append(self._confidence_diag(context, GraphDiagnosticCode.CONFIDENCE_INVALID, "Claim confidence must be between 0 and 1.", candidate.localId, f"{path}.confidence", candidate.confidence))
                continue
            if candidate.confidence < MIN_TRUSTED_CONFIDENCE:
                diagnostics.append(self._candidate_diag(
                    context,
                    GraphDiagnosticCode.CONFIDENCE_BELOW_THRESHOLD,
                    GraphDiagnosticSeverity.WARN,
                    "Claim confidence is below the trusted fact threshold.",
                    candidate.localId,
                    {"confidence": candidate.confidence},
                    path=f"{path}.confidence",
                    validation_error=GraphValidationError(
                        code=GraphValidationErrorCode.CONFIDENCE_BELOW_THRESHOLD,
                        path=f"{path}.confidence",
                        message="Claim confidence is below the trusted fact threshold.",
                        expected=f"Confidence >= {MIN_TRUSTED_CONFIDENCE}.",
                        actual=candidate.confidence,
                        candidate_id=candidate.localId,
                        repair_hint="Raise confidence only if evidence supports it; otherwise remove this claim.",
                        severity=GraphDiagnosticSeverity.WARN,
                    ),
                ))
                continue
            node = local_to_node.get(candidate.nodeLocalId)
            if node is None:
                diagnostics.append(self._unknown_reference_diag(
                    context,
                    GraphDiagnosticCode.CLAIM_NODE_MISSING,
                    "Claim nodeLocalId does not reference a trusted node.",
                    candidate.localId,
                    f"{path}.nodeLocalId",
                    "nodeLocalId",
                    candidate.nodeLocalId,
                ))
                continue
            if not candidate.evidence:
                diagnostics.append(self._missing_evidence_diag(context, GraphDiagnosticCode.CLAIM_EVIDENCE_MISSING, "Trusted claim requires evidence.", candidate.localId, f"{path}.evidence"))
                continue
            evidence_ids: List[str] = []
            valid = True
            for evidence_index, evidence_range in enumerate(candidate.evidence):
                line_error = self._line_error(evidence_range.lineStart, evidence_range.lineEnd, context["lineCount"])
                if line_error:
                    diagnostics.append(self._line_diag(context, candidate.localId, f"{path}.evidence[{evidence_index}]", line_error, evidence_range.lineStart, evidence_range.lineEnd))
                    valid = False
                    break
                fact_origin = self._candidate_origin(candidate)
                evidence = self._evidence(job_id, context, evidence_range.lineStart, evidence_range.lineEnd, GraphEvidenceKind.CLAIM, candidate.localId, fact_origin)
                evidence_facts.append(evidence)
                evidence_ids.append(evidence.id)
            if not valid:
                continue
            summary = self._compact_summary(candidate.summary)
            stable_key = self._stable_key("claim", context["sourceId"], context["flowDomain"].value, node.stable_key, claim_kind.value, summary)
            metadata = dict(candidate.metadata or {})
            metadata["localId"] = candidate.localId
            facts.append(GraphClaimFact(
                id=self._id("graph-claim", stable_key),
                job_id=job_id,
                source_id=context["sourceId"],
                node_id=node.id,
                claim_kind=claim_kind,
                summary=summary,
                confidence=candidate.confidence,
                status=GraphFactStatus.TRUSTED,
                evidence_ids=evidence_ids,
                fact_origin=fact_origin,
                flow_domain=context["flowDomain"],
                metadata=metadata,
            ))
        return facts, evidence_facts

    def _derived_contains_edges(
        self,
        job_id: str,
        context: Dict[str, Any],
        nodes: Sequence[GraphNodeFact],
        existing_edges: Sequence[GraphEdgeFact],
    ) -> List[GraphEdgeFact]:
        result: List[GraphEdgeFact] = []
        by_id = {node.id: node for node in nodes}
        existing_contains_pairs = {
            (edge.from_node_id, edge.to_node_id)
            for edge in existing_edges
            if edge.edge_type == GraphEdgeType.CONTAINS and edge.to_node_id is not None
        }
        for node in nodes:
            parent_id = node.parent_node_id
            if not parent_id or parent_id not in by_id:
                continue
            if (parent_id, node.id) in existing_contains_pairs:
                continue
            stable_key = self._stable_key("edge", context["sourceId"], context["flowDomain"].value, by_id[parent_id].stable_key, node.stable_key, GraphEdgeType.CONTAINS.value, "derived")
            result.append(GraphEdgeFact(
                id=self._id("graph-edge", stable_key),
                job_id=job_id,
                source_id=context["sourceId"],
                inventory_file_id=context["inventoryFileId"],
                analysis_file_id=context["analysisFileId"],
                from_node_id=parent_id,
                to_node_id=node.id,
                edge_type=GraphEdgeType.CONTAINS,
                resolution_status=GraphResolutionStatus.RESOLVED,
                confidence=1.0,
                evidence_id=node.metadata.get("evidenceId"),
                unresolved_target=None,
                metadata={"derived": True, "sourceNodeId": node.id},
                status=GraphFactStatus.DERIVED,
                fact_origin=GraphFactOrigin.DERIVED,
                flow_domain=context["flowDomain"],
            ))
        return result

    def _derived_type_responsibilities(
        self,
        job_id: str,
        context: Dict[str, Any],
        nodes: Sequence[GraphNodeFact],
        claims: Sequence[GraphClaimFact],
    ) -> List[GraphClaimFact]:
        responsibility_by_node: Dict[str, List[GraphClaimFact]] = {}
        for claim in claims:
            if claim.claim_kind == GraphClaimKind.RESPONSIBILITY and claim.status in {GraphFactStatus.TRUSTED, GraphFactStatus.DERIVED}:
                responsibility_by_node.setdefault(claim.node_id, []).append(claim)
        derived: List[GraphClaimFact] = []
        by_parent: Dict[str, List[GraphNodeFact]] = {}
        for node in nodes:
            if node.node_kind == GraphNodeKind.CALLABLE and node.parent_node_id:
                by_parent.setdefault(node.parent_node_id, []).append(node)
        for node in nodes:
            if node.node_kind != GraphNodeKind.TYPE or responsibility_by_node.get(node.id):
                continue
            child_claims: List[GraphClaimFact] = []
            for child in by_parent.get(node.id, []):
                child_claims.extend(responsibility_by_node.get(child.id, []))
            if not child_claims:
                continue
            snippets = [self._strip_period(claim.summary) for claim in child_claims[:3]]
            summary = self._compact_summary("Derived from callable responsibilities: " + "; ".join(snippets) + ".")
            evidence_ids = self._unique([evidence_id for claim in child_claims for evidence_id in claim.evidence_ids])
            if not evidence_ids:
                continue
            confidence = min(sum(claim.confidence for claim in child_claims) / len(child_claims) * 0.9, 0.95)
            stable_key = self._stable_key("claim", context["sourceId"], context["flowDomain"].value, node.stable_key, GraphClaimKind.RESPONSIBILITY.value, summary)
            derived.append(GraphClaimFact(
                id=self._id("graph-claim", stable_key),
                job_id=job_id,
                source_id=context["sourceId"],
                node_id=node.id,
                claim_kind=GraphClaimKind.RESPONSIBILITY,
                summary=summary,
                confidence=confidence,
                status=GraphFactStatus.DERIVED,
                evidence_ids=evidence_ids,
                fact_origin=GraphFactOrigin.DERIVED,
                flow_domain=context["flowDomain"],
                metadata={"derived": True, "sourceClaimIds": [claim.id for claim in child_claims]},
            ))
        return derived

    def _file_identity_matches(self, response_file: GraphAnalysisFile, context: Dict[str, Any]) -> bool:
        return (
            response_file.sourceId == context["sourceId"]
            and response_file.inventoryFileId == context["inventoryFileId"]
            and response_file.relativePath == context["relativePath"]
            and response_file.contentHash == context["contentHash"]
            and response_file.lineCount == context["lineCount"]
        )

    def _evidence(
        self,
        job_id: str,
        context: Dict[str, Any],
        line_start: int,
        line_end: int,
        evidence_kind: GraphEvidenceKind,
        candidate_id: str,
        fact_origin: GraphFactOrigin = GraphFactOrigin.UNKNOWN,
    ) -> GraphEvidenceFact:
        excerpt = "\n".join(context["lines"][line_start - 1:line_end])
        excerpt_hash = hashlib.sha256(f"{context['decodePolicy']}\0{excerpt}".encode("utf-8")).hexdigest()
        stable_key = self._stable_key("evidence", context["sourceId"], str(context["inventoryFileId"]), context["contentHash"], str(line_start), str(line_end), evidence_kind.value, candidate_id)
        return GraphEvidenceFact(
            id=self._id("graph-evidence", stable_key),
            job_id=job_id,
            source_id=context["sourceId"],
            inventory_file_id=context["inventoryFileId"],
            analysis_file_id=context["analysisFileId"],
            content_hash=context["contentHash"],
            line_start=line_start,
            line_end=line_end,
            excerpt_hash=excerpt_hash,
            evidence_kind=evidence_kind,
            fact_origin=fact_origin,
            flow_domain=context["flowDomain"],
            metadata={"candidateId": candidate_id, "decodePolicy": context["decodePolicy"]},
        )

    def _line_error(self, line_start: Optional[int], line_end: Optional[int], line_count: int) -> Optional[str]:
        if line_start is None and line_end is None:
            return None
        if line_start is None or line_end is None:
            return "Line range must include both lineStart and lineEnd."
        if line_start < 1:
            return "Line range starts before the file."
        if line_end < line_start:
            return "Line range end is before its start."
        if line_count <= 0 or line_end > line_count:
            return "Line range is outside the indexed file line count."
        return None

    def _confidence_valid(self, confidence: Any) -> bool:
        return isinstance(confidence, (float, int)) and 0 <= float(confidence) <= 1

    def _initial_resolution_status(self, unresolved_target: Optional[Dict[str, Any]], metadata: Dict[str, Any]) -> GraphResolutionStatus:
        kind_hint = str((unresolved_target or {}).get("kindHint") or metadata.get("kindHint") or "").upper()
        if kind_hint in {GraphNodeKind.EXTERNAL.value, GraphNodeKind.RESOURCE.value}:
            return GraphResolutionStatus.EXTERNAL_TARGET
        if kind_hint == "INTERFACE":
            return GraphResolutionStatus.INTERFACE_TARGET
        if kind_hint == "DYNAMIC" or metadata.get("dynamic") is True:
            return GraphResolutionStatus.DYNAMIC_TARGET
        return GraphResolutionStatus.UNRESOLVED if unresolved_target else GraphResolutionStatus.UNKNOWN

    def _diagnostic(
        self,
        context: Dict[str, Any],
        stage: GraphDiagnosticStage,
        code: GraphDiagnosticCode,
        severity: GraphDiagnosticSeverity,
        message: str,
        candidate_id: Optional[str] = None,
        line_start: Optional[int] = None,
        line_end: Optional[int] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> GraphDiagnostic:
        return GraphDiagnostic(
            source_id=context["sourceId"],
            inventory_file_id=context["inventoryFileId"],
            analysis_file_id=context["analysisFileId"],
            relative_path=context["relativePath"],
            stage=stage,
            code=code,
            severity=severity,
            message=message,
            candidate_id=candidate_id,
            line_start=line_start,
            line_end=line_end,
            metadata=metadata or {},
            fact_origin=GraphFactOrigin.UNKNOWN,
            flow_domain=context["flowDomain"],
        )

    def _candidate_diag(
        self,
        context: Dict[str, Any],
        code: GraphDiagnosticCode,
        severity: GraphDiagnosticSeverity,
        message: str,
        candidate_id: Optional[str],
        metadata: Optional[Dict[str, Any]] = None,
        line_start: Optional[int] = None,
        line_end: Optional[int] = None,
        path: Optional[str] = None,
        validation_error: Optional[GraphValidationError] = None,
    ) -> GraphDiagnostic:
        merged = dict(metadata or {})
        if path and "path" not in merged:
            merged["path"] = path
        if validation_error is not None:
            merged["validationError"] = validation_error.to_dict()
        return self._diagnostic(context, GraphDiagnosticStage.CANDIDATE_VALIDATE, code, severity, message, candidate_id, line_start, line_end, merged)

    def _confidence_diag(
        self,
        context: Dict[str, Any],
        code: GraphDiagnosticCode,
        message: str,
        candidate_id: Optional[str],
        path: str,
        actual: Any,
    ) -> GraphDiagnostic:
        return self._candidate_diag(
            context,
            code,
            GraphDiagnosticSeverity.ERROR,
            message,
            candidate_id,
            {"confidence": actual},
            path=path,
            validation_error=GraphValidationError(
                code=GraphValidationErrorCode.INVALID_CONFIDENCE,
                path=path,
                message=message,
                expected="Number between 0 and 1.",
                actual=actual,
                candidate_id=candidate_id,
                repair_hint="Set confidence to a number between 0 and 1, or remove the candidate.",
            ),
        )

    def _line_diag(
        self,
        context: Dict[str, Any],
        candidate_id: Optional[str],
        path: str,
        message: str,
        line_start: Optional[int],
        line_end: Optional[int],
    ) -> GraphDiagnostic:
        validation_code = GraphValidationErrorCode.LINE_RANGE_INVALID
        if line_start is not None and (line_start < 1 or context["lineCount"] <= 0):
            validation_code = GraphValidationErrorCode.LINE_RANGE_OUTSIDE_FILE
        if line_end is not None and line_end > context["lineCount"]:
            validation_code = GraphValidationErrorCode.LINE_RANGE_OUTSIDE_FILE
        return self._candidate_diag(
            context,
            GraphDiagnosticCode.LINE_RANGE_INVALID,
            GraphDiagnosticSeverity.ERROR,
            message,
            candidate_id,
            {"lineStart": line_start, "lineEnd": line_end, "lineCount": context["lineCount"]},
            line_start=line_start,
            line_end=line_end,
            path=path,
            validation_error=GraphValidationError(
                code=validation_code,
                path=path,
                message=message,
                expected=f"lineStart and lineEnd inside file line count 1..{context['lineCount']}.",
                actual={"lineStart": line_start, "lineEnd": line_end},
                candidate_id=candidate_id,
                line_start=line_start,
                line_end=line_end,
                repair_hint="Use only line ranges that exist in the provided file. If no evidence exists, remove the candidate.",
            ),
        )

    def _missing_evidence_diag(
        self,
        context: Dict[str, Any],
        code: GraphDiagnosticCode,
        message: str,
        candidate_id: Optional[str],
        path: str,
    ) -> GraphDiagnostic:
        return self._candidate_diag(
            context,
            code,
            GraphDiagnosticSeverity.ERROR,
            message,
            candidate_id,
            path=path,
            validation_error=GraphValidationError(
                code=GraphValidationErrorCode.MISSING_EVIDENCE,
                path=path,
                message=message,
                expected="Valid line evidence inside the analyzed file.",
                actual=None,
                candidate_id=candidate_id,
                repair_hint="Add evidence line ranges from the file, or remove this candidate if evidence is unavailable.",
            ),
        )

    def _unknown_reference_diag(
        self,
        context: Dict[str, Any],
        code: GraphDiagnosticCode,
        message: str,
        candidate_id: Optional[str],
        path: str,
        field_name: str,
        actual: Any,
    ) -> GraphDiagnostic:
        return self._candidate_diag(
            context,
            code,
            GraphDiagnosticSeverity.ERROR,
            message,
            candidate_id,
            {field_name: actual},
            path=path,
            validation_error=GraphValidationError(
                code=GraphValidationErrorCode.UNKNOWN_LOCAL_REFERENCE,
                path=path,
                message=message,
                expected="A localId defined in the repaired response and accepted as a trusted node.",
                actual=actual,
                candidate_id=candidate_id,
                repair_hint="Point this reference to an existing localId, use unresolvedTarget when the target is external or cross-file, or remove the candidate.",
            ),
        )

    def _node_kind(self, value: str) -> Optional[GraphNodeKind]:
        try:
            return GraphNodeKind(value)
        except ValueError:
            return None

    def _edge_type(self, value: str) -> Optional[GraphEdgeType]:
        try:
            return GraphEdgeType(value)
        except ValueError:
            return None

    def _claim_kind(self, value: str) -> Optional[GraphClaimKind]:
        try:
            return GraphClaimKind(value)
        except ValueError:
            return None

    def _severity(self, value: Any) -> GraphDiagnosticSeverity:
        try:
            return GraphDiagnosticSeverity(str(value))
        except ValueError:
            return GraphDiagnosticSeverity.INFO

    def _candidate_origin(self, candidate: Any) -> GraphFactOrigin:
        metadata = getattr(candidate, "metadata", None) or {}
        if metadata.get("deterministic") is True or metadata.get("staticExtractor"):
            return GraphFactOrigin.STATIC
        origin = metadata.get("factOrigin") or metadata.get("origin") or metadata.get("producer")
        if isinstance(origin, str):
            try:
                return GraphFactOrigin(origin.upper())
            except ValueError:
                pass
        return GraphFactOrigin.LLM

    def _domain_node_kind(self, flow_domain: GraphFlowDomain, node_kind: GraphNodeKind) -> GraphNodeKind:
        if flow_domain in {GraphFlowDomain.WORKFLOW, GraphFlowDomain.CONFIG, GraphFlowDomain.BUILD} and node_kind == GraphNodeKind.CALLABLE:
            return GraphNodeKind.CONFIG
        if flow_domain == GraphFlowDomain.DATA and node_kind in {GraphNodeKind.CALLABLE, GraphNodeKind.TYPE, GraphNodeKind.FIELD}:
            return GraphNodeKind.DATA
        if flow_domain == GraphFlowDomain.DOC and node_kind == GraphNodeKind.CALLABLE:
            return GraphNodeKind.MODULE
        return node_kind

    def _domain_edge_type(self, flow_domain: GraphFlowDomain, edge_type: GraphEdgeType) -> GraphEdgeType:
        if flow_domain in {GraphFlowDomain.WORKFLOW, GraphFlowDomain.CONFIG, GraphFlowDomain.BUILD} and edge_type == GraphEdgeType.CALLS:
            return GraphEdgeType.CONFIGURES
        if flow_domain in {GraphFlowDomain.DATA, GraphFlowDomain.DOC} and edge_type == GraphEdgeType.CALLS:
            return GraphEdgeType.REFERENCES
        return edge_type

    def _stable_key(self, *parts: str) -> str:
        return "\0".join(parts)

    def _id(self, prefix: str, stable_key: str) -> str:
        return f"{prefix}:{hashlib.sha256(stable_key.encode('utf-8')).hexdigest()[:24]}"

    def _language(self, row: Any) -> str:
        return self._row_value(row, "language") or "unknown"

    def _flow_domain(self, row: Any) -> GraphFlowDomain:
        value = self._row_value(row, "flow_domain")
        if value:
            try:
                return GraphFlowDomain(value)
            except ValueError:
                return GraphFlowDomain.UNKNOWN
        return classify_flow_domain(row["relative_path"], row["extension"])

    def _row_value(self, row: Any, key: str) -> Optional[Any]:
        try:
            if hasattr(row, "keys") and key not in row.keys():
                return None
            return row[key]
        except (KeyError, IndexError):
            return None

    def _compact_summary(self, value: str) -> str:
        text = " ".join((value or "").strip().split())
        if len(text) <= 220:
            return text
        return text[:217].rstrip() + "..."

    def _strip_period(self, value: str) -> str:
        return value.strip().rstrip(".")

    def _unique(self, values: Iterable[str]) -> List[str]:
        result: List[str] = []
        seen: set[str] = set()
        for value in values:
            if value in seen:
                continue
            seen.add(value)
            result.append(value)
        return result
