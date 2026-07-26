from __future__ import annotations

import asyncio
import importlib
import json
from dataclasses import replace
from pathlib import Path

import httpx
import pytest

from knowledge_service.analysis_client import OllamaAnalysisClient
from knowledge_service.analysis_graph_contract import GraphContractProvider, contract_payload
from knowledge_service.analysis_service import AnalysisSupervisor
from knowledge_service.analysis_progress import CurrentFileTargetProgressTracker
from knowledge_service.analysis_policy import PromptDefinition
from knowledge_service.analysis_policy_loader import load_analysis_policy
from knowledge_service.analyzer_runtime import AnalyzerPolicyRuntimeResolver, AnalyzerRuntime, ExtractorRegistry
from knowledge_service.errors import KnowledgeError
from knowledge_service.analysis_parse_failure import GraphAnalysisParseFailure
from knowledge_service.graph_schema import GraphAnalysisResult, GraphEdge, GraphEvidenceRef, GraphNode
from knowledge_service.target_enrichment import (
    BEGIN_INPUT_MARKER,
    END_INPUT_MARKER,
    TARGET_INPUT_SCHEMA_VERSION,
    TARGET_REQUEST_KIND,
    AnchorRefRegistry,
    FileEnrichmentMerger,
    LlmEnrichmentInputBuilder,
    LlmEnrichmentPlanner,
    TargetPromptRenderer,
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
    target = LlmEnrichmentPlanner().plan(_mixed_anchor_graph(), context.graph_contract, max_target_calls=10).targets[0]

    payload = LlmEnrichmentInputBuilder().build(context=context, registry=registry, target=target, budget_chars=50000)
    llm_input = payload["llmInput"]

    assert llm_input["schemaVersion"] == TARGET_INPUT_SCHEMA_VERSION
    assert llm_input["requestKind"] == TARGET_REQUEST_KIND
    assert llm_input["file"]["relativePath"] == "src/Foo.java"
    assert llm_input["file"]["language"] == "java"
    assert llm_input["file"]["lineCount"] == 3
    assert llm_input["file"]["contentLines"][0] == {"line": 1, "text": "class Foo {"}
    assert llm_input["contextAnchors"]
    assert llm_input["targetAnchor"]["kind"] == target.kind
    assert llm_input["claimScope"]["targetKind"] == target.kind
    assert llm_input["claimScope"]["targetLineStart"] == llm_input["targetAnchor"]["lineStart"]
    assert llm_input["claimScope"]["rules"]
    assert payload["targetRef"] == target.ref
    assert "RESPONSIBILITY" in llm_input["allowedValues"]["claimKind"]
    assert set(llm_input["allowedValues"]) == {"claimKind"}
    assert set(llm_input["responseShape"]) == {"claims", "boundaries"}
    assert any(item["kind"] == "FIELD" and item["role"] == "context" for item in llm_input["contextAnchors"])
    for forbidden in (
        "anchorRegistry",
        "ref",
        "parentRef",
        "edgeOptions",
        "endpointRules",
        "edgeType",
        "toRef",
        "unresolvedStatus",
        "unresolvedTarget",
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
        "factOrigin",
        "flowDomain",
        "resolutionReason",
        "unresolvedReason",
        "sliceDefaultVisibility",
    ):
        assert not _contains_key(llm_input, forbidden)


def test_target_prompt_renderer_loads_policy_template_and_response_shape():
    payload, _ = _target_payload()

    renderer = TargetPromptRenderer()
    prompt = renderer.render(payload)
    rendered_input = _llm_input_from_prompt(prompt)

    assert "Code target-anchor enrichment prompt." in prompt
    assert BEGIN_INPUT_MARKER in prompt
    assert END_INPUT_MARKER in prompt
    assert rendered_input["requestKind"] == TARGET_REQUEST_KIND
    assert rendered_input["file"]["contentLines"]
    response_shape = renderer.response_shape(payload=payload)
    assert set(response_shape) == {"claims", "boundaries"}
    assert not _contains_key(response_shape, "schemaVersion")
    assert not _contains_key(response_shape, "edgeType")
    assert not _contains_key(response_shape, "toRef")
    for forbidden in (
        "File metadata and content JSON",
        "staticAnchors",
        "edgeOptions",
        "endpointRules",
        "targetStableKey",
        "fromStableKey",
        "toStableKey",
        "knowledge.graph.enrichment.v1",
    ):
        assert forbidden not in prompt
    assert "staticAnchors" not in rendered_input
    assert "callsites" not in rendered_input
    assert "contextAnchors" in rendered_input
    assert "anchorRegistry" not in rendered_input
    assert "Do not return graph topology, refs, semanticEdges, or edge facts." in prompt
    assert "FILE target: describe file-level purpose only" in prompt
    assert "TYPE target: describe class/type-level responsibility only" in prompt
    assert "CALLABLE target: describe only the current callable" in prompt
    assert not _contains_key(rendered_input, "stableKey")


@pytest.mark.parametrize(
    ("relative_path", "content_lines", "prompt_id", "prompt_text"),
    [
        ("src/Foo.java", ["class Foo {", "  void call() {}", "}"], "code_target_anchor_enrichment", "Code target-anchor enrichment prompt."),
        ("config.yaml", ["jobs:", "  build:", "    steps: []"], "structured_text_target_anchor_enrichment", "Structured-text target-anchor enrichment prompt."),
        ("model.xml", ["<project><dependencies /></project>"], "structured_text_target_anchor_enrichment", "Structured-text target-anchor enrichment prompt."),
        ("README.md", ["# Service", "Documents service behavior."], "document_target_anchor_enrichment", "Document target-anchor enrichment prompt."),
    ],
)
def test_target_prompt_renderer_uses_format_specific_prompt_and_shared_response_shape(relative_path, content_lines, prompt_id, prompt_text):
    payload, contract = _target_payload_for(relative_path, content_lines)
    renderer = TargetPromptRenderer()

    prompt = renderer.render(payload, contract=contract)

    assert payload["analysisPolicy"]["promptId"] == prompt_id
    assert prompt_text in prompt
    assert set(renderer.response_shape(payload=payload)) == {"claims", "boundaries"}
    assert payload["llmInput"]["responseShape"] == renderer.response_shape(payload=payload)


def test_target_prompt_renderer_uses_policy_selected_prompt_id_without_code_change(tmp_path):
    policy = load_analysis_policy(POLICY_PATH)
    prompt_id = "custom_target_anchor_enrichment"
    schema_dir = tmp_path / "schemas"
    schema_dir.mkdir()
    (tmp_path / "custom-target.md").write_text(
        "\n".join(
            [
                "Custom target prompt from policy fixture.",
                "{{REPAIR_INSTRUCTIONS}}",
                "{{LLM_INPUT_JSON}}",
                "Shape:",
                "{{TARGET_RESPONSE_SHAPE}}",
            ]
        ),
        encoding="utf-8",
    )
    (schema_dir / "custom-response-shape.json").write_text(
        json.dumps(
            {
                "claims": [{"claimKind": "RESPONSIBILITY", "summary": "shape-from-policy", "evidence": [{"lineStart": 1, "lineEnd": 1}]}],
            }
        ),
        encoding="utf-8",
    )
    custom_policy = replace(
        policy,
        prompt_root=tmp_path,
        prompts={
            prompt_id: PromptDefinition(
                id=prompt_id,
                file="custom-target.md",
                response_shape="schemas/custom-response-shape.json",
            )
        },
        formats={key: replace(value, prompt=prompt_id) for key, value in policy.formats.items()},
    )
    content_lines = ["class Foo {", "  void call() {}", "}"]
    context = AnalyzerPolicyRuntimeResolver(custom_policy).resolve(_row("src/Foo.java", "\n".join(content_lines)), {}, content_lines)
    graph = _mixed_anchor_graph()
    registry = AnchorRefRegistry.build(graph, context.graph_contract)
    target = LlmEnrichmentPlanner().plan(graph, context.graph_contract, max_target_calls=10).targets[0]
    renderer = TargetPromptRenderer(policy=custom_policy)
    builder = LlmEnrichmentInputBuilder(policy=custom_policy)

    payload = builder.build(context=context, registry=registry, target=target, budget_chars=50000)
    prompt = renderer.render(payload, contract=context.graph_contract)
    rendered_input = _llm_input_from_prompt(prompt)
    captured: list[dict[str, object]] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content.decode("utf-8"))
        captured.append(body)
        return httpx.Response(
            200,
            json={"response": json.dumps({"claims": []})},
        )

    client = OllamaAnalysisClient(
        "http://127.0.0.1:11434",
        "model",
        32768,
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
        policy=custom_policy,
    )
    try:
        result = asyncio.run(client.analyze(payload, context.line_count))
    finally:
        asyncio.run(client.aclose())

    captured_prompt = str(captured[0]["prompt"])
    captured_input = _llm_input_from_prompt(captured_prompt)
    assert context.graph_contract.prompt_id == prompt_id
    assert "Custom target prompt from policy fixture." in prompt
    assert "Custom target prompt from policy fixture." in captured_prompt
    assert "shape-from-policy" in prompt
    assert rendered_input["responseShape"]["claims"][0]["summary"] == "shape-from-policy"
    assert captured_input["responseShape"]["claims"][0]["summary"] == "shape-from-policy"
    assert isinstance(result, GraphAnalysisResult)


