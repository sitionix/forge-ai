from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Mapping, Optional

from knowledge_service.analysis_graph_contract import AnalysisGraphContract, ResolutionStatusContract, contract_payload
from knowledge_service.analysis_parse_failure import GraphAnalysisParseFailure
from knowledge_service.analysis_policy import AnalysisPolicy
from knowledge_service.analysis_policy_loader import load_analysis_policy
from knowledge_service.errors import KnowledgeError
from knowledge_service.graph_schema import GraphAnalysisResult, GraphClaim, GraphEdge, GraphEvidenceRef, GraphNode


TARGET_INPUT_SCHEMA_VERSION = "knowledge.graph.enrichment.input.v2"
TARGET_RESPONSE_SCHEMA_VERSION = "knowledge.graph.enrichment.response.v2"
TARGET_REQUEST_KIND = "TARGET_ANCHOR_ENRICHMENT"

BEGIN_INPUT_MARKER = "BEGIN_LLM_INPUT_JSON"
END_INPUT_MARKER = "END_LLM_INPUT_JSON"
_INPUT_JSON_PLACEHOLDER = "{{LLM_INPUT_JSON}}"
_REPAIR_INSTRUCTIONS_PLACEHOLDER = "{{REPAIR_INSTRUCTIONS}}"
_TARGET_RESPONSE_SHAPE_PLACEHOLDER = "{{TARGET_RESPONSE_SHAPE}}"

_DEFAULT_REF_PREFIXES = {
    "FILE": "F",
    "TYPE": "T",
    "CALLABLE": "M",
    "FIELD": "FIELD",
}


@dataclass(frozen=True)
class AnchorRegistryEntry:
    ref: str
    stable_key: str
    kind: str
    name: str
    qualified_name: Optional[str]
    line_start: Optional[int]
    line_end: Optional[int]
    parent_ref: Optional[str]
    signature: Optional[str] = None
    return_type: Optional[str] = None
    type_name: Optional[str] = None
    annotations: tuple[dict[str, Any], ...] = field(default_factory=tuple)

    def to_llm_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "ref": self.ref,
            "kind": self.kind,
            "name": self.name,
            "qualifiedName": self.qualified_name,
            "lineStart": self.line_start,
            "lineEnd": self.line_end,
            "parentRef": self.parent_ref,
        }
        if self.signature:
            payload["signature"] = self.signature
        if self.return_type:
            payload["returnType"] = self.return_type
        if self.type_name:
            payload["typeName"] = self.type_name
        if self.annotations:
            payload["annotations"] = [dict(item) for item in self.annotations]
        return payload


@dataclass(frozen=True)
class AnchorRefRegistry:
    entries: tuple[AnchorRegistryEntry, ...]
    ref_to_stable_key: Mapping[str, str]
    stable_key_to_ref: Mapping[str, str]
    ref_to_kind: Mapping[str, str]

    @classmethod
    def build(cls, static_graph: GraphAnalysisResult, contract: AnalysisGraphContract) -> "AnchorRefRegistry":
        prefix_registry = _prefix_registry(contract.allowed_node_kinds)
        counters: dict[str, int] = {}
        refs_by_stable_key: dict[str, str] = {}
        sorted_nodes = _sorted_anchor_nodes(static_graph.nodes)
        for node in sorted_nodes:
            prefix = prefix_registry.get(node.nodeKind) or _generic_prefix(node.nodeKind)
            counters[prefix] = counters.get(prefix, 0) + 1
            refs_by_stable_key[node.localId] = f"{prefix}{counters[prefix]}"

        entries: list[AnchorRegistryEntry] = []
        for node in sorted_nodes:
            ref = refs_by_stable_key[node.localId]
            metadata = node.metadata or {}
            entries.append(
                AnchorRegistryEntry(
                    ref=ref,
                    stable_key=node.localId,
                    kind=node.nodeKind,
                    name=node.name,
                    qualified_name=node.qualifiedName,
                    line_start=node.lineStart,
                    line_end=node.lineEnd,
                    parent_ref=refs_by_stable_key.get(node.parentLocalId or ""),
                    signature=_bounded_string(metadata.get("signature"), 300),
                    return_type=_bounded_string(metadata.get("returnType"), 160),
                    type_name=_bounded_string(metadata.get("typeName"), 160),
                    annotations=tuple(_annotation_payloads(metadata.get("annotations"))),
                )
            )
        return cls(
            entries=tuple(entries),
            ref_to_stable_key={entry.ref: entry.stable_key for entry in entries},
            stable_key_to_ref={entry.stable_key: entry.ref for entry in entries},
            ref_to_kind={entry.ref: entry.kind for entry in entries},
        )

    def to_llm_list(self) -> list[dict[str, Any]]:
        return [entry.to_llm_dict() for entry in self.entries]

    def entry_for_ref(self, ref: str) -> AnchorRegistryEntry:
        for entry in self.entries:
            if entry.ref == ref:
                return entry
        raise KeyError(ref)


@dataclass(frozen=True)
class PlannedTargetAnchor:
    ref: str
    stable_key: str
    kind: str


@dataclass(frozen=True)
class LlmEnrichmentPlan:
    registry: AnchorRefRegistry
    targets: tuple[PlannedTargetAnchor, ...]


