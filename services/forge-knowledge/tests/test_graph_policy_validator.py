from __future__ import annotations

import hashlib
from dataclasses import replace
from pathlib import Path

import pytest

from knowledge_service.analysis_policy_loader import load_analysis_policy
from knowledge_service.analyzer_runtime import AnalyzerPolicyRuntimeResolver, ExtractorRegistry
from knowledge_service.errors import KnowledgeError
from knowledge_service.graph_policy_validator import GraphPolicyValidator
from knowledge_service.graph_schema import GraphAnalysisResult, GraphClaim, GraphEdge, GraphEvidenceRef, GraphNode

REPO_ROOT = Path(__file__).resolve().parents[3]
POLICY_PATH = REPO_ROOT / "config" / "knowledge" / "analysis-policy.yaml"


def test_valid_java_ast_extractor_output_passes_policy_validation():
    policy, context = _context("src/main/java/example/Foo.java", "package example;\nclass Foo { void call() {} }\n", language="java")
    extractor_result = ExtractorRegistry().extract(policy, context)

    GraphPolicyValidator(policy).validate_extractor_output(
        extractor_result.graph_result,
        context.graph_contract,
        policy.extractors[extractor_result.extractor_id],
        context.line_count,
        relative_path=context.row["relative_path"],
    )


@pytest.mark.parametrize(
    ("relative_path", "content"),
    [
        ("script.py", "print('hello')\n"),
        ("settings.yaml", "service:\n  url: http://example\n"),
        ("README.md", "# Service\nDocuments service behavior.\n"),
    ],
)
def test_valid_light_extractors_pass_policy_validation(relative_path, content):
    policy, context = _context(relative_path, content)
    extractor_result = ExtractorRegistry().extract(policy, context)

    GraphPolicyValidator(policy).validate_extractor_output(
        extractor_result.graph_result,
        context.graph_contract,
        policy.extractors[extractor_result.extractor_id],
        context.line_count,
        relative_path=context.row["relative_path"],
    )


def test_extractor_output_rejects_undeclared_node_kind():
    policy, context = _context("settings.yaml", "service:\n  url: http://example\n")
    graph = _graph(nodes=[_file_node(), _node("workflow", "WORKFLOW")])

    with pytest.raises(KnowledgeError) as exc:
        _validate_extractor_graph(policy, context, "structured_text_light", graph)

    assert exc.value.code == "ANALYSIS_EXTRACTOR_OUTPUT_INVALID"
    assert exc.value.details["field"] == "nodeKind"
    assert exc.value.details["actual"] == "WORKFLOW"
    assert "FILE" in exc.value.details["allowedValues"]


def test_extractor_output_rejects_node_kind_not_declared_by_policy():
    policy, context = _context("settings.yaml", "service:\n  url: http://example\n")
    graph = _graph(nodes=[_file_node(), _node("external", "EXTERNAL")])

    with pytest.raises(KnowledgeError) as exc:
        _validate_extractor_graph(policy, context, "structured_text_light", graph)

    assert exc.value.code == "ANALYSIS_EXTRACTOR_OUTPUT_INVALID"
    assert exc.value.details["field"] == "nodeKind"
    assert exc.value.details["actual"] == "EXTERNAL"
    assert "EXTERNAL" not in exc.value.details["allowedValues"]
    assert set(exc.value.details["allowedValues"]) >= {"FILE", "TYPE", "CALLABLE"}


def test_extractor_output_rejects_edge_type_not_listed_in_produces():
    policy, context = _context("settings.yaml", "service:\n  url: http://example\n")
    graph = _graph(
        nodes=[_file_node(), _node("config", "CONFIG", parent="file")],
        edges=[_edge("ref", "REFERENCES", "file", "config")],
    )

    with pytest.raises(KnowledgeError) as exc:
        _validate_extractor_graph(policy, context, "structured_text_light", graph)

    assert exc.value.code == "ANALYSIS_EXTRACTOR_OUTPUT_INVALID"
    assert exc.value.details["field"] == "edgeType"
    assert exc.value.details["actual"] == "REFERENCES"
    assert exc.value.details["allowedValues"] == ["DECLARES"]


