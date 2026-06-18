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
        "\n".join(
            [
                "package demo;",
                "",
                "public interface JarvisGateway {",
                "  String askJarvis(String text);",
                "}",
            ]
        ),
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


def build_ranking_store(tmp_path):
    workspace = tmp_path / "workspace"
    auth = workspace / "auth-service"
    other = workspace / "other-service"
    (auth / "src/main/java/demo").mkdir(parents=True)
    (auth / "src/test/java/demo").mkdir(parents=True)
    (auth / ".github/workflows").mkdir(parents=True)
    (auth / "apis/auth/rest").mkdir(parents=True)
    (other / "src/main/java/demo").mkdir(parents=True)
    (auth / "src/main/java/demo/AuthFlow.java").write_text(
        "package demo;\nclass AuthFlow { void login() { /* auth token refresh provider */ } }\n",
        encoding="utf-8",
    )
    (auth / "src/test/java/demo/AuthFlowTest.java").write_text(
        "package demo;\nclass AuthFlowTest { void testAuthTokenRefreshProvider() {} }\n",
        encoding="utf-8",
    )
    (auth / ".github/workflows/auth-deploy.yml").write_text(
        "name: auth deploy workflow\non: workflow_dispatch\njobs: { deploy: {} }\n",
        encoding="utf-8",
    )
    (auth / "apis/auth/rest/openapi.yml").write_text(
        "openapi: 3.0.3\ninfo: { title: Auth API, version: '1' }\npaths: { /auth/login: {} }\n",
        encoding="utf-8",
    )
    (other / "src/main/java/demo/AuthClient.java").write_text(
        "package demo;\nclass AuthClient { void auth() {} }\n",
        encoding="utf-8",
    )
    catalog = tmp_path / "services.yaml"
    catalog.write_text(
        """services:
  authsvc:
    label: Authorisation Service
    path: auth-service
    group: backend
    tags: [java, auth]
    domain_keywords: [login, token, authentication]
    owns_business_areas: [Authorisation]
    contract_refs:
      api:
        root: auth-service/apis/auth/rest/openapi.yml
  other:
    label: Other Service
    path: other-service
    group: backend
    tags: [java]
""",
        encoding="utf-8",
    )
    config_file = tmp_path / "knowledge-sources.yaml"
    config_file.write_text(
        f"""catalog:
  path: "{catalog}"
  workspace_root: "{workspace}"
indexing:
  include: ["**/*.java", "**/*.yml"]
""",
        encoding="utf-8",
    )
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    InventoryBuilder(load_source_config(config_file), store).build([], [])
    return store


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


def test_runtime_source_ranks_above_test_for_explanation_query(tmp_path):
    store = build_ranking_store(tmp_path)
    result = ContextService(store).context(ContextRequest(query="explain auth flow", maxItems=5, includeContent=False))

    assert result["context"][0]["relativePath"] == "src/main/java/demo/AuthFlow.java"


def test_workflow_down_ranked_for_runtime_query(tmp_path):
    store = build_ranking_store(tmp_path)
    result = ContextService(store).context(ContextRequest(query="auth runtime", maxItems=5, includeContent=False))
    paths = [item["relativePath"] for item in result["context"]]

    assert paths.index("src/main/java/demo/AuthFlow.java") < paths.index(".github/workflows/auth-deploy.yml")


def test_workflow_preferred_for_deploy_query(tmp_path):
    store = build_ranking_store(tmp_path)
    result = ContextService(store).context(ContextRequest(query="auth deploy workflow", maxItems=5, includeContent=False))

    assert result["context"][0]["relativePath"] == ".github/workflows/auth-deploy.yml"


def test_tests_preferred_for_test_specific_query(tmp_path):
    store = build_ranking_store(tmp_path)
    result = ContextService(store).context(ContextRequest(query="auth tests", maxItems=5, includeContent=False))

    assert result["context"][0]["relativePath"] == "src/test/java/demo/AuthFlowTest.java"


def test_service_metadata_match_boosts_correct_source(tmp_path):
    store = build_ranking_store(tmp_path)
    result = ContextService(store).context(ContextRequest(query="authorisation auth", maxItems=5, includeContent=False))

    assert result["context"][0]["sourceId"] == "authsvc"


def test_contract_files_preferred_for_contract_query(tmp_path):
    store = build_ranking_store(tmp_path)
    result = ContextService(store).context(ContextRequest(query="auth openapi contract", maxItems=5, includeContent=False))

    assert result["context"][0]["relativePath"] == "apis/auth/rest/openapi.yml"


class pytest_raises_validation:
    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        assert exc_type is ValidationError
        return True
