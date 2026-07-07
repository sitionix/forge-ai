from __future__ import annotations

import asyncio
import json
from dataclasses import replace
from pathlib import Path

import httpx
import pytest

from knowledge_service.analysis_client import OllamaAnalysisClient
from knowledge_service.analysis_graph_contract import GraphContractProvider
from knowledge_service.analysis_policy_loader import load_analysis_policy
from knowledge_service.analyzer_runtime import AnalyzerPolicyRuntimeResolver, AnalyzerRuntime, ExtractorRegistry
from knowledge_service.errors import KnowledgeError
from knowledge_service.graph_response_parser import GraphAnalysisParseFailure
from knowledge_service.graph_schema import GraphAnalysisResult, GraphEdge, GraphEvidenceRef, GraphNode
from knowledge_service.target_enrichment import (
    BEGIN_INPUT_MARKER,
    END_INPUT_MARKER,
    TARGET_INPUT_SCHEMA_VERSION,
    TARGET_REQUEST_KIND,
    TARGET_RESPONSE_SCHEMA_VERSION,
    AnchorRefRegistry,
    FileEnrichmentMerger,
    LlmEnrichmentInputBuilder,
    LlmEnrichmentPlanner,
    TargetResponseParserValidator,
)


REPO_ROOT = Path(__file__).resolve().parents[3]
POLICY_PATH = REPO_ROOT / "config" / "knowledge" / "analysis-policy.yaml"


def test_anchor_ref_registry_is_deterministic_compact_and_keeps_internal_stable_key_map():
    contract = _contract("src/Foo.java")
    graph = _mixed_anchor_graph()

    first = AnchorRefRegistry.build(graph, contract)
    second = AnchorRefRegistry.build(graph, contract)
    rendered = first.to_llm_list()

    assert [item["ref"] for item in rendered] == [item["ref"] for item in second.to_llm_list()]
    assert len({item["ref"] for item in rendered}) == len(rendered)
    assert [item["ref"] for item in rendered] == ["F1", "T1", "FIELD1", "M1", "M2", "M3"]
    assert next(item for item in rendered if item["ref"] == "T1")["parentRef"] == "F1"
    assert next(item for item in rendered if item["ref"] == "FIELD1")["parentRef"] == "T1"
    assert next(item for item in rendered if item["ref"] == "M2")["annotations"][0]["arguments"] == "(timeout = 1)"
    assert first.ref_to_stable_key["M1"] == "svc|src/Foo.java|CALLABLE|Foo.call()"
    assert first.stable_key_to_ref["svc|src/Foo.java|CALLABLE|Foo.call(String)"] == "M3"
    assert not _contains_key(rendered, "stableKey")
    assert not _contains_key(rendered, "metadata")


def test_llm_input_projection_includes_minimal_contract_and_excludes_internal_payload():
    policy = load_analysis_policy(POLICY_PATH)
    resolver = AnalyzerPolicyRuntimeResolver(policy)
    content_lines = ["class Foo {", "  void call() {}", "}"]
    context = resolver.resolve(_row("src/Foo.java", "\n".join(content_lines)), {"absoluteRoot": "/tmp/root"}, content_lines)
    registry = AnchorRefRegistry.build(_mixed_anchor_graph(), context.graph_contract)
    target = LlmEnrichmentPlanner().plan(_mixed_anchor_graph(), context.graph_contract).targets[0]

    payload = LlmEnrichmentInputBuilder().build(context=context, registry=registry, target=target, budget_chars=50000)
    llm_input = payload["llmInput"]

    assert llm_input["schemaVersion"] == TARGET_INPUT_SCHEMA_VERSION
    assert llm_input["requestKind"] == TARGET_REQUEST_KIND
    assert llm_input["file"]["relativePath"] == "src/Foo.java"
    assert llm_input["file"]["language"] == "java"
    assert llm_input["file"]["lineCount"] == 3
    assert llm_input["file"]["contentLines"][0] == {"line": 1, "text": "class Foo {"}
    assert llm_input["anchorRegistry"]
    assert llm_input["targetAnchor"]["ref"] == target.ref
    assert "RESPONSIBILITY" in llm_input["allowedValues"]["claimKind"]
    assert llm_input["endpointRules"]["CALLS"] == {"fromKinds": ["CALLABLE"], "toKinds": ["CALLABLE"]}
    assert llm_input["responseShape"]["schemaVersion"] == TARGET_RESPONSE_SCHEMA_VERSION
    for forbidden in (
        "serviceLabel",
        "tags",
        "domainKeywords",
        "contractRefs",
        "contentHash",
        "sizeBytes",
        "staticAnchors",
        "callsites",
        "callsiteStableKey",
        "stableKey",
        "metadata",
        "parser",
        "engineVersion",
        "factOrigin",
        "flowDomain",
        "resolutionReason",
        "unresolvedReason",
        "sliceDefaultVisibility",
    ):
        assert not _contains_key(llm_input, forbidden)