def test_extractor_output_rejects_claim_kind_not_listed_in_produces():
    policy, context = _context("settings.yaml", "service:\n  url: http://example\n")
    graph = _graph(nodes=[_file_node()], claims=[_claim("purpose", "RESPONSIBILITY", "file")])

    with pytest.raises(KnowledgeError) as exc:
        _validate_extractor_graph(policy, context, "structured_text_light", graph)

    assert exc.value.code == "ANALYSIS_EXTRACTOR_OUTPUT_INVALID"
    assert exc.value.details["field"] == "claimKind"
    assert exc.value.details["actual"] == "RESPONSIBILITY"
    assert exc.value.details["allowedValues"] == []


def test_extractor_output_rejects_invalid_line_range():
    policy, context = _context("settings.yaml", "service:\n")
    graph = _graph(nodes=[_file_node(line_end=10)])

    with pytest.raises(KnowledgeError) as exc:
        _validate_extractor_graph(policy, context, "structured_text_light", graph)

    assert exc.value.code == "ANALYSIS_EXTRACTOR_OUTPUT_INVALID"
    assert exc.value.details["field"] == "lineStart"


@pytest.mark.parametrize("case", ["parent", "edge", "claim"])
def test_extractor_output_rejects_broken_references(case):
    policy, context = _context("src/main/java/example/Foo.java", "class Foo {}\n", language="java")
    if case == "parent":
        graph = _graph(nodes=[_file_node(), _node("type", "TYPE", parent="missing")])
    elif case == "edge":
        graph = _graph(nodes=[_file_node()], edges=[_edge("declares", "DECLARES", "missing", "file")])
    else:
        graph = _graph(nodes=[_file_node()], claims=[_claim("entrypoint", "ENTRYPOINT_HINT", "missing")])

    with pytest.raises(KnowledgeError) as exc:
        _validate_extractor_graph(policy, context, "java_ast", graph)

    assert exc.value.code == "ANALYSIS_EXTRACTOR_OUTPUT_INVALID"
    assert exc.value.details["field"] in {"parentLocalId", "fromNodeLocalId", "nodeLocalId"}


def test_extractor_output_rejects_edge_endpoint_rule_violation():
    policy, context = _context("src/main/java/example/Foo.java", "class Foo { void call() {} }\n", language="java")
    graph = _graph(
        nodes=[_file_node(), _node("call", "CALLABLE", parent="file")],
        edges=[_edge("call-edge", "CALLS", "file", "call")],
    )

    with pytest.raises(KnowledgeError) as exc:
        _validate_extractor_graph(policy, context, "java_ast", graph)

    assert exc.value.code == "ANALYSIS_EXTRACTOR_OUTPUT_INVALID"
    assert exc.value.details["field"] == "fromNodeLocalId"
    assert exc.value.details["actual"] == "FILE"
    assert exc.value.details["allowedValues"] == ["CALLABLE"]


def test_final_graph_rejects_imports_target_when_yaml_forbids_targets():
    policy, context = _context("src/main/java/example/Foo.java", "class Foo {}\n", language="java")
    graph = _graph(
        nodes=[_file_node(), _node("type", "TYPE", parent="file")],
        edges=[_edge("bad-import", "IMPORTS", "file", "type", resolution_status="RESOLVED")],
    )

    with pytest.raises(KnowledgeError) as exc:
        GraphPolicyValidator(policy).validate_final_graph(
            graph,
            context.graph_contract,
            context.line_count,
            relative_path=context.row["relative_path"],
        )

    assert exc.value.code == "ANALYSIS_GRAPH_POLICY_VALIDATION_FAILED"
    assert exc.value.details["field"] == "toNodeLocalId"
    assert exc.value.details["allowedValues"] == []
    assert "not allowed for this edge type" in exc.value.details["validationErrors"][0]["message"]


def test_final_graph_accepts_imports_external_target_without_graph_node():
    policy, context = _context("src/main/java/example/Foo.java", "import java.util.List;\nclass Foo {}\n", language="java")
    graph = _graph(
        nodes=[_file_node(line_end=2)],
        edges=[
            _edge(
                "import-list",
                "IMPORTS",
                "file",
                None,
                resolution_status="EXTERNAL_TARGET",
                unresolved_target={"name": "List", "qualifiedName": "java.util.List", "kindHint": "IMPORT"},
            )
        ],
    )

    GraphPolicyValidator(policy).validate_final_graph(
        graph,
        context.graph_contract,
        context.line_count,
        relative_path=context.row["relative_path"],
    )


