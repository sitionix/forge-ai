from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Mapping, Optional


EXTRACTOR_MODE_REQUIRED_OR_FILE_ANCHOR_FALLBACK = "required_or_file_anchor_fallback"
EXTRACTOR_MODE_FILE_ANCHOR_PLUS_OPTIONAL_LIGHT_STRUCTURE = "file_anchor_plus_optional_light_structure"
EXTRACTOR_MODE_FILE_ANCHOR_ONLY = "file_anchor_only"

ALLOWED_EXTRACTOR_MODES = (
    EXTRACTOR_MODE_REQUIRED_OR_FILE_ANCHOR_FALLBACK,
    EXTRACTOR_MODE_FILE_ANCHOR_PLUS_OPTIONAL_LIGHT_STRUCTURE,
    EXTRACTOR_MODE_FILE_ANCHOR_ONLY,
)

LLM_MODE_ENRICH_EXISTING_ANCHORS = "enrich_existing_anchors"
LLM_MODE_ANALYZE_TEXT_AND_PROPOSE_GROUNDED_FACTS = "analyze_text_and_propose_grounded_facts"
LLM_MODE_NONE = "none"

ALLOWED_LLM_MODES = (
    LLM_MODE_ENRICH_EXISTING_ANCHORS,
    LLM_MODE_ANALYZE_TEXT_AND_PROPOSE_GROUNDED_FACTS,
    LLM_MODE_NONE,
)

EXTRACTOR_MODES_ALLOWING_FILE_ANCHOR_FALLBACK = (
    EXTRACTOR_MODE_REQUIRED_OR_FILE_ANCHOR_FALLBACK,
    EXTRACTOR_MODE_FILE_ANCHOR_PLUS_OPTIONAL_LIGHT_STRUCTURE,
)

LLM_MODES_REQUIRING_PROVIDER = (
    LLM_MODE_ENRICH_EXISTING_ANCHORS,
    LLM_MODE_ANALYZE_TEXT_AND_PROPOSE_GROUNDED_FACTS,
)


def policy_allows_extractor_fallback(mode: Optional[str]) -> bool:
    _require_known_mode(mode, ALLOWED_EXTRACTOR_MODES, "extractorMode")
    return str(mode) in EXTRACTOR_MODES_ALLOWING_FILE_ANCHOR_FALLBACK


def policy_requires_llm(mode: Optional[str]) -> bool:
    _require_known_mode(mode, ALLOWED_LLM_MODES, "llmMode")
    return str(mode) in LLM_MODES_REQUIRING_PROVIDER


def _require_known_mode(mode: Optional[str], allowed: tuple[str, ...], label: str) -> None:
    if mode not in allowed:
        raise ValueError(f"{label} must be one of: {', '.join(allowed)}")


@dataclass(frozen=True)
class AnalysisPolicyDiagnostic:
    path: str
    reason: str
    invalid_value: Optional[Any] = None
    allowed_values: Optional[List[str]] = None
    preview: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        payload: Dict[str, Any] = {
            "path": self.path,
            "reason": self.reason,
        }
        if self.invalid_value is not None:
            payload["invalidValue"] = self.invalid_value
        if self.allowed_values is not None:
            payload["allowedValues"] = self.allowed_values
        if self.preview is not None:
            payload["preview"] = self.preview
        return payload


class AnalysisPolicyError(Exception):
    def __init__(self, diagnostics: List[AnalysisPolicyDiagnostic], message: Optional[str] = None):
        self.diagnostics = diagnostics
        self.message = message or _format_error_message(diagnostics)
        super().__init__(self.message)


@dataclass(frozen=True)
class PromptDefinition:
    id: str
    file: str
    response_shape: str


@dataclass(frozen=True)
class AnalysisPolicyDefaults:
    max_file_chars: int
    canonical_source_view: str
    default_policy: str
    default_graph_profiles: List[str]
    evidence_policy: str


@dataclass(frozen=True)
class GraphNodeDefinition:
    kind: str
    identity: str
    semantic_eligible: bool


@dataclass(frozen=True)
class GraphEdgeDefinition:
    kind: str
    from_kinds: List[str]
    to_kinds: List[str]
    semantic_eligible: bool


@dataclass(frozen=True)
class GraphClaimDefinition:
    kind: str
    evidence_required: bool
    material_support_required: bool
    semantic_eligible: bool


@dataclass(frozen=True)
class GraphContract:
    nodes: Dict[str, GraphNodeDefinition]
    edges: Dict[str, GraphEdgeDefinition]
    claims: Dict[str, GraphClaimDefinition]
    statuses: Dict[str, Mapping[str, Any]]
    origins: Dict[str, Mapping[str, Any]]
    evidence_kinds: Dict[str, Mapping[str, Any]]
    resolution_statuses: Dict[str, Mapping[str, Any]]


@dataclass(frozen=True)
class SemanticPolicy:
    indexed_node_kinds: List[str]
    indexed_edge_kinds: List[str]
    indexed_claim_kinds: List[str]
    unsupported_semantic_kind: str


@dataclass(frozen=True)
class ArtifactClassifier:
    id: str
    detection: Mapping[str, Any]
    adds_graph_profiles: List[str] = field(default_factory=list)


@dataclass(frozen=True)
class FormatPolicy:
    id: str
    extensions: List[str]
    family: str
    extractor: str
    policy: str
    prompt: str
    graph_profiles: List[str]
    artifact_classifiers: List[ArtifactClassifier] = field(default_factory=list)


@dataclass(frozen=True)
class AnalyzerExecutionPolicy:
    id: str
    source_view: str
    extractor_mode: str
    llm_mode: str
    response_schema: str
    evidence_required: bool
    allow_llm_created_anchors: bool = False
    trust_llm_created_anchors: bool = False


@dataclass(frozen=True)
class GraphProfile:
    id: str
    nodes: List[str]
    edges: List[str]
    claims: List[str]


@dataclass(frozen=True)
class ExtractorProduces:
    nodes: List[str] = field(default_factory=list)
    edges: List[str] = field(default_factory=list)
    claims: List[str] = field(default_factory=list)


@dataclass(frozen=True)
class ExtractorDefinition:
    id: str
    implementation: str
    trust: str
    produces: ExtractorProduces


@dataclass(frozen=True)
class AnalysisPolicy:
    schema_version: int
    source_path: Path
    prompt_root: Path
    defaults: AnalysisPolicyDefaults
    prompts: Dict[str, PromptDefinition]
    graph: GraphContract
    semantic: SemanticPolicy
    formats: Dict[str, FormatPolicy]
    policies: Dict[str, AnalyzerExecutionPolicy]
    graph_profiles: Dict[str, GraphProfile]
    extractors: Dict[str, ExtractorDefinition]
    unsupported: Dict[str, str]
    extension_to_format: Dict[str, str]

    def prompt_path(self, prompt_id: str) -> Path:
        prompt = self.prompts[prompt_id]
        return (self.prompt_root / prompt.file).resolve()

    def prompt_response_shape_path(self, prompt_id: str) -> Path:
        prompt = self.prompts[prompt_id]
        return (self.prompt_root / prompt.response_shape).resolve()


def _format_error_message(diagnostics: List[AnalysisPolicyDiagnostic]) -> str:
    if not diagnostics:
        return "Analysis policy is invalid"
    first = diagnostics[0]
    suffix = "" if len(diagnostics) == 1 else f" (+{len(diagnostics) - 1} more)"
    return f"Analysis policy is invalid at {first.path}: {first.reason}{suffix}"
