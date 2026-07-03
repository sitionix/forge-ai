from __future__ import annotations

from pathlib import Path
from typing import Any, Dict, List, Mapping, Optional, Set, Union

import yaml

from knowledge_service.analysis_policy import (
    ALLOWED_EXTRACTOR_MODES,
    ALLOWED_LLM_MODES,
    AnalysisPolicy,
    AnalysisPolicyDefaults,
    AnalysisPolicyDiagnostic,
    AnalysisPolicyError,
    AnalyzerExecutionPolicy,
    ArtifactClassifier,
    ExtractorDefinition,
    ExtractorProduces,
    FormatPolicy,
    GraphClaimDefinition,
    GraphContract,
    GraphEdgeDefinition,
    GraphNodeDefinition,
    GraphProfile,
    PromptDefinition,
    SemanticPolicy,
)
from knowledge_service.knowledge_defaults import find_knowledge_config_file, forge_ai_home, resolve_config_path

POLICY_FILENAME = "analysis-policy.yaml"

ALLOWED_UNSUPPORTED_ACTIONS = {
    "fail_file",
    "reject_fact",
    "emit_diagnostic",
    "allow_only_when_semanticEligible_false",
    "fail_config",
}

FORBIDDEN_KEYS = {
    "path_globs",
    "pathGlobs",
    "filenames",
    "filename",
    "promptHints",
    "supported",
    "reserved",
    "future",
}

FORBIDDEN_GRAPH_VALUES = {
    "UNKNOWN",
    "DIAGNOSTIC",
    "RELATED_TO",
    "SECTION",
    "KEY",
    "TASK",
    "STEP",
    "DEPENDENCY",
    "PLUGIN",
}

ROOT_KEYS = {"analysis"}
ANALYSIS_KEYS = {
    "schemaVersion",
    "promptRoot",
    "defaults",
    "prompts",
    "graph",
    "semantic",
    "formats",
    "policies",
    "graphProfiles",
    "extractors",
    "unsupported",
}
DEFAULT_KEYS = {"maxFileChars", "canonicalSourceView", "defaultPolicy", "defaultGraphProfiles", "evidencePolicy"}
PROMPT_KEYS = {"file", "responseShape"}
GRAPH_KEYS = {"nodes", "edges", "claims", "statuses", "origins", "evidenceKinds", "resolutionStatuses"}
GRAPH_NODE_KEYS = {"identity", "semanticEligible"}
GRAPH_EDGE_KEYS = {"from", "to", "semanticEligible"}
GRAPH_CLAIM_KEYS = {"evidenceRequired", "materialSupportRequired", "semanticEligible"}
GRAPH_STATUS_KEYS = {"persistGraphFact", "requiresValidEvidence", "emitDiagnostic", "requiresDerivationTrace", "queryEligible"}
GRAPH_ORIGIN_KEYS = {"canBeTrusted", "requiresValidEvidence", "requiresDerivationTrace"}
SEMANTIC_KEYS = {"indexedNodeKinds", "indexedClaimKinds", "indexedEdgeKinds", "unsupportedSemanticKind"}
FORMAT_KEYS = {"extensions", "family", "extractor", "policy", "prompt", "graphProfiles", "artifactClassifiers"}
ARTIFACT_CLASSIFIER_KEYS = {"id", "detection", "addsGraphProfiles"}
DETECTION_KEYS = {"all", "topLevelKeysAny", "recurringKeysAny", "rootElement", "elementNamesAny"}
POLICY_KEYS = {
    "sourceView",
    "extractorMode",
    "llmMode",
    "allowLlmCreatedAnchors",
    "trustLlmCreatedAnchors",
    "responseSchema",
    "evidenceRequired",
}
GRAPH_PROFILE_KEYS = {"nodes", "edges", "claims"}
EXTRACTOR_KEYS = {"implementation", "trust", "produces"}
EXTRACTOR_PRODUCES_KEYS = {"nodes", "edges", "claims"}
UNSUPPORTED_KEY_ORDER = (
    "unknownNodeKind",
    "unknownEdgeKind",
    "unknownClaimKind",
    "unknownStatus",
    "unknownOrigin",
    "unknownEvidenceKind",
    "unsupportedFormat",
    "unsupportedExtractor",
)
UNSUPPORTED_KEYS = set(UNSUPPORTED_KEY_ORDER)


class _UniqueKeySafeLoader(yaml.SafeLoader):
    pass


def _construct_mapping(loader: _UniqueKeySafeLoader, node: yaml.nodes.MappingNode, deep: bool = False) -> Dict[Any, Any]:
    mapping: Dict[Any, Any] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise yaml.constructor.ConstructorError(
                "while constructing a mapping",
                node.start_mark,
                f"found duplicate key {key!r}",
                key_node.start_mark,
            )
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


_UniqueKeySafeLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_mapping)