def test_target_prompt_renderer_fails_closed_when_prompt_id_missing():
    payload, _ = _target_payload()
    payload = {**payload, "analysisPolicy": {key: value for key, value in payload["analysisPolicy"].items() if key != "promptId"}}

    with pytest.raises(KnowledgeError) as exc:
        TargetPromptRenderer().render(payload)

    assert exc.value.code == "ANALYSIS_POLICY_PROMPT_REQUIRED"


def test_planner_uses_contract_semantic_node_kinds_without_path_or_language_special_cases():
    contract = _contract("src/Foo.java")
    graph = _mixed_anchor_graph()

    default_targets = LlmEnrichmentPlanner().plan(graph, contract, max_target_calls=10).targets
    field_only_contract = replace(contract, semantic_node_kinds=("FIELD",))
    field_targets = LlmEnrichmentPlanner().plan(graph, field_only_contract, max_target_calls=10).targets

    assert {target.kind for target in default_targets} == {"FILE", "TYPE", "CALLABLE"}
    assert [target.kind for target in field_targets] == ["FIELD"]


def test_planner_fails_closed_when_target_count_exceeds_policy_cap():
    contract = _contract("src/Foo.java")
    graph = _mixed_anchor_graph()
    many_graph = graph.copy(
        update={
            "nodes": [
                *graph.nodes,
                GraphNode(
                    localId="svc|src/Foo.java|CALLABLE|Foo.extra()",
                    nodeKind="CALLABLE",
                    name="extra",
                    qualifiedName="example.Foo.extra",
                    parentLocalId="svc|src/Foo.java|TYPE|Foo",
                    lineStart=18,
                    lineEnd=18,
                    confidence=1.0,
                    metadata={"signature": "void extra()"},
                ),
            ]
        }
    )

    targets = LlmEnrichmentPlanner().plan(many_graph, contract, max_target_calls=6).targets
    with pytest.raises(KnowledgeError) as exc:
        LlmEnrichmentPlanner().plan(many_graph, contract, max_target_calls=5, source_id="svc", relative_path="src/Foo.java")

    assert len(targets) == 6
    assert exc.value.code == "ANALYSIS_TARGET_PLAN_TOO_LARGE"
    assert exc.value.details["targetCount"] == 6
    assert exc.value.details["maxTargetCalls"] == 5
    assert exc.value.details["semanticNodeKinds"] == list(contract.semantic_node_kinds)