class LlmEnrichmentPlanner:
    def plan(
        self,
        static_graph: GraphAnalysisResult,
        contract: AnalysisGraphContract,
        *,
        max_target_calls: int,
        source_id: Optional[str] = None,
        relative_path: Optional[str] = None,
    ) -> LlmEnrichmentPlan:
        registry = AnchorRefRegistry.build(static_graph, contract)
        eligible_kinds = set(contract.semantic_node_kinds)
        targets = tuple(
            PlannedTargetAnchor(entry.ref, entry.stable_key, entry.kind)
            for entry in registry.entries
            if entry.kind in eligible_kinds
        )
        if not targets:
            raise KnowledgeError(
                "ANALYSIS_TARGET_PLANNING_EMPTY",
                "No semantically eligible target anchors were available for LLM enrichment.",
                stage="LLM_ENRICHMENT",
                severity="ERROR",
                allowedNodeKinds=list(contract.allowed_node_kinds),
                semanticNodeKinds=list(contract.semantic_node_kinds),
            )
        if max_target_calls < 1 or len(targets) > max_target_calls:
            raise KnowledgeError(
                "ANALYSIS_TARGET_PLAN_TOO_LARGE",
                "Target-anchor enrichment plan exceeds the configured maximum target calls per file.",
                stage="LLM_ENRICHMENT",
                severity="ERROR",
                sourceId=source_id,
                relativePath=relative_path,
                targetCount=len(targets),
                maxTargetCalls=max_target_calls,
                semanticNodeKinds=list(contract.semantic_node_kinds),
            )
        return LlmEnrichmentPlan(registry=registry, targets=targets)


class LlmEnrichmentInputBuilder:
    def __init__(
        self,
        policy: Optional[AnalysisPolicy] = None,
        policy_path: Optional[str | Path] = None,
        response_shape: Optional[Mapping[str, Any]] = None,
    ):
        self._response_shape = _json_copy(response_shape) if response_shape is not None else None
        self._response_shape_loader = None if response_shape is not None else TargetPromptRenderer(policy=policy, policy_path=policy_path)

    def build(
        self,
        *,
        context: Any,
        registry: AnchorRefRegistry,
        target: PlannedTargetAnchor,
        budget_chars: int,
    ) -> dict[str, Any]:
        target_entry = registry.entry_for_ref(target.ref)
        llm_input = {
            "schemaVersion": TARGET_INPUT_SCHEMA_VERSION,
            "requestKind": TARGET_REQUEST_KIND,
            "file": {
                "sourceId": context.row.get("source_id"),
                "relativePath": context.row.get("relative_path"),
                "language": self._language(context),
                "format": context.policy_resolution.format_id,
                "lineCount": context.line_count,
                "contentLines": [{"line": index, "text": line} for index, line in enumerate(context.content_lines, start=1)],
            },
            "anchorRegistry": registry.to_llm_list(),
            "targetAnchor": target_entry.to_llm_dict(),
            "allowedValues": {
                "claimKind": list(context.graph_contract.allowed_claim_kinds),
                "edgeType": list(context.graph_contract.allowed_edge_types),
                "resolutionStatus": list(context.graph_contract.allowed_resolution_statuses),
            },
            "endpointRules": {
                edge_type: {
                    "fromKinds": list(context.graph_contract.edge_from_kinds.get(edge_type, ())),
                    "toKinds": list(context.graph_contract.edge_to_kinds.get(edge_type, ())),
                }
                for edge_type in context.graph_contract.allowed_edge_types
            },
            "responseShape": self.response_shape(contract=context.graph_contract),
        }
        return {
            "sourceId": context.row.get("source_id"),
            "relativePath": context.row.get("relative_path"),
            "targetRef": target.ref,
            "targetKind": target.kind,
            "requestKind": TARGET_REQUEST_KIND,
            "schemaVersion": TARGET_INPUT_SCHEMA_VERSION,
            "budgetChars": int(budget_chars),
            "analysisPolicy": contract_payload(context.graph_contract),
            "llmInput": llm_input,
            "_refToStableKey": dict(registry.ref_to_stable_key),
            "_stableKeyToRef": dict(registry.stable_key_to_ref),
            "_refToKind": dict(registry.ref_to_kind),
        }

    def response_shape(
        self,
        *,
        payload: Optional[Mapping[str, Any]] = None,
        contract: Optional[AnalysisGraphContract] = None,
    ) -> dict[str, Any]:
        if self._response_shape is not None:
            return _json_copy(self._response_shape)
        if self._response_shape_loader is None:
            raise KnowledgeError(
                "ANALYSIS_POLICY_RESPONSE_SHAPE_REQUIRED",
                "Target response shape loader is not configured.",
                stage="LLM_ENRICHMENT",
                severity="ERROR",
            )
        return self._response_shape_loader.response_shape(payload=payload, contract=contract)

    def _language(self, context: Any) -> Optional[str]:
        language = str(context.row.get("language") or "").strip().lower()
        if language and language != "unknown":
            return language
        return context.policy_resolution.family or context.policy_resolution.format_id


