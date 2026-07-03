from __future__ import annotations

import copy
import sqlite3
from pathlib import Path
from typing import Any, Dict

import httpx
import pytest
import yaml

from knowledge_service.analysis_policy import ALLOWED_EXTRACTOR_MODES, ALLOWED_LLM_MODES, AnalysisPolicyError
from knowledge_service.analysis_policy_loader import load_analysis_policy
from knowledge_service.analysis_policy_resolver import AnalysisPolicyResolveRequest, resolve_analysis_policy

REPO_ROOT = Path(__file__).resolve().parents[3]
POLICY_PATH = REPO_ROOT / "config" / "knowledge" / "analysis-policy.yaml"
FORBIDDEN_KEYS = {"path_globs", "pathGlobs", "filenames", "filename", "promptHints", "supported", "reserved", "future"}
FORBIDDEN_GRAPH_VALUES = {"UNKNOWN", "DIAGNOSTIC", "RELATED_TO", "SECTION", "KEY", "TASK", "STEP", "DEPENDENCY", "PLUGIN"}


def test_loads_default_policy_and_validates_references():
    policy = load_analysis_policy(POLICY_PATH)
    data = _policy_data()

    assert policy.source_path == POLICY_PATH
    assert policy.schema_version == 1
    assert policy.prompts["code_graph_enrichment"].response_shape == "schemas/graph-enrichment-v1-response-shape.json"
    assert policy.prompt_response_shape_path("code_graph_enrichment").name == "graph-enrichment-v1-response-shape.json"
    assert _find_forbidden_entries(data) == []
    assert set(policy.semantic.indexed_node_kinds) == {"FILE", "TYPE", "CALLABLE", "EXTERNAL"}

    for kind in policy.semantic.indexed_node_kinds:
        assert policy.graph.nodes[kind].semantic_eligible is True
    for kind in policy.semantic.indexed_edge_types:
        assert policy.graph.edges[kind].semantic_eligible is True
    for kind in policy.semantic.indexed_claim_kinds:
        assert policy.graph.claims[kind].semantic_eligible is True

    for extractor in policy.extractors.values():
        assert set(extractor.produces.nodes) <= set(policy.graph.nodes)
        assert set(extractor.produces.edges) <= set(policy.graph.edges)
        assert set(extractor.produces.claims) <= set(policy.graph.claims)

    for profile in policy.graph_profiles.values():
        assert set(profile.nodes) <= set(policy.graph.nodes)
        assert set(profile.edges) <= set(policy.graph.edges)
        assert set(profile.claims) <= set(policy.graph.claims)

    assert {execution.extractor_mode for execution in policy.policies.values()} <= set(ALLOWED_EXTRACTOR_MODES)
    assert {execution.llm_mode for execution in policy.policies.values()} <= set(ALLOWED_LLM_MODES)


@pytest.mark.parametrize("mode", ALLOWED_EXTRACTOR_MODES)
def test_valid_extractor_modes_are_accepted(tmp_path, mode):
    data = _policy_data()
    data["analysis"]["policies"]["text_graph_enrichment"]["extractorMode"] = mode

    policy = _load_valid(tmp_path, data)

    assert policy.policies["text_graph_enrichment"].extractor_mode == mode


def test_invalid_extractor_mode_fails_with_allowed_values(tmp_path):
    data = _policy_data()
    data["analysis"]["policies"]["text_graph_enrichment"]["extractorMode"] = "fallback_disabled"

    error = _load_invalid(tmp_path, data)
    diagnostic = _diagnostic_for_path(error, "$.analysis.policies.text_graph_enrichment.extractorMode")

    assert diagnostic.reason == "extractorMode is not an allowed runtime mode"
    assert diagnostic.invalid_value == "fallback_disabled"
    assert diagnostic.allowed_values == list(ALLOWED_EXTRACTOR_MODES)


@pytest.mark.parametrize("mode", ALLOWED_LLM_MODES)
def test_valid_llm_modes_are_accepted(tmp_path, mode):
    data = _policy_data()
    data["analysis"]["policies"]["text_graph_enrichment"]["llmMode"] = mode

    policy = _load_valid(tmp_path, data)

    assert policy.policies["text_graph_enrichment"].llm_mode == mode


def test_invalid_llm_mode_fails_with_allowed_values(tmp_path):
    data = _policy_data()
    data["analysis"]["policies"]["text_graph_enrichment"]["llmMode"] = "not_none"

    error = _load_invalid(tmp_path, data)
    diagnostic = _diagnostic_for_path(error, "$.analysis.policies.text_graph_enrichment.llmMode")

    assert diagnostic.reason == "llmMode is not an allowed runtime mode"
    assert diagnostic.invalid_value == "not_none"
    assert diagnostic.allowed_values == list(ALLOWED_LLM_MODES)