def load_analysis_policy(config_dir_or_path: Optional[Union[str, Path]] = None) -> AnalysisPolicy:
    path = _resolve_policy_path(config_dir_or_path)
    if not path.exists():
        raise AnalysisPolicyError(
            [
                AnalysisPolicyDiagnostic(
                    path="$",
                    reason="analysis policy file does not exist",
                    invalid_value=str(path),
                )
            ]
        )
    try:
        data = yaml.load(path.read_text(encoding="utf-8"), Loader=_UniqueKeySafeLoader) or {}
    except yaml.YAMLError as exc:
        raise AnalysisPolicyError(
            [
                AnalysisPolicyDiagnostic(
                    path="$",
                    reason="analysis policy YAML is invalid",
                    invalid_value=str(path),
                    preview=str(exc),
                )
            ]
        ) from exc
    return _parse_analysis_policy(data, path)


def validate_analysis_policy(policy: AnalysisPolicy) -> AnalysisPolicy:
    diagnostics: List[AnalysisPolicyDiagnostic] = []
    _validate_policy_references(policy, diagnostics)
    _raise_if_diagnostics(diagnostics)
    return policy


def _resolve_policy_path(config_dir_or_path: Optional[Union[str, Path]]) -> Path:
    if config_dir_or_path is not None:
        candidate = Path(config_dir_or_path).expanduser()
        if candidate.is_dir():
            return (candidate / POLICY_FILENAME).resolve()
        return candidate.resolve()
    found = find_knowledge_config_file(POLICY_FILENAME)
    if found is not None:
        return found.resolve()
    return (forge_ai_home() / "config" / "knowledge" / POLICY_FILENAME).resolve()


def _parse_analysis_policy(data: Any, source_path: Path) -> AnalysisPolicy:
    diagnostics: List[AnalysisPolicyDiagnostic] = []
    _scan_for_forbidden_entries(data, "$", diagnostics)
    root = _require_mapping(data, "$", diagnostics)
    _check_allowed_keys(root, "$", ROOT_KEYS, diagnostics)

    analysis = _required_mapping(root, "analysis", "$", diagnostics)
    _check_allowed_keys(analysis, "$.analysis", ANALYSIS_KEYS, diagnostics)

    schema_version = _required_int(analysis, "schemaVersion", "$.analysis", diagnostics)
    if schema_version != 1:
        diagnostics.append(
            AnalysisPolicyDiagnostic(
                path="$.analysis.schemaVersion",
                reason="schemaVersion is only a YAML shape guard and must be 1",
                invalid_value=schema_version,
                allowed_values=["1"],
            )
        )
    prompt_root_value = _required_str(analysis, "promptRoot", "$.analysis", diagnostics)
    prompt_root = resolve_config_path(prompt_root_value or "config/knowledge/prompts", config_file=source_path, prefer_root=True)

    defaults = _parse_defaults(_required_mapping(analysis, "defaults", "$.analysis", diagnostics), diagnostics)
    prompts = _parse_prompts(_required_mapping(analysis, "prompts", "$.analysis", diagnostics), diagnostics)
    graph = _parse_graph(_required_mapping(analysis, "graph", "$.analysis", diagnostics), diagnostics)
    semantic = _parse_semantic(_required_mapping(analysis, "semantic", "$.analysis", diagnostics), diagnostics)
    formats = _parse_formats(_required_mapping(analysis, "formats", "$.analysis", diagnostics), diagnostics)
    policies = _parse_policies(_required_mapping(analysis, "policies", "$.analysis", diagnostics), diagnostics)
    graph_profiles = _parse_graph_profiles(_required_mapping(analysis, "graphProfiles", "$.analysis", diagnostics), diagnostics)
    extractors = _parse_extractors(_required_mapping(analysis, "extractors", "$.analysis", diagnostics), diagnostics)
    unsupported = _parse_unsupported(_required_mapping(analysis, "unsupported", "$.analysis", diagnostics), diagnostics)

    extension_to_format = _build_extension_map(formats, diagnostics)
    policy = AnalysisPolicy(
        schema_version=schema_version,
        source_path=source_path,
        prompt_root=prompt_root,
        defaults=defaults,
        prompts=prompts,
        graph=graph,
        semantic=semantic,
        formats=formats,
        policies=policies,
        graph_profiles=graph_profiles,
        extractors=extractors,
        unsupported=unsupported,
        extension_to_format=extension_to_format,
    )
    _validate_policy_references(policy, diagnostics)
    _raise_if_diagnostics(diagnostics)
    return policy


def _parse_defaults(data: Mapping[Any, Any], diagnostics: List[AnalysisPolicyDiagnostic]) -> AnalysisPolicyDefaults:
    path = "$.analysis.defaults"
    _check_allowed_keys(data, path, DEFAULT_KEYS, diagnostics)
    return AnalysisPolicyDefaults(
        max_file_chars=_required_int(data, "maxFileChars", path, diagnostics),
        canonical_source_view=_required_str(data, "canonicalSourceView", path, diagnostics),
        default_policy=_required_str(data, "defaultPolicy", path, diagnostics),
        default_graph_profiles=_required_str_list(data, "defaultGraphProfiles", path, diagnostics),
        evidence_policy=_required_str(data, "evidencePolicy", path, diagnostics),
    )