class TargetPromptRenderer:
    def __init__(
        self,
        policy: Optional[AnalysisPolicy] = None,
        policy_path: Optional[str | Path] = None,
        default_prompt_id: Optional[str] = None,
    ):
        self.policy = policy or load_analysis_policy(policy_path)
        self.default_prompt_id = default_prompt_id
        self._template_cache: dict[str, str] = {}
        self._response_shape_cache: dict[str, dict[str, Any]] = {}

    def render(
        self,
        payload: Mapping[str, Any],
        repair_prompt: Optional[str] = None,
        *,
        contract: Optional[AnalysisGraphContract] = None,
    ) -> str:
        llm_input = payload.get("llmInput")
        if not isinstance(llm_input, Mapping):
            raise KnowledgeError(
                "ANALYSIS_TARGET_INPUT_REQUIRED",
                "Analyzer provider requires a target-anchor LLM input payload.",
                stage="LLM_ENRICHMENT",
                severity="ERROR",
                relativePath=str(payload.get("relativePath") or ""),
            )
        prompt_id = self._prompt_id(payload=payload, contract=contract)
        return (
            self._template(prompt_id)
            .replace(_INPUT_JSON_PLACEHOLDER, self._input_json_block(llm_input))
            .replace(_REPAIR_INSTRUCTIONS_PLACEHOLDER, str(repair_prompt or ""))
            .replace(_TARGET_RESPONSE_SHAPE_PLACEHOLDER, self._response_shape_text(prompt_id))
            .strip()
        )

    def estimate_prompt_chars(
        self,
        payload: Mapping[str, Any],
        repair_prompt: Optional[str] = None,
        *,
        contract: Optional[AnalysisGraphContract] = None,
    ) -> int:
        return len(self.render(payload, repair_prompt, contract=contract))

    def ensure_within_budget(
        self,
        payload: Mapping[str, Any],
        budget_chars: int,
        repair_prompt: Optional[str] = None,
        *,
        contract: Optional[AnalysisGraphContract] = None,
    ) -> int:
        rendered_prompt_chars = self.estimate_prompt_chars(payload, repair_prompt, contract=contract)
        if rendered_prompt_chars > budget_chars:
            raise KnowledgeError(
                "ANALYSIS_LLM_TARGET_INPUT_TOO_LARGE",
                "Rendered target-anchor LLM prompt exceeds the configured analysis budget; no fallback prompt was used.",
                stage="LLM_ENRICHMENT",
                severity="ERROR",
                sourceId=payload.get("sourceId"),
                relativePath=payload.get("relativePath"),
                targetRef=payload.get("targetRef"),
                targetKind=payload.get("targetKind"),
                budgetChars=budget_chars,
                renderedPromptChars=rendered_prompt_chars,
            )
        return rendered_prompt_chars

    def response_shape(
        self,
        *,
        payload: Optional[Mapping[str, Any]] = None,
        contract: Optional[AnalysisGraphContract] = None,
    ) -> dict[str, Any]:
        prompt_id = self._prompt_id(payload=payload, contract=contract)
        return self._response_shape_for_prompt_id(prompt_id)

    def _response_shape_for_prompt_id(self, prompt_id: str) -> dict[str, Any]:
        if prompt_id in self._response_shape_cache:
            return _json_copy(self._response_shape_cache[prompt_id])
        path = self._response_shape_path(prompt_id)
        text = path.read_text(encoding="utf-8").strip()
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError as exc:
            raise KnowledgeError(
                "ANALYSIS_POLICY_RESPONSE_SHAPE_INVALID_JSON",
                f"Analysis policy response shape file is invalid JSON: {path}",
                promptId=prompt_id,
                responseShapePath=str(path),
                jsonError=str(exc),
            ) from exc
        if not isinstance(parsed, dict):
            raise KnowledgeError(
                "ANALYSIS_POLICY_RESPONSE_SHAPE_INVALID_JSON",
                f"Analysis policy response shape file must contain a JSON object: {path}",
                promptId=prompt_id,
                responseShapePath=str(path),
                actualType=type(parsed).__name__,
            )
        self._response_shape_cache[prompt_id] = _json_copy(parsed)
        return _json_copy(parsed)

    def _input_json_block(self, llm_input: Mapping[str, Any]) -> str:
        return "\n".join(
            [
                BEGIN_INPUT_MARKER,
                json.dumps(dict(llm_input), ensure_ascii=False, indent=2, sort_keys=True),
                END_INPUT_MARKER,
            ]
        )

    def _response_shape_text(self, prompt_id: str) -> str:
        return json.dumps(self._response_shape_for_prompt_id(prompt_id), ensure_ascii=False, indent=2, sort_keys=True)

    def _template(self, prompt_id: str) -> str:
        if prompt_id in self._template_cache:
            return self._template_cache[prompt_id]
        if prompt_id not in self.policy.prompts:
            raise KnowledgeError(
                "ANALYSIS_POLICY_PROMPT_MISSING",
                f"Analysis policy target prompt id is not declared: {prompt_id}",
                promptId=prompt_id,
            )
        path = self.policy.prompt_path(prompt_id)
        if not path.exists():
            raise KnowledgeError(
                "ANALYSIS_POLICY_PROMPT_FILE_MISSING",
                f"Analysis policy target prompt file does not exist: {path}",
                promptId=prompt_id,
                promptPath=str(path),
            )
        template = path.read_text(encoding="utf-8")
        missing = [
            placeholder
            for placeholder in (
                _INPUT_JSON_PLACEHOLDER,
                _REPAIR_INSTRUCTIONS_PLACEHOLDER,
                _TARGET_RESPONSE_SHAPE_PLACEHOLDER,
            )
            if placeholder not in template
        ]
        if missing:
            raise KnowledgeError(
                "ANALYSIS_POLICY_PROMPT_TEMPLATE_INVALID",
                "Analysis policy target prompt template is missing required placeholders.",
                promptId=prompt_id,
                promptPath=str(path),
                missingPlaceholders=missing,
            )
        self._template_cache[prompt_id] = template
        return template

    def _response_shape_path(self, prompt_id: str) -> Path:
        if prompt_id not in self.policy.prompts:
            raise KnowledgeError(
                "ANALYSIS_POLICY_PROMPT_MISSING",
                f"Analysis policy target prompt id is not declared: {prompt_id}",
                promptId=prompt_id,
            )
        path = self.policy.prompt_response_shape_path(prompt_id)
        if not path.exists():
            raise KnowledgeError(
                "ANALYSIS_POLICY_RESPONSE_SHAPE_FILE_MISSING",
                f"Analysis policy target response shape file does not exist: {path}",
                promptId=prompt_id,
                responseShapePath=str(path),
            )
        return path

    def _prompt_id(
        self,
        *,
        payload: Optional[Mapping[str, Any]] = None,
        contract: Optional[AnalysisGraphContract] = None,
    ) -> str:
        raw_policy = payload.get("analysisPolicy") if isinstance(payload, Mapping) else None
        if isinstance(raw_policy, Mapping):
            raw_prompt_id = raw_policy.get("promptId")
            if isinstance(raw_prompt_id, str) and raw_prompt_id.strip():
                return raw_prompt_id.strip()
        if contract is not None and isinstance(contract.prompt_id, str) and contract.prompt_id.strip():
            return contract.prompt_id.strip()
        if isinstance(self.default_prompt_id, str) and self.default_prompt_id.strip():
            return self.default_prompt_id.strip()
        raise KnowledgeError(
            "ANALYSIS_POLICY_PROMPT_REQUIRED",
            "Target-anchor prompt id is required in the resolved analysis policy contract.",
            stage="LLM_ENRICHMENT",
            severity="ERROR",
            relativePath=str(payload.get("relativePath") or "") if isinstance(payload, Mapping) else "",
        )


