from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any

import pytest
import yaml

from knowledge_service.analysis_graph_contract import AnalysisPromptRenderer, GraphContractProvider, contract_payload
from knowledge_service.analysis_policy_loader import load_analysis_policy
from knowledge_service.errors import KnowledgeError
from knowledge_service.graph_response_parser import GraphAnalysisParseFailure, GraphAnalysisResponseParser
from knowledge_service.graph_schema import GraphAnalysisResult

REPO_ROOT = Path(__file__).resolve().parents[3]
POLICY_PATH = REPO_ROOT / "config" / "knowledge" / "analysis-policy.yaml"
RESPONSE_SHAPE_PATH = REPO_ROOT / "config" / "knowledge" / "prompts" / "schemas" / "graph-enrichment-v1-response-shape.json"
PROMPT_TEMPLATE_PATHS = [
    REPO_ROOT / "config" / "knowledge" / "prompts" / "code-graph-enrichment.md",
    REPO_ROOT / "config" / "knowledge" / "prompts" / "text-graph-enrichment.md",
    REPO_ROOT / "config" / "knowledge" / "prompts" / "document-graph-enrichment.md",
]
FORBIDDEN_PROMPT_VALUES = {"UNKNOWN", "DIAGNOSTIC", "RELATED_TO"}


def test_prompt_contract_rendering_uses_yaml_for_code_text_and_document():
    policy = load_analysis_policy(POLICY_PATH)
    renderer = AnalysisPromptRenderer(policy=policy)

    code_prompt = _render_prompt(renderer, "src/Foo.java")
    text_prompt = _render_prompt(renderer, "config.yaml", "service:\n  endpoint: http://example\n")
    document_prompt = _render_prompt(renderer, "README.md", "# Service\nDocuments service behavior.\n")

    assert "CALLS" in code_prompt
    assert "ENTRYPOINT_HINT" in code_prompt
    assert "CONFIGURES" in text_prompt
    assert "CONFIG_REFERENCE" in text_prompt
    assert "document_graph" in document_prompt
    assert "allowedNodeKinds" in document_prompt
    assert all(value not in code_prompt for value in FORBIDDEN_PROMPT_VALUES)
    assert all(value not in text_prompt for value in FORBIDDEN_PROMPT_VALUES)
    assert all(value not in document_prompt for value in FORBIDDEN_PROMPT_VALUES)


def test_prompt_response_shape_rendering_uses_shared_json_for_code_text_and_document():
    policy = load_analysis_policy(POLICY_PATH)
    renderer = AnalysisPromptRenderer(policy=policy)
    response_shape = _shared_response_shape()

    code_prompt = _render_prompt(renderer, "src/Foo.java")
    text_prompt = _render_prompt(renderer, "config.yaml", "service:\n  endpoint: http://example\n")
    document_prompt = _render_prompt(renderer, "README.md", "# Service\nDocuments service behavior.\n")

    assert response_shape in code_prompt
    assert response_shape in text_prompt
    assert response_shape in document_prompt
    assert "{{GRAPH_RESPONSE_SHAPE}}" not in code_prompt
    assert "{{GRAPH_RESPONSE_SHAPE}}" not in text_prompt
    assert "{{GRAPH_RESPONSE_SHAPE}}" not in document_prompt


def test_prompt_markdown_uses_response_shape_placeholder_instead_of_duplicated_json():
    response_shape = _shared_response_shape()

    for path in PROMPT_TEMPLATE_PATHS:
        text = path.read_text(encoding="utf-8")
        assert "{{GRAPH_RESPONSE_SHAPE}}" in text
        assert response_shape not in text