def _parse_prompts(data: Mapping[Any, Any], diagnostics: List[AnalysisPolicyDiagnostic]) -> Dict[str, PromptDefinition]:
    prompts: Dict[str, PromptDefinition] = {}
    _require_string_keys(data, "$.analysis.prompts", diagnostics)
    for prompt_id, raw in data.items():
        if not isinstance(prompt_id, str):
            continue
        path = f"$.analysis.prompts.{prompt_id}"
        item = _require_mapping(raw, path, diagnostics)
        _check_allowed_keys(item, path, PROMPT_KEYS, diagnostics)
        prompts[prompt_id] = PromptDefinition(
            id=prompt_id,
            file=_required_str(item, "file", path, diagnostics),
            response_shape=_required_str(item, "responseShape", path, diagnostics),
        )
    return prompts


def _parse_graph(data: Mapping[Any, Any], diagnostics: List[AnalysisPolicyDiagnostic]) -> GraphContract:
    path = "$.analysis.graph"
    _check_allowed_keys(data, path, GRAPH_KEYS, diagnostics)
    nodes = _parse_graph_nodes(_required_mapping(data, "nodes", path, diagnostics), diagnostics)
    edges = _parse_graph_edges(_required_mapping(data, "edges", path, diagnostics), diagnostics)
    claims = _parse_graph_claims(_required_mapping(data, "claims", path, diagnostics), diagnostics)
    statuses = _parse_freeform_flag_map(_required_mapping(data, "statuses", path, diagnostics), f"{path}.statuses", GRAPH_STATUS_KEYS, diagnostics)
    origins = _parse_freeform_flag_map(_required_mapping(data, "origins", path, diagnostics), f"{path}.origins", GRAPH_ORIGIN_KEYS, diagnostics)
    evidence_kinds = _parse_empty_map(_required_mapping(data, "evidenceKinds", path, diagnostics), f"{path}.evidenceKinds", diagnostics)
    resolution_statuses = _parse_empty_map(
        _required_mapping(data, "resolutionStatuses", path, diagnostics),
        f"{path}.resolutionStatuses",
        diagnostics,
    )
    return GraphContract(
        nodes=nodes,
        edges=edges,
        claims=claims,
        statuses=statuses,
        origins=origins,
        evidence_kinds=evidence_kinds,
        resolution_statuses=resolution_statuses,
    )


def _parse_graph_nodes(data: Mapping[Any, Any], diagnostics: List[AnalysisPolicyDiagnostic]) -> Dict[str, GraphNodeDefinition]:
    nodes: Dict[str, GraphNodeDefinition] = {}
    _require_string_keys(data, "$.analysis.graph.nodes", diagnostics)
    for kind, raw in data.items():
        if not isinstance(kind, str):
            continue
        path = f"$.analysis.graph.nodes.{kind}"
        item = _require_mapping(raw, path, diagnostics)
        _check_allowed_keys(item, path, GRAPH_NODE_KEYS, diagnostics)
        nodes[kind] = GraphNodeDefinition(
            kind=kind,
            identity=_required_str(item, "identity", path, diagnostics),
            semantic_eligible=_required_bool(item, "semanticEligible", path, diagnostics),
        )
    return nodes


def _parse_graph_edges(data: Mapping[Any, Any], diagnostics: List[AnalysisPolicyDiagnostic]) -> Dict[str, GraphEdgeDefinition]:
    edges: Dict[str, GraphEdgeDefinition] = {}
    _require_string_keys(data, "$.analysis.graph.edges", diagnostics)
    for kind, raw in data.items():
        if not isinstance(kind, str):
            continue
        path = f"$.analysis.graph.edges.{kind}"
        item = _require_mapping(raw, path, diagnostics)
        _check_allowed_keys(item, path, GRAPH_EDGE_KEYS, diagnostics)
        edges[kind] = GraphEdgeDefinition(
            kind=kind,
            from_kinds=_required_str_list(item, "from", path, diagnostics),
            to_kinds=_required_str_list(item, "to", path, diagnostics),
            semantic_eligible=_required_bool(item, "semanticEligible", path, diagnostics),
        )
    return edges


def _parse_graph_claims(data: Mapping[Any, Any], diagnostics: List[AnalysisPolicyDiagnostic]) -> Dict[str, GraphClaimDefinition]:
    claims: Dict[str, GraphClaimDefinition] = {}
    _require_string_keys(data, "$.analysis.graph.claims", diagnostics)
    for kind, raw in data.items():
        if not isinstance(kind, str):
            continue
        path = f"$.analysis.graph.claims.{kind}"
        item = _require_mapping(raw, path, diagnostics)
        _check_allowed_keys(item, path, GRAPH_CLAIM_KEYS, diagnostics)
        claims[kind] = GraphClaimDefinition(
            kind=kind,
            evidence_required=_required_bool(item, "evidenceRequired", path, diagnostics),
            material_support_required=_required_bool(item, "materialSupportRequired", path, diagnostics),
            semantic_eligible=_required_bool(item, "semanticEligible", path, diagnostics),
        )
    return claims


