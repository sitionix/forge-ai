from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, Iterable, List, Mapping, Optional, Sequence, Set, Tuple


_CAMEL_BOUNDARY_1 = re.compile(r"(?<=[a-z0-9])(?=[A-Z])")
_CAMEL_BOUNDARY_2 = re.compile(r"(?<=[A-Z])(?=[A-Z][a-z])")
_WORD_RE = re.compile(r"[^\W_]+", re.UNICODE)
_ENDPOINT_RE = re.compile(r"/(?:[A-Za-z0-9._~:-]+/)*[A-Za-z0-9._~:-]+")
_FILE_EXTENSION_RE = re.compile(r"^[^/\s]+\.[A-Za-z0-9]{1,12}$")


class SearchQueryProfile(str, Enum):
    IDENTIFIER_LIKE = "IDENTIFIER_LIKE"
    PATH_LIKE = "PATH_LIKE"
    QUALIFIED_NAME_LIKE = "QUALIFIED_NAME_LIKE"
    ENDPOINT_LIKE = "ENDPOINT_LIKE"
    HUMAN_TEXT_LIKE = "HUMAN_TEXT_LIKE"
    MIXED = "MIXED"


@dataclass(frozen=True)
class SearchQuery:
    raw: str
    normalized: str
    profile: SearchQueryProfile
    tokens: Tuple[str, ...]
    important_tokens: Tuple[str, ...]
    compact: str
    path_segments: Tuple[str, ...]
    qualified_segments: Tuple[str, ...]
    endpoint_path: Optional[str] = None

    @property
    def lower(self) -> str:
        return self.normalized


class QueryNormalizer:
    def normalize(self, value: str) -> SearchQuery:
        raw = str(value or "")
        normalized = self.normalize_text(raw)
        tokens = tuple(self.unique_tokens(raw))
        compact = compact_identifier(raw)
        path_segments = tuple(self.path_segments(raw))
        qualified_segments = tuple(self.qualified_segments(raw))
        endpoint_path = first_endpoint_path(raw)
        profile = self.profile(raw, normalized, path_segments, qualified_segments, endpoint_path)
        important_tokens = tuple(self.important_tokens(tokens))
        return SearchQuery(
            raw=raw,
            normalized=normalized,
            profile=profile,
            tokens=tokens,
            important_tokens=important_tokens,
            compact=compact,
            path_segments=path_segments,
            qualified_segments=qualified_segments,
            endpoint_path=endpoint_path,
        )

    def normalize_text(self, value: str) -> str:
        text = str(value or "").strip().replace("\\", "/")
        text = re.sub(r"\s+", " ", text)
        return text.lower()

    def unique_tokens(self, value: str) -> List[str]:
        tokens: List[str] = []
        seen: Set[str] = set()
        for piece in self._pieces(value):
            for token in self._split_identifier_piece(piece):
                lowered = token.lower()
                if not lowered or lowered in seen:
                    continue
                seen.add(lowered)
                tokens.append(lowered)
            compact = compact_identifier(piece)
            if len(compact) >= 3 and compact not in seen:
                seen.add(compact)
                tokens.append(compact)
        whole_compact = compact_identifier(value)
        if len(whole_compact) >= 3 and whole_compact not in seen:
            tokens.append(whole_compact)
        return tokens

    def important_tokens(self, tokens: Sequence[str]) -> List[str]:
        selected: List[str] = []
        for token in tokens:
            if len(token) >= 3 or any(char.isdigit() for char in token):
                selected.append(token)
        return selected or [token for token in tokens if token]

    def path_segments(self, value: str) -> List[str]:
        segments: List[str] = []
        seen: Set[str] = set()
        for segment in re.split(r"[/\\]+", str(value or "").strip()):
            for token in self.unique_tokens(segment):
                if token not in seen:
                    seen.add(token)
                    segments.append(token)
        return segments

    def qualified_segments(self, value: str) -> List[str]:
        text = str(value or "").strip()
        if "." not in text:
            return []
        segments: List[str] = []
        seen: Set[str] = set()
        for segment in text.split("."):
            for token in self.unique_tokens(segment):
                if token not in seen:
                    seen.add(token)
                    segments.append(token)
        return segments

    def profile(
        self,
        raw: str,
        normalized: str,
        path_segments: Sequence[str],
        qualified_segments: Sequence[str],
        endpoint_path: Optional[str],
    ) -> SearchQueryProfile:
        stripped = raw.strip()
        has_space = bool(re.search(r"\s", stripped))
        has_slash = "/" in stripped or "\\" in stripped
        has_dot = "." in stripped
        has_identifier_shape = bool(re.search(r"[A-Za-z][A-Za-z0-9_.$:/\\-]*", stripped))
        has_human_shape = has_space and len(self.unique_tokens(stripped)) > 1
        if endpoint_path and has_space:
            return SearchQueryProfile.MIXED
        if endpoint_path and (stripped.startswith("/") or "/api/" in normalized):
            return SearchQueryProfile.ENDPOINT_LIKE
        if has_slash:
            return SearchQueryProfile.MIXED if has_space else SearchQueryProfile.PATH_LIKE
        if has_dot and _FILE_EXTENSION_RE.match(stripped):
            return SearchQueryProfile.PATH_LIKE
        if has_dot and len([segment for segment in stripped.split(".") if segment]) >= 3 and qualified_segments:
            return SearchQueryProfile.MIXED if has_space else SearchQueryProfile.QUALIFIED_NAME_LIKE
        if has_human_shape and has_identifier_shape and re.search(r"[.$:/\\_-]", stripped):
            return SearchQueryProfile.MIXED
        if has_human_shape:
            return SearchQueryProfile.HUMAN_TEXT_LIKE
        return SearchQueryProfile.IDENTIFIER_LIKE

    def _pieces(self, value: str) -> List[str]:
        return _WORD_RE.findall(str(value or ""))

    def _split_identifier_piece(self, piece: str) -> List[str]:
        if not piece:
            return []
        split = _CAMEL_BOUNDARY_2.sub(" ", piece)
        split = _CAMEL_BOUNDARY_1.sub(" ", split)
        return [part for part in split.split() if part]


