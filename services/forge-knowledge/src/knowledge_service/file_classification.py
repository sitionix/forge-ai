from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

from knowledge_service.path_glob import PathGlobMatcher


UNKNOWN_FLOW_DOMAIN = "UNKNOWN"
UNKNOWN_LANGUAGE = "unknown"


@dataclass(frozen=True)
class FileClassification:
    extension: str
    language: str
    flow_domain: str


@dataclass(frozen=True)
class FileClassificationRule:
    name: str
    flow_domain: str
    extensions: List[str] = field(default_factory=list)
    filenames: List[str] = field(default_factory=list)
    path_patterns: List[str] = field(default_factory=list)
    path_matcher: PathGlobMatcher = field(default_factory=PathGlobMatcher, repr=False, compare=False)
    language: Optional[str] = None
    language_by_extension: Dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class FileClassifier:
    rules: List[FileClassificationRule] = field(default_factory=list)

    @classmethod
    def from_config(cls, config: Dict[str, Any]) -> "FileClassifier":
        raw_rules = config.get("rules") if isinstance(config, dict) else None
        rules = [
            FileClassificationRule(
                name=str(item.get("name") or ""),
                flow_domain=str(item.get("flow_domain") or UNKNOWN_FLOW_DOMAIN).upper(),
                extensions=_normalized_extensions(item.get("extensions")),
                filenames=_lower_list(item.get("filenames")),
                path_patterns=_string_list(item.get("path_patterns")),
                path_matcher=PathGlobMatcher(_string_list(item.get("path_patterns"))),
                language=str(item.get("language")) if item.get("language") else None,
                language_by_extension=_language_by_extension(item.get("language_by_extension")),
            )
            for item in (raw_rules or [])
            if isinstance(item, dict)
        ]
        return cls(rules=rules)

    def classify(self, relative_path: str, extension: Optional[str] = None) -> FileClassification:
        ext = self.extension(relative_path, extension)
        normalized_path = _normalized_path(relative_path)
        filename = normalized_path.rsplit("/", 1)[-1].lower()
        for rule in self.rules:
            if not self._matches(rule, normalized_path, filename, ext):
                continue
            return FileClassification(
                extension=ext,
                language=rule.language_by_extension.get(ext) or rule.language or UNKNOWN_LANGUAGE,
                flow_domain=rule.flow_domain or UNKNOWN_FLOW_DOMAIN,
            )
        return FileClassification(extension=ext, language=UNKNOWN_LANGUAGE, flow_domain=UNKNOWN_FLOW_DOMAIN)

    def extension(self, relative_path: str, extension: Optional[str] = None) -> str:
        value = (extension or Path(str(relative_path or "")).suffix or "").strip().lower()
        if value and not value.startswith("."):
            return f".{value}"
        return value

    def _matches(self, rule: FileClassificationRule, normalized_path: str, filename: str, extension: str) -> bool:
        if rule.path_patterns and not rule.path_matcher.matches(normalized_path):
            return False
        if rule.filenames and filename not in rule.filenames:
            return False
        if rule.extensions and extension not in rule.extensions:
            return False
        return bool(rule.path_patterns or rule.filenames or rule.extensions)


def _normalized_path(value: str) -> str:
    return str(value or "").replace("\\", "/")


def _lower_list(value: Any) -> List[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip().lower() for item in value if item is not None and str(item).strip()]


def _string_list(value: Any) -> List[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip() for item in value if item is not None and str(item).strip()]


def _normalized_extensions(value: Any) -> List[str]:
    result = []
    for item in _lower_list(value):
        result.append(item if item.startswith(".") else f".{item}")
    return result


def _language_by_extension(value: Any) -> Dict[str, str]:
    if not isinstance(value, dict):
        return {}
    return {
        key if key.startswith(".") else f".{key}": str(language).strip().lower()
        for key, language in ((str(k).strip().lower(), v) for k, v in value.items())
        if key and language is not None and str(language).strip()
    }