def _parse_freeform_flag_map(
    data: Mapping[Any, Any],
    path: str,
    allowed_item_keys: Set[str],
    diagnostics: List[AnalysisPolicyDiagnostic],
) -> Dict[str, Mapping[str, Any]]:
    result: Dict[str, Mapping[str, Any]] = {}
    _require_string_keys(data, path, diagnostics)
    for item_id, raw in data.items():
        if not isinstance(item_id, str):
            continue
        item_path = f"{path}.{item_id}"
        item = _require_mapping(raw, item_path, diagnostics)
        _check_allowed_keys(item, item_path, allowed_item_keys, diagnostics)
        for key, value in item.items():
            if not isinstance(value, bool):
                diagnostics.append(
                    AnalysisPolicyDiagnostic(
                        path=f"{item_path}.{key}",
                        reason="flag values must be booleans",
                        invalid_value=value,
                        allowed_values=["true", "false"],
                    )
                )
        result[item_id] = dict(item)
    return result


def _parse_empty_map(data: Mapping[Any, Any], path: str, diagnostics: List[AnalysisPolicyDiagnostic]) -> Dict[str, Mapping[str, Any]]:
    result: Dict[str, Mapping[str, Any]] = {}
    _require_string_keys(data, path, diagnostics)
    for item_id, raw in data.items():
        if not isinstance(item_id, str):
            continue
        item_path = f"{path}.{item_id}"
        item = _require_mapping(raw, item_path, diagnostics)
        _check_allowed_keys(item, item_path, set(), diagnostics)
        result[item_id] = dict(item)
    return result


def _parse_semantic(data: Mapping[Any, Any], diagnostics: List[AnalysisPolicyDiagnostic]) -> SemanticPolicy:
    path = "$.analysis.semantic"
    _check_allowed_keys(data, path, SEMANTIC_KEYS, diagnostics)
    return SemanticPolicy(
        indexed_node_kinds=_required_str_list(data, "indexedNodeKinds", path, diagnostics),
        indexed_edge_types=_required_str_list(data, "indexedEdgeKinds", path, diagnostics),
        indexed_claim_kinds=_required_str_list(data, "indexedClaimKinds", path, diagnostics),
        unsupported_semantic_kind=_required_str(data, "unsupportedSemanticKind", path, diagnostics),
    )


def _parse_formats(data: Mapping[Any, Any], diagnostics: List[AnalysisPolicyDiagnostic]) -> Dict[str, FormatPolicy]:
    formats: Dict[str, FormatPolicy] = {}
    _require_string_keys(data, "$.analysis.formats", diagnostics)
    for format_id, raw in data.items():
        if not isinstance(format_id, str):
            continue
        path = f"$.analysis.formats.{format_id}"
        item = _require_mapping(raw, path, diagnostics)
        _check_allowed_keys(item, path, FORMAT_KEYS, diagnostics)
        classifiers = _parse_artifact_classifiers(item.get("artifactClassifiers") or [], f"{path}.artifactClassifiers", diagnostics)
        formats[format_id] = FormatPolicy(
            id=format_id,
            extensions=_required_str_list(item, "extensions", path, diagnostics),
            family=_required_str(item, "family", path, diagnostics),
            extractor=_required_str(item, "extractor", path, diagnostics),
            policy=_required_str(item, "policy", path, diagnostics),
            prompt=_required_str(item, "prompt", path, diagnostics),
            graph_profiles=_required_str_list(item, "graphProfiles", path, diagnostics),
            artifact_classifiers=classifiers,
        )
    return formats


def _parse_artifact_classifiers(raw: Any, path: str, diagnostics: List[AnalysisPolicyDiagnostic]) -> List[ArtifactClassifier]:
    if raw in (None, []):
        return []
    if not isinstance(raw, list):
        diagnostics.append(AnalysisPolicyDiagnostic(path=path, reason="artifactClassifiers must be a list", invalid_value=raw))
        return []
    classifiers: List[ArtifactClassifier] = []
    seen: Set[str] = set()
    for index, item_raw in enumerate(raw):
        item_path = f"{path}[{index}]"
        item = _require_mapping(item_raw, item_path, diagnostics)
        _check_allowed_keys(item, item_path, ARTIFACT_CLASSIFIER_KEYS, diagnostics)
        classifier_id = _required_str(item, "id", item_path, diagnostics)
        if classifier_id in seen:
            diagnostics.append(
                AnalysisPolicyDiagnostic(
                    path=f"{item_path}.id",
                    reason="artifact classifier id must be unique within a format",
                    invalid_value=classifier_id,
                )
            )
        seen.add(classifier_id)
        detection = _required_mapping(item, "detection", item_path, diagnostics)
        _validate_detection(detection, f"{item_path}.detection", diagnostics)
        classifiers.append(
            ArtifactClassifier(
                id=classifier_id,
                detection=dict(detection),
                adds_graph_profiles=_required_str_list(item, "addsGraphProfiles", item_path, diagnostics),
            )
        )
    return classifiers