_NORMALIZER = QueryNormalizer()


def compact_identifier(value: str) -> str:
    return "".join(char.lower() for char in str(value or "") if char.isalnum())


def first_endpoint_path(value: str) -> Optional[str]:
    match = _ENDPOINT_RE.search(str(value or "").replace("\\", "/"))
    if not match:
        return None
    return match.group(0).lower()


def _json_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    try:
        return json.dumps(value, sort_keys=True)
    except TypeError:
        return str(value)


@dataclass(frozen=True)
class SearchDocument:
    source_id: str
    node_id: str
    node_kind: str
    name: str
    label: str
    stable_key: str = ""
    qualified_name: str = ""
    relative_path: str = ""
    file_name: str = ""
    file_stem: str = ""
    endpoint_path: str = ""
    graph_id: Optional[str] = None
    graph_revision: Optional[str] = None
    flow_domain: str = ""
    summary: str = ""
    metadata_text: str = ""
    signature: str = ""
    declaring_file: str = ""
    line_start: Optional[int] = None
    line_end: Optional[int] = None
    confidence: float = 0.0
    degree: int = 0
    token_set: frozenset[str] = field(default_factory=frozenset)
    path_segments: Tuple[str, ...] = ()
    qualified_segments: Tuple[str, ...] = ()
    exact_values: Tuple[Tuple[str, str], ...] = ()
    compact_values: Tuple[Tuple[str, str], ...] = ()
    fuzzy_values: Tuple[Tuple[str, str], ...] = ()

    @classmethod
    def from_graph_node(cls, row: Dict[str, Any]) -> "SearchDocument":
        source_id = str(row.get("sourceId") or row.get("source_id") or "")
        node_id = str(row.get("id") or row.get("nodeId") or "")
        node_kind = str(row.get("nodeKind") or row.get("node_kind") or "")
        name = str(row.get("name") or "")
        label = str(row.get("label") or row.get("displayName") or row.get("display_name") or name or node_id)
        stable_key = str(row.get("stableKey") or row.get("stable_key") or node_id)
        qualified_name = str(row.get("qualifiedName") or row.get("qualified_name") or "")
        relative_path = str(row.get("relativePath") or row.get("relative_path") or "")
        summary = str(row.get("summary") or "")
        metadata_text = _json_text(row.get("metadataText") or row.get("metadata") or row.get("metadata_json") or "")
        signature = str(row.get("signature") or "")
        declaring_file = str(row.get("declaringFile") or row.get("declaring_file") or "")

        path_source = relative_path
        if not path_source and node_kind.upper() == "FILE":
            for candidate in (name, label, stable_key):
                if "/" in candidate or "\\" in candidate:
                    path_source = candidate
                    break
        file_name = os.path.basename(path_source.replace("\\", "/")) if path_source else ""
        if not file_name and node_kind.upper() == "FILE":
            file_name = os.path.basename((name or label).replace("\\", "/"))
        file_stem = file_name.rsplit(".", 1)[0] if "." in file_name else file_name

        endpoint_path = ""
        for value in (metadata_text, name, label, qualified_name, stable_key, relative_path):
            endpoint_path = first_endpoint_path(value) or ""
            if endpoint_path:
                break

        values = {
            "ID": node_id,
            "STABLE_KEY": stable_key,
            "KIND": node_kind,
            "NAME": name,
            "LABEL": label,
            "QUALIFIED_NAME": qualified_name,
            "PATH": relative_path,
            "FILE_NAME": file_name,
            "FILE_STEM": file_stem,
            "ENDPOINT": endpoint_path,
            "SIGNATURE": signature,
            "DECLARING_FILE": declaring_file,
        }
        exact_values = _field_values(values)
        compact_values = tuple((field_name, compact) for field_name, value in values.items() if (compact := compact_identifier(value)))
        fuzzy_values = _fuzzy_values(values)

        tokens: Set[str] = set()
        for value in [*values.values(), summary, metadata_text]:
            tokens.update(_NORMALIZER.unique_tokens(value))
        path_segments = tuple(_NORMALIZER.path_segments(" ".join(value for value in (relative_path, endpoint_path, declaring_file) if value)))
        qualified_segments = tuple(_NORMALIZER.qualified_segments(qualified_name))
        return cls(
            source_id=source_id,
            node_id=node_id,
            node_kind=node_kind,
            name=name,
            label=label,
            stable_key=stable_key,
            qualified_name=qualified_name,
            relative_path=relative_path,
            file_name=file_name,
            file_stem=file_stem,
            endpoint_path=endpoint_path,
            graph_id=str(row.get("graphId") or row.get("graph_id") or "") or None,
            graph_revision=str(row.get("graphRevision") or row.get("graph_revision") or "") or None,
            flow_domain=str(row.get("flowDomain") or row.get("flow_domain") or ""),
            summary=summary,
            metadata_text=metadata_text,
            signature=signature,
            declaring_file=declaring_file,
            line_start=_optional_int(row.get("lineStart") or row.get("line_start")),
            line_end=_optional_int(row.get("lineEnd") or row.get("line_end")),
            confidence=_optional_float(row.get("confidence")),
            degree=int(row.get("degree") or row.get("graph_degree") or 0),
            token_set=frozenset(tokens),
            path_segments=path_segments,
            qualified_segments=qualified_segments,
            exact_values=exact_values,
            compact_values=compact_values,
            fuzzy_values=fuzzy_values,
        )

    def to_matched_node_dict(self, score: float, reasons: Sequence[str]) -> Dict[str, Any]:
        return {
            "sourceId": self.source_id,
            "nodeId": self.node_id,
            "stableKey": self.stable_key or self.node_id,
            "nodeKind": self.node_kind,
            "label": self.label or self.name or self.node_id,
            "score": round(score, 4),
            "matchReasons": list(reasons),
            "graphId": self.graph_id,
            "graphRevision": self.graph_revision,
            "relativePath": self.relative_path or None,
            "qualifiedName": self.qualified_name or None,
            "flowDomain": self.flow_domain or None,
        }