def test_prompt_contract_rendering_changes_when_yaml_changes(tmp_path):
    data = copy.deepcopy(yaml.safe_load(POLICY_PATH.read_text(encoding="utf-8")))
    data["analysis"]["graph"]["claims"]["SECURITY_NOTE"] = {
        "evidenceRequired": True,
        "materialSupportRequired": True,
        "semanticEligible": False,
    }
    data["analysis"]["graphProfiles"]["code_text_graph"]["claims"].append("SECURITY_NOTE")
    policy_path = tmp_path / "analysis-policy.yaml"
    policy_path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
    policy = load_analysis_policy(policy_path)
    renderer = AnalysisPromptRenderer(policy=policy)

    prompt = _render_prompt(renderer, "script.py", "print('hello')\n")

    assert "SECURITY_NOTE" in prompt


def test_prompt_response_shape_rendering_changes_when_shared_json_changes(tmp_path):
    default_renderer = AnalysisPromptRenderer(policy=load_analysis_policy(POLICY_PATH))
    default_prompt = _render_prompt(default_renderer, "src/Foo.java")
    prompt_root = tmp_path / "prompts"
    schema_dir = prompt_root / "schemas"
    schema_dir.mkdir(parents=True)
    (prompt_root / "code-graph-enrichment.md").write_text(PROMPT_TEMPLATE_PATHS[0].read_text(encoding="utf-8"), encoding="utf-8")
    mutated_shape = {
        "schemaVersion": "knowledge.graph.enrichment.v1",
        "claims": [],
        "semanticEdges": [],
        "diagnostics": [],
        "fixtureMarker": "changed-response-shape",
    }
    (schema_dir / "graph-enrichment-v1-response-shape.json").write_text(json.dumps(mutated_shape, indent=2), encoding="utf-8")
    data = copy.deepcopy(yaml.safe_load(POLICY_PATH.read_text(encoding="utf-8")))
    data["analysis"]["promptRoot"] = str(prompt_root)
    policy_path = tmp_path / "analysis-policy.yaml"
    policy_path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
    renderer = AnalysisPromptRenderer(policy=load_analysis_policy(policy_path))

    mutated_prompt = _render_prompt(renderer, "src/Foo.java")

    assert mutated_prompt != default_prompt
    assert '"fixtureMarker": "changed-response-shape"' in mutated_prompt
    assert '"fixtureMarker": "changed-response-shape"' not in default_prompt


def test_prompt_renderer_rejects_missing_response_shape_file(tmp_path):
    data = copy.deepcopy(yaml.safe_load(POLICY_PATH.read_text(encoding="utf-8")))
    data["analysis"]["prompts"]["code_graph_enrichment"]["responseShape"] = "schemas/missing-response-shape.json"
    policy_path = tmp_path / "analysis-policy.yaml"
    policy_path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
    policy = load_analysis_policy(policy_path)
    renderer = AnalysisPromptRenderer(policy=policy)
    contract = renderer.provider.resolve("src/Foo.java", "class Foo {}\n")
    payload = {
        "relativePath": "src/Foo.java",
        "content": "class Foo {}\n",
        "analysisPolicy": contract_payload(contract),
    }

    with pytest.raises(KnowledgeError) as exc_info:
        renderer.render_for_payload(payload)

    assert exc_info.value.code == "ANALYSIS_POLICY_RESPONSE_SHAPE_FILE_MISSING"
    assert "missing-response-shape.json" in exc_info.value.details["responseShapePath"]


def test_graph_contract_provider_empty_relative_path_fails_explicitly():
    provider = GraphContractProvider(policy=load_analysis_policy(POLICY_PATH))

    with pytest.raises(KnowledgeError) as exc_info:
        provider.resolve("")

    assert exc_info.value.code == "ANALYSIS_POLICY_RELATIVE_PATH_REQUIRED"


def test_graph_contract_provider_unsupported_extension_fails_explicitly():
    provider = GraphContractProvider(policy=load_analysis_policy(POLICY_PATH))

    with pytest.raises(KnowledgeError) as exc_info:
        provider.resolve("unknown.bin", "opaque bytes")

    assert exc_info.value.code == "UNSUPPORTED_FORMAT"
    assert exc_info.value.details["relativePath"] == "unknown.bin"
    assert exc_info.value.details["unsupportedBehavior"]["unsupportedFormat"] == "fail_file"