def test_final_graph_rejects_resolved_edge_without_target_node():
    policy, context = _context("src/main/java/example/Foo.java", "class Foo { void call() {} }\n", language="java")
    graph = _graph(
        nodes=[_file_node(), _node("call", "CALLABLE", parent="file")],
        edges=[_edge("call-edge", "CALLS", "call", None, resolution_status="RESOLVED")],
    )

    with pytest.raises(KnowledgeError) as exc:
        GraphPolicyValidator(policy).validate_final_graph(
            graph,
            context.graph_contract,
            context.line_count,
            relative_path=context.row["relative_path"],
        )

    assert exc.value.code == "ANALYSIS_GRAPH_POLICY_VALIDATION_FAILED"
    assert exc.value.details["field"] == "resolutionStatus"
    assert exc.value.details["validationErrors"][0]["path"] == "$.edges[0].resolutionStatus"


def test_final_graph_uses_to_ref_resolution_status_rules_from_contract():
    policy, context = _context("src/main/java/example/Foo.java", "class Foo { void call() {} }\n", language="java")
    graph = _graph(
        nodes=[_file_node(), _node("call", "CALLABLE", parent="file")],
        edges=[_edge("call-edge", "CALLS", "call", None, resolution_status="RESOLVED")],
    )
    flexible_contract = replace(
        context.graph_contract,
        resolution_status_rules={
            **context.graph_contract.resolution_status_rules,
            "RESOLVED": {"toRef": "optional", "unresolvedTarget": "optional"},
        },
    )

    GraphPolicyValidator(policy).validate_final_graph(
        graph,
        flexible_contract,
        context.line_count,
        relative_path=context.row["relative_path"],
    )


def test_final_graph_accepts_unresolved_calls_without_target_node():
    policy, context = _context("src/main/java/example/Foo.java", "class Foo { void call() {} }\n", language="java")
    graph = _graph(
        nodes=[_file_node(), _node("call", "CALLABLE", parent="file")],
        edges=[
            _edge(
                "call-edge",
                "CALLS",
                "call",
                None,
                resolution_status="UNRESOLVED",
                unresolved_target={"name": "missing", "kindHint": "CALLABLE"},
            )
        ],
    )

    GraphPolicyValidator(policy).validate_final_graph(
        graph,
        context.graph_contract,
        context.line_count,
        relative_path=context.row["relative_path"],
    )


def test_final_graph_rejects_claim_without_required_evidence():
    policy, context = _context("src/main/java/example/Foo.java", "class Foo {}\n", language="java")
    graph = _graph(nodes=[_file_node()], claims=[_claim("purpose", "RESPONSIBILITY", "file", evidence=[])])

    with pytest.raises(KnowledgeError) as exc:
        GraphPolicyValidator(policy).validate_final_graph(
            graph,
            context.graph_contract,
            context.line_count,
            relative_path=context.row["relative_path"],
        )

    assert exc.value.code == "ANALYSIS_GRAPH_POLICY_VALIDATION_FAILED"
    assert exc.value.details["field"] == "evidence"


def test_final_graph_rejects_runtime_diagnostic_without_required_shape():
    policy, context = _context("src/main/java/example/Foo.java", "class Foo {}\n", language="java")
    graph = _graph(nodes=[_file_node()], diagnostics=[{"code": "BROKEN", "message": "missing stage"}])

    with pytest.raises(KnowledgeError) as exc:
        GraphPolicyValidator(policy).validate_final_graph(
            graph,
            context.graph_contract,
            context.line_count,
            relative_path=context.row["relative_path"],
        )

    assert exc.value.code == "ANALYSIS_GRAPH_POLICY_VALIDATION_FAILED"
    assert exc.value.details["entityType"] == "diagnostic"
    assert exc.value.details["field"] == "severity"


def test_final_graph_accepts_allowed_metadata_contract_values():
    policy, context = _context("settings.yaml", "service:\n  url: http://example\n", language="yaml")
    graph = _metadata_contract_graph(
        node_metadata={"factOrigin": "STATIC", "status": "TRUSTED"},
        edge_metadata={"factOrigin": "STATIC", "status": "CANDIDATE"},
        edge_resolution_status="RESOLVED",
        claim_metadata={"factOrigin": "LLM", "status": "CANDIDATE"},
        edge_evidence_metadata={"factOrigin": "STATIC", "evidenceKind": "EDGE"},
        claim_evidence_metadata={"factOrigin": "LLM", "evidenceKind": "CLAIM"},
    )

    GraphPolicyValidator(policy).validate_final_graph(
        graph,
        context.graph_contract,
        context.line_count,
        relative_path=context.row["relative_path"],
    )


