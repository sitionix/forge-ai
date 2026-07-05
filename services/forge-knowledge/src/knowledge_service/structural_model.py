from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


@dataclass(frozen=True)
class StructuralFileMetadata:
    source_id: str
    inventory_file_id: int
    relative_path: str
    language: str
    flow_domain: str
    content_hash: str
    line_count: int
    decode_policy: Optional[str] = None


@dataclass(frozen=True)
class StructuralAnnotation:
    name: str
    arguments_raw: Optional[str]
    line_start: int
    line_end: int
    target_local_id: Optional[str] = None


@dataclass(frozen=True)
class StructuralImport:
    imported_name: str
    is_static: bool
    is_wildcard: bool
    line_start: int
    line_end: int
    stable_key: str


@dataclass(frozen=True)
class StructuralType:
    local_id: str
    name: str
    qualified_name: str
    type_kind: str
    line_start: int
    line_end: int
    body_line_start: Optional[int]
    body_line_end: Optional[int]
    annotations: List[StructuralAnnotation] = field(default_factory=list)
    parent_type_local_id: Optional[str] = None
    stable_key: str = ""


@dataclass(frozen=True)
class StructuralCallable:
    local_id: str
    name: str
    qualified_name: str
    callable_kind: str
    owner_type_local_id: Optional[str]
    signature: str
    return_type: Optional[str]
    parameters: List[str]
    line_start: int
    line_end: int
    body_line_start: Optional[int]
    body_line_end: Optional[int]
    annotations: List[StructuralAnnotation] = field(default_factory=list)
    visibility: Optional[str] = None
    is_static: Optional[bool] = None
    stable_key: str = ""
    parameter_names: List[str] = field(default_factory=list)


@dataclass(frozen=True)
class StructuralField:
    local_id: str
    name: str
    qualified_name: str
    owner_type_local_id: str
    type_name: Optional[str]
    line_start: int
    line_end: int
    annotations: List[StructuralAnnotation] = field(default_factory=list)
    visibility: Optional[str] = None
    stable_key: str = ""


@dataclass(frozen=True)
class StructuralCallsite:
    local_id: str
    caller_callable_local_id: str
    receiver_text: Optional[str]
    receiver_type_hint: Optional[str]
    method_name: str
    argument_count: Optional[int]
    target_type_text: Optional[str]
    call_kind: str
    line_start: int
    line_end: int
    raw_text: str
    resolution_status: str
    stable_key: str
    target_callable_local_id: Optional[str] = None
    unresolved_reason: Optional[str] = None
    resolution_reason: Optional[str] = None
    owner_type_hint: Optional[str] = None
    import_hint: Optional[str] = None


@dataclass(frozen=True)
class StructuralParseDiagnostic:
    code: str
    message: str
    severity: str = "WARN"
    stage: str = "STRUCTURAL_PARSE"
    line_start: Optional[int] = None
    line_end: Optional[int] = None
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class StructuralParseResult:
    file: StructuralFileMetadata
    package_name: Optional[str] = None
    imports: List[StructuralImport] = field(default_factory=list)
    types: List[StructuralType] = field(default_factory=list)
    callables: List[StructuralCallable] = field(default_factory=list)
    fields: List[StructuralField] = field(default_factory=list)
    annotations: List[StructuralAnnotation] = field(default_factory=list)
    callsites: List[StructuralCallsite] = field(default_factory=list)
    diagnostics: List[StructuralParseDiagnostic] = field(default_factory=list)

    @property
    def file_stable_key(self) -> str:
        return "|".join([self.file.source_id, self.file.relative_path, "FILE"])