def test_parser_without_relative_path_fails_explicitly_instead_of_default_contract():
    parser = GraphAnalysisResponseParser(GraphContractProvider(policy=load_analysis_policy(POLICY_PATH)))

    with pytest.raises(KnowledgeError) as exc_info:
        parser.parse(json.dumps(_graph_payload()), 5)

    assert exc_info.value.code == "ANALYSIS_POLICY_RELATIVE_PATH_REQUIRED"


def test_prompt_renderer_rejects_unsupported_path_without_legacy_fallback():
    renderer = AnalysisPromptRenderer(policy=load_analysis_policy(POLICY_PATH))

    with pytest.raises(KnowledgeError) as exc_info:
        renderer.render_for_payload(
            {"relativePath": "unknown.bin", "content": "opaque bytes"},
        )

    assert exc_info.value.code == "UNSUPPORTED_FORMAT"


def test_prompt_renderer_requires_prompt_id_without_legacy_fallback():
    policy = load_analysis_policy(POLICY_PATH)
    renderer = AnalysisPromptRenderer(policy=policy)
    contract = renderer.provider.resolve("src/Foo.java", "class Foo {}\n")
    payload = {
        "relativePath": "src/Foo.java",
        "content": "class Foo {}\n",
        "analysisPolicy": contract_payload(contract),
    }
    payload["analysisPolicy"].pop("promptId")

    with pytest.raises(KnowledgeError) as exc_info:
        renderer.render_for_payload(payload)

    assert exc_info.value.code == "ANALYSIS_POLICY_PROMPT_REQUIRED"


def test_prompt_renderer_rejects_missing_prompt_file_without_legacy_fallback(tmp_path):
    data = copy.deepcopy(yaml.safe_load(POLICY_PATH.read_text(encoding="utf-8")))
    data["analysis"]["prompts"]["code_graph_enrichment"]["file"] = "missing-code-prompt.md"
    policy_path = tmp_path / "analysis-policy.yaml"
    policy_path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
    policy = load_analysis_policy(policy_path)
    renderer = AnalysisPromptRenderer(policy=policy)
    contract = renderer.provider.resolve("src/Foo.java", "class Foo {}\n")
    payload = {
        "relativePath": "src/Foo.java",
        "content": "class Foo {}\n",
        "analysisPolicy": contract_payload(contract),
    }

    with pytest.raises(KnowledgeError) as exc_info:
        renderer.render_for_payload(payload)

    assert exc_info.value.code == "ANALYSIS_POLICY_PROMPT_FILE_MISSING"
    assert "missing-code-prompt.md" in exc_info.value.details["promptPath"]


def test_prompt_renderer_rejects_undeclared_prompt_id_without_legacy_fallback():
    policy = load_analysis_policy(POLICY_PATH)
    renderer = AnalysisPromptRenderer(policy=policy)
    contract = renderer.provider.resolve("src/Foo.java", "class Foo {}\n")
    payload = {
        "relativePath": "src/Foo.java",
        "content": "class Foo {}\n",
        "analysisPolicy": contract_payload(contract),
    }
    payload["analysisPolicy"]["promptId"] = "missing_prompt"

    with pytest.raises(KnowledgeError) as exc_info:
        renderer.render_for_payload(payload)

    assert exc_info.value.code == "ANALYSIS_POLICY_PROMPT_MISSING"
    assert exc_info.value.details["promptId"] == "missing_prompt"


def test_parser_allows_yaml_declared_claim_and_edge_types():
    parser, contract = _parser_and_contract("src/Foo.java")
    payload = _graph_payload()
    payload["claims"][0]["claimKind"] = "SIDE_EFFECT"
    payload["edges"][0]["edgeType"] = "CALLS"
    payload["edges"][0]["fromNodeLocalId"] = "callable1"
    payload["edges"][0]["toNodeLocalId"] = "external1"

    result = parser.parse(json.dumps(payload), 5, contract=contract)

    assert isinstance(result, GraphAnalysisResult)
    assert result.claims[0].claimKind == "SIDE_EFFECT"
    assert result.edges[0].edgeType == "CALLS"


