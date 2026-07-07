from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any

import pytest
import yaml

from knowledge_service.analysis_graph_contract import GraphContractProvider, contract_payload
from knowledge_service.analysis_policy_loader import load_analysis_policy
from knowledge_service.errors import KnowledgeError
from knowledge_service.graph_response_parser import GraphAnalysisParseFailure, GraphAnalysisResponseParser
from knowledge_service.graph_schema import GraphAnalysisResult

REPO_ROOT = Path(__file__).resolve().parents[3]
POLICY_PATH = REPO_ROOT / "config" / "knowledge" / "analysis-policy.yaml"


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


def test_parser_without_explicit_contract_or_analysis_policy_fails_explicitly():
    parser = GraphAnalysisResponseParser(GraphContractProvider(policy=load_analysis_policy(POLICY_PATH)))

    with pytest.raises(KnowledgeError) as exc_info:
        parser.parse(json.dumps(_graph_payload()), 5)

    assert exc_info.value.code == "ANALYSIS_POLICY_CONTRACT_REQUIRED"


def test_parser_with_explicit_contract_succeeds_without_analysis_policy_payload():
    parser, contract = _parser_and_contract("src/Foo.java")

    result = parser.parse(json.dumps(_graph_payload()), 5, contract=contract)

    assert isinstance(result, GraphAnalysisResult)
    assert result.nodes[0].nodeKind == "FILE"


def test_parser_with_analysis_policy_payload_succeeds_without_explicit_contract():
    parser, contract = _parser_and_contract("src/Foo.java")
    payload = _graph_payload()
    payload["analysisPolicy"] = contract_payload(contract)

    result = parser.parse(json.dumps(payload), 5)

    assert isinstance(result, GraphAnalysisResult)
    assert result.nodes[0].nodeKind == "FILE"


def test_graph_contract_provider_uses_only_canonical_edge_type_payload_names():
    provider = GraphContractProvider(policy=load_analysis_policy(POLICY_PATH))
    contract = provider.resolve("src/Foo.java", "class Foo {}\n")
    payload = contract_payload(contract)

    assert "allowedEdgeTypes" in payload
    assert "semanticEdgeTypes" in payload
    assert "allowedEdgeKinds" not in payload
    assert "semanticEdgeKinds" not in payload

    legacy_payload = {"analysisPolicy": copy.deepcopy(payload)}
    legacy_payload["analysisPolicy"]["allowedEdgeKinds"] = legacy_payload["analysisPolicy"].pop("allowedEdgeTypes")
    legacy_payload["analysisPolicy"]["semanticEdgeKinds"] = legacy_payload["analysisPolicy"].pop("semanticEdgeTypes")

    legacy_contract = provider.resolve_payload(legacy_payload)

    assert legacy_contract.allowed_edge_types == ()
    assert legacy_contract.semantic_edge_types == ()


def test_parser_does_not_resolve_contract_from_relative_path_fallback():
    class NoPathFallbackProvider(GraphContractProvider):
        def resolve(self, *args, **kwargs):
            raise AssertionError("parser must not resolve analysis policy from relativePath")

    parser = GraphAnalysisResponseParser(NoPathFallbackProvider(policy=load_analysis_policy(POLICY_PATH)))
    payload = _graph_payload()
    payload["file"] = {"relativePath": "src/Foo.java", "content": "class Foo {}\n"}

    with pytest.raises(KnowledgeError) as exc_info:
        parser.parse(json.dumps(payload), 5)

    assert exc_info.value.code == "ANALYSIS_POLICY_CONTRACT_REQUIRED"


def test_legacy_analysis_prompt_path_fallback_stays_removed():
    assert not (REPO_ROOT / "config" / "knowledge" / "analysis-prompt.md").exists()
    config = yaml.safe_load((REPO_ROOT / "config" / "forge-ai.yaml").read_text(encoding="utf-8"))
    analysis_config = config["forge"]["ai"]["services"]["knowledge"]["analysis"]
    assert "prompt-path" not in analysis_config
    assert "promptPath" not in analysis_config


def test_parser_allows_yaml_declared_claim_and_edge_types():
    parser, contract = _parser_and_contract("src/Foo.java")
    payload = _graph_payload()
    payload["claims"][0]["claimKind"] = "SIDE_EFFECT"
    payload["edges"][0]["edgeType"] = "CALLS"
    payload["edges"][0]["fromNodeLocalId"] = "callable1"
    payload["edges"][0]["toNodeLocalId"] = None
    payload["edges"][0]["unresolvedTarget"] = {"name": "External.call", "kindHint": "CALLABLE"}
    payload["edges"][0]["resolutionStatus"] = "EXTERNAL_TARGET"

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
        lambda payload: payload["edges"][0].update({"resolutionStatus": "GUESS"}),
    )

    assert "TRUSTED" in _detail(status_failure, "$.claims[0].metadata.status")["allowedValues"]
    assert "LLM" in _detail(origin_failure, "$.claims[0].metadata.factOrigin")["allowedValues"]
    assert "CLAIM" in _detail(evidence_failure, "$.claims[0].evidence[0].metadata.evidenceKind")["allowedValues"]
    assert "RESOLVED" in _detail(resolution_failure, "$.edges[0].resolutionStatus")["allowedValues"]


def test_parser_ignores_metadata_resolution_status():
    parser, contract = _parser_and_contract("src/Foo.java")
    payload = _graph_payload()
    payload["edges"][0].pop("resolutionStatus")
    payload["edges"][0]["metadata"]["resolutionStatus"] = "GUESS"

    result = parser.parse(json.dumps(payload), 5, contract=contract)

    assert isinstance(result, GraphAnalysisResult)
    assert result.edges[0].resolutionStatus is None