def _field_values(values: Dict[str, str]) -> Tuple[Tuple[str, str], ...]:
    pairs: List[Tuple[str, str]] = []
    seen: Set[Tuple[str, str]] = set()
    for field_name, value in values.items():
        normalized = _NORMALIZER.normalize_text(value)
        if not normalized:
            continue
        key = (field_name, normalized)
        if key in seen:
            continue
        seen.add(key)
        pairs.append(key)
    return tuple(pairs)


def _fuzzy_values(values: Dict[str, str]) -> Tuple[Tuple[str, str], ...]:
    fields = ("NAME", "LABEL", "FILE_NAME", "FILE_STEM", "QUALIFIED_NAME", "ENDPOINT")
    pairs: List[Tuple[str, str]] = []
    seen: Set[Tuple[str, str]] = set()
    for field_name in fields:
        value = values.get(field_name) or ""
        if field_name == "QUALIFIED_NAME" and "." in value:
            value = value.rsplit(".", 1)[-1]
        compact = compact_identifier(value)
        if len(compact) < 5:
            continue
        key = (field_name, compact)
        if key in seen:
            continue
        seen.add(key)
        pairs.append(key)
    return tuple(pairs)


def _optional_int(value: Any) -> Optional[int]:
    if value is None or value == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _optional_float(value: Any) -> float:
    try:
        return float(value or 0.0)
    except (TypeError, ValueError):
        return 0.0


@dataclass(frozen=True)
class SearchConfig:
    max_candidates_per_provider: int = 100
    min_lexical_score: float = 0.28
    min_fuzzy_score: float = 0.58
    fuzzy_max_edit_distance: int = 3
    enable_fuzzy_search: bool = True
    source_revisions: Mapping[str, str] = field(default_factory=dict)
    document_hydrator: Optional[Callable[[Sequence[Tuple[str, str]]], Sequence[SearchDocument]]] = field(default=None, repr=False, compare=False)


@dataclass(frozen=True)
class SearchCandidate:
    document: SearchDocument
    provider: str
    reason: str
    score: float
    confidence: str
    priority: int
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class MergedCandidate:
    document: SearchDocument
    score: float
    confidence: str
    priority: int
    reasons: List[str] = field(default_factory=list)
    providers: List[str] = field(default_factory=list)
    retrieval_phases: list[str] = field(default_factory=list)
    query_inputs: list[str] = field(default_factory=list)
    contributions: list[dict[str, Any]] = field(default_factory=list)


@dataclass(frozen=True)
class SearchRunResult:
    candidates: List[MergedCandidate]
    candidate_limit_reached: bool = False
    low_confidence_matches: bool = False
    diagnostics: List[Dict[str, Any]] = field(default_factory=list)
    raw_candidates: List[SearchCandidate] = field(default_factory=list)