@pytest.mark.parametrize(
    ("field", "missing_kind", "declared_path"),
    [
        ("nodes", "MISSING_NODE", "nodes"),
        ("edges", "MISSING_EDGE", "edges"),
        ("claims", "MISSING_CLAIM", "claims"),
    ],
)
def test_extractor_produces_references_must_be_declared_graph_kinds_with_allowed_values(tmp_path, field, missing_kind, declared_path):
    data = _policy_data()
    data["analysis"]["extractors"]["file_anchor"]["produces"].setdefault(field, []).append(missing_kind)

    error = _load_invalid(tmp_path, data)
    diagnostic = next(item for item in error.diagnostics if item.invalid_value == missing_kind)

    assert f"undeclared {field[:-1]} kind" in diagnostic.reason
    assert diagnostic.allowed_values == sorted(data["analysis"]["graph"][declared_path].keys())


@pytest.mark.parametrize(
    ("relative_path", "format_id", "extractor_id", "policy_id", "prompt_id"),
    [
        ("src/Foo.java", "java", "java_ast", "parser_assisted_graph_enrichment", "code_graph_enrichment"),
        ("script.py", "python", "file_anchor", "text_graph_enrichment", "code_graph_enrichment"),
        ("component.tsx", "typescript", "file_anchor", "text_graph_enrichment", "code_graph_enrichment"),
        ("config.yaml", "yaml", "structured_text_light", "text_graph_enrichment", "text_graph_enrichment"),
        ("model.xml", "xml", "structured_text_light", "text_graph_enrichment", "text_graph_enrichment"),
        ("README.md", "markdown", "document_heading_light", "text_graph_enrichment", "document_graph_enrichment"),
    ],
)
def test_resolves_supported_formats(relative_path, format_id, extractor_id, policy_id, prompt_id):
    policy = load_analysis_policy(POLICY_PATH)

    resolution = resolve_analysis_policy(policy, AnalysisPolicyResolveRequest(relative_path=relative_path))

    assert resolution.supported is True
    assert resolution.format_id == format_id
    assert resolution.extractor_id == extractor_id
    assert resolution.policy_id == policy_id
    assert resolution.prompt_id == prompt_id
    assert resolution.source_view == "contentLines"
    assert resolution.evidence_required is True
    assert "FILE" in resolution.allowed_node_kinds
    assert resolution.status_kinds == ["TRUSTED", "CANDIDATE", "REJECTED", "DERIVED", "STALE"]
    assert resolution.origin_kinds == ["STATIC", "LLM", "DERIVED", "RESOLVER"]
    assert resolution.evidence_kinds == ["NODE", "EDGE", "CLAIM", "CALLSITE"]


@pytest.mark.parametrize(
    ("relative_path", "extension"),
    [
        ("unknown.bin", ".bin"),
        ("Dockerfile", ""),
    ],
)
def test_resolves_unsupported_format_without_filename_inference(relative_path, extension):
    policy = load_analysis_policy(POLICY_PATH)

    resolution = resolve_analysis_policy(policy, AnalysisPolicyResolveRequest(relative_path=relative_path))

    assert resolution.supported is False
    assert resolution.failure_code == "UNSUPPORTED_FORMAT"
    assert resolution.extension == extension
    assert resolution.format_id is None
    assert resolution.unsupported_behavior["unsupportedFormat"] == "fail_file"


def test_yaml_automation_classifier_adds_label_and_profile():
    content = """
name: CI
on: push
jobs:
  build:
    steps:
      - run: mvn test
"""
    policy = load_analysis_policy(POLICY_PATH)

    resolution = resolve_analysis_policy(policy, AnalysisPolicyResolveRequest(relative_path="workflow.yaml", content=content))

    assert resolution.artifact_labels == ["automation_like"]
    assert resolution.artifact_graph_profiles == ["automation_text_graph"]
    assert resolution.effective_graph_profiles == ["base_file_graph", "structured_text_graph", "automation_text_graph"]


def test_yaml_api_contract_classifier_adds_label_and_profile():
    content = """
openapi: 3.0.0
paths:
  /v1/example: {}
"""
    policy = load_analysis_policy(POLICY_PATH)

    resolution = resolve_analysis_policy(policy, AnalysisPolicyResolveRequest(relative_path="api.yml", content=content))

    assert resolution.artifact_labels == ["api_contract_like"]
    assert resolution.artifact_graph_profiles == ["api_contract_text_graph"]
    assert "api_contract_text_graph" in resolution.effective_graph_profiles


