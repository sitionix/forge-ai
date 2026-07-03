from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, List, Mapping, Optional, Sequence, Tuple
from xml.etree import ElementTree

import yaml

from knowledge_service.analysis_policy import AnalysisPolicy, FormatPolicy, GraphProfile


@dataclass(frozen=True)
class AnalysisPolicyResolveRequest:
    relative_path: str
    content: Optional[str] = None
    content_lines: Optional[List[str]] = None


@dataclass(frozen=True)
class AnalysisPolicyResolution:
    supported: bool
    failure_code: Optional[str] = None
    failure_message: Optional[str] = None
    relative_path: str = ""
    extension: str = ""
    format_id: Optional[str] = None
    family: Optional[str] = None
    extractor_id: Optional[str] = None
    policy_id: Optional[str] = None
    prompt_id: Optional[str] = None
    prompt_path: Optional[Path] = None
    base_graph_profiles: List[str] = field(default_factory=list)
    artifact_labels: List[str] = field(default_factory=list)
    artifact_graph_profiles: List[str] = field(default_factory=list)
    effective_graph_profiles: List[str] = field(default_factory=list)
    allowed_node_kinds: List[str] = field(default_factory=list)
    allowed_edge_kinds: List[str] = field(default_factory=list)
    allowed_claim_kinds: List[str] = field(default_factory=list)
    semantic_node_kinds: List[str] = field(default_factory=list)
    semantic_edge_kinds: List[str] = field(default_factory=list)
    semantic_claim_kinds: List[str] = field(default_factory=list)
    status_kinds: List[str] = field(default_factory=list)
    origin_kinds: List[str] = field(default_factory=list)
    evidence_kinds: List[str] = field(default_factory=list)
    resolution_statuses: List[str] = field(default_factory=list)
    source_view: Optional[str] = None
    extractor_mode: Optional[str] = None
    llm_mode: Optional[str] = None
    allow_llm_created_anchors: bool = False
    trust_llm_created_anchors: bool = False
    evidence_required: bool = False
    unsupported_behavior: Mapping[str, str] = field(default_factory=dict)


class AnalysisPolicyResolver:
    def __init__(self, policy: AnalysisPolicy):
        self.policy = policy
        self._extensions_by_length = sorted(policy.extension_to_format.keys(), key=len, reverse=True)

    def resolve(self, request: AnalysisPolicyResolveRequest) -> AnalysisPolicyResolution:
        extension, format_id = self._resolve_extension(request.relative_path)
        if format_id is None:
            return AnalysisPolicyResolution(
                supported=False,
                failure_code="UNSUPPORTED_FORMAT",
                failure_message=f"No analysis policy format matches extension for {request.relative_path}",
                relative_path=request.relative_path,
                extension=extension,
                unsupported_behavior=dict(self.policy.unsupported),
            )

        format_policy = self.policy.formats.get(format_id)
        if format_policy is None:
            return self._missing_reference(request, extension, format_id, "FORMAT_REFERENCE_MISSING", f"Format {format_id} is not declared")

        execution_policy = self.policy.policies.get(format_policy.policy)
        extractor = self.policy.extractors.get(format_policy.extractor)
        prompt = self.policy.prompts.get(format_policy.prompt)
        if execution_policy is None:
            return self._missing_reference(
                request,
                extension,
                format_id,
                "POLICY_REFERENCE_MISSING",
                f"Policy {format_policy.policy} is not declared",
            )
        if extractor is None:
            return self._missing_reference(
                request,
                extension,
                format_id,
                "EXTRACTOR_REFERENCE_MISSING",
                f"Extractor {format_policy.extractor} is not declared",
            )
        if prompt is None:
            return self._missing_reference(
                request,
                extension,
                format_id,
                "PROMPT_REFERENCE_MISSING",
                f"Prompt {format_policy.prompt} is not declared",
            )

        content = _request_content(request)
        artifact_labels, artifact_graph_profiles = _resolve_artifacts(format_policy, content)
        effective_graph_profiles = _ordered_union([format_policy.graph_profiles, artifact_graph_profiles])
        allowed_node_kinds = _allowed_kinds(self.policy.graph_profiles, effective_graph_profiles, "nodes")
        allowed_edge_kinds = _allowed_kinds(self.policy.graph_profiles, effective_graph_profiles, "edges")
        allowed_claim_kinds = _allowed_kinds(self.policy.graph_profiles, effective_graph_profiles, "claims")

        return AnalysisPolicyResolution(
            supported=True,
            relative_path=request.relative_path,
            extension=extension,
            format_id=format_id,
            family=format_policy.family,
            extractor_id=extractor.id,
            policy_id=execution_policy.id,
            prompt_id=prompt.id,
            prompt_path=self.policy.prompt_path(prompt.id),
            base_graph_profiles=list(format_policy.graph_profiles),
            artifact_labels=artifact_labels,
            artifact_graph_profiles=artifact_graph_profiles,
            effective_graph_profiles=effective_graph_profiles,
            allowed_node_kinds=allowed_node_kinds,
            allowed_edge_kinds=allowed_edge_kinds,
            allowed_claim_kinds=allowed_claim_kinds,
            semantic_node_kinds=[kind for kind in self.policy.semantic.indexed_node_kinds if kind in allowed_node_kinds],
            semantic_edge_kinds=[kind for kind in self.policy.semantic.indexed_edge_kinds if kind in allowed_edge_kinds],
            semantic_claim_kinds=[kind for kind in self.policy.semantic.indexed_claim_kinds if kind in allowed_claim_kinds],
            status_kinds=list(self.policy.graph.statuses.keys()),
            origin_kinds=list(self.policy.graph.origins.keys()),
            evidence_kinds=list(self.policy.graph.evidence_kinds.keys()),
            resolution_statuses=list(self.policy.graph.resolution_statuses.keys()),
            source_view=execution_policy.source_view,
            extractor_mode=execution_policy.extractor_mode,
            llm_mode=execution_policy.llm_mode,
            allow_llm_created_anchors=execution_policy.allow_llm_created_anchors,
            trust_llm_created_anchors=execution_policy.trust_llm_created_anchors,
            evidence_required=execution_policy.evidence_required,
            unsupported_behavior=dict(self.policy.unsupported),
        )

    def _resolve_extension(self, relative_path: str) -> Tuple[str, Optional[str]]:
        normalized_path = relative_path.lower()
        for extension in self._extensions_by_length:
            if normalized_path.endswith(extension):
                return extension, self.policy.extension_to_format[extension]
        return Path(relative_path).suffix.lower(), None

    def _missing_reference(
        self,
        request: AnalysisPolicyResolveRequest,
        extension: str,
        format_id: str,
        failure_code: str,
        failure_message: str,
    ) -> AnalysisPolicyResolution:
        return AnalysisPolicyResolution(
            supported=False,
            failure_code=failure_code,
            failure_message=failure_message,
            relative_path=request.relative_path,
            extension=extension,
            format_id=format_id,
            unsupported_behavior=dict(self.policy.unsupported),
        )