def test_planner_uses_contract_semantic_node_kinds_without_path_or_language_special_cases():
    contract = _contract("src/Foo.java")
    graph = _mixed_anchor_graph()

    default_targets = LlmEnrichmentPlanner().plan(graph, contract).targets
    field_only_contract = replace(contract, semantic_node_kinds=("FIELD",))
    field_targets = LlmEnrichmentPlanner().plan(graph, field_only_contract).targets

    assert {target.kind for target in default_targets} == {"FILE", "TYPE", "CALLABLE"}
    assert [target.kind for target in field_targets] == ["FIELD"]


@pytest.mark.parametrize(
    ("mutate", "path"),
    [
        (lambda payload: payload["claims"][0].update({"targetRef": "M999"}), "$.claims[0].targetRef"),
        (lambda payload: payload["semanticEdges"][0].update({"toRef": "M999"}), "$.semanticEdges[0].toRef"),
        (lambda payload: payload["claims"][0].update({"targetRef": "M2"}), "$.claims[0].targetRef"),
        (lambda payload: payload["semanticEdges"][0].update({"fromRef": "M2"}), "$.semanticEdges[0].fromRef"),
        (lambda payload: payload["claims"][0].update({"claimKind": "BOGUS"}), "$.claims[0].claimKind"),
        (lambda payload: payload["semanticEdges"][0].update({"edgeType": "BOGUS"}), "$.semanticEdges[0].edgeType"),
        (lambda payload: payload["semanticEdges"][0].update({"resolutionStatus": "BOGUS"}), "$.semanticEdges[0].resolutionStatus"),
        (lambda payload: payload["semanticEdges"][0].update({"fromRef": "F1", "edgeType": "CALLS"}), "$.semanticEdges[0].fromRef"),
        (lambda payload: payload["claims"][0]["evidence"][0].update({"lineStart": 99, "lineEnd": 100}), "$.claims[0].evidence[0]"),
    ],
)
def test_target_response_validator_rejects_invalid_refs_values_endpoint_rules_and_evidence(mutate, path):
    payload, contract = _target_payload()
    response = _valid_target_response()
    mutate(response)

    parsed = TargetResponseParserValidator().parse(json.dumps(response), payload=payload, line_count=5, contract=contract)

    assert isinstance(parsed, GraphAnalysisParseFailure)
    assert any(detail.get("jsonPath") == path for detail in parsed.error_details)


def test_target_response_validator_accepts_valid_refs_and_maps_to_stable_keys():
    payload, contract = _target_payload()

    parsed = TargetResponseParserValidator().parse(json.dumps(_valid_target_response()), payload=payload, line_count=5, contract=contract)

    assert isinstance(parsed, GraphAnalysisResult)
    assert parsed.claims[0].nodeLocalId == "svc|src/Foo.java|CALLABLE|Foo.call()"
    assert parsed.edges[0].fromNodeLocalId == "svc|src/Foo.java|CALLABLE|Foo.call()"
    assert parsed.edges[0].toNodeLocalId == "svc|src/Foo.java|CALLABLE|Foo.helper()"
    assert parsed.claims[0].metadata["factOrigin"] == "LLM"


def test_file_enrichment_merger_deduplicates_exact_duplicate_claims_and_edges():
    payload, contract = _target_payload()
    parsed = TargetResponseParserValidator().parse(json.dumps(_valid_target_response()), payload=payload, line_count=5, contract=contract)
    assert isinstance(parsed, GraphAnalysisResult)

    merged = FileEnrichmentMerger().merge([parsed, parsed])

    assert len(merged.claims) == 1
    assert len(merged.edges) == 1


def test_budget_overflow_fails_closed_before_provider_call():
    policy = load_analysis_policy(POLICY_PATH)
    policy = replace(policy, defaults=replace(policy.defaults, max_file_chars=160))
    analyzer = _CountingAnalyzer()

    with pytest.raises(KnowledgeError) as exc:
        asyncio.run(_run_runtime("src/Foo.java", "class Foo { void call() {} }\n", policy=policy, analyzer=analyzer))

    assert exc.value.code == "ANALYSIS_LLM_TARGET_INPUT_TOO_LARGE"
    assert analyzer.calls == 0


