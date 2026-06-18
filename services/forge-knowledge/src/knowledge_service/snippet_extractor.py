from __future__ import annotations

from pathlib import Path
from typing import List, Optional

from knowledge_service.path_security import is_under_root


class SnippetExtractor:
    def __init__(self, before_lines: int = 8, after_lines: int = 16):
        self.before_lines = before_lines
        self.after_lines = after_lines

    def read_lines(self, absolute_path: str, source_path: str) -> Optional[List[str]]:
        path = Path(absolute_path)
        root = Path(source_path)
        if not is_under_root(path, root):
            return None
        try:
            return path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            return None

    def content_range(self, lines: List[str], matched_line: int) -> tuple[int, int]:
        start = max(1, matched_line - self.before_lines)
        end = min(len(lines), matched_line + self.after_lines)
        return start, end

    def first_meaningful_range(self, lines: List[str], language: str, flow_domain: str) -> tuple[int, int]:
        if not lines:
            return 1, 1
        if (language or "").lower() == "markdown":
            first_heading = self._first_line(lines, lambda line: line.strip().startswith("#"))
            if first_heading:
                return self._markdown_section(lines, first_heading)
        if (flow_domain or "").upper() in {"CODE", "TEST"}:
            declaration = self._first_line(lines, self._looks_like_declaration)
            if declaration:
                return max(1, declaration - 8), min(len(lines), declaration + 40)
        return 1, min(len(lines), 40)

    def slice_content(self, lines: List[str], start: int, end: int, max_chars: int) -> tuple[str, int]:
        content = "\n".join(lines[start - 1 : end])
        if len(content) <= max_chars:
            return content, end
        truncated = content[:max_chars]
        line_count = truncated.count("\n") + 1 if truncated else 1
        return truncated, min(end, start + line_count - 1)

    def _first_line(self, lines: List[str], predicate) -> Optional[int]:
        for index, line in enumerate(lines, start=1):
            if predicate(line):
                return index
        return None

    def _markdown_section(self, lines: List[str], heading: int) -> tuple[int, int]:
        end = min(len(lines), heading + 40)
        for index in range(heading + 1, min(len(lines), heading + 40) + 1):
            if lines[index - 1].strip().startswith("#"):
                end = index - 1
                break
        return heading, max(heading, end)

    def _looks_like_declaration(self, line: str) -> bool:
        stripped = line.strip()
        return any(
            token in stripped
            for token in [
                " class ",
                " interface ",
                " enum ",
                " record ",
                " object ",
                " function ",
                " const ",
            ]
        ) or stripped.startswith(("class ", "interface ", "enum ", "record ", "public class ", "public interface "))