@pytest.mark.parametrize(
    ("mutate", "path"),
    [
        (lambda payload: payload.update({"schemaVersion": "knowledge.graph.enrichment.response.v2"}), "$.schemaVersion"),
        (lambda payload: payload.update({"diagnostics": []}), "$.diagnostics"),
        (lambda payload: payload.update({"semanticEdges": []}), "$.semanticEdges"),
        (lambda payload: payload.update({"edgeType": "CALLS"}), "$.edgeType"),
        (lambda payload: payload.update({"toRef": "M2"}), "$.toRef"),
        (lambda payload: payload.update({"unresolvedStatus": "EXTERNAL_TARGET"}), "$.unresolvedStatus"),
        (lambda payload: payload.update({"unresolvedTarget": {"name": "external"}}), "$.unresolvedTarget"),
        (lambda payload: payload["claims"][0].update({"localId": "claim-1"}), "$.claims[0].localId"),
        (lambda payload: payload["claims"][0].update({"targetRef": "M1"}), "$.claims[0].targetRef"),
        (lambda payload: payload["claims"][0].update({"confidence": 0.8}), "$.claims[0].confidence"),
        (lambda payload: payload["claims"][0].update({"edgeType": "CALLS"}), "$.claims[0].edgeType"),
        (lambda payload: payload["claims"][0].update({"toRef": "M2"}), "$.claims[0].toRef"),
        (lambda payload: payload["claims"][0].update({"unresolvedStatus": "EXTERNAL_TARGET"}), "$.claims[0].unresolvedStatus"),
        (lambda payload: payload["claims"][0].update({"unresolvedTarget": {"name": "external"}}), "$.claims[0].unresolvedTarget"),
        (lambda payload: payload["claims"][0]["evidence"][0].update({"text": "helper();"}), "$.claims[0].evidence[0].text"),
        (lambda payload: payload["claims"][0].update({"claimKind": "BOGUS"}), "$.claims[0].claimKind"),
        (lambda payload: payload["claims"][0]["evidence"][0].update({"lineStart": 99, "lineEnd": 100}), "$.claims[0].evidence[0]"),
    ],
)
def test_target_response_validator_rejects_old_fields_invalid_values_refs_and_evidence(mutate, path):
    payload, contract = _target_payload()
    response = _valid_target_response()
    mutate(response)

    parsed = TargetResponseParserValidator().parse(json.dumps(response), payload=payload, line_count=5, contract=contract)

    assert isinstance(parsed, GraphAnalysisParseFailure)
    assert any(detail.get("jsonPath") == path for detail in parsed.error_details)


def test_target_response_validator_collects_all_evidence_errors_without_fail_fast():
    payload, contract = _target_payload()
    _replace_content_lines(
        payload,
        [
            "class Foo {",
            "  //given",
            "  void helper() {}",
            "}",
            "",
        ],
    )
    payload["llmInput"]["targetAnchor"]["lineEnd"] = 3
    response = {
        "claims": [
            {
                "claimKind": "RESPONSIBILITY",
                "summary": "Handles the call.",
                "evidence": [
                    {"lineStart": 3, "lineEnd": 2},
                    {"lineStart": 2, "lineEnd": 2},
                ],
            },
            {
                "claimKind": "RESPONSIBILITY",
                "summary": "Uses outside evidence.",
                "evidence": [{"lineStart": 4, "lineEnd": 4}],
            },
        ]
    }

    parsed = TargetResponseParserValidator().parse(json.dumps(response), payload=payload, line_count=5, contract=contract)

    assert isinstance(parsed, GraphAnalysisParseFailure)
    assert parsed.validation_report is not None
    errors = parsed.validation_report["validationErrors"]
    assert [item["code"] for item in errors] == [
        "EVIDENCE_RANGE_INVERTED",
        "EVIDENCE_NOT_MATERIAL",
        "EVIDENCE_RANGE_OUTSIDE_TARGET",
    ]
    assert errors[0]["jsonPath"] == "$.claims[0].evidence[0]"
    assert errors[0]["actual"] == {"lineStart": 3, "lineEnd": 2}
    assert errors[0]["expected"] == "lineStart <= lineEnd"
    assert errors[1]["jsonPath"] == "$.claims[0].evidence[1]"
    assert errors[1]["actual"]["lineClass"] == "COMMENT_ONLY"
    assert errors[2]["jsonPath"] == "$.claims[1].evidence[0]"
    assert errors[2]["targetRange"] == {"lineStart": 2, "lineEnd": 3}
    assert errors[2]["evidenceRange"] == {"lineStart": 4, "lineEnd": 4}


def test_target_response_validator_accepts_minimal_claim_and_injects_backend_fields():
    payload, contract = _target_payload()
    response = {
        "claims": [
            {
                "claimKind": "RESPONSIBILITY",
                "summary": "Handles workspace lookup.",
                "evidence": [{"lineStart": 2, "lineEnd": 2}],
            }
        ],
    }

    parsed = TargetResponseParserValidator().parse(json.dumps(response), payload=payload, line_count=5, contract=contract)

    assert isinstance(parsed, GraphAnalysisResult)
    assert parsed.claims[0].nodeLocalId == "svc|src/Foo.java|CALLABLE|Foo.call()"
    assert parsed.claims[0].localId == "llm-claim-M1-1"
    assert parsed.claims[0].confidence == 0.8
    assert parsed.claims[0].evidence[0].text == "  void call() { helper(); }"
    assert parsed.claims[0].metadata["factOrigin"] == "LLM"


def test_target_response_validator_accepts_generic_boundary_descriptors():
    payload, contract = _target_payload()
    response = {
        "claims": [],
        "boundaries": [
            {
                "role": "REQUIRED",
                "confidence": 0.74,
                "evidence": [{"lineStart": 2, "lineEnd": 2}],
                "descriptors": [
                    {"path": "call.method", "value": "helper", "origin": "LLM", "confidence": 0.74},
                    {"path": "payload.shape", "value": {"kind": "object", "required": ["id"]}, "origin": "LLM"},
                ],
            }
        ],
    }

    parsed = TargetResponseParserValidator().parse(json.dumps(response), payload=payload, line_count=5, contract=contract)

    assert isinstance(parsed, GraphAnalysisResult)
    assert parsed.claims == []
    assert len(parsed.boundaries) == 1
    boundary = parsed.boundaries[0]
    assert boundary.nodeLocalId == "svc|src/Foo.java|CALLABLE|Foo.call()"
    assert boundary.localId == "llm-boundary-M1-1"
    assert boundary.role == "REQUIRED"
    assert boundary.origin == "LLM"
    assert boundary.confidence == 0.74
    assert {descriptor.path for descriptor in boundary.descriptors} == {"call.method", "payload.shape"}
    assert boundary.descriptors[1].value == {"kind": "object", "required": ["id"]}
    assert boundary.descriptors[0].evidence[0].text == "  void call() { helper(); }"


