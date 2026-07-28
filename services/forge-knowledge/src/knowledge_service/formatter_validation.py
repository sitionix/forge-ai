from __future__ import annotations

import json
from collections import Counter, defaultdict
from typing import Any, Mapping, Sequence

from knowledge_service.canonical_narration_contract import CanonicalNarrationPlan, FormatterValidationSummary
from knowledge_service.formatter_placeholders import validate_clause_placeholders
from knowledge_service.formatter_protocol import EndToEndFormatterSegment, EndToEndFormatterValidationError, ValidatedFormatterClause


def validate_provider_clauses(
    raw_text: str,
    plan: CanonicalNarrationPlan,
    segment: EndToEndFormatterSegment,
) -> dict[str, ValidatedFormatterClause]:
    errors: list[str] = []
    try:
        payload = json.loads(raw_text)
    except (TypeError, ValueError) as exc:
        raise EndToEndFormatterValidationError(("formatter response is not valid JSON",)) from exc
    if not isinstance(payload, dict):
        raise EndToEndFormatterValidationError(("formatter response must be a JSON object",))
    if set(payload) != {"clauses"}:
        errors.append("formatter response must contain exactly the 'clauses' field")
    clauses = payload.get("clauses")
    if not isinstance(clauses, list):
        errors.append("formatter response clauses must be an array")
        raise EndToEndFormatterValidationError(errors)

    expected_refs = tuple(segment.clause_refs)
    actual_refs = tuple(str(item.get("clauseRef") or "") for item in clauses if isinstance(item, dict))
    if actual_refs != expected_refs:
        errors.append(f"formatter clauses must preserve exact clause order {list(expected_refs)}")
    if len(actual_refs) != len(set(actual_refs)):
        errors.append("formatter response contains duplicate clause refs")
    unknown = tuple(ref for ref in actual_refs if ref not in expected_refs)
    if unknown:
        errors.append(f"formatter response contains unknown clause refs: {list(unknown)}")
    missing = tuple(ref for ref in expected_refs if ref not in actual_refs)
    if missing:
        errors.append(f"formatter response is missing clause refs: {list(missing)}")

    clause_by_ref = {clause.clause_ref: clause for clause in plan.clauses}
    validated: dict[str, ValidatedFormatterClause] = {}
    for index, item in enumerate(clauses):
        if not isinstance(item, dict):
            errors.append(f"formatter clause {index} must be an object")
            continue
        if set(item) != {"clauseRef", "referencedCanonicalRefs", "textTemplate"}:
            errors.append(f"formatter clause {index} must contain exactly clauseRef, referencedCanonicalRefs, and textTemplate")
        clause_ref = str(item.get("clauseRef") or "")
        clause = clause_by_ref.get(clause_ref)
        referenced = item.get("referencedCanonicalRefs")
        if not isinstance(referenced, list) or not all(isinstance(ref, str) and ref for ref in referenced):
            errors.append(f"formatter clause {clause_ref or index} referencedCanonicalRefs must be strings")
            referenced = []
        sorted_referenced = sorted(dict.fromkeys(str(ref) for ref in referenced))
        if list(referenced) != sorted_referenced:
            errors.append(f"formatter clause {clause_ref or index} referencedCanonicalRefs must be sorted and deduplicated")
        text_template = str(item.get("textTemplate") or "").strip()
        if not text_template:
            errors.append(f"formatter clause {clause_ref or index} textTemplate must be non-empty")
        rendered_text = text_template
        if clause is not None:
            unknown_references = tuple(str(ref) for ref in referenced if str(ref) not in set(clause.allowed_canonical_refs))
            if unknown_references:
                errors.append(f"formatter clause {clause_ref} references canonical refs outside the clause contract: {list(unknown_references)}")
            placeholder_result = validate_clause_placeholders(text_template, referenced, clause)
            errors.extend(placeholder_result.errors)
            rendered_text = placeholder_result.rendered_text
        validated[clause_ref] = ValidatedFormatterClause(
            clause_ref=clause_ref,
            referenced_canonical_refs=tuple(str(ref) for ref in referenced),
            text_template=text_template,
            text=rendered_text,
        )
    if errors:
        raise EndToEndFormatterValidationError(tuple(errors))
    return validated


