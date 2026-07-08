from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, Iterable, Mapping, Optional

from knowledge_service.analysis_graph_contract import AnalysisGraphContract
from knowledge_service.graph_schema import GraphAnalysisResult, GraphNode


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