class CandidateProvider:
    name = "CandidateProvider"

    def search(self, query: SearchQuery, documents: Sequence[SearchDocument], config: SearchConfig) -> List[SearchCandidate]:
        raise NotImplementedError

    def _candidate(self, document: SearchDocument, reason: str, score: float, confidence: str, priority: int) -> SearchCandidate:
        return SearchCandidate(
            document=document,
            provider=self.name,
            reason=reason,
            score=max(0.0, min(score, 1.0)),
            confidence=confidence,
            priority=priority,
        )


class ExactCandidateProvider(CandidateProvider):
    name = "ExactCandidateProvider"

    _SCORES = {
        "ID": ("EXACT_ID", 0.94),
        "STABLE_KEY": ("EXACT_STABLE_KEY", 0.97),
        "KIND": ("EXACT_KIND", 0.55),
        "NAME": ("EXACT_NAME", 0.98),
        "LABEL": ("EXACT_LABEL", 0.97),
        "QUALIFIED_NAME": ("EXACT_QUALIFIED_NAME", 0.995),
        "PATH": ("EXACT_PATH", 0.99),
        "FILE_NAME": ("EXACT_FILE_NAME", 1.0),
        "FILE_STEM": ("EXACT_FILE_STEM", 0.965),
        "ENDPOINT": ("EXACT_ENDPOINT", 0.995),
        "SIGNATURE": ("EXACT_SIGNATURE", 0.94),
        "DECLARING_FILE": ("EXACT_DECLARING_FILE", 0.91),
    }

    _COMPACT_SCORES = {
        "NAME": ("EXACT_IDENTIFIER_COMPACT", 0.955),
        "LABEL": ("EXACT_LABEL_COMPACT", 0.94),
        "QUALIFIED_NAME": ("EXACT_QUALIFIED_COMPACT", 0.945),
        "FILE_NAME": ("EXACT_FILE_COMPACT", 0.965),
        "FILE_STEM": ("EXACT_FILE_STEM_COMPACT", 0.965),
        "ENDPOINT": ("EXACT_ENDPOINT_COMPACT", 0.94),
        "ID": ("EXACT_ID_COMPACT", 0.91),
        "STABLE_KEY": ("EXACT_STABLE_KEY_COMPACT", 0.9),
    }

    def search(self, query: SearchQuery, documents: Sequence[SearchDocument], config: SearchConfig) -> List[SearchCandidate]:
        results: List[SearchCandidate] = []
        query_lower = query.lower.strip()
        query_endpoint = query.endpoint_path
        for document in documents:
            for field_name, value in document.exact_values:
                if query_lower and value == query_lower:
                    if field_name in {"FILE_NAME", "FILE_STEM"} and document.node_kind.upper() != "FILE":
                        continue
                    reason, score = self._SCORES.get(field_name, ("EXACT_FIELD", 0.9))
                    if reason == "EXACT_KIND" and len(query_lower) < 4:
                        continue
                    results.append(self._candidate(document, reason, score, "HIGH" if score >= 0.9 else "LOW", 10))
            if query_endpoint and document.endpoint_path and query_endpoint == document.endpoint_path:
                results.append(self._candidate(document, "EXACT_ENDPOINT", 0.995, "HIGH", 10))
            if len(query.compact) >= 4:
                for field_name, value in document.compact_values:
                    if value == query.compact:
                        if field_name in {"FILE_NAME", "FILE_STEM"} and document.node_kind.upper() != "FILE":
                            continue
                        reason, score = self._COMPACT_SCORES.get(field_name, ("EXACT_COMPACT", 0.9))
                        results.append(self._candidate(document, reason, score, "HIGH", 12))
                    elif field_name == "STABLE_KEY" and _stable_key_segment_match(query.compact, value, document.stable_key):
                        results.append(self._candidate(document, "STABLE_KEY_SEGMENT", 0.9, "HIGH", 18))
        return results