def test_ollama_client_captures_outer_request_with_minimal_marked_input_json():
    captured: list[dict[str, object]] = []
    payload, _ = _target_payload()

    async def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content.decode("utf-8"))
        captured.append(body)
        prompt_input = _llm_input_from_prompt(body["prompt"])
        response = _valid_target_response(target_ref=prompt_input["targetAnchor"]["ref"])
        return httpx.Response(200, json={"response": json.dumps(response)})

    client = OllamaAnalysisClient(
        "http://127.0.0.1:11434",
        "model",
        1,
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )
    try:
        result = asyncio.run(client.analyze(payload, 5))
    finally:
        asyncio.run(client.aclose())

    assert isinstance(result, GraphAnalysisResult)
    body = captured[0]
    assert set(body) == {"model", "prompt", "stream", "format", "options"}
    prompt_input = _llm_input_from_prompt(body["prompt"])
    assert prompt_input["requestKind"] == TARGET_REQUEST_KIND
    assert prompt_input["file"]["contentLines"]
    assert prompt_input["anchorRegistry"]
    assert prompt_input["targetAnchor"]["ref"] == "M1"
    assert "staticAnchors" not in prompt_input
    assert "callsites" not in prompt_input
    assert not _contains_key(prompt_input, "stableKey")


class _CountingAnalyzer:
    name = "counting-test"
    version = "1"

    def __init__(self):
        self.calls = 0

    def analyze(self, payload, line_count, repair_prompt=None):
        self.calls += 1
        return GraphAnalysisResult()


async def _run_runtime(relative_path: str, content: str, *, policy=None, analyzer=None):
    loaded_policy = policy or load_analysis_policy(POLICY_PATH)
    runtime = AnalyzerRuntime(loaded_policy, extractor_registry=ExtractorRegistry())
    row = _row(relative_path, content)

    async def retry(provider, payload, line_count):
        result = provider.analyze(payload, line_count)
        return result, [], {"attempt_count": 1, "last_attempt_at": "now", "last_error_code": None, "last_error_message": None, "last_raw_response_preview": None}

    return await runtime.execute(row, {}, content.splitlines(), analyzer or _CountingAnalyzer(), retry)


def _target_payload():
    contract = _contract("src/Foo.java")
    graph = _mixed_anchor_graph()
    registry = AnchorRefRegistry.build(graph, contract)
    target = next(item for item in LlmEnrichmentPlanner().plan(graph, contract).targets if item.kind == "CALLABLE")
    context = AnalyzerPolicyRuntimeResolver(load_analysis_policy(POLICY_PATH)).resolve(
        _row("src/Foo.java", "class Foo {\n  void call() { helper(); }\n  void helper() {}\n}\n"),
        {},
        ["class Foo {", "  void call() { helper(); }", "  void helper() {}", "}", ""],
    )
    return LlmEnrichmentInputBuilder().build(context=context, registry=registry, target=target, budget_chars=50000), contract


def _valid_target_response(target_ref: str = "M1"):
    return {
        "schemaVersion": TARGET_RESPONSE_SCHEMA_VERSION,
        "claims": [
            {
                "localId": "claim-1",
                "targetRef": target_ref,
                "claimKind": "RESPONSIBILITY",
                "summary": "Calls the helper.",
                "confidence": 0.8,
                "evidence": [{"lineStart": 2, "lineEnd": 2, "text": "helper();"}],
            }
        ],
        "semanticEdges": [
            {
                "localId": "edge-1",
                "fromRef": target_ref,
                "toRef": "M2",
                "edgeType": "CALLS",
                "resolutionStatus": "RESOLVED",
                "confidence": 0.8,
                "evidence": [{"lineStart": 2, "lineEnd": 2, "text": "helper();"}],
                "unresolvedTarget": None,
            }
        ],
        "diagnostics": [],
    }


