from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Sequence

from knowledge_service.entrypoint_flow_engine import LocalFlowUnit


@dataclass(frozen=True)
class LocalFlowUnitSelectionResult:
    selected_unit_ids: tuple[str, ...]
    selected_units: tuple[LocalFlowUnit, ...]
    score_by_unit_id: Mapping[str, float]
    selection_reasons_by_unit_id: Mapping[str, tuple[str, ...]]
    rejected_unit_ids: tuple[str, ...]
    diagnostics: tuple[Mapping[str, Any], ...] = ()
    truncated: bool = False


class LocalFlowUnitSelector:
    def __init__(self, *, min_relevance_score: float = 0.05, top_delta: float = 0.25) -> None:
        self.min_relevance_score = max(0.0, float(min_relevance_score))
        self.top_delta = max(0.0, float(top_delta))

    def select(self, units: Sequence[LocalFlowUnit], *, code_identifiers: Sequence[str] = ()) -> LocalFlowUnitSelectionResult:
        ordered = tuple(sorted(self._dedupe(units), key=lambda item: item.unit_id))
        identifiers = tuple(str(item or "").strip() for item in code_identifiers if str(item or "").strip())
        if identifiers:
            selected = tuple(unit for unit in ordered if any(self._unit_matches_identifier(unit, identifier) for identifier in identifiers))
            reasons = {unit.unit_id: ("EXACT_CODE_IDENTIFIER",) for unit in selected}
            scores = {unit.unit_id: 1.0 for unit in selected}
            return self._result(ordered, selected, scores, reasons, diagnostics=({"code": "LOCAL_FLOW_UNIT_SELECTION_DIAGNOSTICS", "mode": "EXACT_IDENTIFIER"},))

        scored = [(self._grounded_score(unit), unit) for unit in ordered]
        if not scored:
            return self._result(ordered, (), {}, {}, diagnostics=({"code": "LOCAL_FLOW_UNIT_SELECTION_DIAGNOSTICS", "mode": "RELEVANCE"},))
        top_score = max(score for score, _unit in scored)
        threshold = max(self.min_relevance_score, top_score - self.top_delta)
        selected = tuple(unit for score, unit in scored if score >= threshold)
        scores = {unit.unit_id: score for score, unit in scored}
        reasons = {
            unit.unit_id: self._relevance_reasons(unit)
            for score, unit in scored
            if score >= threshold
        }
        return self._result(ordered, selected, scores, reasons, diagnostics=({"code": "LOCAL_FLOW_UNIT_SELECTION_DIAGNOSTICS", "mode": "RELEVANCE"},))

    def _result(
        self,
        ordered: Sequence[LocalFlowUnit],
        selected: Sequence[LocalFlowUnit],
        scores: Mapping[str, float],
        reasons: Mapping[str, tuple[str, ...]],
        *,
        diagnostics: Sequence[Mapping[str, Any]],
    ) -> LocalFlowUnitSelectionResult:
        selected_ids = tuple(unit.unit_id for unit in sorted(selected, key=lambda item: item.unit_id))
        selected_by_id = {unit.unit_id: unit for unit in selected}
        rejected = tuple(unit.unit_id for unit in ordered if unit.unit_id not in selected_by_id)
        return LocalFlowUnitSelectionResult(
            selected_unit_ids=selected_ids,
            selected_units=tuple(selected_by_id[unit_id] for unit_id in selected_ids),
            score_by_unit_id=dict(sorted(scores.items())),
            selection_reasons_by_unit_id=dict(sorted(reasons.items())),
            rejected_unit_ids=rejected,
            diagnostics=tuple(dict(item) for item in diagnostics),
        )

    def _dedupe(self, units: Sequence[LocalFlowUnit]) -> tuple[LocalFlowUnit, ...]:
        by_id: dict[str, LocalFlowUnit] = {}
        for unit in units or ():
            if str(unit.unit_id or ""):
                by_id.setdefault(unit.unit_id, unit)
        return tuple(by_id[key] for key in sorted(by_id))

    def _grounded_score(self, unit: LocalFlowUnit) -> float:
        anchor_score = max((float(anchor.original_anchor.score or 0.0) for anchor in unit.anchors), default=0.0)
        support = min(len(unit.anchors), 10) * 0.01
        root_support = min(len(unit.roots), 5) * 0.005
        distance = min((anchor.distance_to_nearest_root for anchor in unit.anchors), default=999)
        proximity = 0.01 / (1 + max(0, distance))
        return round(anchor_score + support + root_support + proximity, 6)

    def _relevance_reasons(self, unit: LocalFlowUnit) -> tuple[str, ...]:
        reasons: set[str] = set()
        for anchor in unit.anchors:
            reasons.update(str(item or "") for item in anchor.query_provenance if str(item or ""))
            reasons.update(str(item or "") for item in anchor.anchor_to_seed_reasons if str(item or ""))
            reasons.update(str(item or "") for item in anchor.original_anchor.matchReasons if str(item or ""))
        return tuple(sorted(reasons or {"GROUNDED_ANCHOR_PROVENANCE"}))

    def _unit_matches_identifier(self, unit: LocalFlowUnit, identifier: str) -> bool:
        for candidate in self._unit_identifier_candidates(unit):
            if candidate == identifier or self._has_symbol_suffix(candidate, identifier):
                return True
        return False

    def _unit_identifier_candidates(self, unit: LocalFlowUnit) -> set[str]:
        candidates: set[str] = {unit.unit_id}
        nodes = [*(root.node for root in unit.roots), *unit.execution_nodes, *(anchor.expanded_seed for anchor in unit.anchors)]
        for node in nodes:
            candidates.update(
                str(value or "").strip()
                for value in (
                    node.stable_key,
                    node.qualified_name,
                    node.label,
                    node.node_id,
                )
                if str(value or "").strip()
            )
        for anchor in unit.anchors:
            candidates.update(
                str(value or "").strip()
                for value in (
                    anchor.original_anchor.stableKey,
                    anchor.original_anchor.qualifiedName,
                    anchor.original_anchor.label,
                    anchor.original_anchor.nodeId,
                )
                if str(value or "").strip()
            )
        return candidates

    def _has_symbol_suffix(self, candidate: str, identifier: str) -> bool:
        if len(candidate) <= len(identifier) or not candidate.endswith(identifier):
            return False
        delimiter_index = len(candidate) - len(identifier) - 1
        return delimiter_index >= 0 and candidate[delimiter_index] in {".", "#", ":", "/", "$"}