class PathCandidateProvider(CandidateProvider):
    name = "PathCandidateProvider"

    def search(self, query: SearchQuery, documents: Sequence[SearchDocument], config: SearchConfig) -> List[SearchCandidate]:
        if query.profile not in {
            SearchQueryProfile.PATH_LIKE,
            SearchQueryProfile.ENDPOINT_LIKE,
            SearchQueryProfile.MIXED,
        }:
            return []
        results: List[SearchCandidate] = []
        query_path = query.lower.replace("\\", "/").strip()
        query_path_stripped = query_path.strip("/")
        query_segments = tuple(segment for segment in query.path_segments if segment)
        for document in documents:
            path_values = [
                ("PATH", document.relative_path),
                ("FILE_NAME", document.file_name),
                ("FILE_STEM", document.file_stem),
                ("ENDPOINT", document.endpoint_path),
                ("DECLARING_FILE", document.declaring_file),
            ]
            for field_name, value in path_values:
                if field_name in {"FILE_NAME", "FILE_STEM"} and document.node_kind.upper() != "FILE":
                    continue
                lowered = str(value or "").replace("\\", "/").lower().strip()
                stripped = lowered.strip("/")
                if not lowered:
                    continue
                if query_path and lowered == query_path:
                    results.append(self._candidate(document, f"PATH_EXACT_{field_name}", 0.985, "HIGH", 18))
                elif query_path_stripped and stripped.endswith(query_path_stripped) and _path_boundary_match(stripped, query_path_stripped):
                    score = 0.94 if field_name in {"PATH", "ENDPOINT"} else 0.91
                    results.append(self._candidate(document, f"PATH_SUFFIX_{field_name}", score, "HIGH", 20))
                elif query.compact and len(query.compact) >= 4 and compact_identifier(stripped) == query.compact:
                    results.append(self._candidate(document, f"PATH_COMPACT_{field_name}", 0.9, "HIGH", 22))
            if query_segments and document.path_segments:
                overlap = _ordered_segment_overlap(query_segments, document.path_segments)
                if overlap:
                    coverage = overlap / max(len(query_segments), 1)
                    if coverage >= 0.5:
                        score = min(0.88, 0.48 + 0.36 * coverage)
                        results.append(self._candidate(document, "PATH_SEGMENT_MATCH", score, "MEDIUM" if score < 0.82 else "HIGH", 28))
        return results


class QualifiedNameCandidateProvider(CandidateProvider):
    name = "QualifiedNameCandidateProvider"

    def search(self, query: SearchQuery, documents: Sequence[SearchDocument], config: SearchConfig) -> List[SearchCandidate]:
        if query.profile not in {SearchQueryProfile.QUALIFIED_NAME_LIKE, SearchQueryProfile.IDENTIFIER_LIKE, SearchQueryProfile.MIXED}:
            return []
        results: List[SearchCandidate] = []
        query_lower = query.lower
        query_compact = query.compact
        query_segments = tuple(segment for segment in query.qualified_segments if segment)
        for document in documents:
            qualified = document.qualified_name.lower()
            if not qualified:
                continue
            qualified_segments = tuple(segment for segment in qualified.split(".") if segment)
            last_segment = qualified_segments[-1] if qualified_segments else qualified
            if query_lower == qualified:
                results.append(self._candidate(document, "QUALIFIED_NAME_EXACT", 0.995, "HIGH", 14))
            elif query_lower and qualified.endswith(f".{query_lower}"):
                results.append(self._candidate(document, "QUALIFIED_NAME_SUFFIX", 0.94, "HIGH", 24))
            elif query_lower == last_segment:
                results.append(self._candidate(document, "QUALIFIED_NAME_LEAF", 0.925, "HIGH", 26))
            elif query_segments and _qualified_suffix(query_segments, qualified_segments):
                results.append(self._candidate(document, "QUALIFIED_SEGMENT_SUFFIX", 0.9, "HIGH", 28))
            elif len(query_compact) >= 6:
                qualified_compact = compact_identifier(qualified)
                leaf_compact = compact_identifier(last_segment)
                if qualified_compact.endswith(query_compact):
                    results.append(self._candidate(document, "QUALIFIED_COMPACT_SUFFIX", 0.84, "MEDIUM", 32))
                elif leaf_compact and (leaf_compact.endswith(query_compact) or query_compact.endswith(leaf_compact)):
                    results.append(self._candidate(document, "QUALIFIED_LEAF_COMPACT", 0.82, "MEDIUM", 34))
        return results


class LexicalCandidateProvider(CandidateProvider):
    name = "LexicalCandidateProvider"

    def search(self, query: SearchQuery, documents: Sequence[SearchDocument], config: SearchConfig) -> List[SearchCandidate]:
        query_tokens = tuple(token for token in query.important_tokens if len(token) >= 3 or any(char.isdigit() for char in token))
        if not query_tokens:
            return []
        query_set = set(query_tokens)
        results: List[SearchCandidate] = []
        for document in documents:
            overlap = query_set.intersection(document.token_set)
            if not overlap:
                continue
            coverage = len(overlap) / len(query_set)
            precision = len(overlap) / max(min(len(document.token_set), len(query_set) + 8), 1)
            prefix_suffix = _prefix_suffix_score(query_set, document.token_set)
            score = 0.22 + 0.42 * coverage + 0.16 * precision + 0.08 * prefix_suffix
            if overlap.intersection(set(_NORMALIZER.unique_tokens(document.file_name))):
                score += 0.05
            if overlap.intersection(set(document.qualified_segments)):
                score += 0.04
            if document.node_kind.upper() == "FILE" and query.profile == SearchQueryProfile.PATH_LIKE:
                score += 0.04
            if document.node_kind.upper() in {"TYPE", "CALLABLE"} and query.profile == SearchQueryProfile.QUALIFIED_NAME_LIKE:
                score += 0.03
            score = min(score, 0.82)
            if score >= config.min_lexical_score:
                reason = "LEXICAL_FULL_COVERAGE" if coverage >= 0.999 else "LEXICAL_TOKEN_OVERLAP"
                confidence = "HIGH" if score >= 0.78 else "MEDIUM" if score >= 0.48 else "LOW"
                results.append(self._candidate(document, reason, score, confidence, 42))
        return results