def _mixed_anchor_graph():
    return GraphAnalysisResult(
        nodes=[
            GraphNode(
                localId="svc|src/Foo.java|FILE",
                nodeKind="FILE",
                name="Foo.java",
                lineStart=1,
                lineEnd=20,
                confidence=1.0,
                metadata={"stableKey": "svc|src/Foo.java|FILE", "parser": "tree-sitter-java"},
            ),
            GraphNode(
                localId="svc|src/Foo.java|TYPE|Foo",
                nodeKind="TYPE",
                name="Foo",
                qualifiedName="example.Foo",
                parentLocalId="svc|src/Foo.java|FILE",
                lineStart=2,
                lineEnd=19,
                confidence=1.0,
                metadata={"stableKey": "svc|src/Foo.java|TYPE|Foo"},
            ),
            GraphNode(
                localId="svc|src/Foo.java|FIELD|repository",
                nodeKind="FIELD",
                name="repository",
                qualifiedName="example.Foo.repository",
                parentLocalId="svc|src/Foo.java|TYPE|Foo",
                lineStart=3,
                lineEnd=3,
                confidence=1.0,
                metadata={"typeName": "WorkspaceRepository", "stableKey": "svc|src/Foo.java|FIELD|repository"},
            ),
            GraphNode(
                localId="svc|src/Foo.java|CALLABLE|Foo.call()",
                nodeKind="CALLABLE",
                name="call",
                qualifiedName="example.Foo.call",
                parentLocalId="svc|src/Foo.java|TYPE|Foo",
                lineStart=5,
                lineEnd=8,
                confidence=1.0,
                metadata={"signature": "void call()", "returnType": "void", "visibility": "PUBLIC", "stableKey": "svc|src/Foo.java|CALLABLE|Foo.call()"},
            ),
            GraphNode(
                localId="svc|src/Foo.java|CALLABLE|Foo.helper()",
                nodeKind="CALLABLE",
                name="helper",
                qualifiedName="example.Foo.helper",
                parentLocalId="svc|src/Foo.java|TYPE|Foo",
                lineStart=10,
                lineEnd=12,
                confidence=1.0,
                metadata={
                    "signature": "void helper()",
                    "returnType": "void",
                    "visibility": "PRIVATE",
                    "stableKey": "svc|src/Foo.java|CALLABLE|Foo.helper()",
                    "annotations": [{"name": "Test", "argumentsRaw": "(timeout = 1)", "lineStart": 9, "lineEnd": 9}],
                },
            ),
            GraphNode(
                localId="svc|src/Foo.java|CALLABLE|Foo.call(String)",
                nodeKind="CALLABLE",
                name="call",
                qualifiedName="example.Foo.call",
                parentLocalId="svc|src/Foo.java|TYPE|Foo",
                lineStart=14,
                lineEnd=17,
                confidence=1.0,
                metadata={"signature": "void call(String id)", "returnType": "void", "stableKey": "svc|src/Foo.java|CALLABLE|Foo.call(String)"},
            ),
        ],
        edges=[
            GraphEdge(
                localId="static-call",
                fromNodeLocalId="svc|src/Foo.java|CALLABLE|Foo.call()",
                toNodeLocalId="svc|src/Foo.java|CALLABLE|Foo.helper()",
                edgeType="CALLS",
                resolutionStatus="RESOLVED",
                confidence=1.0,
                evidence=[GraphEvidenceRef(lineStart=6, lineEnd=6, text="helper();")],
                metadata={"callsiteStableKey": "internal-callsite"},
            )
        ],
    )


def _contract(relative_path: str):
    return GraphContractProvider(policy=load_analysis_policy(POLICY_PATH)).resolve(relative_path, "class Foo { void call() {} }\n")


def _row(relative_path: str, content: str):
    return {
        "id": 1,
        "source_id": "svc",
        "source_path": "/tmp/svc",
        "absolute_path": f"/tmp/svc/{relative_path}",
        "relative_path": relative_path,
        "display_name": "Service",
        "group_name": "group",
        "tags_json": '["tag"]',
        "extension": Path(relative_path).suffix,
        "language": Path(relative_path).suffix.lstrip(".") or "unknown",
        "flow_domain": "CODE",
        "size_bytes": len(content.encode("utf-8")),
        "content_hash": "hash",
        "decode_policy": "utf-8:replace",
    }


def _llm_input_from_prompt(prompt: str):
    start = prompt.index(BEGIN_INPUT_MARKER) + len(BEGIN_INPUT_MARKER)
    end = prompt.index(END_INPUT_MARKER, start)
    return json.loads(prompt[start:end].strip())


def _contains_key(value, key_name: str) -> bool:
    if isinstance(value, dict):
        return key_name in value or any(_contains_key(item, key_name) for item in value.values())
    if isinstance(value, list):
        return any(_contains_key(item, key_name) for item in value)
    return False