class TargetResponseParserValidator:
    _TOP_LEVEL_FIELDS = {"schemaVersion", "claims", "semanticEdges", "diagnostics"}
    _CLAIM_FIELDS = {"localId", "targetRef", "claimKind", "summary", "confidence", "evidence"}
    _EDGE_FIELDS = {"localId", "fromRef", "toRef", "edgeType", "resolutionStatus", "confidence", "evidence", "unresolvedTarget"}
    _EVIDENCE_FIELDS = {"lineStart", "lineEnd", "text"}
    _DIAGNOSTIC_FIELDS = {"code", "stage", "severity", "message"}
    _DIAGNOSTIC_SEVERITIES = {"INFO", "WARN", "ERROR"}

    def parse(
        self,
        raw: str,
        *,
        payload: Mapping[str, Any],
        line_count: int,
        contract: AnalysisGraphContract,
    ) -> GraphAnalysisResult | GraphAnalysisParseFailure:
        parsed, load_error = self._load(raw)
        if load_error is not None:
            return load_error
        if not isinstance(parsed, dict):
            return self._failure(raw, self._schema_error("$", "Response must be one JSON object.", actual=type(parsed).__name__, expected="object"))
        llm_input = payload.get("llmInput")
        if not isinstance(llm_input, Mapping):
            return self._failure(raw, self._schema_error("$", "Target input context is missing.", expected="llmInput"))
        target_anchor = llm_input.get("targetAnchor")
        if not isinstance(target_anchor, Mapping):
            return self._failure(raw, self._schema_error("$.targetAnchor", "Target input anchor is missing.", expected="targetAnchor"))
        target_ref = str(target_anchor.get("ref") or "")
        known_refs = self._known_refs(llm_input)
        ref_to_stable_key = {str(key): str(value) for key, value in (payload.get("_refToStableKey") or {}).items()}
        ref_to_kind = {str(key): str(value) for key, value in (payload.get("_refToKind") or {}).items()}
        details: list[dict[str, Any]] = []

        self._validate_object_fields(parsed, "$", self._TOP_LEVEL_FIELDS, details)
        if parsed.get("schemaVersion") != TARGET_RESPONSE_SCHEMA_VERSION:
            details.append(
                self._schema_error(
                    "$.schemaVersion",
                    "schemaVersion must match the target-anchor response contract.",
                    actual=parsed.get("schemaVersion"),
                    expected=TARGET_RESPONSE_SCHEMA_VERSION,
                )
            )
        claims = parsed.get("claims")
        edges = parsed.get("semanticEdges")
        diagnostics = parsed.get("diagnostics")
        if not isinstance(claims, list):
            details.append(self._schema_error("$.claims", "claims must be an array.", actual=claims, expected="array"))
            claims = []
        if not isinstance(edges, list):
            details.append(self._schema_error("$.semanticEdges", "semanticEdges must be an array.", actual=edges, expected="array"))
            edges = []
        if not isinstance(diagnostics, list):
            details.append(self._schema_error("$.diagnostics", "diagnostics must be an array.", actual=diagnostics, expected="array"))
            diagnostics = []

        for index, item in enumerate(claims):
            self._validate_claim(item, index, target_ref, known_refs, contract, line_count, details)
        for index, item in enumerate(edges):
            self._validate_edge(item, index, target_ref, known_refs, ref_to_kind, contract, line_count, details)
        for index, item in enumerate(diagnostics):
            self._validate_diagnostic(item, index, details)

        if details:
            return self._failure(raw, *details)
        try:
            return self._to_graph_result(
                parsed,
                target_ref=target_ref,
                ref_to_stable_key=ref_to_stable_key,
            )
        except (TypeError, ValueError) as exc:
            return self._failure(raw, self._schema_error("$", str(exc), expected="valid graph result"))

    def _validate_claim(
        self,
        item: Any,
        index: int,
        target_ref: str,
        known_refs: set[str],
        contract: AnalysisGraphContract,
        line_count: int,
        details: list[dict[str, Any]],
    ) -> None:
        path = f"$.claims[{index}]"
        if not isinstance(item, dict):
            details.append(self._schema_error(path, "claim must be an object.", actual=type(item).__name__, expected="object"))
            return
        self._validate_object_fields(item, path, self._CLAIM_FIELDS, details)
        self._required_string(item, path, "localId", details)
        target = self._required_string(item, path, "targetRef", details)
        if target and target not in known_refs:
            details.append(self._schema_error(f"{path}.targetRef", "targetRef is not in anchorRegistry.", actual=target, expected="known ref"))
        if target and target != target_ref:
            details.append(self._schema_error(f"{path}.targetRef", "claim targetRef must equal targetAnchor.ref.", actual=target, expected=target_ref))
        claim_kind = self._required_string(item, path, "claimKind", details)
        if claim_kind and claim_kind not in contract.allowed_claim_kinds:
            details.append(
                self._schema_error(
                    f"{path}.claimKind",
                    "claimKind is not allowed by the effective analysis graph policy.",
                    actual=claim_kind,
                    expected="allowed claim kind",
                    allowed_values=list(contract.allowed_claim_kinds),
                )
            )
        self._required_string(item, path, "summary", details)
        self._confidence(item, path, details)
        self._evidence_list(item.get("evidence"), f"{path}.evidence", line_count, details)

    def _validate_edge(
        self,
        item: Any,
        index: int,
        target_ref: str,
        known_refs: set[str],
        ref_to_kind: Mapping[str, str],
        contract: AnalysisGraphContract,
        line_count: int,
        details: list[dict[str, Any]],
    ) -> None:
        path = f"$.semanticEdges[{index}]"
        if not isinstance(item, dict):
            details.append(self._schema_error(path, "semantic edge must be an object.", actual=type(item).__name__, expected="object"))
            return
        self._validate_object_fields(item, path, self._EDGE_FIELDS, details)
        self._required_string(item, path, "localId", details)
        from_ref = self._required_string(item, path, "fromRef", details)
        if from_ref and from_ref not in known_refs:
            details.append(self._schema_error(f"{path}.fromRef", "fromRef is not in anchorRegistry.", actual=from_ref, expected="known ref"))
        if from_ref and from_ref != target_ref:
            details.append(self._schema_error(f"{path}.fromRef", "semantic edge fromRef must equal targetAnchor.ref.", actual=from_ref, expected=target_ref))
        to_ref = item.get("toRef")
        if to_ref is not None and (not isinstance(to_ref, str) or not to_ref.strip()):
            details.append(self._schema_error(f"{path}.toRef", "toRef must be a known ref or null.", actual=to_ref, expected="known ref or null"))
        elif isinstance(to_ref, str) and to_ref not in known_refs:
            details.append(self._schema_error(f"{path}.toRef", "toRef is not in anchorRegistry.", actual=to_ref, expected="known ref or null"))
        edge_type = self._required_string(item, path, "edgeType", details)
        if edge_type and edge_type not in contract.allowed_edge_types:
            details.append(
                self._schema_error(
                    f"{path}.edgeType",
                    "edgeType is not allowed by the effective analysis graph policy.",
                    actual=edge_type,
                    expected="allowed edge type",
                    allowed_values=list(contract.allowed_edge_types),
                )
            )
        elif edge_type:
            self._edge_endpoint(edge_type, from_ref, to_ref, ref_to_kind, contract, path, details)
        resolution_status = self._required_string(item, path, "resolutionStatus", details)
        if resolution_status and resolution_status not in contract.allowed_resolution_statuses:
            details.append(
                self._schema_error(
                    f"{path}.resolutionStatus",
                    "resolutionStatus is not allowed by the effective analysis graph policy.",
                    actual=resolution_status,
                    expected="allowed resolution status",
                    allowed_values=list(contract.allowed_resolution_statuses),
                )
            )
        elif resolution_status:
            self._edge_resolution_status(item, path, resolution_status, ResolutionStatusContract.from_graph_contract(contract), details)
        self._confidence(item, path, details)
        self._evidence_list(item.get("evidence"), f"{path}.evidence", line_count, details)

    def _validate_diagnostic(self, item: Any, index: int, details: list[dict[str, Any]]) -> None:
        path = f"$.diagnostics[{index}]"
        if not isinstance(item, dict):
            details.append(self._schema_error(path, "diagnostic must be an object.", actual=type(item).__name__, expected="object"))
            return
        self._validate_object_fields(item, path, self._DIAGNOSTIC_FIELDS, details)
        self._required_string(item, path, "code", details)
        stage = self._required_string(item, path, "stage", details)
        if stage and stage != "LLM_ENRICHMENT":
            details.append(self._schema_error(f"{path}.stage", "diagnostic stage must be LLM_ENRICHMENT.", actual=stage, expected="LLM_ENRICHMENT"))
        severity = self._required_string(item, path, "severity", details)
        if severity and severity not in self._DIAGNOSTIC_SEVERITIES:
            details.append(
                self._schema_error(
                    f"{path}.severity",
                    "diagnostic severity is not allowed.",
                    actual=severity,
                    expected="diagnostic severity",
                    allowed_values=sorted(self._DIAGNOSTIC_SEVERITIES),
                )
            )
        self._required_string(item, path, "message", details)

    def _edge_endpoint(
        self,
        edge_type: str,
        from_ref: Optional[str],
        to_ref: Any,
        ref_to_kind: Mapping[str, str],
        contract: AnalysisGraphContract,
        path: str,
        details: list[dict[str, Any]],
    ) -> None:
        from_kind = ref_to_kind.get(str(from_ref)) if from_ref else None
        to_kind = ref_to_kind.get(str(to_ref)) if to_ref else None
        allowed_from = list(contract.edge_from_kinds.get(edge_type, ()))
        allowed_to = list(contract.edge_to_kinds.get(edge_type, ()))
        if from_kind is not None and allowed_from and from_kind not in allowed_from:
            details.append(
                self._schema_error(
                    f"{path}.fromRef",
                    "edge source anchor kind violates endpoint rules.",
                    actual=from_kind,
                    expected=f"{edge_type} source kind",
                    allowed_values=allowed_from,
                )
            )
        if to_kind is not None and allowed_to and to_kind not in allowed_to:
            details.append(
                self._schema_error(
                    f"{path}.toRef",
                    "edge target anchor kind violates endpoint rules.",
                    actual=to_kind,
                    expected=f"{edge_type} target kind",
                    allowed_values=allowed_to,
                )
            )
        if to_ref is not None and not allowed_to:
            details.append(
                self._schema_error(
                    f"{path}.toRef",
                    "edge target is not allowed by endpoint rules for this edge type.",
                    actual=to_ref,
                    expected=f"{edge_type} target kind",
                    allowed_values=[],
                )
            )

    def _edge_resolution_status(
        self,
        item: Mapping[str, Any],
        path: str,
        status: str,
        rules: ResolutionStatusContract,
        details: list[dict[str, Any]],
    ) -> None:
        to_ref = item.get("toRef")
        unresolved_target = item.get("unresolvedTarget")
        if rules.requires_resolved_target(status) and not to_ref:
            details.append(self._schema_error(f"{path}.resolutionStatus", "edge resolution requires toRef.", actual=status, expected="toRef"))
        if rules.forbids_resolved_target(status) and to_ref:
            details.append(self._schema_error(f"{path}.resolutionStatus", "edge resolution must not have toRef.", actual=status, expected="null toRef"))
        if rules.requires_unresolved_target(status) and not isinstance(unresolved_target, dict):
            details.append(
                self._schema_error(
                    f"{path}.unresolvedTarget",
                    "edge resolution requires unresolvedTarget.",
                    actual=unresolved_target,
                    expected="object",
                )
            )
        if unresolved_target is not None and not rules.allows_unresolved_target(status):
            details.append(self._schema_error(f"{path}.unresolvedTarget", "edge resolution must not include unresolvedTarget.", actual=unresolved_target, expected="null"))
        if to_ref is None and unresolved_target is not None and not isinstance(unresolved_target, dict):
            details.append(self._schema_error(f"{path}.unresolvedTarget", "unresolvedTarget must be an object or null.", actual=unresolved_target, expected="object or null"))

    def _evidence_list(self, value: Any, path: str, line_count: int, details: list[dict[str, Any]]) -> None:
        if not isinstance(value, list) or not value:
            details.append(self._schema_error(path, "evidence must be a non-empty array.", actual=value, expected="non-empty array"))
            return
        for index, item in enumerate(value):
            evidence_path = f"{path}[{index}]"
            if not isinstance(item, dict):
                details.append(self._schema_error(evidence_path, "evidence must be an object.", actual=type(item).__name__, expected="object"))
                continue
            self._validate_object_fields(item, evidence_path, self._EVIDENCE_FIELDS, details)
            line_start = self._required_int(item, evidence_path, "lineStart", details)
            line_end = self._required_int(item, evidence_path, "lineEnd", details)
            if line_start is not None and line_end is not None and (line_start < 1 or line_end < line_start or line_end > max(line_count, 1)):
                details.append(self._graph_error(evidence_path, "Evidence line range outside file."))
            text = item.get("text")
            if text is not None and not isinstance(text, str):
                details.append(self._schema_error(f"{evidence_path}.text", "evidence text must be a string.", actual=text, expected="string"))
            if isinstance(text, str) and len(text) > 2000:
                details.append(self._schema_error(f"{evidence_path}.text", "evidence text is too long.", actual=len(text), expected="2000 chars or fewer"))

    def _to_graph_result(
        self,
        parsed: Mapping[str, Any],
        *,
        target_ref: str,
        ref_to_stable_key: Mapping[str, str],
    ) -> GraphAnalysisResult:
        claims: list[GraphClaim] = []
        for item in parsed.get("claims") or []:
            stable_key = ref_to_stable_key[str(item["targetRef"])]
            claims.append(
                GraphClaim(
                    localId=str(item["localId"]),
                    nodeLocalId=stable_key,
                    claimKind=str(item["claimKind"]),
                    summary=str(item["summary"]),
                    confidence=float(item["confidence"]),
                    evidence=self._evidence_refs(item.get("evidence") or []),
                    metadata={"factOrigin": "LLM"},
                )
            )
        edges: list[GraphEdge] = []
        for item in parsed.get("semanticEdges") or []:
            to_ref = item.get("toRef")
            edges.append(
                GraphEdge(
                    localId=str(item["localId"]),
                    fromNodeLocalId=ref_to_stable_key[str(item["fromRef"] or target_ref)],
                    toNodeLocalId=ref_to_stable_key[str(to_ref)] if to_ref else None,
                    edgeType=str(item["edgeType"]),
                    resolutionStatus=str(item["resolutionStatus"]),
                    confidence=float(item["confidence"]),
                    evidence=self._evidence_refs(item.get("evidence") or []),
                    unresolvedTarget=item.get("unresolvedTarget"),
                    metadata={"factOrigin": "LLM"},
                )
            )
        return GraphAnalysisResult(
            nodes=[],
            edges=edges,
            claims=claims,
            diagnostics=[dict(item) for item in parsed.get("diagnostics") or []],
        )

    def _evidence_refs(self, value: Iterable[Any]) -> list[GraphEvidenceRef]:
        refs: list[GraphEvidenceRef] = []
        for item in value:
            refs.append(GraphEvidenceRef(lineStart=int(item["lineStart"]), lineEnd=int(item["lineEnd"]), text=item.get("text")))
        return refs

    def _known_refs(self, llm_input: Mapping[str, Any]) -> set[str]:
        registry = llm_input.get("anchorRegistry")
        if not isinstance(registry, list):
            return set()
        return {str(item.get("ref")) for item in registry if isinstance(item, Mapping) and item.get("ref")}

    def _load(self, raw: str) -> tuple[Any | None, GraphAnalysisParseFailure | None]:
        if raw is None or not str(raw).strip():
            return None, self._failure("", self._schema_error("$", "AI analyzer returned an empty response.", expected="JSON object"))
        try:
            return json.loads(raw), None
        except json.JSONDecodeError as exc:
            return None, GraphAnalysisParseFailure(
                "ANALYSIS_AI_INVALID_JSON",
                f"AI analyzer returned invalid JSON: JSON parse error at line {exc.lineno} column {exc.colno}: {exc.msg}",
                str(raw)[:4000],
                [
                    {
                        "errorType": "JSON_PARSE_ERROR",
                        "message": exc.msg,
                        "line": exc.lineno,
                        "column": exc.colno,
                        "charPosition": exc.pos,
                        "rawPreview": str(raw)[:800],
                        "responseTruncated": len(str(raw)) > 800,
                    }
                ],
            )

    def _validate_object_fields(self, item: Mapping[str, Any], path: str, allowed: set[str], details: list[dict[str, Any]]) -> None:
        extra = sorted(set(item.keys()) - allowed)
        for field_name in extra:
            details.append(
                self._schema_error(
                    f"{path}.{field_name}",
                    "Unknown field is not allowed by the target-anchor response contract.",
                    actual=field_name,
                    expected="no extra fields",
                )
            )

    def _required_string(self, item: Mapping[str, Any], path: str, field_name: str, details: list[dict[str, Any]]) -> Optional[str]:
        value = item.get(field_name)
        if value is None:
            details.append(
                self._schema_error(
                    f"{path}.{field_name}",
                    f"{field_name} is required.",
                    actual=None,
                    expected="non-empty string",
                    missing_required_field=field_name,
                )
            )
            return None
        if not isinstance(value, str) or not value.strip():
            details.append(self._schema_error(f"{path}.{field_name}", f"{field_name} must be a non-empty string.", actual=value, expected="non-empty string"))
            return None
        return value

    def _required_int(self, item: Mapping[str, Any], path: str, field_name: str, details: list[dict[str, Any]]) -> Optional[int]:
        value = item.get(field_name)
        if value is None:
            details.append(
                self._schema_error(
                    f"{path}.{field_name}",
                    f"{field_name} is required.",
                    actual=None,
                    expected="integer",
                    missing_required_field=field_name,
                )
            )
            return None
        if not isinstance(value, int):
            details.append(self._schema_error(f"{path}.{field_name}", f"{field_name} must be an integer.", actual=value, expected="integer"))
            return None
        return value

    def _confidence(self, item: Mapping[str, Any], path: str, details: list[dict[str, Any]]) -> None:
        value = item.get("confidence")
        if value is None:
            details.append(
                self._schema_error(
                    f"{path}.confidence",
                    "confidence is required.",
                    actual=None,
                    expected="number between 0 and 1",
                    missing_required_field="confidence",
                )
            )
            return
        if not isinstance(value, (int, float)) or isinstance(value, bool) or float(value) < 0 or float(value) > 1:
            details.append(self._schema_error(f"{path}.confidence", "confidence must be a number between 0 and 1.", actual=value, expected="number between 0 and 1"))

    def _schema_error(
        self,
        path: str,
        message: str,
        *,
        actual: Any = None,
        expected: Optional[str] = None,
        allowed_values: Optional[list[str]] = None,
        missing_required_field: Optional[str] = None,
    ) -> dict[str, Any]:
        return {
            "errorType": "SCHEMA_VALIDATION_ERROR",
            "jsonPath": path,
            "message": message,
            "actual": actual,
            "invalidValue": actual,
            "expected": expected,
            "allowedValues": list(allowed_values or []),
            "missingRequiredField": missing_required_field,
        }

    def _graph_error(self, path: str, reason: str) -> dict[str, Any]:
        return {
            "errorType": "GRAPH_VALIDATION_ERROR",
            "jsonPath": path,
            "reason": reason,
            "invalidValue": None,
            "allowedValues": [],
        }

    def _failure(self, raw: str, *details: dict[str, Any]) -> GraphAnalysisParseFailure:
        summaries = "; ".join(str(detail.get("reason") or detail.get("message") or detail) for detail in details[:3])
        if len(details) > 3:
            summaries = f"{summaries}; and {len(details) - 3} more error(s)"
        return GraphAnalysisParseFailure(
            "ANALYSIS_AI_SCHEMA_INVALID",
            f"AI analyzer response does not match target-anchor graph schema: {summaries}",
            str(raw)[:4000],
            list(details),
        )