def test_generic_yaml_has_no_artifact_label():
    content = """
name: example
settings:
  enabled: true
"""
    policy = load_analysis_policy(POLICY_PATH)

    resolution = resolve_analysis_policy(policy, AnalysisPolicyResolveRequest(relative_path="config.yaml", content=content))

    assert resolution.artifact_labels == []
    assert resolution.artifact_graph_profiles == []
    assert resolution.effective_graph_profiles == ["base_file_graph", "structured_text_graph"]


def test_xml_build_classifier_adds_label_and_profile():
    content = "<project><dependencies><dependency><artifactId>demo</artifactId></dependency></dependencies></project>"
    policy = load_analysis_policy(POLICY_PATH)

    resolution = resolve_analysis_policy(policy, AnalysisPolicyResolveRequest(relative_path="model.xml", content=content))

    assert resolution.artifact_labels == ["build_model_like"]
    assert resolution.artifact_graph_profiles == ["build_text_graph"]
    assert "build_text_graph" in resolution.effective_graph_profiles


def test_generic_xml_has_no_artifact_label():
    content = "<note><body>hello</body></note>"
    policy = load_analysis_policy(POLICY_PATH)

    resolution = resolve_analysis_policy(policy, AnalysisPolicyResolveRequest(relative_path="note.xml", content=content))

    assert resolution.artifact_labels == []
    assert resolution.artifact_graph_profiles == []


def test_duplicate_extension_fails(tmp_path):
    data = _policy_data()
    data["analysis"]["formats"]["python"]["extensions"].append(".java")

    error = _load_invalid(tmp_path, data)

    assert _has_reason(error, "extension is already mapped")


@pytest.mark.parametrize(
    ("mutator", "reason"),
    [
        (lambda data: data["analysis"]["formats"]["python"]["graphProfiles"].append("missing_profile"), "format references a missing graph profile"),
        (lambda data: data["analysis"]["formats"]["python"].update({"extractor": "missing_extractor"}), "format references a missing extractor"),
        (lambda data: data["analysis"]["formats"]["python"].update({"policy": "missing_policy"}), "format references a missing policy"),
        (lambda data: data["analysis"]["formats"]["python"].update({"prompt": "missing_prompt"}), "format references a missing prompt"),
        (lambda data: data.update({"unexpected": {}}), "unknown field is not allowed"),
        (lambda data: data["analysis"]["defaults"].update({"unexpected": True}), "unknown field is not allowed"),
        (lambda data: data["analysis"]["defaults"].pop("defaultPolicy"), "required string is missing"),
        (lambda data: data["analysis"]["formats"]["python"]["extensions"].append("py"), "extensions must start with '.'"),
        (
            lambda data: data["analysis"]["formats"]["yaml"]["artifactClassifiers"][0]["addsGraphProfiles"].append("missing_profile"),
            "artifact classifier references a missing graph profile",
        ),
        (
            lambda data: data["analysis"]["extractors"]["file_anchor"]["produces"].update({"edges": ["MISSING_EDGE"]}),
            "extractor produces an undeclared edge kind",
        ),
        (
            lambda data: data["analysis"]["extractors"]["file_anchor"]["produces"].update({"claims": ["MISSING_CLAIM"]}),
            "extractor produces an undeclared claim kind",
        ),
        (lambda data: data["analysis"]["semantic"]["indexedNodeKinds"].append("MISSING_NODE"), "semantic index references an undeclared graph kind"),
        (lambda data: data["analysis"]["semantic"]["indexedEdgeKinds"].append("MISSING_EDGE"), "semantic index references an undeclared graph kind"),
        (lambda data: data["analysis"]["semantic"]["indexedClaimKinds"].append("MISSING_CLAIM"), "semantic index references an undeclared graph kind"),
        (lambda data: data["analysis"]["unsupported"].update({"unsupportedFormat": "ignore_file"}), "unsupported action is not allowed"),
        (
            lambda data: data["analysis"]["formats"]["yaml"]["artifactClassifiers"][0]["detection"].update({"containsText": ["foo"]}),
            "unknown field is not allowed",
        ),
        (lambda data: data["analysis"]["graphProfiles"]["document_graph"]["nodes"].append("UNKNOWN"), "forbidden graph value"),
        (lambda data: data["analysis"]["graphProfiles"]["document_graph"]["edges"].append("RELATED_TO"), "forbidden graph value"),
        (
            lambda data: data["analysis"]["graph"]["claims"].update(
                {"DIAGNOSTIC": {"evidenceRequired": True, "materialSupportRequired": True, "semanticEligible": False}}
            ),
            "forbidden graph value",
        ),
        (lambda data: data["analysis"]["formats"]["python"].update({"path_globs": ["src/**"]}), "forbidden key"),
        (lambda data: data["analysis"]["formats"]["python"].update({"pathGlobs": ["src/**"]}), "forbidden key"),
        (lambda data: data["analysis"]["formats"]["python"].update({"filenames": ["Dockerfile"]}), "forbidden key"),
        (lambda data: data["analysis"]["formats"]["python"].update({"filename": "Dockerfile"}), "forbidden key"),
        (lambda data: data["analysis"]["formats"]["python"].update({"promptHints": ["be specific"]}), "forbidden key"),
        (lambda data: data["analysis"].update({"supported": {}}), "forbidden key"),
        (lambda data: data["analysis"].update({"reserved": {}}), "forbidden key"),
        (lambda data: data["analysis"].update({"future": {}}), "forbidden key"),
        (lambda data: data["analysis"]["semantic"]["indexedNodeKinds"].append("FIELD"), "semanticEligible is false"),
        (lambda data: data["analysis"]["extractors"]["file_anchor"]["produces"]["nodes"].append("MISSING_NODE"), "extractor produces an undeclared node kind"),
        (lambda data: data["analysis"]["graph"]["edges"]["CALLS"]["from"].append("MISSING_NODE"), "edge from references an undeclared node kind"),
        (lambda data: data["analysis"]["graph"]["edges"]["CALLS"]["to"].append("MISSING_NODE"), "edge to references an undeclared node kind"),
    ],
)
def test_strict_policy_failures(tmp_path, mutator, reason):
    data = _policy_data()
    mutator(data)

    error = _load_invalid(tmp_path, data)

    assert _has_reason(error, reason)