def _validate_detection(data: Mapping[Any, Any], path: str, diagnostics: List[AnalysisPolicyDiagnostic]) -> None:
    _check_allowed_keys(data, path, DETECTION_KEYS, diagnostics)
    if "all" in data:
        raw_all = data["all"]
        if not isinstance(raw_all, list):
            diagnostics.append(AnalysisPolicyDiagnostic(path=f"{path}.all", reason="all must be a list of detection mappings", invalid_value=raw_all))
        else:
            for index, raw_item in enumerate(raw_all):
                item_path = f"{path}.all[{index}]"
                item = _require_mapping(raw_item, item_path, diagnostics)
                _validate_detection(item, item_path, diagnostics)
    for key in ("topLevelKeysAny", "recurringKeysAny", "elementNamesAny"):
        if key in data:
            _validate_string_list_value(data[key], f"{path}.{key}", diagnostics)
    if "rootElement" in data and not isinstance(data["rootElement"], str):
        diagnostics.append(
            AnalysisPolicyDiagnostic(
                path=f"{path}.rootElement",
                reason="rootElement must be a string",
                invalid_value=data["rootElement"],
            )
        )


def _parse_policies(data: Mapping[Any, Any], diagnostics: List[AnalysisPolicyDiagnostic]) -> Dict[str, AnalyzerExecutionPolicy]:
    policies: Dict[str, AnalyzerExecutionPolicy] = {}
    _require_string_keys(data, "$.analysis.policies", diagnostics)
    for policy_id, raw in data.items():
        if not isinstance(policy_id, str):
            continue
        path = f"$.analysis.policies.{policy_id}"
        item = _require_mapping(raw, path, diagnostics)
        _check_allowed_keys(item, path, POLICY_KEYS, diagnostics)
        policies[policy_id] = AnalyzerExecutionPolicy(
            id=policy_id,
            source_view=_required_str(item, "sourceView", path, diagnostics),
            extractor_mode=_required_allowed_str(item, "extractorMode", path, diagnostics, allowed_values=ALLOWED_EXTRACTOR_MODES),
            llm_mode=_required_allowed_str(item, "llmMode", path, diagnostics, allowed_values=ALLOWED_LLM_MODES),
            response_schema=_required_str(item, "responseSchema", path, diagnostics),
            evidence_required=_required_bool(item, "evidenceRequired", path, diagnostics),
            allow_llm_created_anchors=_optional_bool(item, "allowLlmCreatedAnchors", path, diagnostics, default=False),
            trust_llm_created_anchors=_optional_bool(item, "trustLlmCreatedAnchors", path, diagnostics, default=False),
        )
    return policies


def _parse_graph_profiles(data: Mapping[Any, Any], diagnostics: List[AnalysisPolicyDiagnostic]) -> Dict[str, GraphProfile]:
    profiles: Dict[str, GraphProfile] = {}
    _require_string_keys(data, "$.analysis.graphProfiles", diagnostics)
    for profile_id, raw in data.items():
        if not isinstance(profile_id, str):
            continue
        path = f"$.analysis.graphProfiles.{profile_id}"
        item = _require_mapping(raw, path, diagnostics)
        _check_allowed_keys(item, path, GRAPH_PROFILE_KEYS, diagnostics)
        profiles[profile_id] = GraphProfile(
            id=profile_id,
            nodes=_required_str_list(item, "nodes", path, diagnostics),
            edges=_required_str_list(item, "edges", path, diagnostics),
            claims=_required_str_list(item, "claims", path, diagnostics),
        )
    return profiles


def _parse_extractors(data: Mapping[Any, Any], diagnostics: List[AnalysisPolicyDiagnostic]) -> Dict[str, ExtractorDefinition]:
    extractors: Dict[str, ExtractorDefinition] = {}
    _require_string_keys(data, "$.analysis.extractors", diagnostics)
    for extractor_id, raw in data.items():
        if not isinstance(extractor_id, str):
            continue
        path = f"$.analysis.extractors.{extractor_id}"
        item = _require_mapping(raw, path, diagnostics)
        _check_allowed_keys(item, path, EXTRACTOR_KEYS, diagnostics)
        produces = _required_mapping(item, "produces", path, diagnostics)
        _check_allowed_keys(produces, f"{path}.produces", EXTRACTOR_PRODUCES_KEYS, diagnostics)
        extractors[extractor_id] = ExtractorDefinition(
            id=extractor_id,
            implementation=_required_str(item, "implementation", path, diagnostics),
            trust=_required_str(item, "trust", path, diagnostics),
            produces=ExtractorProduces(
                nodes=_optional_str_list(produces, "nodes", f"{path}.produces", diagnostics),
                edges=_optional_str_list(produces, "edges", f"{path}.produces", diagnostics),
                claims=_optional_str_list(produces, "claims", f"{path}.produces", diagnostics),
            ),
        )
    return extractors