def test_target_response_validator_rejects_malformed_boundary_descriptor():
    payload, contract = _target_payload()
    response = {
        "claims": [],
        "boundaries": [
            {
                "role": "REQUIRED",
                "evidence": [{"lineStart": 2, "lineEnd": 2}],
                "descriptors": [
                    {"path": "call.method", "value": None},
                ],
            }
        ],
    }

    parsed = TargetResponseParserValidator().parse(json.dumps(response), payload=payload, line_count=5, contract=contract)

    assert isinstance(parsed, GraphAnalysisParseFailure)
    assert any(detail.get("code") == "BOUNDARY_DESCRIPTOR_VALUE_MISSING" for detail in parsed.error_details)


def test_target_response_validator_rejects_callable_evidence_outside_target_range():
    payload, contract = _target_payload()
    response = _valid_target_response()
    response["claims"][0]["evidence"] = [{"lineStart": 3, "lineEnd": 3}]

    parsed = TargetResponseParserValidator().parse(json.dumps(response), payload=payload, line_count=5, contract=contract)

    assert isinstance(parsed, GraphAnalysisParseFailure)
    detail = next(item for item in parsed.error_details if item.get("jsonPath") == "$.claims[0].evidence[0]")
    assert detail["code"] == "EVIDENCE_RANGE_OUTSIDE_TARGET"
    assert detail["message"] == "Evidence line range is outside target anchor range."
    assert detail["targetRef"] == "M1"
    assert detail["targetKind"] == "CALLABLE"
    assert detail["targetName"] == "call"
    assert detail["targetRange"] == {"lineStart": 2, "lineEnd": 2}
    assert detail["evidenceRange"] == {"lineStart": 3, "lineEnd": 3}


def test_target_response_validator_rejects_type_evidence_outside_target_range():
    payload, contract = _target_payload_for_kind("TYPE")
    response = {
        "claims": [
            {
                "claimKind": "RESPONSIBILITY",
                "summary": "Describes the type.",
                "evidence": [{"lineStart": 5, "lineEnd": 5}],
            }
        ]
    }

    parsed = TargetResponseParserValidator().parse(json.dumps(response), payload=payload, line_count=5, contract=contract)

    assert isinstance(parsed, GraphAnalysisParseFailure)
    detail = next(item for item in parsed.error_details if item.get("jsonPath") == "$.claims[0].evidence[0]")
    assert detail["code"] == "EVIDENCE_RANGE_OUTSIDE_TARGET"
    assert detail["targetKind"] == "TYPE"


def test_target_response_validator_reports_inverted_evidence_range():
    payload, contract = _target_payload()
    response = _valid_target_response()
    response["claims"][0]["evidence"] = [{"lineStart": 55, "lineEnd": 46}]

    parsed = TargetResponseParserValidator().parse(json.dumps(response), payload=payload, line_count=5, contract=contract)

    assert isinstance(parsed, GraphAnalysisParseFailure)
    detail = next(item for item in parsed.error_details if item.get("jsonPath") == "$.claims[0].evidence[0]")
    assert detail["code"] == "EVIDENCE_RANGE_INVERTED"
    assert "ascending source order" in detail["message"]
    assert "lineStart must be the smaller/earlier line" in detail["message"]
    assert "lineEnd must be the larger/later line" in detail["message"]
    assert "For actual range 55-46" in detail["message"]
    assert "use lineStart=46 and lineEnd=55 only if those same lines materially support the claim" in detail["message"]
    assert "otherwise choose another valid evidence range inside the target" in detail["message"]
    assert detail["actual"] == {"lineStart": 55, "lineEnd": 46}
    assert detail["expected"] == "lineStart <= lineEnd"
    assert detail["evidenceRange"] == {"lineStart": 55, "lineEnd": 46}
    assert "correctionHint" not in detail
    assert "EVIDENCE_RANGE_INVERTED" in parsed.message


def test_target_response_validator_rejects_comment_only_callable_evidence():
    payload, contract = _target_payload()
    _replace_content_lines(
        payload,
        [
            "class Foo {",
            "  //given",
            "  void helper() {}",
            "}",
            "",
        ],
    )
    response = _valid_target_response()
    response["claims"][0]["evidence"] = [{"lineStart": 2, "lineEnd": 2}]

    parsed = TargetResponseParserValidator().parse(json.dumps(response), payload=payload, line_count=5, contract=contract)

    assert isinstance(parsed, GraphAnalysisParseFailure)
    detail = next(item for item in parsed.error_details if item.get("jsonPath") == "$.claims[0].evidence[0]")
    assert detail["code"] == "EVIDENCE_NOT_MATERIAL"
    assert detail["actual"]["lineClass"] == "COMMENT_ONLY"
    assert detail["expected"] == "Evidence must cite material code lines that support the claim."


def test_target_response_validator_rejects_closing_brace_only_callable_evidence():
    payload, contract = _target_payload()
    payload["llmInput"]["targetAnchor"]["lineEnd"] = 4
    response = _valid_target_response()
    response["claims"][0]["evidence"] = [{"lineStart": 4, "lineEnd": 4}]

    parsed = TargetResponseParserValidator().parse(json.dumps(response), payload=payload, line_count=5, contract=contract)

    assert isinstance(parsed, GraphAnalysisParseFailure)
    detail = next(item for item in parsed.error_details if item.get("jsonPath") == "$.claims[0].evidence[0]")
    assert detail["code"] == "EVIDENCE_NOT_MATERIAL"
    assert detail["actual"]["lineClass"] == "CLOSING_BRACE_ONLY"
    assert detail["expected"] == "Evidence must cite material code lines that support the claim."