def resolve_analysis_policy(policy: AnalysisPolicy, request: AnalysisPolicyResolveRequest) -> AnalysisPolicyResolution:
    return AnalysisPolicyResolver(policy).resolve(request)


def _request_content(request: AnalysisPolicyResolveRequest) -> Optional[str]:
    if request.content is not None:
        return request.content
    if request.content_lines is not None:
        return "\n".join(request.content_lines)
    return None


def _resolve_artifacts(format_policy: FormatPolicy, content: Optional[str]) -> Tuple[List[str], List[str]]:
    if content is None:
        return [], []
    labels: List[str] = []
    graph_profiles: List[str] = []
    for classifier in format_policy.artifact_classifiers:
        if _evaluate_detection(classifier.detection, content):
            labels.append(classifier.id)
            graph_profiles = _ordered_union([graph_profiles, classifier.adds_graph_profiles])
    return labels, graph_profiles


def _evaluate_detection(detection: Mapping[str, Any], content: str) -> bool:
    if "all" in detection:
        raw_all = detection.get("all")
        if not isinstance(raw_all, list):
            return False
        return all(isinstance(item, Mapping) and _evaluate_detection(item, content) for item in raw_all)
    if "topLevelKeysAny" in detection:
        return _yaml_top_level_keys_any(content, _as_string_sequence(detection.get("topLevelKeysAny")))
    if "recurringKeysAny" in detection:
        return _yaml_recurring_keys_any(content, _as_string_sequence(detection.get("recurringKeysAny")))
    if "rootElement" in detection:
        root = _parse_xml_root(content)
        return root is not None and _local_name(root.tag) == detection.get("rootElement")
    if "elementNamesAny" in detection:
        root = _parse_xml_root(content)
        wanted = set(_as_string_sequence(detection.get("elementNamesAny")))
        return root is not None and any(_local_name(element.tag) in wanted for element in root.iter())
    return False


class _YamlContentLoader(yaml.SafeLoader):
    pass


_YamlContentLoader.yaml_implicit_resolvers = {
    key: [(tag, regexp) for tag, regexp in resolvers if tag != "tag:yaml.org,2002:bool"]
    for key, resolvers in yaml.SafeLoader.yaml_implicit_resolvers.items()
}


def _parse_yaml_content(content: str) -> Any:
    try:
        return yaml.load(content, Loader=_YamlContentLoader)
    except yaml.YAMLError:
        return None


def _yaml_top_level_keys_any(content: str, keys: Sequence[str]) -> bool:
    parsed = _parse_yaml_content(content)
    if not isinstance(parsed, Mapping):
        return False
    top_level_keys = {str(key) for key in parsed.keys()}
    return bool(top_level_keys.intersection(keys))


def _yaml_recurring_keys_any(content: str, keys: Sequence[str]) -> bool:
    parsed = _parse_yaml_content(content)
    if parsed is None:
        return False
    wanted = set(keys)
    return _contains_mapping_key(parsed, wanted)


def _contains_mapping_key(value: Any, keys: Sequence[str]) -> bool:
    wanted = set(keys)
    if isinstance(value, Mapping):
        for key, item in value.items():
            if str(key) in wanted:
                return True
            if _contains_mapping_key(item, keys):
                return True
    elif isinstance(value, list):
        return any(_contains_mapping_key(item, keys) for item in value)
    return False


def _parse_xml_root(content: str) -> Optional[ElementTree.Element]:
    upper = content[:1024].upper()
    if "<!DOCTYPE" in upper or "<!ENTITY" in upper:
        return None
    try:
        return ElementTree.fromstring(content)
    except ElementTree.ParseError:
        return None


def _local_name(tag: str) -> str:
    if "}" in tag:
        return tag.rsplit("}", 1)[1]
    if ":" in tag:
        return tag.rsplit(":", 1)[1]
    return tag


def _as_string_sequence(value: Any) -> List[str]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, str)]


def _allowed_kinds(graph_profiles: Mapping[str, GraphProfile], profile_ids: Sequence[str], field_name: str) -> List[str]:
    values: List[List[str]] = []
    for profile_id in profile_ids:
        profile = graph_profiles[profile_id]
        values.append(list(getattr(profile, field_name)))
    return _ordered_union(values)


def _ordered_union(values: Sequence[Sequence[str]]) -> List[str]:
    result: List[str] = []
    seen = set()
    for group in values:
        for item in group:
            if item in seen:
                continue
            seen.add(item)
            result.append(item)
    return result
