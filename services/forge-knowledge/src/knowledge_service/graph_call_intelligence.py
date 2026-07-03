from __future__ import annotations

from enum import Enum
from typing import Any, Dict, Optional


class CallKind(str, Enum):
    LOCAL_METHOD = "LOCAL_METHOD"
    THIS_METHOD = "THIS_METHOD"
    FIELD_RECEIVER = "FIELD_RECEIVER"
    PARAMETER_RECEIVER = "PARAMETER_RECEIVER"
    LOCAL_VARIABLE_RECEIVER = "LOCAL_VARIABLE_RECEIVER"
    STATIC_METHOD = "STATIC_METHOD"
    CONSTRUCTOR = "CONSTRUCTOR"
    SUPER_METHOD = "SUPER_METHOD"
    CHAINED_CALL = "CHAINED_CALL"
    METHOD_REFERENCE = "METHOD_REFERENCE"
    LAMBDA_CALL = "LAMBDA_CALL"
    FRAMEWORK_CALLBACK = "FRAMEWORK_CALLBACK"
    UNKNOWN = "UNKNOWN"


class CallTargetCategory(str, Enum):
    INTERNAL_CODE = "INTERNAL_CODE"
    INTERNAL_TEST = "INTERNAL_TEST"
    INTERNAL_CONFIG = "INTERNAL_CONFIG"
    EXTERNAL_JDK = "EXTERNAL_JDK"
    EXTERNAL_FRAMEWORK = "EXTERNAL_FRAMEWORK"
    EXTERNAL_LIBRARY = "EXTERNAL_LIBRARY"
    EXTERNAL_SERVICE = "EXTERNAL_SERVICE"
    WORKFLOW = "WORKFLOW"
    BUILD = "BUILD"
    UNKNOWN = "UNKNOWN"


class UnresolvedReason(str, Enum):
    TARGET_NOT_ANALYZED = "TARGET_NOT_ANALYZED"
    RECEIVER_TYPE_UNKNOWN = "RECEIVER_TYPE_UNKNOWN"
    LOCAL_VARIABLE_TYPE_UNKNOWN = "LOCAL_VARIABLE_TYPE_UNKNOWN"
    PARAMETER_TYPE_UNKNOWN = "PARAMETER_TYPE_UNKNOWN"
    FIELD_TYPE_UNKNOWN = "FIELD_TYPE_UNKNOWN"
    METHOD_OVERLOADED = "METHOD_OVERLOADED"
    MULTIPLE_TYPES_MATCH = "MULTIPLE_TYPES_MATCH"
    MULTIPLE_METHODS_MATCH = "MULTIPLE_METHODS_MATCH"
    DYNAMIC_DISPATCH = "DYNAMIC_DISPATCH"
    INTERFACE_TARGET = "INTERFACE_TARGET"
    CHAINED_CALL_TARGET_UNKNOWN = "CHAINED_CALL_TARGET_UNKNOWN"
    EXTERNAL_NOT_MODELED = "EXTERNAL_NOT_MODELED"
    PARSER_LIMITATION = "PARSER_LIMITATION"
    NO_MATCH = "NO_MATCH"
    UNKNOWN = "UNKNOWN"


class ResolutionReason(str, Enum):
    SAME_TYPE_METHOD = "SAME_TYPE_METHOD"
    SAME_FILE_UNIQUE_METHOD = "SAME_FILE_UNIQUE_METHOD"
    FIELD_TYPE_HINT = "FIELD_TYPE_HINT"
    PARAMETER_TYPE_HINT = "PARAMETER_TYPE_HINT"
    LOCAL_VARIABLE_TYPE_HINT = "LOCAL_VARIABLE_TYPE_HINT"
    IMPORT_EXACT_MATCH = "IMPORT_EXACT_MATCH"
    STATIC_IMPORT_MATCH = "STATIC_IMPORT_MATCH"
    QUALIFIED_NAME_MATCH = "QUALIFIED_NAME_MATCH"
    INTERFACE_IMPLEMENTATION_CANDIDATE = "INTERFACE_IMPLEMENTATION_CANDIDATE"
    EXTERNAL_PACKAGE_CLASSIFICATION = "EXTERNAL_PACKAGE_CLASSIFICATION"
    NOT_RESOLVED = "NOT_RESOLVED"


JDK_TYPES = {
    "Arrays",
    "BigDecimal",
    "Boolean",
    "Collections",
    "Collectors",
    "Duration",
    "Instant",
    "Integer",
    "List",
    "LocalDate",
    "LocalDateTime",
    "Long",
    "Map",
    "Math",
    "Objects",
    "Optional",
    "Set",
    "String",
    "System",
    "UUID",
}

JDK_METHODS = {"equals", "getClass", "hashCode", "requireNonNull", "toString", "valueOf"}