def test_target_response_validator_collects_old_contract_fields_and_semantic_edges():
    payload, contract = _target_payload()
    response = _valid_target_response()
    response["semanticEdges"] = []
    response["claims"][0]["confidence"] = 0.8

    parsed = TargetResponseParserValidator().parse(json.dumps(response), payload=payload, line_count=5, contract=contract)

    assert isinstance(parsed, GraphAnalysisParseFailure)
    by_path = {item["jsonPath"]: item for item in parsed.error_details}
    assert by_path["$.semanticEdges"]["code"] == "SEMANTIC_EDGES_RETURNED"
    assert by_path["$.claims[0].confidence"]["code"] == "OLD_CONTRACT_FIELD_RETURNED"
    assert parsed.validation_report is not None
    assert [item["code"] for item in parsed.validation_report["validationErrors"]] == [
        "SEMANTIC_EDGES_RETURNED",
        "OLD_CONTRACT_FIELD_RETURNED",
    ]


@pytest.mark.parametrize(
    "raw",
    [
        lambda: f"Here is JSON: {json.dumps(_valid_target_response())}",
        lambda: f"```json\n{json.dumps(_valid_target_response())}\n```",
        lambda: f"{json.dumps(_valid_target_response())}\n{json.dumps(_valid_target_response())}",
    ],
)
def test_target_response_validator_rejects_embedded_or_fenced_json(raw):
    payload, contract = _target_payload()

    parsed = TargetResponseParserValidator().parse(raw(), payload=payload, line_count=5, contract=contract)

    assert isinstance(parsed, GraphAnalysisParseFailure)
    assert parsed.code == "ANALYSIS_AI_INVALID_JSON"


def test_target_input_builder_exposes_context_anchors_without_graph_topology_selectors():
    contract = _contract("src/Foo.java")
    graph = _mixed_anchor_graph()
    registry = AnchorRefRegistry.build(graph, contract)
    context = AnalyzerPolicyRuntimeResolver(load_analysis_policy(POLICY_PATH)).resolve(
        _row("src/Foo.java", "class Foo {\n  void call() { helper(); }\n  void helper() {}\n}\n"),
        {},
        ["class Foo {", "  void call() { helper(); }", "  void helper() {}", "}", ""],
    )
    targets = LlmEnrichmentPlanner().plan(graph, contract, max_target_calls=10).targets

    by_kind = {}
    for target in targets:
        payload = LlmEnrichmentInputBuilder().build(context=context, registry=registry, target=target, budget_chars=50000)
        by_kind.setdefault(target.kind, payload["llmInput"])
    field_contract = replace(contract, semantic_node_kinds=("FIELD",))
    field_target = LlmEnrichmentPlanner().plan(graph, field_contract, max_target_calls=10).targets[0]
    field_payload = LlmEnrichmentInputBuilder().build(context=context, registry=registry, target=field_target, budget_chars=50000)
    by_kind["FIELD"] = field_payload["llmInput"]

    assert "USES_FIELD" in contract.allowed_edge_types
    for llm_input in by_kind.values():
        assert set(llm_input["allowedValues"]) == {"claimKind"}
        assert "edgeOptions" not in llm_input
        assert "endpointRules" not in llm_input
        assert "anchorRegistry" not in llm_input
        assert "contextAnchors" in llm_input
        assert "claimScope" in llm_input
        assert llm_input["claimScope"]["targetKind"] in by_kind
        assert not _contains_key(llm_input, "ref")
        assert not _contains_key(llm_input, "edgeType")
        assert not _contains_key(llm_input, "toRef")
        assert not _contains_key(llm_input, "unresolvedStatus")
    assert any(item["kind"] == "FIELD" and item["role"] == "context" for item in by_kind["CALLABLE"]["contextAnchors"])


def test_target_response_validator_rejects_static_owned_edges_from_llm():
    payload, contract = _target_payload_for_kind("CALLABLE")
    response = {
        "claims": [],
        "semanticEdges": [
            {
                "edgeType": "USES_FIELD",
                "toRef": "FIELD1",
                "evidence": [{"lineStart": 2, "lineEnd": 2}],
            }
        ],
    }

    parsed = TargetResponseParserValidator().parse(json.dumps(response), payload=payload, line_count=20, contract=contract)

    assert isinstance(parsed, GraphAnalysisParseFailure)
    assert parsed.code == "ANALYSIS_AI_SCHEMA_INVALID"
    detail = next(item for item in parsed.error_details if item.get("jsonPath") == "$.semanticEdges")
    assert detail["code"] == "SEMANTIC_EDGES_RETURNED"
    assert detail["actual"] == "semanticEdges"


def test_validation_feedback_prompt_for_old_fields_includes_only_observed_field_errors():
    payload, _ = _target_payload_for_kind("FILE")
    error = KnowledgeError(
        "ANALYSIS_AI_SCHEMA_INVALID",
        "AI analyzer response failed target-anchor validation.",
        error_details=[
            {
                "code": "SEMANTIC_EDGES_RETURNED",
                "errorType": "SEMANTIC_EDGES_RETURNED",
                "jsonPath": "$.semanticEdges",
                "message": "Unknown or removed field is not allowed by the target-anchor response contract.",
                "actual": "semanticEdges",
                "expected": "no extra fields",
            },
            {
                "code": "OLD_CONTRACT_FIELD_RETURNED",
                "errorType": "OLD_CONTRACT_FIELD_RETURNED",
                "jsonPath": "$.claims[0].confidence",
                "message": "Unknown or removed field is not allowed by the target-anchor response contract.",
                "actual": "confidence",
                "expected": "no extra fields",
            }
        ],
        raw_preview=json.dumps({"claims": [{"confidence": 0.8}], "semanticEdges": []}),
    )
    supervisor = object.__new__(AnalysisSupervisor)

    feedback = supervisor._validation_feedback_prompt(payload, error, 2, 3)
    prompt = TargetPromptRenderer().render(payload, repair_prompt=feedback)

    assert "Structured validationErrors:" in prompt
    assert "SEMANTIC_EDGES_RETURNED" in prompt
    assert "OLD_CONTRACT_FIELD_RETURNED" in prompt
    assert "$.semanticEdges" in prompt
    assert "$.claims[0].confidence" in prompt
    assert "Fix only the listed validation errors." in prompt
    assert "Remove invalid fields" not in prompt
    assert "Convert useful information into grounded claims" not in prompt
    assert "edgeOptions" not in prompt
    assert "endpointRules" not in prompt