class FileEnrichmentMerger:
    def merge(self, target_results: Iterable[GraphAnalysisResult]) -> GraphAnalysisResult:
        nodes: list[GraphNode] = []
        claims: list[GraphClaim] = []
        edges: list[GraphEdge] = []
        diagnostics: list[dict[str, Any]] = []
        seen_nodes: set[str] = set()
        seen_claims: set[tuple[Any, ...]] = set()
        seen_edges: set[tuple[Any, ...]] = set()
        for result in target_results:
            diagnostics.extend(dict(item) for item in result.diagnostics or [])
            for node in result.nodes:
                if node.localId in seen_nodes:
                    continue
                seen_nodes.add(node.localId)
                nodes.append(node)
            for claim in result.claims:
                key = self._claim_key(claim)
                if key in seen_claims:
                    continue
                seen_claims.add(key)
                claims.append(claim)
            for edge in result.edges:
                key = self._edge_key(edge)
                if key in seen_edges:
                    continue
                seen_edges.add(key)
                edges.append(edge)
        return GraphAnalysisResult(nodes=nodes, edges=edges, claims=claims, diagnostics=diagnostics)

    def _claim_key(self, claim: GraphClaim) -> tuple[Any, ...]:
        return (
            claim.nodeLocalId,
            claim.claimKind,
            _normalize_text(claim.summary),
            tuple((item.lineStart, item.lineEnd) for item in claim.evidence),
        )

    def _edge_key(self, edge: GraphEdge) -> tuple[Any, ...]:
        unresolved_name = None
        if isinstance(edge.unresolvedTarget, Mapping):
            unresolved_name = edge.unresolvedTarget.get("name") or edge.unresolvedTarget.get("qualifiedName")
        return (
            edge.fromNodeLocalId,
            edge.toNodeLocalId or unresolved_name,
            edge.edgeType,
            edge.resolutionStatus,
            tuple((item.lineStart, item.lineEnd) for item in edge.evidence),
        )