@pytest.mark.parametrize("forbidden_value", ["SECTION", "KEY", "TASK", "STEP", "DEPENDENCY", "PLUGIN"])
def test_forbidden_graph_values_fail(tmp_path, forbidden_value):
    data = _policy_data()
    data["analysis"]["graphProfiles"]["document_graph"]["nodes"].append(forbidden_value)

    error = _load_invalid(tmp_path, data)

    assert _has_reason(error, "forbidden graph value")


def test_loader_and_resolver_do_not_call_db_ollama_or_write_files(tmp_path, monkeypatch):
    policy_path = tmp_path / "analysis-policy.yaml"
    policy_path.write_text(POLICY_PATH.read_text(encoding="utf-8"), encoding="utf-8")

    def fail(*args, **kwargs):
        raise AssertionError("unexpected side effect")

    monkeypatch.setattr(sqlite3, "connect", fail)
    monkeypatch.setattr(httpx, "Client", fail)
    monkeypatch.setattr(httpx, "post", fail, raising=False)
    monkeypatch.setattr(Path, "write_text", fail)

    policy = load_analysis_policy(policy_path)
    resolution = resolve_analysis_policy(policy, AnalysisPolicyResolveRequest(relative_path="src/Foo.java", content="class Foo {}"))

    assert resolution.supported is True
    assert resolution.format_id == "java"


def _policy_data() -> Dict[str, Any]:
    return copy.deepcopy(yaml.safe_load(POLICY_PATH.read_text(encoding="utf-8")))


def _load_invalid(tmp_path: Path, data: Dict[str, Any]) -> AnalysisPolicyError:
    path = tmp_path / "analysis-policy.yaml"
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
    with pytest.raises(AnalysisPolicyError) as exc:
        load_analysis_policy(path)
    return exc.value


def _load_valid(tmp_path: Path, data: Dict[str, Any]):
    path = tmp_path / "analysis-policy.yaml"
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
    return load_analysis_policy(path)


def _has_reason(error: AnalysisPolicyError, expected: str) -> bool:
    return any(expected in diagnostic.reason for diagnostic in error.diagnostics)


def _diagnostic_for_path(error: AnalysisPolicyError, path: str):
    return next(diagnostic for diagnostic in error.diagnostics if diagnostic.path == path)


def _find_forbidden_entries(value: Any, path: str = "$") -> list:
    matches = []
    if isinstance(value, dict):
        for key, item in value.items():
            key_path = f"{path}.{key}" if path != "$" else f"$.{key}"
            if isinstance(key, str) and (key in FORBIDDEN_KEYS or key in FORBIDDEN_GRAPH_VALUES):
                matches.append((key_path, key))
            matches.extend(_find_forbidden_entries(item, key_path))
    elif isinstance(value, list):
        for index, item in enumerate(value):
            matches.extend(_find_forbidden_entries(item, f"{path}[{index}]"))
    elif isinstance(value, str) and value in FORBIDDEN_GRAPH_VALUES:
        matches.append((path, value))
    return matches