class FuzzyCandidateProvider(CandidateProvider):
    name = "FuzzyCandidateProvider"

    def search(self, query: SearchQuery, documents: Sequence[SearchDocument], config: SearchConfig) -> List[SearchCandidate]:
        if not config.enable_fuzzy_search or len(query.compact) < 5:
            return []
        if query.profile in {SearchQueryProfile.PATH_LIKE, SearchQueryProfile.ENDPOINT_LIKE} and ("/" in query.raw or "\\" in query.raw):
            return []
        results: List[SearchCandidate] = []
        max_distance = max(1, min(config.fuzzy_max_edit_distance, max(1, len(query.compact) // 4)))
        for document in documents:
            best: Optional[Tuple[str, float, int]] = None
            for field_name, value in document.fuzzy_values:
                if value == query.compact:
                    continue
                length_delta = abs(len(value) - len(query.compact))
                if length_delta > max_distance:
                    continue
                distance = damerau_levenshtein(query.compact, value, max_distance)
                if distance is None:
                    continue
                similarity = 1.0 - (distance / max(len(query.compact), len(value), 1))
                score = min(0.72, 0.46 + 0.27 * similarity)
                if score < config.min_fuzzy_score:
                    continue
                if best is None or score > best[1] or (score == best[1] and distance < best[2]):
                    best = (field_name, score, distance)
            if best is not None:
                field_name, score, distance = best
                reason = "FUZZY_NAME" if field_name in {"NAME", "LABEL", "QUALIFIED_NAME"} else "FUZZY_PATH"
                results.append(self._candidate(document, f"{reason}_EDIT_DISTANCE_{distance}", score, "MEDIUM", 64))
        return results


def _path_boundary_match(path_value: str, query_value: str) -> bool:
    if path_value == query_value:
        return True
    index = path_value.rfind(query_value)
    if index <= 0:
        return False
    return path_value[index - 1] in {"/", ".", "-", "_"}


def _stable_key_segment_match(query_compact: str, stable_compact: str, stable_key: str) -> bool:
    if not query_compact or len(query_compact) < 4 or query_compact not in stable_compact:
        return False
    for piece in _NORMALIZER.unique_tokens(stable_key):
        if compact_identifier(piece) == query_compact:
            return True
    return False


def _ordered_segment_overlap(query_segments: Sequence[str], document_segments: Sequence[str]) -> int:
    count = 0
    doc_index = 0
    for query_segment in query_segments:
        found = False
        while doc_index < len(document_segments):
            document_segment = document_segments[doc_index]
            doc_index += 1
            if query_segment == document_segment or document_segment.startswith(query_segment) or query_segment.startswith(document_segment):
                found = True
                break
        if found:
            count += 1
    return count


def _qualified_suffix(query_segments: Sequence[str], qualified_segments: Sequence[str]) -> bool:
    if not query_segments or len(query_segments) > len(qualified_segments):
        return False
    return tuple(qualified_segments[-len(query_segments) :]) == tuple(query_segments)


def _prefix_suffix_score(query_tokens: Set[str], document_tokens: Iterable[str]) -> float:
    score = 0.0
    document_tokens = tuple(document_tokens)
    for token in query_tokens:
        if any(value.startswith(token) or value.endswith(token) or token.startswith(value) for value in document_tokens if len(value) >= 3):
            score += 1.0
    return score / max(len(query_tokens), 1)


def damerau_levenshtein(left: str, right: str, max_distance: int) -> Optional[int]:
    if left == right:
        return 0
    if abs(len(left) - len(right)) > max_distance:
        return None
    previous_previous: Optional[List[int]] = None
    previous = list(range(len(right) + 1))
    for i, left_char in enumerate(left, start=1):
        current = [i] + [0] * len(right)
        row_min = current[0]
        for j, right_char in enumerate(right, start=1):
            cost = 0 if left_char == right_char else 1
            current[j] = min(
                previous[j] + 1,
                current[j - 1] + 1,
                previous[j - 1] + cost,
            )
            if (
                previous_previous is not None
                and i > 1
                and j > 1
                and left_char == right[j - 2]
                and left[i - 2] == right_char
            ):
                current[j] = min(current[j], previous_previous[j - 2] + 1)
            row_min = min(row_min, current[j])
        if row_min > max_distance:
            return None
        previous_previous = previous
        previous = current
    distance = previous[-1]
    return distance if distance <= max_distance else None


_CONFIDENCE_RANK = {"LOW": 1, "MEDIUM": 2, "HIGH": 3}
_REASON_ALIASES = {
    "EXACT_NAME": "NAME_MATCH",
    "EXACT_LABEL": "NAME_MATCH",
    "EXACT_IDENTIFIER_COMPACT": "NAME_MATCH",
    "EXACT_QUALIFIED_NAME": "QUALIFIED_NAME_MATCH",
    "QUALIFIED_NAME_EXACT": "QUALIFIED_NAME_MATCH",
    "QUALIFIED_NAME_SUFFIX": "QUALIFIED_NAME_MATCH",
    "QUALIFIED_NAME_LEAF": "QUALIFIED_NAME_MATCH",
    "EXACT_QUALIFIED_COMPACT": "QUALIFIED_NAME_MATCH",
    "EXACT_STABLE_KEY": "STABLE_KEY_MATCH",
    "EXACT_STABLE_KEY_COMPACT": "STABLE_KEY_MATCH",
    "STABLE_KEY_SEGMENT": "STABLE_KEY_MATCH",
    "EXACT_PATH": "PATH_MATCH",
    "EXACT_FILE_NAME": "PATH_MATCH",
    "EXACT_FILE_STEM": "PATH_MATCH",
    "EXACT_FILE_COMPACT": "PATH_MATCH",
    "EXACT_FILE_STEM_COMPACT": "PATH_MATCH",
    "PATH_EXACT_PATH": "PATH_MATCH",
    "PATH_EXACT_FILE_NAME": "PATH_MATCH",
    "PATH_EXACT_ENDPOINT": "PATH_MATCH",
    "EXACT_ENDPOINT": "PATH_MATCH",
}


class CandidateMerger:
    def merge(self, candidates: Sequence[SearchCandidate]) -> list[MergedCandidate]:
        merged: dict[tuple[str, str, str], MergedCandidate] = {}
        for candidate in candidates:
            key = (
                candidate.document.source_id,
                candidate.document.graph_revision or candidate.document.graph_id or "",
                candidate.document.node_id,
            )
            current = merged.get(key)
            if current is None:
                current = MergedCandidate(
                    document=candidate.document,
                    score=candidate.score,
                    confidence=candidate.confidence,
                    priority=candidate.priority,
                )
                merged[key] = current
            else:
                current.score = max(current.score, candidate.score)
                current.priority = min(current.priority, candidate.priority)
                if _CONFIDENCE_RANK[candidate.confidence] > _CONFIDENCE_RANK[current.confidence]:
                    current.confidence = candidate.confidence
            _append_unique(current.providers, candidate.provider)
            _append_reason(current.reasons, candidate.reason)
            alias = _REASON_ALIASES.get(candidate.reason)
            if alias:
                _append_reason(current.reasons, alias)
            metadata = dict(candidate.metadata or {})
            phase = str(metadata.get("retrievalPhase") or "")
            query_input = str(metadata.get("queryInput") or "")
            if phase:
                _append_unique(current.retrieval_phases, phase)
            if query_input:
                _append_unique(current.query_inputs, query_input)
            current.contributions.append(
                {
                    "provider": candidate.provider,
                    "reason": candidate.reason,
                    "score": round(float(candidate.score), 6),
                    "sourceId": candidate.document.source_id,
                    "graphRevision": candidate.document.graph_revision or candidate.document.graph_id or None,
                    "queryReason": metadata.get("queryReason"),
                    "queryInput": metadata.get("queryInput"),
                    "retrievalPhase": metadata.get("retrievalPhase"),
                }
            )

        for item in merged.values():
            provider_bonus = min(0.035, 0.012 * max(0, len(item.providers) - 1))
            reason_bonus = min(0.025, 0.004 * max(0, len(item.reasons) - 1))
            graph_bonus = min(0.015, max(0.0, item.document.confidence) * 0.006 + min(item.document.degree, 10) * 0.001)
            item.score = min(1.0, item.score + provider_bonus + reason_bonus + graph_bonus)
            item.reasons.sort(key=lambda value: (0 if value.startswith("EXACT") else 1, value))
            item.providers.sort()
            item.retrieval_phases.sort()
            item.query_inputs.sort()
            item.contributions.sort(
                key=lambda value: (
                    str(value.get("retrievalPhase") or ""),
                    str(value.get("queryReason") or ""),
                    str(value.get("queryInput") or ""),
                    str(value.get("provider") or ""),
                    str(value.get("reason") or ""),
                    -float(value.get("score") or 0.0),
                )
            )
        return sorted(merged.values(), key=self.sort_key)

    def sort_key(self, candidate: MergedCandidate) -> Tuple[float, int, str, str, str, str]:
        return (
            -round(candidate.score, 6),
            candidate.priority,
            candidate.document.source_id,
            candidate.document.node_kind,
            (candidate.document.label or candidate.document.name or "").lower(),
            candidate.document.node_id,
        )


def _append_unique(values: List[str], value: str) -> None:
    if value not in values:
        values.append(value)


def _append_reason(values: List[str], value: str) -> None:
    if value and value not in values:
        values.append(value)


class SearchRanker:
    def __init__(self, merger: CandidateMerger | None = None) -> None:
        self.merger = merger or CandidateMerger()

    def rank(self, candidates: Sequence[SearchCandidate]) -> List[MergedCandidate]:
        return self.merger.merge(candidates)


class DeterministicCodeSearchEngine:
    def __init__(
        self,
        normalizer: QueryNormalizer | None = None,
        providers: Sequence[CandidateProvider] | None = None,
        extra_broad_providers: Sequence[CandidateProvider] | None = None,
        ranker: SearchRanker | None = None,
    ) -> None:
        self.normalizer = normalizer or QueryNormalizer()
        self.precise_providers: Tuple[CandidateProvider, ...] = (
            ExactCandidateProvider(),
            PathCandidateProvider(),
            QualifiedNameCandidateProvider(),
        )
        self.broad_providers: Tuple[CandidateProvider, ...] = (
            LexicalCandidateProvider(),
            FuzzyCandidateProvider(),
        )
        self.supplemental_providers: Tuple[CandidateProvider, ...] = ()
        if providers is not None:
            self.precise_providers = tuple(providers)
            self.broad_providers = ()
        elif extra_broad_providers is not None:
            self.supplemental_providers = tuple(extra_broad_providers)
        self.ranker = ranker or SearchRanker()

    def search(self, raw_query: str, documents: Sequence[SearchDocument], config: SearchConfig) -> SearchRunResult:
        query = self.normalizer.normalize(raw_query)
        if not query.tokens or (not documents and not self.supplemental_providers):
            return SearchRunResult(candidates=[])

        all_candidates: List[SearchCandidate] = []
        diagnostics: List[Dict[str, Any]] = []
        candidate_limit_reached = False
        precise_candidates = self._run_providers(self.precise_providers, query, documents, config)
        all_candidates.extend(precise_candidates.candidates)
        diagnostics.extend(precise_candidates.diagnostics)
        candidate_limit_reached = candidate_limit_reached or precise_candidates.limit_reached

        broad_candidates = self._run_providers(self.broad_providers, query, documents, config)
        all_candidates.extend(broad_candidates.candidates)
        diagnostics.extend(broad_candidates.diagnostics)
        candidate_limit_reached = candidate_limit_reached or broad_candidates.limit_reached

        supplemental_candidates = self._run_providers(self.supplemental_providers, query, documents, config)
        all_candidates.extend(supplemental_candidates.candidates)
        diagnostics.extend(supplemental_candidates.diagnostics)
        candidate_limit_reached = candidate_limit_reached or supplemental_candidates.limit_reached

        ranked = self.ranker.rank(all_candidates) if all_candidates else []
        low_confidence_matches = bool(all_candidates and not ranked)
        return SearchRunResult(
            candidates=ranked,
            raw_candidates=list(all_candidates),
            candidate_limit_reached=candidate_limit_reached,
            low_confidence_matches=low_confidence_matches,
            diagnostics=diagnostics,
        )

    def _run_providers(
        self,
        providers: Sequence[CandidateProvider],
        query: SearchQuery,
        documents: Sequence[SearchDocument],
        config: SearchConfig,
    ) -> "_ProviderRun":
        results: List[SearchCandidate] = []
        results_diagnostics: List[Dict[str, Any]] = []
        limit_reached = False
        provider_limit = max(1, config.max_candidates_per_provider)
        for provider in providers:
            candidates = provider.search(query, documents, config)
            diagnostics = getattr(provider, "last_diagnostics", [])
            if diagnostics:
                results_diagnostics.extend([dict(item) for item in diagnostics])
            candidates.sort(key=lambda candidate: (-candidate.score, candidate.priority, candidate.document.source_id, candidate.document.node_id, candidate.reason))
            if len(candidates) > provider_limit:
                limit_reached = True
                candidates = self._bounded_provider_candidates(candidates, provider_limit)
            results.extend(candidates)
        return _ProviderRun(candidates=results, limit_reached=limit_reached, diagnostics=results_diagnostics)

    def _bounded_provider_candidates(self, candidates: Sequence[SearchCandidate], limit: int) -> list[SearchCandidate]:
        remaining_by_source: dict[str, list[SearchCandidate]] = {}
        for candidate in candidates:
            remaining_by_source.setdefault(candidate.document.source_id, []).append(candidate)
        selected: list[SearchCandidate] = []
        while remaining_by_source and len(selected) < limit:
            next_round: list[tuple[SearchCandidate, str]] = []
            for source_id, source_candidates in list(remaining_by_source.items()):
                if not source_candidates:
                    remaining_by_source.pop(source_id, None)
                    continue
                next_round.append((source_candidates[0], source_id))
            if not next_round:
                break
            next_round.sort(
                key=lambda item: (
                    -round(item[0].score, 6),
                    item[0].priority,
                    item[0].document.source_id,
                    item[0].document.node_id,
                    item[0].reason,
                )
            )
            for candidate, source_id in next_round:
                if len(selected) >= limit:
                    break
                selected.append(candidate)
                remaining_by_source[source_id] = remaining_by_source[source_id][1:]
                if not remaining_by_source[source_id]:
                    remaining_by_source.pop(source_id, None)
        return selected


@dataclass(frozen=True)
class _ProviderRun:
    candidates: List[SearchCandidate]
    limit_reached: bool = False
    diagnostics: List[Dict[str, Any]] = field(default_factory=list)