def is_target_enrichment_payload(payload: Mapping[str, Any]) -> bool:
    return payload.get("requestKind") == TARGET_REQUEST_KIND and isinstance(payload.get("llmInput"), Mapping)


def _sorted_anchor_nodes(nodes: Iterable[GraphNode]) -> list[GraphNode]:
    return sorted(
        nodes,
        key=lambda node: (
            node.lineStart if node.lineStart is not None else 10**9,
            node.lineEnd if node.lineEnd is not None else 10**9,
            node.nodeKind,
            node.qualifiedName or "",
            node.name or "",
            node.localId,
        ),
    )


def _prefix_registry(allowed_kinds: Iterable[str]) -> dict[str, str]:
    registry: dict[str, str] = {}
    used: set[str] = set()
    for kind in allowed_kinds:
        preferred = _DEFAULT_REF_PREFIXES.get(kind) or _generic_prefix(kind)
        prefix = preferred
        if prefix in used:
            prefix = _generic_prefix(kind)
        suffix = 2
        base = prefix
        while prefix in used:
            prefix = f"{base}{suffix}"
            suffix += 1
        registry[kind] = prefix
        used.add(prefix)
    return registry


def _generic_prefix(kind: str) -> str:
    value = re.sub(r"[^A-Z0-9]", "", str(kind or "").upper())
    return value or "A"


def _annotation_payloads(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    annotations: list[dict[str, Any]] = []
    for item in value[:12]:
        if not isinstance(item, Mapping):
            continue
        payload = {
            "name": _bounded_string(item.get("name"), 160),
            "lineStart": item.get("lineStart"),
            "lineEnd": item.get("lineEnd"),
        }
        arguments = _bounded_string(item.get("argumentsRaw") or item.get("arguments"), 240)
        if arguments:
            payload["arguments"] = arguments
        annotations.append({key: val for key, val in payload.items() if val is not None})
    return annotations


def _bounded_string(value: Any, limit: int) -> Optional[str]:
    if value is None:
        return None
    text = str(value)
    if not text:
        return None
    if len(text) > limit:
        return text[:limit].rstrip()
    return text


def _normalize_text(value: str) -> str:
    return " ".join(str(value or "").split()).casefold()


def _json_copy(value: Mapping[str, Any]) -> dict[str, Any]:
    return json.loads(json.dumps(dict(value)))