def test_validation_feedback_prompt_for_inverted_range_contains_no_unrelated_rules():
    payload, _ = _target_payload()
    message = (
        "Evidence line range is inverted. Return evidence ranges in ascending source order: "
        "lineStart must be the smaller/earlier line and lineEnd must be the larger/later line. "
        "For actual range 55-46, use lineStart=46 and lineEnd=55 only if those same lines materially support the claim; "
        "otherwise choose another valid evidence range inside the target."
    )
    error = KnowledgeError(
        "ANALYSIS_AI_SCHEMA_INVALID",
        "AI analyzer response failed target-anchor validation.",
        error_details=[
            {
                "code": "EVIDENCE_RANGE_INVERTED",
                "errorType": "EVIDENCE_RANGE_INVERTED",
                "jsonPath": "$.claims[0].evidence[0]",
                "message": message,
                "actual": {"lineStart": 55, "lineEnd": 46},
                "expected": "lineStart <= lineEnd",
                "evidenceRange": {"lineStart": 55, "lineEnd": 46},
            }
        ],
        raw_preview=json.dumps({"claims": [{"claimKind": "RESPONSIBILITY", "summary": "wrong", "evidence": [{"lineStart": 55, "lineEnd": 46}]}]}),
    )
    supervisor = object.__new__(AnalysisSupervisor)

    feedback = supervisor._validation_feedback_prompt(payload, error, 2, 3)
    prompt = TargetPromptRenderer().render(payload, repair_prompt=feedback)

    assert "EVIDENCE_RANGE_INVERTED" in prompt
    assert "$.claims[0].evidence[0]" in prompt
    assert '"lineStart": 55' in prompt
    assert '"lineEnd": 46' in prompt
    assert "lineStart <= lineEnd" in prompt
    assert "ascending source order" in prompt
    assert "lineStart must be the smaller/earlier line" in prompt
    assert "lineEnd must be the larger/later line" in prompt
    assert "For actual range 55-46" in prompt
    assert "use lineStart=46 and lineEnd=55 only if those same lines materially support the claim" in prompt
    assert "otherwise choose another valid evidence range inside the target" in prompt
    assert "correctionHint" not in prompt
    assert "semanticEdges" not in prompt
    assert "toRef" not in prompt
    assert "topology" not in prompt
    assert "COMMENT_ONLY" not in prompt
    assert "CLOSING_BRACE_ONLY" not in prompt
    assert "outside target" not in prompt


def test_validation_feedback_prompt_includes_exactly_multiple_validation_errors():
    payload, _ = _target_payload()
    error = KnowledgeError(
        "ANALYSIS_AI_SCHEMA_INVALID",
        "AI analyzer response failed target-anchor validation.",
        error_details=[
            {"code": "EVIDENCE_RANGE_INVERTED", "jsonPath": "$.claims[0].evidence[0]", "message": "inverted", "actual": {"lineStart": 3, "lineEnd": 2}, "expected": "lineStart <= lineEnd"},
            {"code": "EVIDENCE_NOT_MATERIAL", "jsonPath": "$.claims[0].evidence[1]", "message": "comment", "actual": {"lineStart": 2, "lineEnd": 2, "lineClass": "COMMENT_ONLY"}, "expected": "material code lines"},
            {"code": "EVIDENCE_RANGE_OUTSIDE_TARGET", "jsonPath": "$.claims[1].evidence[0]", "message": "outside", "actual": {"lineStart": 4, "lineEnd": 4}, "expected": "target range"},
        ],
        raw_preview=json.dumps({"claims": []}),
    )
    supervisor = object.__new__(AnalysisSupervisor)

    feedback = supervisor._validation_feedback_prompt(payload, error, 2, 3)
    marker = "Structured validationErrors:\n"
    validation_json = feedback.split(marker, 1)[1].split("\nReturn corrected JSON only.", 1)[0]
    errors = json.loads(validation_json)

    assert [item["code"] for item in errors] == [
        "EVIDENCE_RANGE_INVERTED",
        "EVIDENCE_NOT_MATERIAL",
        "EVIDENCE_RANGE_OUTSIDE_TARGET",
    ]


def test_validation_feedback_prompt_for_json_parse_error_contains_no_evidence_rules():
    payload, _ = _target_payload()
    error = KnowledgeError(
        "ANALYSIS_AI_INVALID_JSON",
        "JSON parse error at line 1 column 2.",
        error_details=[
            {
                "code": "JSON_PARSE_ERROR",
                "errorType": "JSON_PARSE_ERROR",
                "jsonPath": "$",
                "message": "JSON parse error at line 1 column 2: Expecting property name enclosed in double quotes",
                "actual": {"line": 1, "column": 2, "charPosition": 1},
                "expected": "one valid JSON object",
                "line": 1,
                "column": 2,
                "charPosition": 1,
            }
        ],
        raw_preview="{",
    )
    supervisor = object.__new__(AnalysisSupervisor)

    feedback = supervisor._validation_feedback_prompt(payload, error, 2, 3)
    prompt = TargetPromptRenderer().render(payload, repair_prompt=feedback)

    assert "JSON_PARSE_ERROR" in prompt
    assert "line 1 column 2" in prompt
    assert "Output must be one valid JSON object." in prompt
    assert "Corrected response must match the target-anchor response shape." in prompt
    assert "Fix only the listed validation errors." not in prompt
    assert "EVIDENCE_RANGE" not in prompt
    assert "material code lines" not in prompt
    assert "lineStart <= lineEnd" not in prompt


