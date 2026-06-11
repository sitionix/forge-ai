from __future__ import annotations

from pathlib import Path


def is_under_root(path: Path, root: Path) -> bool:
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except ValueError:
        return False


def safe_relative_path(path: Path, root: Path) -> str:
    resolved = path.resolve()
    root_resolved = root.resolve()
    resolved.relative_to(root_resolved)
    return resolved.relative_to(root_resolved).as_posix()