def test_parser_behavior_changes_when_yaml_policy_removes_claim_kind(tmp_path):
    default_parser, default_contract = _parser_and_contract("src/Foo.java")
    payload = _graph_payload(claim_kind="SIDE_EFFECT")

    default_result = default_parser.parse(json.dumps(payload), 5, contract=default_contract)

    assert isinstance(default_result, GraphAnalysisResult)
    assert default_result.claims[0].claimKind == "SIDE_EFFECT"

    data = copy.deepcopy(yaml.safe_load(POLICY_PATH.read_text(encoding="utf-8")))
    data["analysis"]["graphProfiles"]["java_code_graph"]["claims"].remove("SIDE_EFFECT")
    policy_path = tmp_path / "analysis-policy.yaml"
    policy_path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
    mutated_policy = load_analysis_policy(policy_path)
    mutated_provider = GraphContractProvider(policy=mutated_policy)
    mutated_parser = GraphAnalysisResponseParser(mutated_provider)
    mutated_contract = mutated_provider.resolve("src/Foo.java", "class Foo { void call() {} }\n")

    mutated_failure = mutated_parser.parse(json.dumps(payload), 5, contract=mutated_contract)

    assert isinstance(mutated_failure, GraphAnalysisParseFailure)
    detail = _detail(mutated_failure, "$.claims[0].claimKind")
    assert detail["jsonPath"] == "$.claims[0].claimKind"
    assert detail["actual"] == "SIDE_EFFECT"
    assert detail["invalidValue"] == "SIDE_EFFECT"
    assert "SIDE_EFFECT" not in detail["allowedValues"]
    assert "RESPONSIBILITY" in detail["allowedValues"]

    still_allowed_payload = _graph_payload(claim_kind="RESPONSIBILITY")
    mutated_result = mutated_parser.parse(json.dumps(still_allowed_payload), 5, contract=mutated_contract)

    assert isinstance(mutated_result, GraphAnalysisResult)
    assert mutated_result.claims[0].claimKind == "RESPONSIBILITY"


def test_parser_rejects_invalid_claim_kind_with_yaml_allowed_values():
    failure = _parse_with_mutation("src/Foo.java", lambda payload: payload["claims"][0].update({"claimKind": "PURPOSE"}))

    detail = _detail(failure, "$.claims[0].claimKind")
    assert detail["actual"] == "PURPOSE"
    assert "RESPONSIBILITY" in detail["allowedValues"]
    assert "SIDE_EFFECT" in detail["allowedValues"]
    assert "PURPOSE" not in detail["allowedValues"]


def test_parser_rejects_invalid_edge_type_with_yaml_allowed_values():
    failure = _parse_with_mutation("src/Foo.java", lambda payload: payload["edges"][0].update({"edgeType": "RELATED_TO"}))

    detail = _detail(failure, "$.edges[0].edgeType")
    assert detail["actual"] == "RELATED_TO"
    assert "CALLS" in detail["allowedValues"]
    assert "RELATED_TO" not in detail["allowedValues"]


def test_parser_rejects_invalid_node_kind_with_yaml_allowed_values():
    failure = _parse_with_mutation("src/Foo.java", lambda payload: payload["nodes"][0].update({"nodeKind": "SECTION"}))

    detail = _detail(failure, "$.nodes[0].nodeKind")
    assert detail["actual"] == "SECTION"
    assert "TYPE" in detail["allowedValues"]
    assert "SECTION" not in detail["allowedValues"]