FRAMEWORK_TYPES = {
    "Assertions",
    "AssertThat",
    "Flux",
    "Mono",
    "Mockito",
    "ResponseEntity",
    "RestClient",
    "StepVerifier",
    "TestRestTemplate",
    "WebClient",
}

FRAMEWORK_METHODS = {
    "assertEquals",
    "assertFalse",
    "assertNotNull",
    "assertThat",
    "assertThrows",
    "assertTrue",
    "badRequest",
    "body",
    "bodyToMono",
    "build",
    "exchange",
    "expectNext",
    "get",
    "mock",
    "ok",
    "post",
    "retrieve",
    "status",
    "uri",
    "verify",
    "when",
}

DATA_READ_METHODS = {"count", "exists", "find", "findAll", "findById", "get", "load", "query", "read", "select"}
DATA_WRITE_METHODS = {"delete", "deleteById", "insert", "merge", "persist", "remove", "save", "update", "write"}
EXTERNAL_CLIENT_TYPES = {"Feign", "RestClient", "WebClient"}


def classify_call_metadata(
    metadata: Dict[str, Any],
    flow_domain: Optional[str],
    resolution_status: Optional[str],
    unresolved_target: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    result = dict(metadata or {})
    flow = str(result.get("flowDomain") or flow_domain or "CODE").upper()
    status = str(result.get("resolutionStatus") or resolution_status or "UNKNOWN").upper()
    receiver = _blank_to_none(result.get("receiverText"))
    receiver_type = _blank_to_none(result.get("receiverTypeHint"))
    target_type = _blank_to_none(result.get("targetTypeText") or (unresolved_target or {}).get("receiverTypeHint"))
    method_name = _blank_to_none(result.get("methodName") or (unresolved_target or {}).get("name"))
    raw_text = _blank_to_none(result.get("rawText") or result.get("rawCallText"))
    raw_kind = str(result.get("callKind") or "").upper()

    call_kind = result.get("callKind")
    if call_kind not in {item.value for item in CallKind}:
        call_kind = _normalized_call_kind(raw_kind, receiver, receiver_type, target_type, raw_text)
    target_category = result.get("callTargetCategory")
    if target_category not in {item.value for item in CallTargetCategory}:
        target_category = _target_category(flow, status, receiver_type, target_type, method_name, raw_text)
    resolution_reason = result.get("resolutionReason")
    if resolution_reason not in {item.value for item in ResolutionReason}:
        resolution_reason = _resolution_reason(status, call_kind, receiver_type, result)
    unresolved_reason = result.get("unresolvedReason")
    if status in {"UNRESOLVED", "MULTIPLE_CANDIDATES", "INTERFACE_TARGET"} and unresolved_reason not in {item.value for item in UnresolvedReason}:
        unresolved_reason = _unresolved_reason(status, call_kind, receiver, receiver_type, target_type, result)

    flow_usefulness, noise_category, visibility, scores, reasons = _score_call(
        flow,
        status,
        call_kind,
        target_category,
        unresolved_reason,
        method_name,
        receiver_type,
        target_type,
        raw_text,
    )
    result.update(
        {
            "callKind": call_kind,
            "callImportance": flow_usefulness,
            "callTargetCategory": target_category,
            "resolutionReason": resolution_reason,
            "flowUsefulness": flow_usefulness,
            "noiseCategory": noise_category,
            "sliceDefaultVisibility": visibility,
            "flowScore": scores["flowScore"],
            "displayScore": scores["displayScore"],
            "expansionScore": scores["expansionScore"],
            "reasonCodes": sorted(set([*(result.get("reasonCodes") or []), *reasons])),
            "rawCallText": raw_text,
        }
    )
    if unresolved_reason:
        result["unresolvedReason"] = unresolved_reason
    if receiver is not None:
        result["receiverText"] = receiver
    if receiver_type is not None:
        result["receiverTypeHint"] = receiver_type
    if target_type is not None:
        result["targetTypeHint"] = target_type
    if method_name is not None:
        result["methodName"] = method_name
    return result


def _normalized_call_kind(raw_kind: str, receiver: Optional[str], receiver_type: Optional[str], target_type: Optional[str], raw_text: Optional[str]) -> str:
    if raw_kind == "CONSTRUCTOR_CALL":
        return CallKind.CONSTRUCTOR.value
    if raw_kind == "STATIC_CALL":
        return CallKind.STATIC_METHOD.value
    if raw_kind == "SUPER_CALL":
        return CallKind.SUPER_METHOD.value
    if raw_kind == "THIS_CALL" or receiver == "this":
        return CallKind.THIS_METHOD.value
    if receiver_type:
        source = str(raw_kind or "")
        if source == "PARAMETER_RECEIVER":
            return CallKind.PARAMETER_RECEIVER.value
        if source == "LOCAL_VARIABLE_RECEIVER":
            return CallKind.LOCAL_VARIABLE_RECEIVER.value
        return CallKind.FIELD_RECEIVER.value
    if receiver and raw_text and "." in receiver:
        return CallKind.CHAINED_CALL.value
    if receiver and (receiver[:1].isupper() or target_type):
        return CallKind.STATIC_METHOD.value
    if receiver:
        return CallKind.LOCAL_VARIABLE_RECEIVER.value
    return CallKind.LOCAL_METHOD.value


def _target_category(
    flow: str,
    status: str,
    receiver_type: Optional[str],
    target_type: Optional[str],
    method_name: Optional[str],
    raw_text: Optional[str],
) -> str:
    type_text = " ".join(str(item or "") for item in (receiver_type, target_type, raw_text))
    if flow == "TEST":
        return CallTargetCategory.INTERNAL_TEST.value if status == "RESOLVED" else _external_category(type_text, method_name)
    if flow == "WORKFLOW":
        return CallTargetCategory.WORKFLOW.value
    if flow == "BUILD":
        return CallTargetCategory.BUILD.value
    if flow in {"CONFIG", "DATA"}:
        return CallTargetCategory.INTERNAL_CONFIG.value
    if status == "RESOLVED":
        return CallTargetCategory.INTERNAL_CODE.value
    external = _external_category(type_text, method_name)
    if external != CallTargetCategory.UNKNOWN.value:
        return external
    return CallTargetCategory.UNKNOWN.value


def _external_category(type_text: str, method_name: Optional[str]) -> str:
    simple_tokens = {token.rsplit(".", 1)[-1].split("<", 1)[0] for token in type_text.replace("(", " ").replace(")", " ").replace(".", " ").split()}
    if method_name in JDK_METHODS or simple_tokens & JDK_TYPES or "java." in type_text or "javax." in type_text:
        return CallTargetCategory.EXTERNAL_JDK.value
    if (
        method_name in FRAMEWORK_METHODS
        or simple_tokens & FRAMEWORK_TYPES
        or any(
            prefix in type_text
            for prefix in (
                "org.springframework",
                "reactor.",
                "org.junit",
                "org.mockito",
                "jakarta.",
                "javax.servlet",
            )
        )
    ):
        return CallTargetCategory.EXTERNAL_FRAMEWORK.value
    if any(token in type_text for token in EXTERNAL_CLIENT_TYPES):
        return CallTargetCategory.EXTERNAL_SERVICE.value
    return CallTargetCategory.UNKNOWN.value


def _resolution_reason(status: str, call_kind: str, receiver_type: Optional[str], metadata: Dict[str, Any]) -> str:
    if status == "RESOLVED":
        resolver = metadata.get("resolver")
        if resolver == "STATIC_TYPE_HINT":
            return ResolutionReason.FIELD_TYPE_HINT.value
        if call_kind == CallKind.FIELD_RECEIVER.value and receiver_type:
            return ResolutionReason.FIELD_TYPE_HINT.value
        if call_kind == CallKind.PARAMETER_RECEIVER.value and receiver_type:
            return ResolutionReason.PARAMETER_TYPE_HINT.value
        if call_kind == CallKind.LOCAL_VARIABLE_RECEIVER.value and receiver_type:
            return ResolutionReason.LOCAL_VARIABLE_TYPE_HINT.value
        if call_kind in {CallKind.LOCAL_METHOD.value, CallKind.THIS_METHOD.value}:
            return ResolutionReason.SAME_TYPE_METHOD.value
        if call_kind == CallKind.STATIC_METHOD.value:
            return ResolutionReason.QUALIFIED_NAME_MATCH.value
        return ResolutionReason.SAME_FILE_UNIQUE_METHOD.value
    if status == "EXTERNAL_TARGET":
        return ResolutionReason.EXTERNAL_PACKAGE_CLASSIFICATION.value
    if status == "INTERFACE_TARGET":
        return ResolutionReason.INTERFACE_IMPLEMENTATION_CANDIDATE.value
    return ResolutionReason.NOT_RESOLVED.value


def _unresolved_reason(
    status: str, call_kind: str, receiver: Optional[str], receiver_type: Optional[str], target_type: Optional[str], metadata: Dict[str, Any]
) -> str:
    if status == "MULTIPLE_CANDIDATES":
        if metadata.get("candidateKind") == "TYPE":
            return UnresolvedReason.MULTIPLE_TYPES_MATCH.value
        return UnresolvedReason.MULTIPLE_METHODS_MATCH.value
    if status == "INTERFACE_TARGET":
        return UnresolvedReason.INTERFACE_TARGET.value
    if call_kind == CallKind.CHAINED_CALL.value:
        return UnresolvedReason.CHAINED_CALL_TARGET_UNKNOWN.value
    if call_kind == CallKind.FIELD_RECEIVER.value:
        return UnresolvedReason.TARGET_NOT_ANALYZED.value if receiver_type else UnresolvedReason.FIELD_TYPE_UNKNOWN.value
    if call_kind == CallKind.PARAMETER_RECEIVER.value:
        return UnresolvedReason.TARGET_NOT_ANALYZED.value if receiver_type else UnresolvedReason.PARAMETER_TYPE_UNKNOWN.value
    if call_kind == CallKind.LOCAL_VARIABLE_RECEIVER.value:
        return UnresolvedReason.TARGET_NOT_ANALYZED.value if receiver_type else UnresolvedReason.LOCAL_VARIABLE_TYPE_UNKNOWN.value
    if receiver and not receiver_type and not target_type:
        return UnresolvedReason.RECEIVER_TYPE_UNKNOWN.value
    return UnresolvedReason.NO_MATCH.value


def _score_call(
    flow: str,
    status: str,
    call_kind: str,
    target_category: str,
    unresolved_reason: Optional[str],
    method_name: Optional[str],
    receiver_type: Optional[str],
    target_type: Optional[str],
    raw_text: Optional[str],
) -> tuple[str, str, str, Dict[str, float], list[str]]:
    score = 0.45
    reasons: list[str] = []
    if flow == "CODE":
        score += 0.15
        reasons.append("CODE_DOMAIN")
    if status == "RESOLVED":
        score += 0.2
        reasons.append("RESOLVED")
    if target_category == CallTargetCategory.INTERNAL_CODE.value:
        score += 0.2
        reasons.append("INTERNAL_CODE_TARGET")
    if call_kind in {CallKind.FIELD_RECEIVER.value, CallKind.PARAMETER_RECEIVER.value, CallKind.LOCAL_VARIABLE_RECEIVER.value} and receiver_type:
        score += 0.1
        reasons.append("RECEIVER_TYPE_HINT")
    if target_category in {CallTargetCategory.EXTERNAL_JDK.value, CallTargetCategory.EXTERNAL_FRAMEWORK.value}:
        score -= 0.3
        reasons.append(target_category)
    if flow == "TEST":
        score -= 0.2
        reasons.append("TEST_DOMAIN")
    if method_name in JDK_METHODS or method_name in {"of", "builder", "build"}:
        score -= 0.15
        reasons.append("UTILITY_METHOD")
    if method_name in FRAMEWORK_METHODS:
        score -= 0.15
        reasons.append("FRAMEWORK_METHOD")
    if call_kind == CallKind.CHAINED_CALL.value or (raw_text and raw_text.count(".") >= 3):
        score -= 0.1
        reasons.append("CHAINED_CALL")
    if status in {"UNRESOLVED", "MULTIPLE_CANDIDATES"} and receiver_type:
        score += 0.05
        reasons.append("EXPLAINABLE_UNRESOLVED")
    score = max(0.0, min(1.0, score))
    if score >= 0.75:
        usefulness = "HIGH"
    elif score >= 0.55:
        usefulness = "MEDIUM"
    elif score >= 0.32:
        usefulness = "LOW"
    else:
        usefulness = "NOISE"
    if target_category == CallTargetCategory.EXTERNAL_JDK.value:
        noise_category = "JDK_UTILITY"
    elif target_category == CallTargetCategory.EXTERNAL_FRAMEWORK.value:
        noise_category = "FRAMEWORK_CALL"
    elif flow == "TEST":
        noise_category = "TEST_CALL"
    elif usefulness == "NOISE":
        noise_category = "LOW_VALUE_CALL"
    else:
        noise_category = "NONE"
    if usefulness in {"HIGH", "MEDIUM"} and status == "RESOLVED":
        visibility = "SHOW"
    elif status in {"UNRESOLVED", "MULTIPLE_CANDIDATES", "INTERFACE_TARGET"} and usefulness in {"HIGH", "MEDIUM", "LOW"}:
        visibility = "SHOW_AS_UNCERTAINTY"
    elif target_category in {CallTargetCategory.EXTERNAL_FRAMEWORK.value, CallTargetCategory.EXTERNAL_LIBRARY.value, CallTargetCategory.EXTERNAL_SERVICE.value}:
        visibility = "COLLAPSE"
    else:
        visibility = "HIDE_BY_DEFAULT"
    return (
        usefulness,
        noise_category,
        visibility,
        {
            "flowScore": round(score, 3),
            "displayScore": round(max(0.05, min(1.0, score - (0.1 if visibility == "HIDE_BY_DEFAULT" else 0))), 3),
            "expansionScore": round(max(0.0, min(1.0, score - (0.2 if target_category.startswith("EXTERNAL_") else 0))), 3),
        },
        reasons,
    )


def _blank_to_none(value: Any) -> Optional[str]:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
