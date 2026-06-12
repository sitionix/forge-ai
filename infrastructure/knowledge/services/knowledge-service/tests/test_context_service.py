import sqlite3

from pydantic import ValidationError

from knowledge_service.context_schema import ContextRequest
from knowledge_service.context_service import ContextService
from knowledge_service.inventory_builder import InventoryBuilder
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.snippet_extractor import SnippetExtractor
from knowledge_service.source_config import load_source_config


def build_context_store(tmp_path):
    workspace = tmp_path / "workspace"
    svc = workspace / "svc"
    other = workspace / "other"
    svc.mkdir(parents=True)
    other.mkdir(parents=True)
    (svc / "JarvisGateway.java").write_text(
        "\n".join([
            "package demo;",
            "",
            "public interface JarvisGateway {",
            "  String askJarvis(String text);",
            "}",
        ]),
        encoding="utf-8",
    )
    (svc / "README.md").write_text("# Jarvis\n\nJarvis infrastructure overview\n" + "\n".join(f"line {i}" for i in range(40)), encoding="utf-8")
    (other / "Payments.md").write_text("# Payments\n\nDomain notes\n", encoding="utf-8")
    (svc / "ignored.txt").write_text("OutsideInventory\n", encoding="utf-8")
    catalog = tmp_path / "services.yaml"
    catalog.write_text(
        """services:
  svc:
    label: Service
    path: svc
    group: backend
    tags: [java]
    domain_keywords: [jarvis]
    owns_business_areas: [assistant]
    contract_refs:
      api: jarvis-contract
  other:
    label: Other
    path: other
    group: frontend
    tags: [ui]
    domain_keywords: [payments]
""",
        encoding="utf-8",
    )
    config_file = tmp_path / "knowledge-sources.yaml"
    config_file.write_text(
        f"""catalog:
  path: "{catalog}"
  workspace_root: "{workspace}"
indexing:
  include: ["**/*.java", "**/*.md"]
""",
        encoding="utf-8",
    )
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    InventoryBuilder(load_source_config(config_file), store).build([], [])
    return store, svc


def test_context_request_validation():
    with pytest_raises_validation():
        ContextRequest(query="x", maxChars=999)
    with pytest_raises_validation():
        ContextRequest(query="x", maxItems=51)


def test_search_results_converted_to_context_items(tmp_path):
    store, _ = build_context_store(tmp_path)
    result = ContextService(store).context(ContextRequest(query="askJarvis", maxChars=12000, maxItems=10))

    assert result["context"][0]["relativePath"] == "JarvisGateway.java"
    assert result["context"][0]["matchType"] == "content"
    assert "askJarvis" in result["context"][0]["content"]


def test_snippet_extraction_before_after_lines():
    lines = [f"line {index}" for index in range(1, 31)]
    extractor = SnippetExtractor(before_lines=2, after_lines=3)

    assert extractor.content_range(lines, 10) == (8, 13)


def test_dedupe_overlapping_snippets(tmp_path):
    store, _ = build_context_store(tmp_path)
    result = ContextService(store).context(ContextRequest(query="Jarvis", maxChars=12000, maxItems=10))
    paths = [item["relativePath"] for item in result["context"]]

    assert paths.count("JarvisGateway.java") == 1


def test_budget_enforcement(tmp_path):
    store, _ = build_context_store(tmp_path)
    result = ContextService(store).context(ContextRequest(query="Jarvis", maxChars=1000, maxItems=10))

    assert result["budget"]["usedChars"] <= 1000


def test_ranking_path_match_above_weak_content_match(tmp_path):
    store, _ = build_context_store(tmp_path)
    result = ContextService(store).context(ContextRequest(query="JarvisGateway", maxChars=12000, maxItems=10))

    assert result["context"][0]["relativePath"] == "JarvisGateway.java"


def test_metadata_tag_domain_keyword_boost(tmp_path):
    store, _ = build_context_store(tmp_path)
    result = ContextService(store).context(ContextRequest(query="assistant", maxChars=12000, maxItems=10))

    assert result["context"]
    assert result["context"][0]["sourceId"] == "svc"


def test_include_content_false_hides_content_but_keeps_metadata(tmp_path):
    store, _ = build_context_store(tmp_path)
    result = ContextService(store).context(ContextRequest(query="Jarvis", includeContent=False))

    assert "content" in result["context"][0]
    assert result["context"][0]["content"] is None
    assert result["context"][0]["metadata"]["tags"] == ["java"]


def test_missing_inventory_returns_diagnostic(tmp_path):
    result = ContextService(InventoryStore(tmp_path / "empty.sqlite")).context(ContextRequest(query="Jarvis"))

    assert result["diagnostics"][0]["code"] == "INVENTORY_EMPTY"


def test_no_source_file_mutation(tmp_path):
    store, svc = build_context_store(tmp_path)
    path = svc / "README.md"
    before = path.read_text(encoding="utf-8")

    ContextService(store).context(ContextRequest(query="Jarvis"))

    assert path.read_text(encoding="utf-8") == before


def test_context_never_reads_outside_indexed_inventory(tmp_path):
    store, svc = build_context_store(tmp_path)
    assert ContextService(store).context(ContextRequest(query="OutsideInventory"))["context"] == []

    outside = tmp_path / "outside.md"
    outside.write_text("OutsideInventory indexed root escape\n", encoding="utf-8")
    with sqlite3.connect(store.db_path) as conn:
        conn.execute("UPDATE files SET absolute_path = ? WHERE relative_path = ?", (str(outside), "README.md"))

    result = ContextService(store).context(ContextRequest(query="OutsideInventory"))

    assert result["context"] == []


class pytest_raises_validation:
    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        assert exc_type is ValidationError
        return True