def test_parser_rejects_resolved_edge_without_target_at_first_class_path():
    failure = _parse_with_mutation(
        "src/Foo.java",
        lambda payload: payload["edges"][0].update(
            {
                "edgeType": "CALLS",
                "fromNodeLocalId": "callable1",
                "toNodeLocalId": None,
                "resolutionStatus": "RESOLVED",
            }
        ),
    )

    detail = _detail(failure, "$.edges[0].resolutionStatus")
    assert detail["actual"] == "RESOLVED"
    assert "requires toNodeLocalId" in detail["message"]


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

    document_payload = _graph_payload(node_kind="TYPE", edge_type="CALLS", claim_kind="CONFIG_REFERENCE")
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


def test_enrichment_parser_sets_fact_origin_llm_server_side():
    parser, contract = _parser_and_contract("src/Foo.java")
    payload = _enrichment_payload()
    payload["claims"][0]["metadata"] = {"factOrigin": "STATIC"}
    payload["semanticEdges"][0]["metadata"] = {"factOrigin": "STATIC", "resolutionStatus": "GUESS"}

    result = parser.parse(json.dumps(payload), 5, contract=contract, known_node_kinds={"file1": "FILE"})

    assert isinstance(result, GraphAnalysisResult)
    assert result.claims[0].metadata["factOrigin"] == "LLM"
    assert result.edges[0].metadata["factOrigin"] == "LLM"
    assert "resolutionStatus" not in result.edges[0].metadata


def test_enrichment_parser_still_rejects_old_edge_kind_field():
    parser, contract = _parser_and_contract("src/Foo.java")
    payload = {
        "schemaVersion": "knowledge.graph.enrichment.v1",
        "claims": [],
        "semanticEdges": [
            {
                "localId": "edge-1",
                "fromStableKey": "file1",
                "toStableKey": None,
                "edgeKind": "CALLS",
                "resolutionStatus": "EXTERNAL_TARGET",
                "confidence": 0.8,
                "evidence": [{"lineStart": 1, "lineEnd": 1, "text": "class Foo"}],
                "unresolvedTarget": {"name": "External.call", "kindHint": "CALLABLE"},
                "metadata": {"factOrigin": "LLM"},
            }
        ],
        "diagnostics": [],
    }

    result = parser.parse(json.dumps(payload), 5, contract=contract, known_node_kinds={"file1": "FILE"})

    assert isinstance(result, GraphAnalysisParseFailure)
    detail = _detail(result, "$.semanticEdges[0].edgeType")
    assert detail["missingRequiredField"] == "edgeType"


def test_enrichment_parser_still_rejects_missing_local_id():
    parser, contract = _parser_and_contract("src/Foo.java")
    payload = _enrichment_payload()
    payload["claims"][0].pop("localId")

    result = parser.parse(json.dumps(payload), 5, contract=contract, known_node_kinds={"file1": "FILE"})

    assert isinstance(result, GraphAnalysisParseFailure)
    detail = _detail(result, "$.claims[0].localId")
    assert detail["missingRequiredField"] == "localId"


def test_enrichment_parser_still_rejects_object_shaped_evidence():
    parser, contract = _parser_and_contract("src/Foo.java")
    payload = _enrichment_payload()
    payload["claims"][0]["evidence"] = {"lineStart": 1, "lineEnd": 1, "text": "class Foo"}

    result = parser.parse(json.dumps(payload), 5, contract=contract, known_node_kinds={"file1": "FILE"})

    assert isinstance(result, GraphAnalysisParseFailure)
    detail = _detail(result, "$.claims[0].evidence")
    assert detail["field"] == "evidence"


def test_enrichment_parser_still_rejects_missing_evidence_line_range():
    parser, contract = _parser_and_contract("src/Foo.java")
    payload = _enrichment_payload()
    payload["claims"][0]["evidence"][0].pop("lineStart")

    result = parser.parse(json.dumps(payload), 5, contract=contract, known_node_kinds={"file1": "FILE"})

    assert isinstance(result, GraphAnalysisParseFailure)
    detail = _detail(result, "$.claims[0].evidence[0].lineStart")
    assert detail["missingRequiredField"] == "lineStart"


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
        ],
        "edges": [
            {
                "localId": "edge1",
                "fromNodeLocalId": "file1",
                "toNodeLocalId": "type1" if node_kind != "CONFIG" else "config1",
                "edgeType": edge_type,
                "resolutionStatus": "RESOLVED",
                "confidence": 0.8,
                "evidence": [{"lineStart": 1, "lineEnd": 2, "text": "evidence", "metadata": {"evidenceKind": "EDGE"}}],
                "metadata": {"factOrigin": "LLM", "status": "TRUSTED"},
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


def _enrichment_payload() -> dict[str, Any]:
    return {
        "schemaVersion": "knowledge.graph.enrichment.v1",
        "claims": [
            {
                "localId": "claim-1",
                "targetStableKey": "file1",
                "claimKind": "RESPONSIBILITY",
                "summary": "The file defines Foo.",
                "confidence": 0.8,
                "evidence": [{"lineStart": 1, "lineEnd": 1, "text": "class Foo"}],
            }
        ],
        "semanticEdges": [
            {
                "localId": "edge-1",
                "fromStableKey": "file1",
                "toStableKey": None,
                "edgeType": "REFERENCES",
                "resolutionStatus": "EXTERNAL_TARGET",
                "confidence": 0.8,
                "evidence": [{"lineStart": 1, "lineEnd": 1, "text": "class Foo"}],
                "unresolvedTarget": {"name": "ExternalFoo", "kindHint": "TYPE"},
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