def validate_combined_provider_clauses(
    plan: CanonicalNarrationPlan,
    segment_clauses: Mapping[str, Sequence[ValidatedFormatterClause]],
) -> FormatterValidationSummary:
    summary = formatter_validation_summary(plan, segment_clauses)
    errors: list[str] = []
    if summary.missing_clause_refs:
        errors.append(f"formatter response is missing clause refs: {list(summary.missing_clause_refs)}")
    if summary.duplicate_clause_refs:
        errors.append(f"formatter response duplicated clause refs: {list(summary.duplicate_clause_refs)}")
    if summary.unknown_clause_refs:
        errors.append(f"formatter response returned unknown clause refs: {list(summary.unknown_clause_refs)}")
    if summary.stage_count_contract_matched is False:
        errors.append("formatter response did not match the canonical clause contract")
    if errors:
        raise EndToEndFormatterValidationError(tuple(errors))
    return summary


def formatter_validation_summary(
    plan: CanonicalNarrationPlan,
    segment_clauses: Mapping[str, Sequence[ValidatedFormatterClause]],
) -> FormatterValidationSummary:
    expected_refs = tuple(clause.clause_ref for clause in plan.clauses)
    expected_set = set(expected_refs)
    actual_refs = tuple(str(ref) for ref in segment_clauses)
    missing = tuple(ref for ref in expected_refs if not segment_clauses.get(ref))
    duplicate = tuple(sorted(ref for ref, clauses in segment_clauses.items() if len(clauses) > 1))
    unknown = tuple(sorted(ref for ref in actual_refs if ref not in expected_set))
    matched = not missing and not duplicate and not unknown
    validated_count = sum(len(clauses) for ref, clauses in segment_clauses.items() if ref in expected_set)
    return FormatterValidationSummary(
        missing_clause_refs=missing,
        duplicate_clause_refs=duplicate,
        unknown_clause_refs=unknown,
        omitted_fact_refs=(),
        duplicate_fact_refs=(),
        unowned_fact_refs=(),
        stage_count_contract_matched=matched,
        validated_formatter_clause_count=validated_count,
        public_clause_count=len(expected_refs) if matched else 0,
    )


def narration_ownership_metrics(plans: Sequence[CanonicalNarrationPlan]) -> dict[str, Any]:
    duplicate_clause_count = 0
    duplicate_fact_count = 0
    unknown_owned_count = 0
    for plan in plans:
        clause_refs = [clause.clause_ref for clause in plan.clauses]
        duplicate_clause_count += sum(1 for count in Counter(clause_refs).values() if count > 1)
        owner_by_fact: dict[str, list[str]] = defaultdict(list)
        for clause in plan.clauses:
            for fact_ref in clause.canonical_fact_refs:
                if ":" not in str(fact_ref or ""):
                    unknown_owned_count += 1
                owner_by_fact[str(fact_ref)].append(clause.clause_ref)
        duplicate_fact_count += sum(1 for owners in owner_by_fact.values() if len(owners) > 1)
    return {
        "duplicateStageRefs": duplicate_clause_count,
        "duplicateFactRefs": duplicate_fact_count,
        "unknownOwnedFactRefs": unknown_owned_count,
        "unknownContextFactRefs": 0,
        "unownedFactRefs": 0,
    }


def rollup_formatter_validation_summaries(summaries: Sequence[FormatterValidationSummary]) -> dict[str, Any]:
    return {
        "missingStageRefs": sum(len(summary.missing_clause_refs) for summary in summaries),
        "duplicateStageRefs": sum(len(summary.duplicate_clause_refs) for summary in summaries),
        "unknownStageRefs": sum(len(summary.unknown_clause_refs) for summary in summaries),
        "omittedOwnedFactRefs": sum(len(summary.omitted_fact_refs) for summary in summaries),
        "duplicateFactRefs": sum(len(summary.duplicate_fact_refs) for summary in summaries),
        "unownedFactRefs": sum(len(summary.unowned_fact_refs) for summary in summaries),
        "validatedFormatterStepCount": sum(summary.validated_formatter_clause_count for summary in summaries),
        "publicStepCount": sum(summary.public_clause_count for summary in summaries),
    }