def test_final_graph_ignores_metadata_resolution_status():
    policy, context = _context("settings.yaml", "service:\n  url: http://example\n", language="yaml")
    graph = _metadata_contract_graph(
        edge_metadata={"factOrigin": "STATIC", "resolutionStatus": "BOGUS"},
        edge_resolution_status=None,
    )

    GraphPolicyValidator(policy).validate_final_graph(
        graph,
        context.graph_contract,
        context.line_count,
        relative_path=context.row["relative_path"],
    )


@pytest.mark.parametrize(
    ("case", "field", "actual", "entity_type", "entity_id"),
    [
        ("node_status", "metadata.status", "BOGUS", "node", "file"),
        ("node_origin", "metadata.factOrigin", "BOGUS", "node", "file"),
        ("edge_status", "metadata.status", "BOGUS", "edge", "configures"),
        ("edge_origin", "metadata.factOrigin", "BOGUS", "edge", "configures"),
        ("edge_resolution", "resolutionStatus", "BOGUS", "edge", "configures"),
        ("claim_status", "metadata.status", "BOGUS", "claim", "purpose"),
        ("claim_origin", "metadata.factOrigin", "BOGUS", "claim", "purpose"),
        ("evidence_kind", "metadata.evidenceKind", "BOGUS", "evidence", "configures:evidence:1"),
        ("evidence_origin", "metadata.factOrigin", "BOGUS", "evidence", "purpose:evidence:1"),
    ],
)
def test_final_graph_rejects_invalid_metadata_contract_values(case, field, actual, entity_type, entity_id):
    policy, context = _context("settings.yaml", "service:\n  url: http://example\n", language="yaml")
    graph = _invalid_metadata_contract_graph(case)

    with pytest.raises(KnowledgeError) as exc:
        GraphPolicyValidator(policy).validate_final_graph(
            graph,
            context.graph_contract,
            context.line_count,
            relative_path=context.row["relative_path"],
        )

    assert exc.value.code == "ANALYSIS_GRAPH_POLICY_VALIDATION_FAILED"
    assert exc.value.details["field"] == field
    assert exc.value.details["actual"] == actual
    assert exc.value.details["entityType"] == entity_type
    assert exc.value.details["entityId"] == entity_id
    assert exc.value.details["allowedValues"]


def _validate_extractor_graph(policy, context, extractor_id: str, graph: GraphAnalysisResult) -> None:
    GraphPolicyValidator(policy).validate_extractor_output(
        graph,
        context.graph_contract,
        policy.extractors[extractor_id],
        context.line_count,
        relative_path=context.row["relative_path"],
    )


def _context(relative_path: str, content: str, *, language: str | None = None):
    policy = load_analysis_policy(POLICY_PATH)
    lines = content.splitlines()
    row = {
        "id": 1,
        "source_id": "edge-gateway",
        "source_path": "/workspace/edge-gateway",
        "absolute_path": f"/workspace/edge-gateway/{relative_path}",
        "relative_path": relative_path,
        "display_name": "Edge Gateway",
        "group_name": "edge",
        "tags_json": '["java"]',
        "extension": Path(relative_path).suffix.lower(),
        "language": language or Path(relative_path).suffix.lower().lstrip(".") or "unknown",
        "flow_domain": "UNKNOWN",
        "size_bytes": len(content.encode("utf-8")),
        "content_hash": hashlib.sha256(content.encode("utf-8")).hexdigest(),
        "decode_policy": "utf-8:replace",
    }
    return policy, AnalyzerPolicyRuntimeResolver(policy).resolve(row, {}, lines)


def _graph(
    *,
    nodes: list[GraphNode],
    edges: list[GraphEdge] | None = None,
    claims: list[GraphClaim] | None = None,
    diagnostics: list[dict] | None = None,
) -> GraphAnalysisResult:
    return GraphAnalysisResult(nodes=nodes, edges=edges or [], claims=claims or [], diagnostics=diagnostics or [])


def _file_node(*, line_end: int = 1) -> GraphNode:
    return _node("file", "FILE", name="Foo.java", line_end=line_end)