def test_parser_rejects_invalid_status_origin_evidence_and_resolution_values():
    status_failure = _parse_with_mutation("src/Foo.java", lambda payload: payload["claims"][0]["metadata"].update({"status": "ACTIVE"}))
    origin_failure = _parse_with_mutation("src/Foo.java", lambda payload: payload["claims"][0]["metadata"].update({"factOrigin": "MODEL"}))
    evidence_failure = _parse_with_mutation(
        "src/Foo.java",
        lambda payload: payload["claims"][0]["evidence"][0]["metadata"].update({"evidenceKind": "SNIPPET"}),
    )
    resolution_failure = _parse_with_mutation(
        "src/Foo.java",
        lambda payload: payload["edges"][0]["metadata"].update({"resolutionStatus": "GUESS"}),
    )

    assert "TRUSTED" in _detail(status_failure, "$.claims[0].metadata.status")["allowedValues"]
    assert "LLM" in _detail(origin_failure, "$.claims[0].metadata.factOrigin")["allowedValues"]
    assert "CLAIM" in _detail(evidence_failure, "$.claims[0].evidence[0].metadata.evidenceKind")["allowedValues"]
    assert "RESOLVED" in _detail(resolution_failure, "$.edges[0].metadata.resolutionStatus")["allowedValues"]


def test_parser_rejects_edge_endpoint_rule_violation_when_node_kinds_are_known():
    failure = _parse_with_mutation(
        "src/Foo.java",
        lambda payload: (
            payload["edges"][0].update({"edgeType": "CALLS", "fromNodeLocalId": "file1", "toNodeLocalId": "callable1"})
        ),
    )

    detail = _detail(failure, "$.edges[0].fromNodeLocalId")
    assert detail["actual"] == "FILE"
    assert detail["allowedValues"] == ["CALLABLE"]


def test_effective_profiles_allow_java_text_and_document_specific_contracts():
    java_parser, java_contract = _parser_and_contract("src/Foo.java")
    text_parser, text_contract = _parser_and_contract("settings.yaml", "service:\n  url: http://example\n")
    document_parser, document_contract = _parser_and_contract("README.md", "# Service\n")

    java_payload = _graph_payload(node_kind="FIELD", claim_kind="CONFIG_REFERENCE")
    assert isinstance(java_parser.parse(json.dumps(java_payload), 5, contract=java_contract), GraphAnalysisResult)

    text_payload = _graph_payload(node_kind="CONFIG", edge_type="CONFIGURES", claim_kind="CONFIG_REFERENCE")
    text_payload["nodes"] = [node for node in text_payload["nodes"] if node["nodeKind"] != "CALLABLE"]
    text_payload["edges"][0]["fromNodeLocalId"] = "file1"
    text_payload["edges"][0]["toNodeLocalId"] = "config1"
    assert isinstance(text_parser.parse(json.dumps(text_payload), 5, contract=text_contract), GraphAnalysisResult)

    document_payload = _graph_payload(node_kind="EXTERNAL", edge_type="CALLS", claim_kind="CONFIG_REFERENCE")
    document_payload["nodes"] = [node for node in document_payload["nodes"] if node["nodeKind"] != "CALLABLE"]
    document_payload["edges"][0]["toNodeLocalId"] = "type1"
    document_failure = document_parser.parse(json.dumps(document_payload), 5, contract=document_contract)
    assert isinstance(document_failure, GraphAnalysisParseFailure)
    assert _detail(document_failure, "$.edges[0].edgeType")["allowedValues"] == ["REFERENCES", "DEPENDS_ON", "CONFIGURES"]
    assert _detail(document_failure, "$.claims[0].claimKind")["allowedValues"] == ["RESPONSIBILITY"]


def test_generic_enrichment_uses_effective_yaml_claim_kinds_not_responsibility_only():
    content = "service:\n  url: http://example\n"
    parser, contract = _parser_and_contract("settings.yaml", content)
    payload = {
        "schemaVersion": "knowledge.graph.enrichment.v1",
        "claims": [
            {
                "localId": "claim1",
                "targetStableKey": "file1",
                "claimKind": "CONFIG_REFERENCE",
                "summary": "References a configured service URL.",
                "confidence": 0.8,
                "evidence": [{"lineStart": 1, "lineEnd": 2, "text": "service url", "metadata": {"evidenceKind": "CLAIM"}}],
                "metadata": {"factOrigin": "LLM", "status": "TRUSTED"},
            }
        ],
        "semanticEdges": [],
        "diagnostics": [],
    }

    result = parser.parse(json.dumps(payload), 2, contract=contract, known_node_kinds={"file1": "FILE"})

    assert isinstance(result, GraphAnalysisResult)
    assert result.claims[0].claimKind == "CONFIG_REFERENCE"