def _parse_unsupported(data: Mapping[Any, Any], diagnostics: List[AnalysisPolicyDiagnostic]) -> Dict[str, str]:
    path = "$.analysis.unsupported"
    _check_allowed_keys(data, path, UNSUPPORTED_KEYS, diagnostics)
    result: Dict[str, str] = {}
    for key in UNSUPPORTED_KEY_ORDER:
        value = _required_str(data, key, path, diagnostics)
        if value and value not in ALLOWED_UNSUPPORTED_ACTIONS:
            diagnostics.append(
                AnalysisPolicyDiagnostic(
                    path=f"{path}.{key}",
                    reason="unsupported action is not allowed",
                    invalid_value=value,
                    allowed_values=sorted(ALLOWED_UNSUPPORTED_ACTIONS),
                )
            )
        result[key] = value
    return result


def _build_extension_map(formats: Mapping[str, FormatPolicy], diagnostics: List[AnalysisPolicyDiagnostic]) -> Dict[str, str]:
    extension_to_format: Dict[str, str] = {}
    extension_paths: Dict[str, str] = {}
    for format_id, format_policy in formats.items():
        for index, extension in enumerate(format_policy.extensions):
            path = f"$.analysis.formats.{format_id}.extensions[{index}]"
            if not extension.startswith("."):
                diagnostics.append(
                    AnalysisPolicyDiagnostic(
                        path=path,
                        reason="extensions must start with '.'",
                        invalid_value=extension,
                    )
                )
                continue
            normalized = extension.lower()
            if normalized in extension_to_format:
                diagnostics.append(
                    AnalysisPolicyDiagnostic(
                        path=path,
                        reason="extension is already mapped by another format",
                        invalid_value=extension,
                        preview=extension_paths[normalized],
                    )
                )
                continue
            extension_to_format[normalized] = format_id
            extension_paths[normalized] = path
    return extension_to_format


def _validate_policy_references(policy: AnalysisPolicy, diagnostics: List[AnalysisPolicyDiagnostic]) -> None:
    graph = policy.graph
    _validate_reference(
        policy.defaults.default_policy,
        policy.policies,
        "$.analysis.defaults.defaultPolicy",
        "defaultPolicy references a missing policy",
        diagnostics,
    )
    for index, profile_id in enumerate(policy.defaults.default_graph_profiles):
        _validate_reference(
            profile_id,
            policy.graph_profiles,
            f"$.analysis.defaults.defaultGraphProfiles[{index}]",
            "defaultGraphProfiles references a missing graph profile",
            diagnostics,
        )

    for edge_id, edge in graph.edges.items():
        for index, node_kind in enumerate(edge.from_kinds):
            _validate_reference(
                node_kind,
                graph.nodes,
                f"$.analysis.graph.edges.{edge_id}.from[{index}]",
                "edge from references an undeclared node kind",
                diagnostics,
            )
        for index, node_kind in enumerate(edge.to_kinds):
            _validate_reference(
                node_kind,
                graph.nodes,
                f"$.analysis.graph.edges.{edge_id}.to[{index}]",
                "edge to references an undeclared node kind",
                diagnostics,
            )

    for index, kind in enumerate(policy.semantic.indexed_node_kinds):
        _validate_semantic_reference(kind, graph.nodes, f"$.analysis.semantic.indexedNodeKinds[{index}]", diagnostics)
    for index, kind in enumerate(policy.semantic.indexed_edge_types):
        _validate_semantic_reference(kind, graph.edges, f"$.analysis.semantic.indexedEdgeKinds[{index}]", diagnostics)
    for index, kind in enumerate(policy.semantic.indexed_claim_kinds):
        _validate_semantic_reference(kind, graph.claims, f"$.analysis.semantic.indexedClaimKinds[{index}]", diagnostics)
    if policy.semantic.unsupported_semantic_kind not in ALLOWED_UNSUPPORTED_ACTIONS:
        diagnostics.append(
            AnalysisPolicyDiagnostic(
                path="$.analysis.semantic.unsupportedSemanticKind",
                reason="semantic unsupported action is not allowed",
                invalid_value=policy.semantic.unsupported_semantic_kind,
                allowed_values=sorted(ALLOWED_UNSUPPORTED_ACTIONS),
            )
        )

    for format_id, format_policy in policy.formats.items():
        format_path = f"$.analysis.formats.{format_id}"
        _validate_reference(format_policy.extractor, policy.extractors, f"{format_path}.extractor", "format references a missing extractor", diagnostics)
        _validate_reference(format_policy.policy, policy.policies, f"{format_path}.policy", "format references a missing policy", diagnostics)
        _validate_reference(format_policy.prompt, policy.prompts, f"{format_path}.prompt", "format references a missing prompt", diagnostics)
        for index, profile_id in enumerate(format_policy.graph_profiles):
            _validate_reference(
                profile_id,
                policy.graph_profiles,
                f"{format_path}.graphProfiles[{index}]",
                "format references a missing graph profile",
                diagnostics,
            )
        for classifier_index, classifier in enumerate(format_policy.artifact_classifiers):
            for profile_index, profile_id in enumerate(classifier.adds_graph_profiles):
                _validate_reference(
                    profile_id,
                    policy.graph_profiles,
                    f"{format_path}.artifactClassifiers[{classifier_index}].addsGraphProfiles[{profile_index}]",
                    "artifact classifier references a missing graph profile",
                    diagnostics,
                )

    for profile_id, profile in policy.graph_profiles.items():
        profile_path = f"$.analysis.graphProfiles.{profile_id}"
        for index, kind in enumerate(profile.nodes):
            _validate_reference(kind, graph.nodes, f"{profile_path}.nodes[{index}]", "graph profile references an undeclared node kind", diagnostics)
        for index, kind in enumerate(profile.edges):
            _validate_reference(kind, graph.edges, f"{profile_path}.edges[{index}]", "graph profile references an undeclared edge kind", diagnostics)
        for index, kind in enumerate(profile.claims):
            _validate_reference(kind, graph.claims, f"{profile_path}.claims[{index}]", "graph profile references an undeclared claim kind", diagnostics)

    for extractor_id, extractor in policy.extractors.items():
        produces_path = f"$.analysis.extractors.{extractor_id}.produces"
        for index, kind in enumerate(extractor.produces.nodes):
            _validate_reference(kind, graph.nodes, f"{produces_path}.nodes[{index}]", "extractor produces an undeclared node kind", diagnostics)
        for index, kind in enumerate(extractor.produces.edges):
            _validate_reference(kind, graph.edges, f"{produces_path}.edges[{index}]", "extractor produces an undeclared edge kind", diagnostics)
        for index, kind in enumerate(extractor.produces.claims):
            _validate_reference(kind, graph.claims, f"{produces_path}.claims[{index}]", "extractor produces an undeclared claim kind", diagnostics)


