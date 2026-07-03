from __future__ import annotations

import ast
import re
from pathlib import Path
from typing import List, Tuple

from knowledge_service import graph_schema


REPO_ROOT = Path(__file__).resolve().parents[3]

RUNTIME_SCAN_ROOTS = (
    REPO_ROOT / "services/forge-knowledge/src",
    REPO_ROOT / "services/forge-console/src",
    REPO_ROOT / "services/forge-nexus",
)

EXCLUDED_DIRECTORY_NAMES = {
    ".venv",
    "__pycache__",
    "build",
    "dist",
    "node_modules",
    "target",
}

FORBIDDEN_SNAPSHOT_RUNTIME_TERMS = (
    "graph_snapshots",
    "graph_current_snapshots",
    "graph_snapshot_metrics",
    "snapshot_id",
    "snapshotId",
    "current:{source}",
    "current_snapshot",
    "retired",
)

FORBIDDEN_OLD_NEW_RELINK_TERMS = (
    "old_file_id",
    "new_file_id",
    "oldFileId",
    "newFileId",
    "relink",
    "reattach",
)

GRAPH_SQL_SCAN_FILES = (
    REPO_ROOT / "services/forge-knowledge/src/knowledge_service/analysis_store.py",
    REPO_ROOT / "services/forge-knowledge/src/knowledge_service/semantic_builder.py",
    REPO_ROOT / "services/forge-knowledge/src/knowledge_service/semantic_index.py",
    REPO_ROOT / "services/forge-knowledge/src/knowledge_service/overview_projection.py",
)

FORBIDDEN_INLINE_GRAPH_SQL_VALUES = (
    "TRUSTED",
    "CANDIDATE",
    "REJECTED",
    "DERIVED",
    "STALE",
    "RESPONSIBILITY",
    "ENTRYPOINT_HINT",
    "FILE",
    "TYPE",
    "CALLABLE",
    "CALLS",
    "EXTERNAL",
    "UNKNOWN",
    "RESOLVED",
    "UNRESOLVED",
    "MULTIPLE_CANDIDATES",
    "EXTERNAL_TARGET",
    "CODE",
    "STATIC",
    "LLM",
)


def _is_excluded(path: Path) -> bool:
    return any(part in EXCLUDED_DIRECTORY_NAMES for part in path.parts)


def _iter_runtime_files(roots: Tuple[Path, ...]) -> List[Path]:
    files: List[Path] = []
    for root in roots:
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if _is_excluded(path) or not path.is_file():
                continue
            files.append(path)
    return sorted(files)


def _display_path(path: Path) -> str:
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def _find_forbidden_terms(roots: Tuple[Path, ...], forbidden_terms: Tuple[str, ...]) -> List[Tuple[str, str]]:
    matches: List[Tuple[str, str]] = []
    for path in _iter_runtime_files(roots):
        text = path.read_text(encoding="utf-8", errors="ignore")
        for term in forbidden_terms:
            if term in text:
                matches.append((_display_path(path), term))
    return matches


def test_forbidden_snapshot_runtime_terms_are_absent_from_production_sources():
    matches = _find_forbidden_terms(RUNTIME_SCAN_ROOTS, FORBIDDEN_SNAPSHOT_RUNTIME_TERMS)

    assert matches == []


def test_forbidden_old_new_relink_lifecycle_terms_are_absent_from_production_sources():
    matches = _find_forbidden_terms(RUNTIME_SCAN_ROOTS, FORBIDDEN_OLD_NEW_RELINK_TERMS)

    assert matches == []


def test_active_graph_sql_does_not_inline_yaml_contract_values():
    matches: List[Tuple[str, str]] = []
    for path in GRAPH_SQL_SCAN_FILES:
        text = path.read_text(encoding="utf-8")
        for value in FORBIDDEN_INLINE_GRAPH_SQL_VALUES:
            if f"'{value}'" in text:
                matches.append((_display_path(path), value))

    assert matches == []


def test_analysis_store_index_declarations_are_unique():
    path = REPO_ROOT / "services/forge-knowledge/src/knowledge_service/analysis_store.py"
    text = path.read_text(encoding="utf-8")
    method = text.split("def _create_analysis_indexes", 1)[1].split("\n    def ", 1)[0]
    index_names = re.findall(r"CREATE INDEX IF NOT EXISTS ([A-Za-z0-9_]+)", method)

    assert index_names
    assert sorted(index_names) == sorted(set(index_names))


def test_analysis_store_source_does_not_use_string_literal_concatenation():
    path = REPO_ROOT / "services/forge-knowledge/src/knowledge_service/analysis_store.py"
    tree = ast.parse(path.read_text(encoding="utf-8"))
    offenders = [
        node.lineno
        for node in ast.walk(tree)
        if isinstance(node, ast.BinOp)
        and isinstance(node.op, ast.Add)
        and isinstance(node.left, ast.Constant)
        and isinstance(node.left.value, str)
        and isinstance(node.right, ast.Constant)
        and isinstance(node.right.value, str)
    ]

    assert offenders == []


def test_graph_contract_enum_layer_is_not_reintroduced():
    removed_names = (
        "GraphNodeKind",
        "GraphEdgeType",
        "GraphClaimKind",
        "GraphResolutionStatus",
        "GraphFactStatus",
        "GraphFactOrigin",
        "GraphFlowDomain",
        "GraphEvidenceKind",
    )

    assert not (REPO_ROOT / "services/forge-knowledge/src/knowledge_service/graph_model.py").exists()
    assert [name for name in removed_names if hasattr(graph_schema, name)] == []


def test_runtime_guard_excludes_generated_target_output(tmp_path):
    generated = tmp_path / "services/forge-nexus/boot/target/test-classes/fixture.json"
    generated.parent.mkdir(parents=True)
    generated.write_text('{"snapshotId":"generated-only"}', encoding="utf-8")
    runtime = tmp_path / "services/forge-nexus/boot/src/main/java/App.java"
    runtime.parent.mkdir(parents=True)
    runtime.write_text("class App {}", encoding="utf-8")

    matches = _find_forbidden_terms((tmp_path / "services/forge-nexus",), FORBIDDEN_SNAPSHOT_RUNTIME_TERMS)

    assert generated not in _iter_runtime_files((tmp_path / "services/forge-nexus",))
    assert runtime in _iter_runtime_files((tmp_path / "services/forge-nexus",))
    assert matches == []


def test_runtime_guard_reports_forbidden_terms_in_scanned_runtime_paths(tmp_path):
    runtime = tmp_path / "services/forge-knowledge/src/knowledge_service/bad_runtime.py"
    runtime.parent.mkdir(parents=True)
    runtime.write_text("snapshot_id = 'bad'\n# relink should be rejected\n", encoding="utf-8")

    snapshot_matches = _find_forbidden_terms((tmp_path / "services/forge-knowledge/src",), FORBIDDEN_SNAPSHOT_RUNTIME_TERMS)
    lifecycle_matches = _find_forbidden_terms((tmp_path / "services/forge-knowledge/src",), FORBIDDEN_OLD_NEW_RELINK_TERMS)

    assert (str(runtime), "snapshot_id") in snapshot_matches
    assert (str(runtime), "relink") in lifecycle_matches