def _render_prompt(renderer: AnalysisPromptRenderer, relative_path: str, content: str = "class Foo {}\n") -> str:
    contract = renderer.provider.resolve(relative_path, content)
    return renderer.render_for_payload(
        {
            "relativePath": relative_path,
            "content": content,
            "analysisPolicy": contract_payload(contract),
        },
        contract=contract,
    )


def _parser_and_contract(relative_path: str, content: str = "class Foo { void call() {} }\n"):
    policy = load_analysis_policy(POLICY_PATH)
    provider = GraphContractProvider(policy=policy)
    return GraphAnalysisResponseParser(provider), provider.resolve(relative_path, content)


def _graph_payload(node_kind: str = "TYPE", edge_type: str = "DECLARES", claim_kind: str = "RESPONSIBILITY") -> dict[str, Any]:
    return {
        "nodes": [
            {
                "localId": "file1",
                "nodeKind": "FILE",
                "name": "Foo.java",
                "lineStart": 1,
                "lineEnd": 5,
                "confidence": 1.0,
                "metadata": {"factOrigin": "STATIC", "status": "TRUSTED"},
            },
            {
                "localId": "type1" if node_kind != "CONFIG" else "config1",
                "nodeKind": node_kind,
                "name": "Foo",
                "lineStart": 1,
                "lineEnd": 5,
                "confidence": 0.9,
                "metadata": {"factOrigin": "LLM", "status": "TRUSTED"},
            },
            {
                "localId": "callable1",
                "nodeKind": "CALLABLE",
                "name": "call",
                "lineStart": 2,
                "lineEnd": 4,
                "confidence": 0.9,
                "metadata": {"factOrigin": "LLM", "status": "TRUSTED"},
            },
            {
                "localId": "external1",
                "nodeKind": "EXTERNAL",
                "name": "External",
                "confidence": 0.7,
                "metadata": {"factOrigin": "LLM", "status": "TRUSTED"},
            },
        ],
        "edges": [
            {
                "localId": "edge1",
                "fromNodeLocalId": "file1",
                "toNodeLocalId": "type1" if node_kind != "CONFIG" else "config1",
                "edgeType": edge_type,
                "confidence": 0.8,
                "evidence": [{"lineStart": 1, "lineEnd": 2, "text": "evidence", "metadata": {"evidenceKind": "EDGE"}}],
                "metadata": {"factOrigin": "LLM", "status": "TRUSTED", "resolutionStatus": "RESOLVED"},
            }
        ],
        "claims": [
            {
                "localId": "claim1",
                "nodeLocalId": "type1" if node_kind != "CONFIG" else "config1",
                "claimKind": claim_kind,
                "summary": "Does useful work.",
                "evidence": [{"lineStart": 1, "lineEnd": 2, "text": "evidence", "metadata": {"evidenceKind": "CLAIM"}}],
                "confidence": 0.8,
                "metadata": {"factOrigin": "LLM", "status": "TRUSTED"},
            }
        ],
        "diagnostics": [],
    }


def _parse_with_mutation(relative_path: str, mutate, content: str = "class Foo { void call() {} }\n") -> GraphAnalysisParseFailure:
    parser, contract = _parser_and_contract(relative_path, content)
    payload = _graph_payload()
    mutate(payload)
    result = parser.parse(json.dumps(payload), 5, contract=contract)
    assert isinstance(result, GraphAnalysisParseFailure)
    return result


def _detail(failure: GraphAnalysisParseFailure, path: str) -> dict[str, Any]:
    for detail in failure.error_details:
        if detail.get("jsonPath") == path:
            return detail
    raise AssertionError(f"missing detail for {path}: {failure.error_details}")


def _shared_response_shape() -> str:
    return RESPONSE_SHAPE_PATH.read_text(encoding="utf-8").strip()