def _validate_semantic_reference(kind: str, declared: Mapping[str, Any], path: str, diagnostics: List[AnalysisPolicyDiagnostic]) -> None:
    item = declared.get(kind)
    if item is None:
        diagnostics.append(
            AnalysisPolicyDiagnostic(
                path=path,
                reason="semantic index references an undeclared graph kind",
                invalid_value=kind,
                allowed_values=sorted(declared.keys()),
            )
        )
        return
    if not getattr(item, "semantic_eligible", False):
        diagnostics.append(
            AnalysisPolicyDiagnostic(
                path=path,
                reason="semantic index references a graph kind where semanticEligible is false",
                invalid_value=kind,
            )
        )


def _validate_reference(
    value: str,
    declared: Mapping[str, Any],
    path: str,
    reason: str,
    diagnostics: List[AnalysisPolicyDiagnostic],
) -> None:
    if value not in declared:
        diagnostics.append(
            AnalysisPolicyDiagnostic(
                path=path,
                reason=reason,
                invalid_value=value,
                allowed_values=sorted(declared.keys()),
            )
        )


def _scan_for_forbidden_entries(value: Any, path: str, diagnostics: List[AnalysisPolicyDiagnostic]) -> None:
    if isinstance(value, Mapping):
        for key, item in value.items():
            key_path = f"{path}.{key}" if path != "$" else f"$.{key}"
            if isinstance(key, str):
                if key in FORBIDDEN_KEYS:
                    diagnostics.append(
                        AnalysisPolicyDiagnostic(
                            path=key_path,
                            reason="forbidden key is not allowed in analysis policy",
                            invalid_value=key,
                            allowed_values=sorted(FORBIDDEN_KEYS),
                        )
                    )
                if key in FORBIDDEN_GRAPH_VALUES:
                    diagnostics.append(
                        AnalysisPolicyDiagnostic(
                            path=key_path,
                            reason="forbidden graph value is not allowed in analysis policy",
                            invalid_value=key,
                            allowed_values=sorted(FORBIDDEN_GRAPH_VALUES),
                        )
                    )
            _scan_for_forbidden_entries(item, key_path, diagnostics)
    elif isinstance(value, list):
        for index, item in enumerate(value):
            _scan_for_forbidden_entries(item, f"{path}[{index}]", diagnostics)
    elif isinstance(value, str) and value in FORBIDDEN_GRAPH_VALUES:
        diagnostics.append(
            AnalysisPolicyDiagnostic(
                path=path,
                reason="forbidden graph value is not allowed in analysis policy",
                invalid_value=value,
                allowed_values=sorted(FORBIDDEN_GRAPH_VALUES),
            )
        )


def _required_mapping(data: Mapping[Any, Any], key: str, parent_path: str, diagnostics: List[AnalysisPolicyDiagnostic]) -> Mapping[Any, Any]:
    if key not in data:
        diagnostics.append(AnalysisPolicyDiagnostic(path=f"{parent_path}.{key}", reason="required mapping is missing"))
        return {}
    return _require_mapping(data[key], f"{parent_path}.{key}", diagnostics)


def _require_mapping(value: Any, path: str, diagnostics: List[AnalysisPolicyDiagnostic]) -> Mapping[Any, Any]:
    if not isinstance(value, Mapping):
        diagnostics.append(AnalysisPolicyDiagnostic(path=path, reason="value must be a mapping", invalid_value=value))
        return {}
    return value


