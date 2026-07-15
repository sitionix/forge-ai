from __future__ import annotations

from enum import Enum
from typing import Optional


class EntrypointKind(str, Enum):
    HTTP = "HTTP"
    KAFKA = "KAFKA"
    SCHEDULED = "SCHEDULED"
    MESSAGE = "MESSAGE"
    BOOTSTRAP = "BOOTSTRAP"
    EXCEPTION_HANDLER = "EXCEPTION_HANDLER"
    CONFIGURATION_BEAN = "CONFIGURATION_BEAN"
    LIFECYCLE = "LIFECYCLE"
    TEST = "TEST"


_ANNOTATION_KIND = {
    "ExceptionHandler": EntrypointKind.EXCEPTION_HANDLER,
    "KafkaListener": EntrypointKind.KAFKA,
    "Scheduled": EntrypointKind.SCHEDULED,
    "Bean": EntrypointKind.CONFIGURATION_BEAN,
    "Test": EntrypointKind.TEST,
    "BeforeEach": EntrypointKind.TEST,
    "AfterEach": EntrypointKind.TEST,
    "PostConstruct": EntrypointKind.LIFECYCLE,
    "EventListener": EntrypointKind.MESSAGE,
}

_TREE_KIND = {
    EntrypointKind.HTTP: "HTTP_ENDPOINT",
    EntrypointKind.KAFKA: "KAFKA_LISTENER",
    EntrypointKind.SCHEDULED: "SCHEDULED_TASK",
    EntrypointKind.MESSAGE: "MESSAGE_HANDLER",
    EntrypointKind.BOOTSTRAP: "APPLICATION_ENTRYPOINT",
    EntrypointKind.EXCEPTION_HANDLER: "EXCEPTION_HANDLER",
    EntrypointKind.CONFIGURATION_BEAN: "CONFIGURATION_BEAN",
    EntrypointKind.LIFECYCLE: "LIFECYCLE",
    EntrypointKind.TEST: "TEST",
}


def entrypoint_kind_value(value: EntrypointKind | str | None) -> Optional[str]:
    if value is None:
        return None
    try:
        return EntrypointKind(str(value).strip().upper()).value
    except ValueError:
        return None


def entrypoint_kind_for_annotation(annotation_name: str, *, is_http: bool = False) -> Optional[str]:
    if is_http:
        return EntrypointKind.HTTP.value
    kind = _ANNOTATION_KIND.get(str(annotation_name or "").strip())
    return kind.value if kind else None


def tree_kind_for_entrypoint(value: str | None) -> str:
    kind = entrypoint_kind_value(value)
    if kind is None:
        return "ENTRYPOINT"
    return _TREE_KIND.get(EntrypointKind(kind), "ENTRYPOINT")


def trigger_kind_for_entrypoint(value: str | None) -> Optional[str]:
    return entrypoint_kind_value(value)