def _node(local_id: str, kind: str, *, name: str = "node", parent: str | None = None, line_end: int = 1) -> GraphNode:
    return GraphNode(
        localId=local_id,
        nodeKind=kind,
        name=name,
        parentLocalId=parent,
        lineStart=1,
        lineEnd=line_end,
        confidence=1.0,
        metadata={"factOrigin": "STATIC"},
    )


def _edge(
    local_id: str,
    edge_type: str,
    from_id: str,
    to_id: str | None = None,
    *,
    resolution_status: str | None = None,
    unresolved_target: dict | None = None,
) -> GraphEdge:
    return GraphEdge(
        localId=local_id,
        edgeType=edge_type,
        fromNodeLocalId=from_id,
        toNodeLocalId=to_id,
        resolutionStatus=resolution_status,
        confidence=1.0,
        evidence=[GraphEvidenceRef(lineStart=1, lineEnd=1)],
        unresolvedTarget=unresolved_target,
        metadata={"factOrigin": "STATIC"},
    )


def _claim(local_id: str, claim_kind: str, node_id: str, *, evidence: list[GraphEvidenceRef] | None = None) -> GraphClaim:
    return GraphClaim(
        localId=local_id,
        claimKind=claim_kind,
        nodeLocalId=node_id,
        summary="Supported by the file.",
        confidence=1.0,
        evidence=[GraphEvidenceRef(lineStart=1, lineEnd=1)] if evidence is None else evidence,
        metadata={"factOrigin": "STATIC"},
    )


def _metadata_contract_graph(
    *,
    node_metadata: dict | None = None,
    edge_metadata: dict | None = None,
    edge_resolution_status: str | None = None,
    claim_metadata: dict | None = None,
    edge_evidence_metadata: dict | None = None,
    claim_evidence_metadata: dict | None = None,
) -> GraphAnalysisResult:
    return _graph(
        nodes=[
            _file_node().copy(update={"metadata": node_metadata or {"factOrigin": "STATIC"}}),
            _node("config", "CONFIG", parent="file"),
        ],
        edges=[
            GraphEdge(
                localId="configures",
                edgeType="CONFIGURES",
                fromNodeLocalId="file",
                toNodeLocalId="config",
                resolutionStatus=edge_resolution_status,
                confidence=0.8,
                evidence=[GraphEvidenceRef(lineStart=1, lineEnd=1, metadata=edge_evidence_metadata or {})],
                metadata=edge_metadata or {"factOrigin": "STATIC"},
            )
        ],
        claims=[
            GraphClaim(
                localId="purpose",
                claimKind="RESPONSIBILITY",
                nodeLocalId="file",
                summary="Describes the service.",
                confidence=0.8,
                evidence=[GraphEvidenceRef(lineStart=1, lineEnd=1, metadata=claim_evidence_metadata or {})],
                metadata=claim_metadata or {"factOrigin": "STATIC"},
            )
        ],
    )


def _invalid_metadata_contract_graph(case: str) -> GraphAnalysisResult:
    node_metadata = {"factOrigin": "STATIC"}
    edge_metadata = {"factOrigin": "STATIC"}
    edge_resolution_status = None
    claim_metadata = {"factOrigin": "STATIC"}
    edge_evidence_metadata: dict = {}
    claim_evidence_metadata: dict = {}
    if case == "node_status":
        node_metadata["status"] = "BOGUS"
    elif case == "node_origin":
        node_metadata["factOrigin"] = "BOGUS"
    elif case == "edge_status":
        edge_metadata["status"] = "BOGUS"
    elif case == "edge_origin":
        edge_metadata["factOrigin"] = "BOGUS"
    elif case == "edge_resolution":
        edge_resolution_status = "BOGUS"
    elif case == "claim_status":
        claim_metadata["status"] = "BOGUS"
    elif case == "claim_origin":
        claim_metadata["factOrigin"] = "BOGUS"
    elif case == "evidence_kind":
        edge_evidence_metadata["evidenceKind"] = "BOGUS"
    elif case == "evidence_origin":
        claim_evidence_metadata["factOrigin"] = "BOGUS"
    else:
        raise AssertionError(f"Unknown metadata validation case: {case}")
    return _metadata_contract_graph(
        node_metadata=node_metadata,
        edge_metadata=edge_metadata,
        edge_resolution_status=edge_resolution_status,
        claim_metadata=claim_metadata,
        edge_evidence_metadata=edge_evidence_metadata,
        claim_evidence_metadata=claim_evidence_metadata,
    )