def _required_str(data: Mapping[Any, Any], key: str, parent_path: str, diagnostics: List[AnalysisPolicyDiagnostic]) -> str:
    if key not in data:
        diagnostics.append(AnalysisPolicyDiagnostic(path=f"{parent_path}.{key}", reason="required string is missing"))
        return ""
    value = data[key]
    if not isinstance(value, str) or not value.strip():
        diagnostics.append(AnalysisPolicyDiagnostic(path=f"{parent_path}.{key}", reason="value must be a non-empty string", invalid_value=value))
        return ""
    return value


def _required_allowed_str(
    data: Mapping[Any, Any],
    key: str,
    parent_path: str,
    diagnostics: List[AnalysisPolicyDiagnostic],
    *,
    allowed_values: tuple[str, ...],
) -> str:
    value = _required_str(data, key, parent_path, diagnostics)
    if not value:
        return value
    if value not in allowed_values:
        diagnostics.append(
            AnalysisPolicyDiagnostic(
                path=f"{parent_path}.{key}",
                reason=f"{key} is not an allowed runtime mode",
                invalid_value=value,
                allowed_values=list(allowed_values),
            )
        )
    return value


def _required_int(data: Mapping[Any, Any], key: str, parent_path: str, diagnostics: List[AnalysisPolicyDiagnostic]) -> int:
    if key not in data:
        diagnostics.append(AnalysisPolicyDiagnostic(path=f"{parent_path}.{key}", reason="required integer is missing"))
        return 0
    value = data[key]
    if not isinstance(value, int) or isinstance(value, bool):
        diagnostics.append(AnalysisPolicyDiagnostic(path=f"{parent_path}.{key}", reason="value must be an integer", invalid_value=value))
        return 0
    return value


def _required_bool(data: Mapping[Any, Any], key: str, parent_path: str, diagnostics: List[AnalysisPolicyDiagnostic]) -> bool:
    if key not in data:
        diagnostics.append(AnalysisPolicyDiagnostic(path=f"{parent_path}.{key}", reason="required boolean is missing"))
        return False
    value = data[key]
    if not isinstance(value, bool):
        diagnostics.append(
            AnalysisPolicyDiagnostic(
                path=f"{parent_path}.{key}",
                reason="value must be a boolean",
                invalid_value=value,
                allowed_values=["true", "false"],
            )
        )
        return False
    return value


def _optional_bool(
    data: Mapping[Any, Any],
    key: str,
    parent_path: str,
    diagnostics: List[AnalysisPolicyDiagnostic],
    *,
    default: bool,
) -> bool:
    if key not in data:
        return default
    value = data[key]
    if not isinstance(value, bool):
        diagnostics.append(
            AnalysisPolicyDiagnostic(
                path=f"{parent_path}.{key}",
                reason="value must be a boolean",
                invalid_value=value,
                allowed_values=["true", "false"],
            )
        )
        return default
    return value


def _required_str_list(data: Mapping[Any, Any], key: str, parent_path: str, diagnostics: List[AnalysisPolicyDiagnostic]) -> List[str]:
    if key not in data:
        diagnostics.append(AnalysisPolicyDiagnostic(path=f"{parent_path}.{key}", reason="required string list is missing"))
        return []
    return _validate_string_list_value(data[key], f"{parent_path}.{key}", diagnostics)


def _optional_str_list(data: Mapping[Any, Any], key: str, parent_path: str, diagnostics: List[AnalysisPolicyDiagnostic]) -> List[str]:
    if key not in data:
        return []
    return _validate_string_list_value(data[key], f"{parent_path}.{key}", diagnostics)


def _validate_string_list_value(value: Any, path: str, diagnostics: List[AnalysisPolicyDiagnostic]) -> List[str]:
    if not isinstance(value, list):
        diagnostics.append(AnalysisPolicyDiagnostic(path=path, reason="value must be a list of strings", invalid_value=value))
        return []
    result: List[str] = []
    for index, item in enumerate(value):
        if not isinstance(item, str) or not item.strip():
            diagnostics.append(AnalysisPolicyDiagnostic(path=f"{path}[{index}]", reason="list item must be a non-empty string", invalid_value=item))
            continue
        result.append(item)
    return result


def _check_allowed_keys(data: Mapping[Any, Any], path: str, allowed: Set[str], diagnostics: List[AnalysisPolicyDiagnostic]) -> None:
    for key in data.keys():
        if not isinstance(key, str):
            diagnostics.append(AnalysisPolicyDiagnostic(path=path, reason="mapping keys must be strings", invalid_value=key))
            continue
        if key not in allowed:
            diagnostics.append(
                AnalysisPolicyDiagnostic(
                    path=f"{path}.{key}" if path != "$" else f"$.{key}",
                    reason="unknown field is not allowed",
                    invalid_value=key,
                    allowed_values=sorted(allowed),
                )
            )


def _require_string_keys(data: Mapping[Any, Any], path: str, diagnostics: List[AnalysisPolicyDiagnostic]) -> None:
    for key in data.keys():
        if not isinstance(key, str):
            diagnostics.append(AnalysisPolicyDiagnostic(path=path, reason="mapping keys must be strings", invalid_value=key))


def _raise_if_diagnostics(diagnostics: List[AnalysisPolicyDiagnostic]) -> None:
    if diagnostics:
        raise AnalysisPolicyError(diagnostics)