def test_target_enrichment_package_exports_public_api_without_circular_imports():
    package = importlib.import_module("knowledge_service.target_enrichment")
    submodules = [
        importlib.import_module("knowledge_service.target_enrichment.constants"),
        importlib.import_module("knowledge_service.target_enrichment.registry"),
        importlib.import_module("knowledge_service.target_enrichment.planner"),
        importlib.import_module("knowledge_service.target_enrichment.input_builder"),
        importlib.import_module("knowledge_service.target_enrichment.prompt_renderer"),
        importlib.import_module("knowledge_service.target_enrichment.response_validator"),
        importlib.import_module("knowledge_service.target_enrichment.merger"),
    ]

    assert Path(package.__file__).name == "__init__.py"
    assert package.TargetPromptRenderer is TargetPromptRenderer
    assert package.TargetResponseParserValidator is TargetResponseParserValidator
    assert all(module is not None for module in submodules)


def test_file_enrichment_merger_deduplicates_exact_duplicate_claims_and_edges():
    payload, contract = _target_payload()
    parsed = TargetResponseParserValidator().parse(json.dumps(_valid_target_response()), payload=payload, line_count=5, contract=contract)
    assert isinstance(parsed, GraphAnalysisResult)

    merged = FileEnrichmentMerger().merge([parsed, parsed])

    assert len(merged.claims) == 1
    assert len(merged.edges) == 0


def test_budget_overflow_fails_closed_before_provider_call():
    policy = load_analysis_policy(POLICY_PATH)
    policy = replace(policy, defaults=replace(policy.defaults, max_file_chars=160))
    analyzer = _CountingAnalyzer()

    with pytest.raises(KnowledgeError) as exc:
        asyncio.run(_run_runtime("src/Foo.java", "class Foo { void call() {} }\n", policy=policy, analyzer=analyzer))

    assert exc.value.code == "ANALYSIS_LLM_TARGET_INPUT_TOO_LARGE"
    assert exc.value.details["renderedPromptChars"] > exc.value.details["budgetChars"]
    assert analyzer.calls == 0


def test_rendered_prompt_budget_uses_actual_prompt_and_blocks_http_dispatch():
    captured: list[dict[str, object]] = []
    payload, _ = _target_payload()
    compact_chars = len(json.dumps(payload["llmInput"], ensure_ascii=False, sort_keys=True, separators=(",", ":")))
    rendered_chars = TargetPromptRenderer().estimate_prompt_chars(payload)
    payload = {**payload, "budgetChars": compact_chars + 1}

    async def handler(request: httpx.Request) -> httpx.Response:
        captured.append(json.loads(request.content.decode("utf-8")))
        return httpx.Response(200, json={"response": json.dumps(_valid_target_response())})

    client = OllamaAnalysisClient(
        "http://127.0.0.1:11434",
        "model",
        32768,
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )
    try:
        with pytest.raises(KnowledgeError) as exc:
            asyncio.run(client.analyze(payload, 5))
    finally:
        asyncio.run(client.aclose())

    assert compact_chars < payload["budgetChars"] < rendered_chars
    assert exc.value.code == "ANALYSIS_LLM_TARGET_INPUT_TOO_LARGE"
    assert exc.value.details["renderedPromptChars"] == rendered_chars
    assert exc.value.details["budgetChars"] == payload["budgetChars"]
    assert exc.value.details["targetRef"] == payload["targetRef"]
    assert exc.value.details["relativePath"] == payload["relativePath"]
    assert captured == []


def test_runtime_target_plan_too_large_makes_no_llm_calls():
    policy = load_analysis_policy(POLICY_PATH)
    policy = replace(policy, defaults=replace(policy.defaults, max_target_calls_per_file=1))
    analyzer = _CountingAnalyzer()

    with pytest.raises(KnowledgeError) as exc:
        asyncio.run(_run_runtime("src/Foo.java", "class Foo { void call() {} }\n", policy=policy, analyzer=analyzer))

    assert exc.value.code == "ANALYSIS_TARGET_PLAN_TOO_LARGE"
    assert exc.value.details["targetCount"] > exc.value.details["maxTargetCalls"]
    assert analyzer.calls == 0


def test_runtime_updates_current_file_target_progress_after_successful_targets_only():
    policy = load_analysis_policy(POLICY_PATH)
    tracker = CurrentFileTargetProgressTracker()
    runtime = AnalyzerRuntime(policy, extractor_registry=ExtractorRegistry(), target_progress_tracker=tracker)
    content = "class Foo { void call() {} }\n"
    row = _row("src/Foo.java", content)
    snapshots = []

    async def retry(provider, payload, line_count):
        snapshots.append(tracker.snapshot())
        result = provider.analyze(payload, line_count)
        return result, [], {"attempt_count": 1, "last_attempt_at": "now", "last_error_code": None, "last_error_message": None, "last_raw_response_preview": None}

    asyncio.run(runtime.execute(row, {}, content.splitlines(), _CountingAnalyzer(), retry, job_id="job-1"))

    assert snapshots[0]["entries"][0]["totalTargets"] == 3
    assert snapshots[0]["entries"][0]["completedTargets"] == 0
    assert snapshots[1]["entries"][0]["completedTargets"] == 1
    assert snapshots[2]["entries"][0]["completedTargets"] == 2
    final_entry = tracker.snapshot()["entries"][0]
    assert final_entry["totalTargets"] == 3
    assert final_entry["completedTargets"] == 3
    assert final_entry["percent"] == 100.0
    assert final_entry["showTargetProgress"] is True


def test_ollama_client_captures_outer_request_with_minimal_marked_input_json():
    captured: list[dict[str, object]] = []
    payload, _ = _target_payload()

    async def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content.decode("utf-8"))
        captured.append(body)
        prompt_input = _llm_input_from_prompt(body["prompt"])
        response = _valid_target_response()
        return httpx.Response(200, json={"response": json.dumps(response)})

    client = OllamaAnalysisClient(
        "http://127.0.0.1:11434",
        "model",
        32768,
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
    assert prompt_input["contextAnchors"]
    assert prompt_input["targetAnchor"]["kind"] == "CALLABLE"
    assert "staticAnchors" not in prompt_input
    assert "callsites" not in prompt_input
    assert "anchorRegistry" not in prompt_input
    assert not _contains_key(prompt_input, "stableKey")


def test_ollama_client_has_no_legacy_prompt_renderer_or_response_parser_and_rejects_non_target_payload():
    contract = _contract("src/Foo.java")
    client = OllamaAnalysisClient("http://127.0.0.1:11434", "model", 32768)
    try:
        assert not hasattr(client, "prompt_renderer")
        assert not hasattr(client, "parser")
        with pytest.raises(KnowledgeError) as exc:
            asyncio.run(
                client.analyze(
                    {
                        "relativePath": "src/Foo.java",
                        "analysisPolicy": contract_payload(contract),
                    },
                    1,
                )
            )
    finally:
        asyncio.run(client.aclose())

    assert exc.value.code == "ANALYSIS_TARGET_INPUT_REQUIRED"


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
    target = next(item for item in LlmEnrichmentPlanner().plan(graph, contract, max_target_calls=10).targets if item.kind == "CALLABLE")
    context = AnalyzerPolicyRuntimeResolver(load_analysis_policy(POLICY_PATH)).resolve(
        _row("src/Foo.java", "class Foo {\n  void call() { helper(); }\n  void helper() {}\n}\n"),
        {},
        ["class Foo {", "  void call() { helper(); }", "  void helper() {}", "}", ""],
    )
    return LlmEnrichmentInputBuilder().build(context=context, registry=registry, target=target, budget_chars=50000), contract


def _target_payload_for_kind(kind: str):
    contract = _contract("src/Foo.java")
    graph = _mixed_anchor_graph()
    registry = AnchorRefRegistry.build(graph, contract)
    target = next(item for item in LlmEnrichmentPlanner().plan(graph, contract, max_target_calls=10).targets if item.kind == kind)
    context = AnalyzerPolicyRuntimeResolver(load_analysis_policy(POLICY_PATH)).resolve(
        _row("src/Foo.java", "class Foo {\n  void call() { helper(); }\n  void helper() {}\n}\n"),
        {},
        ["class Foo {", "  void call() { helper(); }", "  void helper() {}", "}", ""],
    )
    return LlmEnrichmentInputBuilder().build(context=context, registry=registry, target=target, budget_chars=50000), contract


def _target_payload_for(relative_path: str, content_lines: list[str]):
    policy = load_analysis_policy(POLICY_PATH)
    content = "\n".join(content_lines)
    context = AnalyzerPolicyRuntimeResolver(policy).resolve(_row(relative_path, content), {}, content_lines)
    graph = GraphAnalysisResult(
        nodes=[
            GraphNode(
                localId=f"svc|{relative_path}|FILE",
                nodeKind="FILE",
                name=Path(relative_path).name,
                lineStart=1,
                lineEnd=max(len(content_lines), 1),
                confidence=1.0,
                metadata={"stableKey": f"svc|{relative_path}|FILE"},
            )
        ]
    )
    registry = AnchorRefRegistry.build(graph, context.graph_contract)
    target = LlmEnrichmentPlanner().plan(graph, context.graph_contract, max_target_calls=10).targets[0]
    return LlmEnrichmentInputBuilder(policy=policy).build(context=context, registry=registry, target=target, budget_chars=50000), context.graph_contract


def _valid_target_response():
    return {
        "claims": [
            {
                "claimKind": "RESPONSIBILITY",
                "summary": "Calls the helper.",
                "evidence": [{"lineStart": 2, "lineEnd": 2}],
            }
        ]
    }


def _replace_content_lines(payload: dict, lines: list[str]) -> None:
    payload["llmInput"]["file"]["lineCount"] = len(lines)
    payload["llmInput"]["file"]["contentLines"] = [{"line": index, "text": text} for index, text in enumerate(lines, start=1)]


def _mixed_anchor_graph():
    return GraphAnalysisResult(
        nodes=[
            GraphNode(
                localId="svc|src/Foo.java|FILE",
                nodeKind="FILE",
                name="Foo.java",
                lineStart=1,
                lineEnd=1,
                confidence=1.0,
                metadata={"stableKey": "svc|src/Foo.java|FILE", "parser": "tree-sitter-java"},
            ),
            GraphNode(
                localId="svc|src/Foo.java|TYPE|Foo",
                nodeKind="TYPE",
                name="Foo",
                qualifiedName="example.Foo",
                parentLocalId="svc|src/Foo.java|FILE",
                lineStart=1,
                lineEnd=2,
                confidence=1.0,
                metadata={"stableKey": "svc|src/Foo.java|TYPE|Foo"},
            ),
            GraphNode(
                localId="svc|src/Foo.java|FIELD|repository",
                nodeKind="FIELD",
                name="repository",
                qualifiedName="example.Foo.repository",
                parentLocalId="svc|src/Foo.java|TYPE|Foo",
                lineStart=1,
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
                lineStart=2,
                lineEnd=2,
                confidence=1.0,
                metadata={
                    "signature": "void call()",
                    "returnType": "void",
                    "visibility": "PUBLIC",
                    "bodyLineStart": 2,
                    "bodyLineEnd": 2,
                    "stableKey": "svc|src/Foo.java|CALLABLE|Foo.call()",
                },
            ),
            GraphNode(
                localId="svc|src/Foo.java|CALLABLE|Foo.helper()",
                nodeKind="CALLABLE",
                name="helper",
                qualifiedName="example.Foo.helper",
                parentLocalId="svc|src/Foo.java|TYPE|Foo",
                lineStart=3,
                lineEnd=3,
                confidence=1.0,
                metadata={
                    "signature": "void helper()",
                    "returnType": "void",
                    "visibility": "PRIVATE",
                    "bodyLineStart": 3,
                    "bodyLineEnd": 3,
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
                lineStart=4,
                lineEnd=4,
                confidence=1.0,
                metadata={
                    "signature": "void call(String id)",
                    "returnType": "void",
                    "bodyLineStart": 4,
                    "bodyLineEnd": 4,
                    "stableKey": "svc|src/Foo.java|CALLABLE|Foo.call(String)",
                },
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
