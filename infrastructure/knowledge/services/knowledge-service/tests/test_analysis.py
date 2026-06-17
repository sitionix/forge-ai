import json
import os
import sqlite3
import threading
import time
from pathlib import Path

import pytest

os.environ.setdefault("KNOWLEDGE_STORE_PATH", "/tmp/forge-ai-knowledge-test-main.sqlite")

from knowledge_service import main
from knowledge_service.analysis_client import OllamaAnalysisClient
from knowledge_service.analysis_schema import AnalysisBuildRequest
from knowledge_service.analysis_service import AnalysisJobRunner
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.config import AppConfig
from knowledge_service.errors import KnowledgeError
from knowledge_service.freshness_service import KnowledgeFreshnessService
from knowledge_service.graph_analysis import GraphAnalysisEngine
from knowledge_service.graph_model import GraphEdgeFact, GraphNodeFact
from knowledge_service.graph_schema import (
    GRAPH_ANALYSIS_ENGINE_VERSION,
    GraphClaimKind,
    GraphEdgeType,
    GraphFactOrigin,
    GraphFlowDomain,
    GraphNodeKind,
    classify_flow_domain,
    enum_values,
)
from knowledge_service.graph_response_parser import GraphAnalysisResponseParser
from knowledge_service.graph_schema import GraphAnalysisResponse
from knowledge_service.graph_validation import GraphValidationErrorCode
from knowledge_service.inventory_builder import InventoryBuilder
from knowledge_service.inventory_file_resolver import InventoryFileContent, InventoryFileReadResult
from knowledge_service.inventory_refresh import BackgroundInventoryScheduler, InventoryRefreshService
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.source_config import load_source_config


class StubAnalyzer:
    name = "ai-file-analyzer"
    version = "1"

    def __init__(self, result=None, fail=False, block_event=None, bad_response_attempts=0, outcomes=None, factory=None):
        self.result = result
        self.fail = fail
        self.block_event = block_event
        self.bad_response_attempts = bad_response_attempts
        self.outcomes = list(outcomes or [])
        self.factory = factory
        self.repair_prompts = []
        self.calls = 0

    def analyze(self, payload, line_count, repair_prompt=None):
        self.calls += 1
        if repair_prompt:
            self.repair_prompts.append(repair_prompt)
        if self.block_event is not None:
            self.block_event.wait(2)
        if self.outcomes:
            outcome = self.outcomes.pop(0)
            if isinstance(outcome, Exception):
                raise outcome
            if callable(outcome):
                return GraphAnalysisResponse.parse_obj(outcome(payload, line_count))
            return outcome
        if self.calls <= self.bad_response_attempts:
            raise KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad", attempt=self.calls)
        if self.fail:
            raise RuntimeError("model failed")
        if self.factory is not None:
            return GraphAnalysisResponse.parse_obj(self.factory(payload, line_count))
        if self.result is not None:
            return self.result
        return GraphAnalysisResponse.parse_obj(valid_graph_response(payload, line_count))


class GraphStubAnalyzer:
    name = "ai-file-analyzer"
    version = "1"

    def __init__(self, factory):
        self.factory = factory
        self.calls = 0
        self.repair_prompts = []

    def analyze(self, payload, line_count, repair_prompt=None):
        self.calls += 1
        if repair_prompt:
            self.repair_prompts.append(repair_prompt)
        return GraphAnalysisResponse.parse_obj(self.factory(payload, line_count))


class CapturingAnalyzer(StubAnalyzer):
    def __init__(self):
        super().__init__(factory=lambda payload, line_count: graph_response(payload, line_count, nodes=[
            {"localId": "s1", "nodeKind": "TYPE", "name": "Resolved", "qualifiedName": "Resolved", "lineStart": 1, "lineEnd": 1, "confidence": 0.7, "metadata": {}},
        ]))
        self.payloads = []
        self.line_counts = []

    def analyze(self, payload, line_count, repair_prompt=None):
        self.payloads.append(payload)
        self.line_counts.append(line_count)
        return super().analyze(payload, line_count, repair_prompt)


class StubResolver:
    def __init__(self):
        self.calls = []

    def read(self, row):
        self.calls.append(row["relative_path"])
        content = "class Resolved {}"
        return InventoryFileReadResult(InventoryFileContent(
            row=row,
            metadata=json.loads(row["metadata_json"]),
            lines=[content],
            content=content,
            lineCount=1,
            decodePolicy=row["decode_policy"],
        ))


def valid_graph_response(payload, line_count):
    type_end = max(1, min(line_count, 5))
    callable_start = max(1, min(line_count, 3))
    callable_end = max(callable_start, min(line_count, 4))
    return graph_response(payload, line_count,
        nodes=[
            {"localId": "s1", "nodeKind": "TYPE", "name": "ObjectHandler", "qualifiedName": "ObjectHandler", "lineStart": 1, "lineEnd": type_end, "confidence": 0.9, "metadata": {"language": "java"}},
            {"localId": "s2", "nodeKind": "CALLABLE", "name": "create", "qualifiedName": "ObjectHandler.create", "parentLocalId": "s1", "lineStart": callable_start, "lineEnd": callable_end, "confidence": 0.8, "metadata": {}},
        ],
        claims=[
            {"localId": "c1", "nodeLocalId": "s1", "claimKind": "ROLE", "summary": "HTTP_HANDLER", "evidence": [{"lineStart": 1, "lineEnd": type_end}], "confidence": 0.9, "metadata": {"role": "HTTP_HANDLER"}},
            {"localId": "c2", "nodeLocalId": "s2", "claimKind": "ROLE", "summary": "ENTRYPOINT", "evidence": [{"lineStart": callable_start, "lineEnd": callable_end}], "confidence": 0.8, "metadata": {"role": "ENTRYPOINT"}},
        ],
    )


def build_inventory(tmp_path, content=None, include_large=False, extra_files=None):
    workspace = tmp_path / "workspace"
    service = workspace / "edge-gateway"
    (service / "src/main/java/example").mkdir(parents=True)
    (service / "src/main/java/example/ObjectHandler.java").write_text(
        content or "public class ObjectHandler {\n  @PostMapping\n  public void create() {\n  }\n}\n",
        encoding="utf-8",
    )
    if include_large:
        (service / "src/main/java/example/LargeFile.java").write_text("x" * 200, encoding="utf-8")
    for relative_path, file_content in (extra_files or {}).items():
        path = service / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(file_content, encoding="utf-8")
    catalog = tmp_path / "services.yaml"
    catalog.write_text(
        f"""services:
  edge-gateway:
    label: Edge Gateway
    path: edge-gateway
    group: edge
    tags: [java]
""",
        encoding="utf-8",
    )
    config = tmp_path / "knowledge-sources.yaml"
    config.write_text(
        f"""catalog:
  path: "{catalog}"
  workspace_root: "{workspace}"
indexing:
  include: ["**/*.java"]
""",
        encoding="utf-8",
    )
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    InventoryBuilder(load_source_config(config), store).build([], [])
    return store, config, service


def build_python_inventory(tmp_path, content):
    workspace = tmp_path / "workspace"
    service = workspace / "python-service"
    (service / "app").mkdir(parents=True)
    (service / "app" / "handlers.py").write_text(content, encoding="utf-8")
    catalog = tmp_path / "services.yaml"
    catalog.write_text(
        f"""services:
  python-service:
    label: Python Service
    path: python-service
    group: py
    tags: [python]
""",
        encoding="utf-8",
    )
    config = tmp_path / "knowledge-sources.yaml"
    config.write_text(
        f"""catalog:
  path: "{catalog}"
  workspace_root: "{workspace}"
indexing:
  include: ["**/*.py"]
""",
        encoding="utf-8",
    )
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    InventoryBuilder(load_source_config(config), store).build([], [])
    return store, config, service


def build_custom_inventory(tmp_path, files, include):
    workspace = tmp_path / "workspace"
    service = workspace / "edge-gateway"
    for relative_path, file_content in files.items():
        path = service / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(file_content, encoding="utf-8")
    catalog = tmp_path / "services.yaml"
    catalog.write_text(
        f"""services:
  edge-gateway:
    label: Edge Gateway
    path: edge-gateway
    group: edge
    tags: [mixed]
""",
        encoding="utf-8",
    )
    config = tmp_path / "knowledge-sources.yaml"
    include_yaml = "\n".join(f'    - "{pattern}"' for pattern in include)
    config.write_text(
        f"""catalog:
  path: "{catalog}"
  workspace_root: "{workspace}"
indexing:
  include:
{include_yaml}
""",
        encoding="utf-8",
    )
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    InventoryBuilder(load_source_config(config), store).build([], [])
    return store, config, service


def build_two_service_inventory(tmp_path):
    workspace = tmp_path / "workspace"
    first = workspace / "first-service"
    second = workspace / "second-service"
    (first / "src").mkdir(parents=True)
    (second / "src").mkdir(parents=True)
    (first / "src" / "First.java").write_text("class First {}\n", encoding="utf-8")
    (second / "src" / "Second.java").write_text("class Second {}\n", encoding="utf-8")
    catalog = tmp_path / "services.yaml"
    catalog.write_text(
        """services:
  first-service:
    label: First Service
    path: first-service
    group: backend
  second-service:
    label: Second Service
    path: second-service
    group: backend
""",
        encoding="utf-8",
    )
    config = tmp_path / "knowledge-sources.yaml"
    config.write_text(
        f"""catalog:
  path: "{catalog}"
  workspace_root: "{workspace}"
indexing:
  include: ["**/*.java"]
""",
        encoding="utf-8",
    )
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    InventoryBuilder(load_source_config(config), store).build([], [])
    return store, config, first, second


def create_source_config(tmp_path, content=None):
    workspace = tmp_path / "workspace"
    service = workspace / "edge-gateway"
    (service / "src/main/java/example").mkdir(parents=True)
    (service / "src/main/java/example/ObjectHandler.java").write_text(
        content or "public class ObjectHandler {\n  public void create() {}\n}\n",
        encoding="utf-8",
    )
    catalog = tmp_path / "services.yaml"
    catalog.write_text(
        f"""services:
  edge-gateway:
    label: Edge Gateway
    path: edge-gateway
    group: edge
    tags: [java]
""",
        encoding="utf-8",
    )
    config = tmp_path / "knowledge-sources.yaml"
    config.write_text(
        f"""catalog:
  path: "{catalog}"
  workspace_root: "{workspace}"
indexing:
  include: ["**/*.java"]
""",
        encoding="utf-8",
    )
    return config


def app_config(tmp_path, max_file_chars=30000):
    return AppConfig(
        tmp_path,
        "127.0.0.1",
        7081,
        tmp_path / "knowledge-sources.yaml",
        tmp_path / "knowledge.sqlite",
        analysis_max_file_chars=max_file_chars,
    )


def app_config_with_retries(tmp_path, retry_attempts):
    return AppConfig(
        tmp_path,
        "127.0.0.1",
        7081,
        tmp_path / "knowledge-sources.yaml",
        tmp_path / "knowledge.sqlite",
        analysis_max_attempts_per_file=retry_attempts,
    )


def wait_job(store, job_id):
    analysis_store = AnalysisStore(store.db_path)
    for _ in range(80):
        job = analysis_store.job(job_id)
        if job["status"] in {"COMPLETED", "FAILED", "STOPPED"}:
            return job
        time.sleep(0.025)
    raise AssertionError("job did not finish")


def graph_response(payload, line_count, nodes=None, edges=None, claims=None, diagnostics=None):
    return {
        "schemaVersion": "knowledge.graph.analysis.v1",
        "file": {
            "sourceId": payload["sourceId"],
            "inventoryFileId": payload["inventoryFileId"],
            "relativePath": payload["relativePath"],
            "contentHash": payload["contentHash"],
            "lineCount": line_count,
        },
        "nodes": nodes or [],
        "edges": edges or [],
        "claims": claims or [],
        "diagnostics": diagnostics or [],
    }


def graph_rows(store, table, where="", params=()):
    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        return conn.execute(f"SELECT * FROM {table} {where}", params).fetchall()


def graph_node_id(store, qualified_name):
    rows = graph_rows(store, "analysis_graph_nodes", "WHERE qualified_name = ?", (qualified_name,))
    assert rows, qualified_name
    return rows[0]["id"]


def current_inventory_file(store):
    return store.search_rows([], [])[0][0]


def seed_current_analysis_file(conn, row, content_hash=None, status="ANALYZED"):
    conn.execute("""
        INSERT OR REPLACE INTO analysis_files(
            file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, engine_version,
            status, analyzed_at, symbol_count, relation_count, attempt_count, diagnostics_json
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'now', 0, 0, 1, '[]')
    """, (
        row["id"],
        row["source_id"],
        row["relative_path"],
        content_hash or row["content_hash"],
        StubAnalyzer.name,
        StubAnalyzer.version,
        GRAPH_ANALYSIS_ENGINE_VERSION,
        status,
    ))


def seed_graph_node(conn, row, node_id, qualified_name, kind="CALLABLE", status="TRUSTED"):
    conn.execute("""
        INSERT INTO analysis_graph_nodes(
            id, job_id, source_id, inventory_file_id, analysis_file_id, stable_key, node_kind, language,
            name, qualified_name, display_name, parent_node_id, line_start, line_end, confidence, status,
            metadata_json, created_at
        )
        VALUES (?, 'job-lineage', ?, ?, ?, ?, ?, 'java', ?, ?, ?, NULL, 1, 1, 0.9, ?, '{}', 'now')
    """, (
        node_id,
        row["source_id"],
        row["id"],
        row["id"],
        f"stable:{node_id}",
        kind,
        qualified_name.split(".")[-1],
        qualified_name,
        qualified_name,
        status,
    ))


def seed_graph_edge(conn, row, edge_id, from_node_id, to_node_id, edge_type="CALLS", status="TRUSTED"):
    conn.execute("""
        INSERT INTO analysis_graph_edges(
            id, job_id, source_id, inventory_file_id, analysis_file_id, from_node_id, to_node_id, edge_type,
            resolution_status, confidence, evidence_id, unresolved_target_json, metadata_json, status, created_at
        )
        VALUES (?, 'job-lineage', ?, ?, ?, ?, ?, ?, 'RESOLVED', 0.9, NULL, NULL, '{}', ?, 'now')
    """, (
        edge_id,
        row["source_id"],
        row["id"],
        row["id"],
        from_node_id,
        to_node_id,
        edge_type,
        status,
    ))


def table_count(store, table):
    with sqlite3.connect(store.db_path) as conn:
        return conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]


def table_exists(store, table):
    with sqlite3.connect(store.db_path) as conn:
        return conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone() is not None


def seed_legacy_analysis_rows(store, file_id=None):
    row = store.search_rows([], [])[0][0]
    file_id = file_id or row["id"]
    with sqlite3.connect(store.db_path) as conn:
        conn.execute("""
            INSERT INTO analysis_jobs(
                job_id, status, started_at, completed_at, source_count, file_count,
                processed_file_count, failed_file_count, current_source_id, current_relative_path,
                source_ids_json, symbol_count, relation_count, diagnostics_json
            )
            VALUES ('legacy-job', 'COMPLETED', 'old', 'old', 1, 1, 1, 0, NULL, NULL, '["edge-gateway"]', 1, 1, '[]')
        """)
        conn.execute("""
            INSERT INTO analysis_files(
                file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version,
                status, analyzed_at, symbol_count, relation_count, diagnostics_json
            )
            VALUES (?, ?, ?, ?, 'ai-file-analyzer', '1', 'ANALYZED', 'old', 1, 1, '[]')
        """, (file_id, row["source_id"], row["relative_path"], row["content_hash"]))
        conn.execute("""
            INSERT INTO analysis_symbols(
                symbol_id, file_id, source_id, relative_path, name, kind, line_start, line_end, summary, metadata_json
            )
            VALUES ('legacy-symbol', ?, ?, ?, 'LegacyHandler', 'CLASS', 1, 1, 'Old direct analyzer fact.', '{}')
        """, (file_id, row["source_id"], row["relative_path"]))
        conn.execute("""
            INSERT INTO analysis_symbol_roles(symbol_id, role, confidence, evidence_json, classifier, classifier_version)
            VALUES ('legacy-symbol', 'HTTP_HANDLER', 0.9, '["old"]', 'ai-file-analyzer', '1')
        """)
        conn.execute("""
            INSERT INTO analysis_relations(
                relation_id, source_id, from_symbol_id, to_symbol_id, relation, confidence,
                evidence_json, line_start, line_end, metadata_json
            )
            VALUES ('legacy-relation', ?, 'legacy-symbol', 'legacy-symbol', 'CALLS', 0.9, '["old"]', 1, 1, '{}')
        """, (row["source_id"],))


def seed_pre_graph_cutover_schema(store):
    row = store.search_rows([], [])[0][0]
    with sqlite3.connect(store.db_path) as conn:
        conn.execute("""
            CREATE TABLE analysis_jobs (
                job_id TEXT PRIMARY KEY,
                status TEXT NOT NULL,
                started_at TEXT,
                completed_at TEXT,
                source_count INTEGER NOT NULL,
                file_count INTEGER NOT NULL,
                processed_file_count INTEGER NOT NULL,
                failed_file_count INTEGER NOT NULL,
                current_source_id TEXT,
                current_relative_path TEXT,
                source_ids_json TEXT,
                last_progress_at TEXT,
                symbol_count INTEGER NOT NULL,
                relation_count INTEGER NOT NULL,
                diagnostics_json TEXT NOT NULL
            )
        """)
        conn.execute("""
            CREATE TABLE analysis_files (
                file_id INTEGER PRIMARY KEY,
                source_id TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                analyzer_name TEXT NOT NULL,
                analyzer_version TEXT NOT NULL,
                status TEXT NOT NULL,
                analyzed_at TEXT,
                symbol_count INTEGER NOT NULL,
                relation_count INTEGER NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                last_attempt_at TEXT,
                last_error_code TEXT,
                last_error_message TEXT,
                last_raw_response_preview TEXT,
                diagnostics_json TEXT NOT NULL
            )
        """)
        conn.execute("""
            CREATE TABLE analysis_symbols (
                symbol_id TEXT PRIMARY KEY,
                file_id INTEGER NOT NULL,
                source_id TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                name TEXT NOT NULL,
                kind TEXT NOT NULL,
                line_start INTEGER NOT NULL,
                line_end INTEGER NOT NULL,
                summary TEXT,
                metadata_json TEXT NOT NULL
            )
        """)
        conn.execute("""
            CREATE TABLE analysis_symbol_roles (
                symbol_id TEXT NOT NULL,
                role TEXT NOT NULL,
                confidence REAL NOT NULL,
                evidence_json TEXT NOT NULL,
                classifier TEXT NOT NULL,
                classifier_version TEXT NOT NULL
            )
        """)
        conn.execute("""
            CREATE TABLE analysis_relations (
                relation_id TEXT PRIMARY KEY,
                source_id TEXT NOT NULL,
                from_symbol_id TEXT NOT NULL,
                to_symbol_id TEXT NOT NULL,
                relation TEXT NOT NULL,
                confidence REAL NOT NULL,
                evidence_json TEXT NOT NULL,
                line_start INTEGER NOT NULL,
                line_end INTEGER NOT NULL,
                metadata_json TEXT NOT NULL
            )
        """)
        conn.execute("""
            CREATE TABLE analysis_schema_migrations (
                version INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                applied_at TEXT NOT NULL
            )
        """)
        conn.execute("INSERT INTO analysis_schema_migrations(version, name, applied_at) VALUES (1, 'remove_legacy_analysis_job_counter', 'old')")
        conn.execute("INSERT INTO analysis_schema_migrations(version, name, applied_at) VALUES (2, 'add_analysis_job_source_scope', 'old')")
    seed_legacy_analysis_rows(store, file_id=row["id"])


def test_graph_response_parser_extracts_markdown_wrapped_json():
    payload = {
        "sourceId": "edge-gateway",
        "inventoryFileId": 7,
        "relativePath": "src/App.java",
        "contentHash": "hash",
    }
    raw = "```json\n" + json.dumps(valid_graph_response(payload, 5)) + "\n```"

    result = GraphAnalysisResponseParser().parse(raw)

    assert isinstance(result, GraphAnalysisResponse)
    assert result.nodes[0].name == "ObjectHandler"


def test_graph_response_parser_rejects_natural_language():
    result = GraphAnalysisResponseParser().parse("I cannot analyze this file.")

    assert result.code == "ANALYSIS_AI_INVALID_JSON"
    assert result.validation_errors[0].code == GraphValidationErrorCode.INVALID_JSON
    assert result.validation_errors[0].path == "$"
    assert result.validation_errors[0].stage.value == "JSON_PARSE"


def test_graph_response_parser_rejects_empty_response():
    result = GraphAnalysisResponseParser().parse("   ")

    assert result.code == "ANALYSIS_AI_EMPTY_RESPONSE"
    assert result.validation_errors[0].code == GraphValidationErrorCode.EMPTY_RESPONSE


def test_graph_response_parser_rejects_schema_invalid_json():
    result = GraphAnalysisResponseParser().parse('{"nodes":[],"edges":[]}')

    assert result.code == "ANALYSIS_AI_SCHEMA_INVALID"
    assert len(result.message) < 560
    assert {error.code for error in result.validation_errors} >= {GraphValidationErrorCode.MISSING_REQUIRED_FIELD}


def test_graph_response_parser_rejects_json_null_as_schema_invalid():
    result = GraphAnalysisResponseParser().parse("null")

    assert result.code == "ANALYSIS_AI_SCHEMA_INVALID"


def test_graph_response_parser_truncates_raw_preview():
    result = GraphAnalysisResponseParser().parse("x" * 5000)

    assert result.code == "ANALYSIS_AI_INVALID_JSON"
    assert len(result.raw_preview) == 4000


def test_graph_response_parser_accepts_valid_graph_json():
    payload = {
        "sourceId": "edge-gateway",
        "inventoryFileId": 7,
        "relativePath": "src/App.java",
        "contentHash": "hash",
    }
    raw = json.dumps(graph_response(payload, 3, nodes=[
        {"localId": "n1", "nodeKind": "CALLABLE", "name": "run", "qualifiedName": "App.run", "lineStart": 1, "lineEnd": 2, "confidence": 0.9, "metadata": {}},
    ]))

    result = GraphAnalysisResponseParser().parse(raw)

    assert isinstance(result, GraphAnalysisResponse)
    assert result.schemaVersion == "knowledge.graph.analysis.v1"
    assert result.nodes[0].nodeKind == "CALLABLE"


def test_graph_response_parser_rejects_schema_invalid_graph_json():
    result = GraphAnalysisResponseParser().parse('{"schemaVersion":"wrong","nodes":[]}')

    assert result.code == "ANALYSIS_AI_SCHEMA_INVALID"


def test_graph_response_parser_reports_missing_required_field_path_and_allowed_values():
    payload = {
        "sourceId": "edge-gateway",
        "inventoryFileId": 7,
        "relativePath": "src/App.java",
        "contentHash": "hash",
    }
    raw = json.dumps(graph_response(payload, 3, nodes=[
        {"localId": "n1", "name": "run", "qualifiedName": "App.run", "lineStart": 1, "lineEnd": 2, "confidence": 0.9, "metadata": {}},
    ]))

    result = GraphAnalysisResponseParser().parse(raw, line_count=3)
    error = result.validation_errors[0]

    assert result.code == "ANALYSIS_AI_SCHEMA_INVALID"
    assert error.code == GraphValidationErrorCode.MISSING_REQUIRED_FIELD
    assert error.path == "$.nodes[0].nodeKind"
    assert "UNKNOWN" in error.allowed_values
    assert "nodeKind" in error.repair_hint


def test_graph_response_parser_reports_invalid_field_type():
    payload = {
        "sourceId": "edge-gateway",
        "inventoryFileId": 7,
        "relativePath": "src/App.java",
        "contentHash": "hash",
    }
    response = graph_response(payload, 3, nodes=[
        {"localId": "n1", "nodeKind": "CALLABLE", "name": "run", "qualifiedName": "App.run", "lineStart": "one", "lineEnd": 2, "confidence": 0.9, "metadata": {}},
    ])

    result = GraphAnalysisResponseParser().parse(json.dumps(response), line_count=3)
    error = result.validation_errors[0]

    assert result.code == "ANALYSIS_AI_SCHEMA_INVALID"
    assert error.code == GraphValidationErrorCode.INVALID_FIELD_TYPE
    assert error.path == "$.nodes[0].lineStart"
    assert "JSON type" in error.repair_hint


def test_prompt_enum_values_match_graph_schema():
    prompt = (Path(__file__).resolve().parents[3] / "config" / "analysis-prompt.md").read_text(encoding="utf-8")

    for value in enum_values(GraphNodeKind):
        assert value in prompt
    for value in enum_values(GraphEdgeType):
        assert value in prompt
    for value in enum_values(GraphClaimKind):
        assert value in prompt
    for value in enum_values(GraphFactOrigin):
        assert value in prompt
    for value in enum_values(GraphFlowDomain):
        assert value in prompt


def test_graph_materializer_returns_typed_facts(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    row = store.search_rows([], [])[0][0]
    resolved = StubResolver().read(row)
    payload = {
        "sourceId": row["source_id"],
        "inventoryFileId": row["id"],
        "relativePath": row["relative_path"],
        "contentHash": row["content_hash"],
    }
    response = GraphAnalysisResponse.parse_obj(graph_response(payload, 1, nodes=[
        {"localId": "n1", "nodeKind": "CALLABLE", "name": "run", "qualifiedName": "App.run", "parentLocalId": "__file__", "lineStart": 1, "lineEnd": 1, "confidence": 0.9, "metadata": {}},
    ]))

    graph = GraphAnalysisEngine().materialize("job-1", row, resolved.content, response, "test", "1")

    assert isinstance(graph.nodes[0], GraphNodeFact)
    assert graph.nodes[0].node_kind == GraphNodeKind.FILE
    assert any(isinstance(node, GraphNodeFact) and node.node_kind == GraphNodeKind.CALLABLE and node.qualified_name == "App.run" for node in graph.nodes)
    assert all(isinstance(edge, GraphEdgeFact) for edge in graph.edges)


def test_graph_v1_cutover_clears_legacy_analysis_cache_without_touching_inventory(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    seed_pre_graph_cutover_schema(store)

    AnalysisStore(store.db_path).init()

    assert table_count(store, "files") == 1
    assert table_count(store, "sources") == 1
    assert table_count(store, "analysis_jobs") == 0
    assert table_count(store, "analysis_files") == 0
    assert not table_exists(store, "analysis_symbols")
    assert not table_exists(store, "analysis_symbol_roles")
    assert not table_exists(store, "analysis_relations")
    assert table_count(store, "analysis_graph_nodes") == 0
    assert table_count(store, "analysis_graph_edges") == 0
    versions = {row["version"] for row in graph_rows(store, "analysis_schema_migrations")}
    assert 3 in versions


def test_graph_analysis_endpoints_read_graph_tables_after_legacy_cache_reset(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    seed_pre_graph_cutover_schema(store)
    analysis_store = AnalysisStore(store.db_path)
    analysis_store.init()

    final = wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    files = analysis_store.files(None, "ANALYZED", None, 10, 0)
    symbols = analysis_store.symbols(None, None, None, None, None, 10, 0)
    relations = analysis_store.relations(None, None, None, None, 10, 0)

    assert final["status"] == "COMPLETED"
    assert files["total"] == 1
    assert files["files"][0]["engineVersion"] == GRAPH_ANALYSIS_ENGINE_VERSION
    assert symbols["total"] == 2
    assert all(symbol["graphNodeId"] for symbol in symbols["symbols"])
    assert all(symbol["factStatus"] == "TRUSTED" for symbol in symbols["symbols"])
    assert relations["total"] == 1
    assert relations["relations"][0]["graphEdgeId"]
    assert relations["relations"][0]["factStatus"] in {"TRUSTED", "DERIVED"}
    assert not table_exists(store, "analysis_symbols")
    assert not table_exists(store, "analysis_symbol_roles")
    assert not table_exists(store, "analysis_relations")


def test_projection_hides_orphan_graph_rows_without_analysis_file_lineage(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analysis_store = AnalysisStore(store.db_path)
    analysis_store.init()
    row = current_inventory_file(store)
    with sqlite3.connect(store.db_path) as conn:
        seed_graph_node(conn, row, "orphan-from", "Orphan.from")
        seed_graph_node(conn, row, "orphan-to", "Orphan.to")
        seed_graph_edge(conn, row, "orphan-edge", "orphan-from", "orphan-to")

    symbols = analysis_store.symbols(None, None, None, None, None, 10, 0)
    relations = analysis_store.relations(None, None, None, None, 10, 0)
    status = analysis_store.status()
    service = analysis_store.service_status(None, StubAnalyzer.name, StubAnalyzer.version, store.status())["services"][0]

    assert store.status()["fileCount"] == 1
    assert symbols["total"] == 0
    assert relations["total"] == 0
    assert status["symbolCount"] == 0
    assert status["relationCount"] == 0
    assert service["facts"]["symbolCount"] == 0
    assert service["facts"]["relationCount"] == 0


def test_projection_hides_graph_rows_with_stale_content_hash(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analysis_store = AnalysisStore(store.db_path)
    analysis_store.init()
    row = current_inventory_file(store)
    with sqlite3.connect(store.db_path) as conn:
        seed_current_analysis_file(conn, row, content_hash="stale-hash")
        seed_graph_node(conn, row, "stale-node", "Stale.run")

    symbols = analysis_store.symbols(None, None, None, None, None, 10, 0)
    service = analysis_store.service_status(None, StubAnalyzer.name, StubAnalyzer.version, store.status())["services"][0]

    assert symbols["total"] == 0
    assert service["facts"]["symbolCount"] == 0
    assert store.status()["fileCount"] == 1


def test_projection_returns_only_current_graph_lineage(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analysis_store = AnalysisStore(store.db_path)
    analysis_store.init()
    row = current_inventory_file(store)
    with sqlite3.connect(store.db_path) as conn:
        seed_current_analysis_file(conn, row)
        seed_graph_node(conn, row, "current-from", "Current.from")
        seed_graph_node(conn, row, "current-to", "Current.to")
        seed_graph_edge(conn, row, "current-edge", "current-from", "current-to")

    symbols = analysis_store.symbols(None, None, None, None, None, 10, 0)
    relations = analysis_store.relations(None, None, None, None, 10, 0)
    service = analysis_store.service_status(None, StubAnalyzer.name, StubAnalyzer.version, store.status())["services"][0]

    assert symbols["total"] == 2
    assert {symbol["graphNodeId"] for symbol in symbols["symbols"]} == {"current-from", "current-to"}
    assert relations["total"] == 1
    assert relations["relations"][0]["graphEdgeId"] == "current-edge"
    assert service["facts"]["symbolCount"] == 2
    assert service["facts"]["relationCount"] == 1


def test_non_localhost_ollama_base_url_rejected(tmp_path):
    with pytest.raises(Exception):
        OllamaAnalysisClient("http://example.com:11434", "model", 1, tmp_path / "missing.md")


def test_large_file_skipped(tmp_path):
    store, _, _ = build_inventory(tmp_path, include_large=True)
    runner = AnalysisJobRunner(store, app_config(tmp_path, max_file_chars=150))

    job = runner.start(AnalysisBuildRequest(), StubAnalyzer())
    final = wait_job(store, job["jobId"])
    files = AnalysisStore(store.db_path).files(None, "SKIPPED_TOO_LARGE_FOR_AI_ANALYSIS", None, 10, 0)

    assert final["status"] == "COMPLETED"
    assert files["total"] == 1


def test_analysis_reads_selected_files_through_inventory_resolver(tmp_path):
    store, _, service = build_inventory(tmp_path)
    (service / "src/main/java/example/ObjectHandler.java").unlink()
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    resolver = StubResolver()
    runner.file_resolver = resolver
    analyzer = CapturingAnalyzer()

    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])

    assert final["status"] == "COMPLETED"
    assert resolver.calls == ["src/main/java/example/ObjectHandler.java"]
    assert analyzer.payloads[0]["content"] == "class Resolved {}"
    assert analyzer.line_counts == [1]


def test_unchanged_file_not_picked_by_new_job(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    analyzer = StubAnalyzer()
    wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    second = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])

    assert second["fileCount"] == 0
    assert second["processedFileCount"] == 0
    assert _legacy_skipped_unchanged_key() not in second
    assert analyzer.calls == 1


def test_failed_file_with_unchanged_hash_is_picked_for_retry(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    first = wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(fail=True))["jobId"])
    retry_analyzer = StubAnalyzer()

    second = wait_job(store, runner.start(AnalysisBuildRequest(), retry_analyzer)["jobId"])
    analyzed = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)

    assert first["failedFileCount"] == 1
    assert second["fileCount"] == 1
    assert second["processedFileCount"] == 1
    assert second["failedFileCount"] == 0
    assert retry_analyzer.calls == 1
    assert analyzed["total"] == 1


def test_inventory_search_rows_filters_currently_analyzed_files(tmp_path):
    extra_files = {
        f"src/main/java/example/Generated{i:03d}Handler.java": "public class GeneratedHandler {\n  public void create() {\n  }\n\n}\n"
        for i in range(405)
    }
    store, _, _ = build_inventory(tmp_path, extra_files=extra_files)
    rows, _ = store.search_rows([], [])
    analysis_store = AnalysisStore(store.db_path)
    for row in rows:
        analysis_store.mark_file(row["id"], {
            "source_id": row["source_id"],
            "relative_path": row["relative_path"],
            "content_hash": row["content_hash"],
            "analyzer_name": StubAnalyzer.name,
            "analyzer_version": StubAnalyzer.version,
            "status": "ANALYZED",
            "symbol_count": 0,
            "relation_count": 0,
            "diagnostics": [],
        })

    pending_rows, _ = store.search_rows(
        [],
        [],
        StubAnalyzer.name,
        StubAnalyzer.version,
        only_needing_analysis=True,
    )

    assert pending_rows == []


def test_analysis_max_files_uses_stable_inventory_order(tmp_path):
    store, _, _ = build_inventory(tmp_path, extra_files={
        "src/main/java/example/AaaHandler.java": "public class AaaHandler {\n  public void create() {\n  }\n\n}\n",
    })
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(maxFiles=1, force=True), StubAnalyzer())["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)

    assert files["total"] == 1
    assert files["files"][0]["relativePath"] == "src/main/java/example/AaaHandler.java"


def test_analysis_max_files_applies_after_current_files_are_filtered(tmp_path):
    store, _, _ = build_inventory(tmp_path, extra_files={
        "src/main/java/example/AaaHandler.java": "public class AaaHandler {\n  public void create() {\n  }\n\n}\n",
    })
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    analyzer = StubAnalyzer()

    first = wait_job(store, runner.start(AnalysisBuildRequest(maxFiles=1), analyzer)["jobId"])
    second = wait_job(store, runner.start(AnalysisBuildRequest(maxFiles=1), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)

    assert first["fileCount"] == 1
    assert second["fileCount"] == 1
    assert second["processedFileCount"] == 1
    assert _legacy_skipped_unchanged_key() not in second
    assert analyzer.calls == 2
    assert files["total"] == 2
    assert [item["relativePath"] for item in files["files"]] == [
        "src/main/java/example/AaaHandler.java",
        "src/main/java/example/ObjectHandler.java",
    ]


def test_changed_file_reanalyzed_and_previous_analysis_removed(tmp_path):
    store, config, service = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    first_symbols = AnalysisStore(store.db_path).symbols(None, None, None, None, None, 100, 0)
    (service / "src/main/java/example/ObjectHandler.java").write_text("public class ObjectHandler {\n  public void updated() {}\n}\n", encoding="utf-8")
    InventoryBuilder(load_source_config(config), store).build([], [])
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(factory=lambda payload, line_count: graph_response(payload, line_count, nodes=[
        {"localId": "s3", "nodeKind": "CALLABLE", "name": "updated", "qualifiedName": "ObjectHandler.updated", "lineStart": 2, "lineEnd": 2, "confidence": 0.7, "metadata": {}},
    ])))["jobId"])
    second_symbols = AnalysisStore(store.db_path).symbols(None, None, None, None, None, 100, 0)

    assert first_symbols["total"] == 2
    assert second_symbols["total"] == 2
    assert "updated" in {symbol["name"] for symbol in second_symbols["symbols"]}
    assert "create" not in {symbol["name"] for symbol in second_symbols["symbols"]}


def test_freshness_up_to_date_after_completed_scan_with_unchanged_files(tmp_path):
    store, config, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    freshness = KnowledgeFreshnessService(load_source_config(config), store).check()

    assert AnalysisStore(store.db_path).status()["scannedFileCount"] == 1
    assert freshness["status"] == "UP_TO_DATE"
    assert freshness["newFiles"] == 0
    assert freshness["modifiedFiles"] == 0
    assert freshness["deletedFiles"] == 0


def test_freshness_outdated_when_scanned_file_modified(tmp_path):
    store, config, service = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service / "src/main/java/example/ObjectHandler.java").write_text("public class ObjectHandler { void updated() {} }\n", encoding="utf-8")

    freshness = KnowledgeFreshnessService(load_source_config(config), store).check()

    assert freshness["status"] == "OUTDATED"
    assert freshness["modifiedFiles"] == 1
    assert freshness["affectedScannedFiles"] == 1


def test_freshness_outdated_when_scanned_file_deleted(tmp_path):
    store, config, service = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service / "src/main/java/example/ObjectHandler.java").unlink()

    freshness = KnowledgeFreshnessService(load_source_config(config), store).check()

    assert freshness["status"] == "OUTDATED"
    assert freshness["deletedFiles"] == 1
    assert freshness["affectedScannedFiles"] == 1


def test_freshness_outdated_when_new_eligible_file_added(tmp_path):
    store, config, service = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service / "src/main/java/example/SecondHandler.java").write_text("public class SecondHandler {}\n", encoding="utf-8")

    freshness = KnowledgeFreshnessService(load_source_config(config), store).check()

    assert freshness["status"] == "OUTDATED"
    assert freshness["newFiles"] == 1
    assert freshness["affectedScannedFiles"] == 0


def test_analyze_refreshes_inventory_and_restores_freshness(tmp_path):
    store, config, service = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service / "src/main/java/example/SecondHandler.java").write_text(
        "public class SecondHandler {\n  @PostMapping\n  public void create() {\n  }\n}\n",
        encoding="utf-8",
    )
    assert KnowledgeFreshnessService(load_source_config(config), store).check()["status"] == "OUTDATED"

    InventoryBuilder(load_source_config(config), store).build([], [])
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    freshness = KnowledgeFreshnessService(load_source_config(config), store).check()
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)

    assert freshness["status"] == "UP_TO_DATE"
    assert AnalysisStore(store.db_path).status()["fileCount"] == 1
    assert files["total"] == 2


def test_inventory_refresh_removes_analysis_for_deleted_files(tmp_path):
    store, _, service = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service / "src/main/java/example/ObjectHandler.java").unlink()

    result = InventoryRefreshService(app_config(tmp_path), store).build([], [])
    files = AnalysisStore(store.db_path).files(None, None, None, 10, 0)
    symbols = AnalysisStore(store.db_path).symbols(None, None, None, None, None, 10, 0)
    relations = AnalysisStore(store.db_path).relations(None, None, None, None, 10, 0)

    assert result["fileCount"] == 0
    assert files["total"] == 0
    assert symbols["total"] == 0
    assert relations["total"] == 0


def test_inventory_refresh_makes_new_files_available_for_next_analysis(tmp_path):
    store, _, service = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service / "src/main/java/example/SecondHandler.java").write_text(
        "public class SecondHandler {\n  @PostMapping\n  public void create() {\n  }\n}\n",
        encoding="utf-8",
    )
    InventoryRefreshService(app_config(tmp_path), store).build([], [])
    analyzer = StubAnalyzer()

    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)

    assert final["fileCount"] == 1
    assert final["processedFileCount"] == 1
    assert analyzer.calls == 1
    assert files["total"] == 2


def test_inventory_refresh_blocked_while_analysis_running(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    AnalysisStore(store.db_path).create_job({
        "jobId": "job-running",
        "status": "RUNNING",
        "startedAt": "now",
        "sourceCount": 1,
        "fileCount": 1,
        "processedFileCount": 0,
        "failedFileCount": 0,
    })

    with pytest.raises(KnowledgeError) as exc:
        InventoryRefreshService(app_config(tmp_path), store).build([], [])

    assert exc.value.code == "INVENTORY_BUILD_BLOCKED_BY_ANALYSIS"


def test_background_inventory_scheduler_skips_while_analysis_running(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    AnalysisStore(store.db_path).create_job({
        "jobId": "job-running",
        "status": "RUNNING",
        "startedAt": "now",
        "sourceCount": 1,
        "fileCount": 1,
        "processedFileCount": 0,
        "failedFileCount": 0,
    })
    refresh = InventoryRefreshService(app_config(tmp_path), store)
    scheduler = BackgroundInventoryScheduler(refresh, app_config(tmp_path))

    state = scheduler.run_once()

    assert state["status"] == "SKIPPED"
    assert state["lastErrorCode"] == "INVENTORY_BUILD_BLOCKED_BY_ANALYSIS"
    assert state["skipCount"] == 1


def test_background_inventory_refresh_skips_only_running_source(tmp_path):
    store, _, first, second = build_two_service_inventory(tmp_path)
    AnalysisStore(store.db_path).create_job({
        "jobId": "job-running",
        "status": "RUNNING",
        "startedAt": "now",
        "sourceCount": 1,
        "fileCount": 1,
        "processedFileCount": 0,
        "failedFileCount": 0,
        "currentSourceId": "first-service",
        "sourceIds": ["first-service"],
    })
    (first / "src" / "First.java").unlink()
    (second / "src" / "Second.java").unlink()
    (second / "src" / "SecondNew.java").write_text("class SecondNew {}\n", encoding="utf-8")
    scheduler = BackgroundInventoryScheduler(InventoryRefreshService(app_config(tmp_path), store), app_config(tmp_path))

    state = scheduler.run_once()
    first_files = store.files("first-service", None, None, 10, 0)
    second_files = store.files("second-service", None, None, 10, 0)

    assert state["status"] == "READY"
    assert state["runCount"] == 1
    assert [item["relativePath"] for item in first_files["files"]] == ["src/First.java"]
    assert [item["relativePath"] for item in second_files["files"]] == ["src/SecondNew.java"]


def test_background_job_returns_id_and_updates_progress(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    unblock = threading.Event()
    runner = AnalysisJobRunner(store, app_config(tmp_path))

    response = runner.start(AnalysisBuildRequest(), StubAnalyzer(block_event=unblock))
    running = AnalysisStore(store.db_path).job(response["jobId"])
    unblock.set()
    final = wait_job(store, response["jobId"])

    assert response["status"] == "QUEUED"
    assert running["status"] in {"QUEUED", "RUNNING"}
    assert final["processedFileCount"] == 1


def test_analysis_jobs_legacy_skipped_unchanged_column_is_removed(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    with sqlite3.connect(db_path) as conn:
        conn.execute("""
            CREATE TABLE analysis_jobs (
                job_id TEXT PRIMARY KEY,
                status TEXT NOT NULL,
                started_at TEXT,
                completed_at TEXT,
                source_count INTEGER NOT NULL,
                file_count INTEGER NOT NULL,
                processed_file_count INTEGER NOT NULL,
                skipped_unchanged_file_count INTEGER NOT NULL,
                failed_file_count INTEGER NOT NULL,
                current_source_id TEXT,
                current_relative_path TEXT,
                last_progress_at TEXT,
                symbol_count INTEGER NOT NULL,
                relation_count INTEGER NOT NULL,
                diagnostics_json TEXT NOT NULL
            )
        """)
        conn.execute("""
            INSERT INTO analysis_jobs(
                job_id, status, source_count, file_count, processed_file_count,
                skipped_unchanged_file_count, failed_file_count, symbol_count,
                relation_count, diagnostics_json
            )
            VALUES ('job-old', 'COMPLETED', 1, 2, 2, 1, 0, 3, 4, '[]')
        """)

    store = AnalysisStore(db_path)
    store.init()
    with sqlite3.connect(db_path) as conn:
        columns = {row[1] for row in conn.execute("PRAGMA table_info(analysis_jobs)").fetchall()}
        migrations = conn.execute("SELECT version, name FROM analysis_schema_migrations ORDER BY version").fetchall()
    job = store.job("job-old")
    store.init()
    with sqlite3.connect(db_path) as conn:
        migration_count = conn.execute("SELECT COUNT(*) FROM analysis_schema_migrations").fetchone()[0]

    assert "skipped_unchanged_file_count" not in columns
    assert "source_ids_json" in columns
    assert job is None
    assert migrations == [
        (1, "remove_legacy_analysis_job_counter"),
        (2, "add_analysis_job_source_scope"),
        (3, "reset_analysis_cache_for_graph_v1_cutover"),
    ]
    assert migration_count == 3


def test_stop_analysis_releases_active_slot_and_prevents_old_file_write(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    unblock = threading.Event()
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    old_job_id = runner.start(AnalysisBuildRequest(), StubAnalyzer(block_event=unblock))["jobId"]
    analysis_store = AnalysisStore(store.db_path)

    for _ in range(80):
        running = analysis_store.job(old_job_id)
        if running["status"] == "RUNNING" and running["currentRelativePath"]:
            break
        time.sleep(0.025)
    else:
        raise AssertionError("job did not start")

    stop = runner.stop(old_job_id)
    assert stop["status"] == "STOP_REQUESTED"
    assert analysis_store.active_job() is None

    new_job_id = runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"]
    new_final = wait_job(store, new_job_id)
    unblock.set()
    old_final = wait_job(store, old_job_id)
    files = analysis_store.files(None, "ANALYZED", None, 10, 0)

    assert new_final["status"] == "COMPLETED"
    assert old_final["status"] == "STOPPED"
    assert files["total"] == 1


def _legacy_skipped_unchanged_key():
    return "skipped" + "UnchangedFileCount"


def test_one_active_job_rule_enforced(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    unblock = threading.Event()
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    runner.start(AnalysisBuildRequest(), StubAnalyzer(block_event=unblock))

    with pytest.raises(Exception):
        runner.start(AnalysisBuildRequest(), StubAnalyzer())
    unblock.set()


def test_failed_ai_file_does_not_crash_whole_service(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(fail=True))["jobId"])

    assert final["status"] == "COMPLETED"
    assert final["processedFileCount"] == 1
    assert final["failedFileCount"] == 1
    service = AnalysisStore(store.db_path).service_status(None, StubAnalyzer.name, StubAnalyzer.version, store.status())["services"][0]
    assert service["analysis"]["processedFileCount"] == 1
    assert service["analysis"]["failedFileCount"] == 1
    assert service["analysis"]["pendingFileCount"] == 0
    assert service["diagnostics"][0]["code"] == "ANALYSIS_FILE_FAILED"
    assert service["diagnostics"][0]["count"] == 1


def test_service_status_includes_completed_outcomes_while_running(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(fail=True))["jobId"])
    analysis_store = AnalysisStore(store.db_path)
    analysis_store.create_job({
        "jobId": "job-running",
        "status": "RUNNING",
        "startedAt": "now",
        "sourceCount": 1,
        "fileCount": 1,
        "processedFileCount": 0,
        "failedFileCount": 0,
        "currentSourceId": "edge-gateway",
        "currentRelativePath": "src/main/java/example/ObjectHandler.java",
    })

    service = analysis_store.service_status(None, StubAnalyzer.name, StubAnalyzer.version, store.status())["services"][0]

    assert service["analysis"]["status"] == "RUNNING"
    assert service["analysis"]["processedFileCount"] == 1
    assert service["analysis"]["failedFileCount"] == 1
    assert service["analysis"]["pendingFileCount"] == 0
    assert service["analysis"]["percent"] == 100.0
    assert service["analysis"]["currentRelativePath"] == "src/main/java/example/ObjectHandler.java"


def test_bad_ai_json_is_retried_before_file_fails(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analyzer = StubAnalyzer(bad_response_attempts=1)
    runner = AnalysisJobRunner(store, app_config_with_retries(tmp_path, 2))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)

    assert final["status"] == "COMPLETED"
    assert final["failedFileCount"] == 0
    assert analyzer.calls == 2
    assert analyzer.repair_prompts
    assert files["total"] == 1
    assert {item["code"] for item in files["files"][0]["diagnostics"]} >= {"ANALYSIS_AI_INVALID_JSON", "ANALYSIS_AI_RETRY_SUCCEEDED"}


def test_invalid_json_repair_prompt_contains_structured_parse_error(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analyzer = StubAnalyzer(outcomes=[
        KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad", attempt=1),
        lambda payload, line_count: valid_graph_response(payload, line_count),
    ])
    runner = AnalysisJobRunner(store, app_config_with_retries(tmp_path, 2))

    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])

    assert final["failedFileCount"] == 0
    assert analyzer.repair_prompts
    prompt = analyzer.repair_prompts[0]
    assert "Structured validation feedback JSON" in prompt
    assert "INVALID_JSON" in prompt
    assert '"path": "$"' in prompt
    assert "{bad" in prompt


def test_max_attempts_exceeded_marks_file_failed_with_preview(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analyzer = StubAnalyzer(outcomes=[
        KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad", attempt=1),
        KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad again", attempt=2),
    ])
    runner = AnalysisJobRunner(store, app_config_with_retries(tmp_path, 2))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, "FAILED", None, 10, 0)

    assert final["failedFileCount"] == 1
    assert files["files"][0]["attemptCount"] == 2
    assert files["files"][0]["lastErrorCode"] == "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"
    assert files["files"][0]["lastRawResponsePreview"] == "{bad again"
    assert {item["code"] for item in files["files"][0]["diagnostics"]} >= {"ANALYSIS_AI_INVALID_JSON", "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"}
    assert files["files"][0]["diagnostics"][-1]["validationCode"] == "INVALID_JSON"


def test_timeout_marks_file_failed_and_continues(tmp_path):
    store, _, _ = build_inventory(tmp_path, extra_files={
        "src/main/java/example/SecondHandler.java": "public class SecondHandler {}\n",
    })
    analyzer = StubAnalyzer(outcomes=[
        KnowledgeError("ANALYSIS_AI_TIMEOUT", "AI analyzer request timed out", attempt=1),
        lambda payload, line_count: valid_graph_response(payload, line_count),
    ])
    runner = AnalysisJobRunner(store, app_config_with_retries(tmp_path, 3))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, None, None, 10, 0)
    failed = AnalysisStore(store.db_path).files(None, "FAILED", None, 10, 0)

    assert final["status"] == "COMPLETED"
    assert final["processedFileCount"] == 2
    assert final["failedFileCount"] == 1
    assert analyzer.calls == 2
    assert {file["analysisStatus"] for file in files["files"]} == {"ANALYZED", "FAILED"}
    assert failed["files"][0]["attemptCount"] == 1
    assert failed["files"][0]["lastErrorCode"] == "ANALYSIS_AI_TIMEOUT"


def test_transport_error_marks_file_failed_and_continues_after_attempts(tmp_path):
    store, _, _ = build_inventory(tmp_path, extra_files={
        "src/main/java/example/SecondHandler.java": "public class SecondHandler {}\n",
    })
    analyzer = StubAnalyzer(outcomes=[
        KnowledgeError("ANALYSIS_AI_TRANSPORT_ERROR", "AI analyzer transport error", attempt=1),
        lambda payload, line_count: valid_graph_response(payload, line_count),
    ])
    runner = AnalysisJobRunner(store, app_config_with_retries(tmp_path, 1))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])

    assert final["processedFileCount"] == 2
    assert final["failedFileCount"] == 1


def test_last_progress_at_updates(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    assert final["lastProgressAt"]


def test_interrupted_running_jobs_are_marked_failed_on_startup_cleanup(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analysis_store = AnalysisStore(store.db_path)
    analysis_store.create_job({
        "jobId": "job-running",
        "status": "RUNNING",
        "startedAt": "now",
        "sourceCount": 1,
        "fileCount": 1,
    })

    analysis_store.mark_interrupted_jobs()
    job = analysis_store.job("job-running")

    assert job["status"] == "FAILED"
    assert job["currentSourceId"] is None
    assert job["diagnostics"][0]["code"] == "ANALYSIS_JOB_INTERRUPTED"


def test_symbols_and_relations_endpoints_return_roles_and_evidence(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    analysis_store = AnalysisStore(store.db_path)
    symbols = analysis_store.symbols(None, "HTTP_HANDLER", None, None, None, 10, 0)
    relations = analysis_store.relations(None, "CONTAINS", None, None, 10, 0)

    assert symbols["symbols"][0]["roles"][0]["evidence"]
    assert relations["relations"][0]["evidence"]


def test_java_static_extractor_creates_type_callable_contains_and_imports_without_ai(tmp_path):
    content = """package example;

import java.util.List;

public class ObjectHandler {
  private final TicketRepository ticketRepository;

  public ObjectHandler(TicketRepository ticketRepository) {
    this.ticketRepository = ticketRepository;
  }

  public Ticket findById(String id) {
    return ticketRepository.findById(id);
  }
}
"""
    store, _, _ = build_inventory(tmp_path, content=content)
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count))

    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), analyzer)["jobId"])
    nodes = graph_rows(store, "analysis_graph_nodes")
    edges = graph_rows(store, "analysis_graph_edges")
    names = {row["qualified_name"] for row in nodes}

    assert "example.ObjectHandler" in names
    assert "example.ObjectHandler.findById" in names
    assert "java.util.List" in names
    assert any(row["node_kind"] == "FIELD" and row["name"] == "ticketRepository" for row in nodes)
    assert any(row["edge_type"] == "CONTAINS" for row in edges)
    assert any(row["edge_type"] == "IMPORTS" for row in edges)
    assert all(row["line_start"] and row["line_end"] for row in nodes if row["node_kind"] != "EXTERNAL")


def test_java_static_extractor_ignores_comments_and_strings_and_keeps_overloads_distinct(tmp_path):
    content = """public class ObjectHandler {
  String text = "public void fakeString() {}";
  // public void fakeComment() {}
  /*
   public void fakeBlock() {}
   */
  public void find(String id) {}
  public void find(Long id) {}
}
"""
    store, _, _ = build_inventory(tmp_path, content=content)
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count))

    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), analyzer)["jobId"])
    callables = graph_rows(store, "analysis_graph_nodes", "WHERE node_kind = 'CALLABLE'")
    callable_names = {row["name"] for row in callables}
    find_qualified_names = {row["qualified_name"] for row in callables if row["name"] == "find"}

    assert "fakeString" not in callable_names
    assert "fakeComment" not in callable_names
    assert "fakeBlock" not in callable_names
    assert len(find_qualified_names) == 2


def test_python_static_extractor_creates_type_callable_contains_and_imports_without_ai(tmp_path):
    content = """import json
from collections import defaultdict

class TicketHandler:
    def find_by_id(self, ticket_id):
        return ticket_id

async def health():
    return "ok"
"""
    store, _, _ = build_python_inventory(tmp_path, content)
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count))

    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), analyzer)["jobId"])
    nodes = graph_rows(store, "analysis_graph_nodes")
    edges = graph_rows(store, "analysis_graph_edges")
    names = {row["qualified_name"] for row in nodes}

    assert "app.handlers.TicketHandler" in names
    assert "app.handlers.TicketHandler.find_by_id" in names
    assert "app.handlers.health" in names
    assert "json" in names
    assert "collections.defaultdict" in names
    assert any(row["edge_type"] == "CONTAINS" for row in edges)
    assert any(row["edge_type"] == "IMPORTS" for row in edges)


def test_flow_domain_classifier_uses_path_and_extension_only():
    assert classify_flow_domain("src/main/java/example/App.java", ".java") == GraphFlowDomain.CODE
    assert classify_flow_domain("src/test/java/example/AppTest.java", ".java") == GraphFlowDomain.TEST
    assert classify_flow_domain(".github/workflows/build.yml", ".yml") == GraphFlowDomain.WORKFLOW
    assert classify_flow_domain("src/main/resources/application.yml", ".yml") == GraphFlowDomain.CONFIG
    assert classify_flow_domain("README.md", ".md") == GraphFlowDomain.DOC
    assert classify_flow_domain("pom.xml", ".xml") == GraphFlowDomain.BUILD
    assert classify_flow_domain("data/sample.json", ".json") == GraphFlowDomain.DATA


def test_analysis_job_files_record_only_job_scoped_processed_files(tmp_path):
    store, _, _ = build_inventory(tmp_path, extra_files={
        "src/main/java/example/AaaHandler.java": "public class AaaHandler {\n  public void create() {}\n}\n",
    })
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    first_job_id = runner.start(AnalysisBuildRequest(maxFiles=1), StubAnalyzer())["jobId"]
    first = wait_job(store, first_job_id)
    analysis_store = AnalysisStore(store.db_path)
    first_job_files = analysis_store.job_files(first_job_id)

    second_job_id = runner.start(AnalysisBuildRequest(), StubAnalyzer(fail=True))["jobId"]
    second = wait_job(store, second_job_id)
    second_job_files = analysis_store.job_files(second_job_id)
    current_files = analysis_store.files(None, None, None, 10, 0)

    assert first["status"] == "COMPLETED"
    assert first_job_files[0]["status"] == "ANALYZED"
    assert first_job_files[0]["jobId"] == first_job_id
    assert first_job_files[0]["flowDomain"] == GraphFlowDomain.CODE.value
    assert second["fileCount"] == 1
    assert second["processedFileCount"] == 1
    assert second["failedFileCount"] == 1
    assert {item["status"] for item in second_job_files} == {"FAILED"}
    assert [item["relativePath"] for item in second_job_files] == ["src/main/java/example/ObjectHandler.java"]
    assert current_files["total"] == 2
    assert {item["analysisStatus"] for item in current_files["files"]} == {"ANALYZED", "FAILED"}
    assert table_count(store, "files") == 2


def test_graph_facts_store_origin_domain_and_visualization_projection_metadata(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count,
        nodes=[
            {"localId": "ai-type", "nodeKind": "TYPE", "name": "AiType", "qualifiedName": "AiType", "lineStart": 1, "lineEnd": 1, "confidence": 0.9, "metadata": {}},
            {"localId": "ai-call", "nodeKind": "CALLABLE", "name": "run", "qualifiedName": "AiType.run", "parentLocalId": "ai-type", "lineStart": 2, "lineEnd": 2, "confidence": 0.9, "metadata": {}},
        ],
        claims=[
            {"localId": "ai-claim", "nodeLocalId": "ai-call", "claimKind": "RESPONSIBILITY", "summary": "Runs the AI-provided action.", "evidence": [{"lineStart": 2, "lineEnd": 2}], "confidence": 0.9, "metadata": {}},
        ]))

    wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    analysis_store = AnalysisStore(store.db_path)
    static_nodes = graph_rows(
        store,
        "analysis_graph_nodes",
        "WHERE fact_origin = ? AND flow_domain = ? AND node_kind IN ('TYPE', 'CALLABLE')",
        (GraphFactOrigin.STATIC.value, GraphFlowDomain.CODE.value),
    )
    llm_claims = graph_rows(
        store,
        "analysis_graph_claims",
        "WHERE fact_origin = ? AND flow_domain = ?",
        (GraphFactOrigin.LLM.value, GraphFlowDomain.CODE.value),
    )
    static_edges = graph_rows(
        store,
        "analysis_graph_edges",
        "WHERE fact_origin = ? AND flow_domain = ? AND edge_type = 'CONTAINS'",
        (GraphFactOrigin.STATIC.value, GraphFlowDomain.CODE.value),
    )
    symbols = analysis_store.symbols(None, None, None, None, None, 100, 0)
    static_symbols = analysis_store.symbols(None, None, None, None, None, 100, 0, fact_origin=GraphFactOrigin.STATIC.value)
    code_symbols = analysis_store.symbols(None, None, None, None, None, 100, 0, flow_domain=GraphFlowDomain.CODE.value)
    static_relations = analysis_store.relations(None, "CONTAINS", None, None, 100, 0, fact_origin=GraphFactOrigin.STATIC.value)

    assert static_nodes
    assert llm_claims
    assert static_edges
    assert static_symbols["total"] > 0
    assert code_symbols["total"] == symbols["total"]
    assert static_relations["total"] > 0
    for symbol in symbols["symbols"]:
        assert symbol["graphNodeId"]
        assert symbol["stableKey"]
        assert symbol["nodeKind"]
        assert symbol["displayName"]
        assert symbol["qualifiedName"]
        assert symbol["factOrigin"] in enum_values(GraphFactOrigin)
        assert symbol["flowDomain"] == GraphFlowDomain.CODE.value
    relation = static_relations["relations"][0]
    assert relation["graphEdgeId"]
    assert relation["fromGraphNodeId"]
    assert relation["toGraphNodeId"]
    assert relation["edgeType"] == GraphEdgeType.CONTAINS.value
    assert relation["factOrigin"] == GraphFactOrigin.STATIC.value
    assert relation["flowDomain"] == GraphFlowDomain.CODE.value


def test_graph_stable_keys_are_deterministic_for_repeated_analysis_of_unchanged_file(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))

    wait_job(store, runner.start(AnalysisBuildRequest(force=True), StubAnalyzer())["jobId"])
    first_keys = {
        row["stable_key"]
        for row in graph_rows(store, "analysis_graph_nodes", "WHERE node_kind != 'FILE'")
    }
    wait_job(store, runner.start(AnalysisBuildRequest(force=True), StubAnalyzer())["jobId"])
    second_keys = {
        row["stable_key"]
        for row in graph_rows(store, "analysis_graph_nodes", "WHERE node_kind != 'FILE'")
    }

    assert first_keys
    assert first_keys == second_keys


def test_workflow_yaml_uses_workflow_domain_and_config_graph_semantics(tmp_path):
    store, _, _ = build_custom_inventory(tmp_path, {
        ".github/workflows/build.yml": """name: Build
jobs:
  test:
    steps:
      - uses: actions/checkout@v4
      - run: mvn test
""",
    }, ["**/*.yml"])
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count,
        nodes=[
            {"localId": "job", "nodeKind": "CALLABLE", "name": "test", "qualifiedName": "workflow.test", "lineStart": 2, "lineEnd": 5, "confidence": 0.9, "metadata": {}},
            {"localId": "step", "nodeKind": "CALLABLE", "name": "mvn test", "qualifiedName": "workflow.test.mvn", "lineStart": 6, "lineEnd": 6, "confidence": 0.9, "metadata": {}},
        ],
        edges=[
            {"localId": "call", "edgeType": "CALLS", "fromLocalId": "job", "toLocalId": "step", "lineStart": 6, "lineEnd": 6, "confidence": 0.9, "metadata": {}},
        ]))

    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), analyzer)["jobId"])
    nodes = graph_rows(store, "analysis_graph_nodes", "WHERE node_kind != 'FILE'")
    edges = graph_rows(store, "analysis_graph_edges")
    symbols = AnalysisStore(store.db_path).symbols(None, None, None, None, None, 100, 0, flow_domain=GraphFlowDomain.WORKFLOW.value)
    code_symbols = AnalysisStore(store.db_path).symbols(None, None, None, None, None, 100, 0, flow_domain=GraphFlowDomain.CODE.value)
    code_relations = AnalysisStore(store.db_path).relations(None, None, None, None, 100, 0, flow_domain=GraphFlowDomain.CODE.value)

    assert nodes
    assert {row["flow_domain"] for row in nodes} == {GraphFlowDomain.WORKFLOW.value}
    assert {row["node_kind"] for row in nodes} == {GraphNodeKind.CONFIG.value}
    assert any(row["edge_type"] == GraphEdgeType.CONFIGURES.value for row in edges)
    assert not any(row["edge_type"] == GraphEdgeType.CALLS.value for row in edges)
    assert symbols["total"] == 2
    assert code_symbols["total"] == 0
    assert code_relations["total"] == 0


def test_valid_graph_response_stores_trusted_nodes_edges_evidence_and_claims(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count,
        nodes=[
            {"localId": "type", "nodeKind": "TYPE", "name": "ObjectHandler", "qualifiedName": "ObjectHandler", "lineStart": 1, "lineEnd": 5, "confidence": 0.91, "metadata": {}},
            {"localId": "create", "nodeKind": "CALLABLE", "name": "create", "qualifiedName": "ObjectHandler.create", "parentLocalId": "type", "lineStart": 3, "lineEnd": 4, "confidence": 0.92, "metadata": {}},
            {"localId": "helper", "nodeKind": "CALLABLE", "name": "helper", "qualifiedName": "ObjectHandler.helper", "parentLocalId": "type", "lineStart": 4, "lineEnd": 4, "confidence": 0.85, "metadata": {}},
        ],
        edges=[
            {"localId": "call", "edgeType": "CALLS", "fromLocalId": "create", "toLocalId": "helper", "lineStart": 4, "lineEnd": 4, "confidence": 0.8, "metadata": {}},
        ],
        claims=[
            {"localId": "resp", "nodeLocalId": "create", "claimKind": "RESPONSIBILITY", "summary": "Creates an object.", "evidence": [{"lineStart": 3, "lineEnd": 4}], "confidence": 0.86, "metadata": {}},
            {"localId": "role", "nodeLocalId": "create", "claimKind": "ROLE", "summary": "ENTRYPOINT_HINT", "evidence": [{"lineStart": 3, "lineEnd": 4}], "confidence": 0.75, "metadata": {"role": "ENTRYPOINT"}},
        ]))
    runner = AnalysisJobRunner(store, app_config(tmp_path))

    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    symbols = AnalysisStore(store.db_path).symbols(None, "ENTRYPOINT", None, None, None, 10, 0)
    relations = AnalysisStore(store.db_path).relations(None, "CALLS", None, None, 10, 0)

    assert final["status"] == "COMPLETED"
    assert len(graph_rows(store, "analysis_graph_nodes", "WHERE status = 'TRUSTED'")) >= 4
    assert len(graph_rows(store, "analysis_graph_edges", "WHERE edge_type = 'CALLS' AND status = 'TRUSTED'")) == 1
    assert len(graph_rows(store, "analysis_graph_evidence")) >= 5
    assert len(graph_rows(store, "analysis_graph_claims", "WHERE claim_kind = 'RESPONSIBILITY'")) >= 2
    assert symbols["symbols"][0]["responsibilitySummary"] == "Creates an object."
    assert symbols["symbols"][0]["graphNodeId"]
    assert relations["relations"][0]["resolutionStatus"] == "RESOLVED"


def test_graph_candidate_validation_rejects_unsupported_enums_and_bad_references(tmp_path):
    store, _, _ = build_inventory(tmp_path, content="// no declarations\n\n")
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count,
        nodes=[
            {"localId": "bad-kind", "nodeKind": "CONTROLLER", "name": "Bad", "lineStart": 1, "lineEnd": 1, "confidence": 0.9, "metadata": {}},
            {"localId": "source", "nodeKind": "CALLABLE", "name": "source", "qualifiedName": "A.source", "lineStart": 2, "lineEnd": 2, "confidence": 0.9, "metadata": {}},
        ],
        edges=[
            {"localId": "bad-edge-kind", "edgeType": "INJECTS", "fromLocalId": "source", "toLocalId": None, "lineStart": 2, "lineEnd": 2, "confidence": 0.9, "metadata": {}},
            {"localId": "missing-source", "edgeType": "CALLS", "fromLocalId": "missing", "toLocalId": "source", "lineStart": 2, "lineEnd": 2, "confidence": 0.9, "metadata": {}},
        ],
        claims=[
            {"localId": "bad-claim-kind", "nodeLocalId": "source", "claimKind": "BUSINESS_ROLE", "summary": "Does a thing.", "evidence": [{"lineStart": 2, "lineEnd": 2}], "confidence": 0.9, "metadata": {}},
            {"localId": "missing-evidence", "nodeLocalId": "source", "claimKind": "RESPONSIBILITY", "summary": "Does a thing.", "evidence": [], "confidence": 0.9, "metadata": {}},
        ]))
    runner = AnalysisJobRunner(store, app_config(tmp_path))

    wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)
    codes = {diagnostic["code"] for diagnostic in files["files"][0]["diagnostics"]}

    assert "ANALYSIS_GRAPH_UNSUPPORTED_NODE_KIND" in codes
    assert "ANALYSIS_GRAPH_UNSUPPORTED_EDGE_TYPE" in codes
    assert "ANALYSIS_GRAPH_EDGE_SOURCE_MISSING" in codes
    assert "ANALYSIS_GRAPH_UNSUPPORTED_CLAIM_KIND" in codes
    assert "ANALYSIS_GRAPH_CLAIM_EVIDENCE_MISSING" in codes
    assert AnalysisStore(store.db_path).symbols(None, None, None, None, None, 10, 0)["total"] == 1


def test_file_identity_mismatch_rejects_ai_candidates(tmp_path):
    store, _, _ = build_inventory(tmp_path, content="// no declarations\n")
    analyzer = GraphStubAnalyzer(lambda payload, line_count: {
        **graph_response(payload, line_count, nodes=[
            {"localId": "ai", "nodeKind": "CALLABLE", "name": "ai", "qualifiedName": "A.ai", "lineStart": 1, "lineEnd": 1, "confidence": 0.9, "metadata": {}},
        ]),
        "file": {
            "sourceId": payload["sourceId"],
            "inventoryFileId": payload["inventoryFileId"],
            "relativePath": payload["relativePath"],
            "contentHash": "wrong-content-hash",
            "lineCount": line_count,
        },
    })

    wait_job(store, AnalysisJobRunner(store, app_config_with_retries(tmp_path, 1)).start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)
    symbols = AnalysisStore(store.db_path).symbols(None, None, None, None, None, 10, 0)

    assert symbols["total"] == 0
    assert "ANALYSIS_GRAPH_FILE_IDENTITY_MISMATCH" in {diagnostic["code"] for diagnostic in files["files"][0]["diagnostics"]}


def test_duplicate_local_ids_are_rejected_with_diagnostics(tmp_path):
    store, _, _ = build_inventory(tmp_path, content="// no declarations\n\n")
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count,
        nodes=[
            {"localId": "source", "nodeKind": "CALLABLE", "name": "source", "qualifiedName": "A.source", "lineStart": 1, "lineEnd": 1, "confidence": 0.9, "metadata": {}},
            {"localId": "target", "nodeKind": "CALLABLE", "name": "target", "qualifiedName": "A.target", "lineStart": 2, "lineEnd": 2, "confidence": 0.9, "metadata": {}},
            {"localId": "target", "nodeKind": "CALLABLE", "name": "dupe", "qualifiedName": "A.dupe", "lineStart": 2, "lineEnd": 2, "confidence": 0.9, "metadata": {}},
        ],
        edges=[
            {"localId": "edge", "edgeType": "CALLS", "fromLocalId": "source", "toLocalId": "target", "lineStart": 1, "lineEnd": 1, "confidence": 0.9, "metadata": {}},
            {"localId": "edge", "edgeType": "CALLS", "fromLocalId": "source", "toLocalId": "target", "lineStart": 1, "lineEnd": 1, "confidence": 0.9, "metadata": {}},
        ],
        claims=[
            {"localId": "claim", "nodeLocalId": "source", "claimKind": "RESPONSIBILITY", "summary": "Does a thing.", "evidence": [{"lineStart": 1, "lineEnd": 1}], "confidence": 0.9, "metadata": {}},
            {"localId": "claim", "nodeLocalId": "source", "claimKind": "RESPONSIBILITY", "summary": "Does it again.", "evidence": [{"lineStart": 1, "lineEnd": 1}], "confidence": 0.9, "metadata": {}},
        ]))

    wait_job(store, AnalysisJobRunner(store, app_config_with_retries(tmp_path, 1)).start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)
    codes = {diagnostic["code"] for diagnostic in files["files"][0]["diagnostics"]}

    assert "ANALYSIS_GRAPH_DUPLICATE_NODE_LOCAL_ID" in codes
    assert "ANALYSIS_GRAPH_DUPLICATE_EDGE_LOCAL_ID" in codes
    assert "ANALYSIS_GRAPH_DUPLICATE_CLAIM_LOCAL_ID" in codes
    assert len(graph_rows(store, "analysis_graph_edges", "WHERE edge_type = 'CALLS' AND status = 'TRUSTED'")) == 1


def test_low_confidence_candidate_is_not_promoted_or_counted(tmp_path):
    store, _, _ = build_inventory(tmp_path, content="// no declarations\n")
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count,
        nodes=[
            {"localId": "low", "nodeKind": "CALLABLE", "name": "low", "qualifiedName": "A.low", "lineStart": 1, "lineEnd": 1, "confidence": 0.49, "metadata": {}},
        ]))

    wait_job(store, AnalysisJobRunner(store, app_config_with_retries(tmp_path, 1)).start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)
    service = AnalysisStore(store.db_path).service_status(None, analyzer.name, analyzer.version, store.status())["services"][0]

    assert AnalysisStore(store.db_path).symbols(None, None, None, None, None, 10, 0)["total"] == 0
    assert service["facts"]["symbolCount"] == 0
    assert "ANALYSIS_GRAPH_CONFIDENCE_BELOW_THRESHOLD" in {diagnostic["code"] for diagnostic in files["files"][0]["diagnostics"]}


def test_semantic_validation_repair_prompt_contains_exact_path_and_allowed_enums(tmp_path):
    store, _, _ = build_inventory(tmp_path, content="// no declarations\n\n")
    analyzer = StubAnalyzer(outcomes=[
        lambda payload, line_count: graph_response(payload, line_count, nodes=[
            {"localId": "bad-kind", "nodeKind": "CONTROLLER", "name": "Bad", "lineStart": 1, "lineEnd": 1, "confidence": 0.9, "metadata": {}},
        ]),
        lambda payload, line_count: graph_response(payload, line_count, nodes=[
            {"localId": "fixed", "nodeKind": "CALLABLE", "name": "fixed", "qualifiedName": "A.fixed", "lineStart": 1, "lineEnd": 1, "confidence": 0.9, "metadata": {}},
        ]),
    ])
    runner = AnalysisJobRunner(store, app_config(tmp_path))

    wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)
    symbols = AnalysisStore(store.db_path).symbols(None, None, None, None, None, 10, 0)

    assert analyzer.calls == 2
    assert symbols["total"] == 1
    assert analyzer.repair_prompts
    prompt = analyzer.repair_prompts[0]
    assert "$.nodes[0].nodeKind" in prompt
    assert "CONTROLLER" in prompt
    assert "CALLABLE" in prompt
    assert "UNKNOWN" in prompt
    assert "ANALYSIS_AI_VALIDATION_REPAIR_REQUESTED" in {item["code"] for item in files["files"][0]["diagnostics"]}


def test_semantic_repair_does_not_rerun_when_valid_facts_remain(tmp_path):
    store, _, _ = build_inventory(tmp_path, content="// no declarations\n\n")
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count,
        nodes=[
            {"localId": "source", "nodeKind": "CALLABLE", "name": "source", "qualifiedName": "A.source", "lineStart": 2, "lineEnd": 2, "confidence": 0.9, "metadata": {}},
        ],
        edges=[
            {"localId": "missing-source", "edgeType": "CALLS", "fromLocalId": "missing", "toLocalId": "source", "lineStart": 2, "lineEnd": 2, "confidence": 0.9, "metadata": {}},
        ]))
    runner = AnalysisJobRunner(store, app_config(tmp_path))

    wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)

    assert analyzer.calls == 1
    assert not analyzer.repair_prompts
    assert files["files"][0]["symbolCount"] == 1
    diagnostic = next(item for item in files["files"][0]["diagnostics"] if item["code"] == "ANALYSIS_GRAPH_EDGE_SOURCE_MISSING")
    assert diagnostic["validationCode"] == "UNKNOWN_LOCAL_REFERENCE"
    assert diagnostic["path"] == "$.edges[0].fromLocalId"


def test_graph_line_range_outside_file_is_rejected_with_diagnostic(tmp_path):
    store, _, _ = build_inventory(tmp_path, content="// no declarations\n")
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count,
        nodes=[
            {"localId": "outside", "nodeKind": "CALLABLE", "name": "outside", "qualifiedName": "A.outside", "lineStart": 1, "lineEnd": line_count + 10, "confidence": 0.9, "metadata": {}},
        ]))
    runner = AnalysisJobRunner(store, app_config(tmp_path))

    wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)

    assert files["files"][0]["symbolCount"] == 0
    assert files["files"][0]["diagnostics"][0]["code"] == "ANALYSIS_GRAPH_LINE_RANGE_INVALID"
    assert files["files"][0]["diagnostics"][0]["validationCode"] == "LINE_RANGE_OUTSIDE_FILE"
    assert files["files"][0]["diagnostics"][0]["path"] == "$.nodes[0]"


def test_responsibility_without_evidence_produces_structured_validation_error(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count,
        nodes=[
            {"localId": "source", "nodeKind": "CALLABLE", "name": "source", "qualifiedName": "A.source", "lineStart": 2, "lineEnd": 2, "confidence": 0.9, "metadata": {}},
        ],
        claims=[
            {"localId": "missing-evidence", "nodeLocalId": "source", "claimKind": "RESPONSIBILITY", "summary": "Does a thing.", "evidence": [], "confidence": 0.9, "metadata": {}},
        ]))
    runner = AnalysisJobRunner(store, app_config(tmp_path))

    wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)
    diagnostic = next(item for item in files["files"][0]["diagnostics"] if item["code"] == "ANALYSIS_GRAPH_CLAIM_EVIDENCE_MISSING")

    assert diagnostic["validationCode"] == "MISSING_EVIDENCE"
    assert diagnostic["path"] == "$.claims[0].evidence"
    assert "evidence" in diagnostic["repairHint"]


def test_unresolved_target_is_allowed_as_trusted_uncertainty(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count,
        nodes=[
            {"localId": "caller", "nodeKind": "CALLABLE", "name": "caller", "qualifiedName": "A.caller", "lineStart": 2, "lineEnd": 2, "confidence": 0.9, "metadata": {}},
        ],
        edges=[
            {"localId": "call", "edgeType": "CALLS", "fromLocalId": "caller", "toLocalId": None, "unresolvedTarget": {"name": "Missing.findById", "kindHint": "CALLABLE"}, "lineStart": 2, "lineEnd": 2, "confidence": 0.9, "metadata": {}},
        ]))
    runner = AnalysisJobRunner(store, app_config(tmp_path))

    wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    edges = graph_rows(store, "analysis_graph_edges", "WHERE edge_type = 'CALLS'")

    assert edges[0]["status"] == "TRUSTED"
    assert edges[0]["resolution_status"] == "UNRESOLVED"
    assert edges[0]["to_node_id"] is None


def test_multiple_target_candidates_and_interface_targets_remain_uncertain(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count,
        nodes=[
            {"localId": "caller", "nodeKind": "CALLABLE", "name": "caller", "qualifiedName": "A.caller", "lineStart": 2, "lineEnd": 2, "confidence": 0.9, "metadata": {}},
            {"localId": "repoA", "nodeKind": "CALLABLE", "name": "findById", "qualifiedName": "RepoA.findById", "lineStart": 3, "lineEnd": 3, "confidence": 0.9, "metadata": {}},
            {"localId": "repoB", "nodeKind": "CALLABLE", "name": "findById", "qualifiedName": "RepoB.findById", "lineStart": 4, "lineEnd": 4, "confidence": 0.9, "metadata": {}},
            {"localId": "iface", "nodeKind": "CALLABLE", "name": "save", "qualifiedName": "OrderRepository.save", "lineStart": 5, "lineEnd": 5, "confidence": 0.9, "metadata": {"declaringTypeKind": "interface"}},
        ],
        edges=[
            {"localId": "multi", "edgeType": "CALLS", "fromLocalId": "caller", "toLocalId": None, "unresolvedTarget": {"name": "findById", "kindHint": "CALLABLE"}, "lineStart": 2, "lineEnd": 2, "confidence": 0.9, "metadata": {}},
            {"localId": "iface-call", "edgeType": "CALLS", "fromLocalId": "caller", "toLocalId": None, "unresolvedTarget": {"name": "OrderRepository.save", "kindHint": "INTERFACE"}, "lineStart": 2, "lineEnd": 2, "confidence": 0.9, "metadata": {}},
        ]))
    runner = AnalysisJobRunner(store, app_config(tmp_path))

    wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    statuses = {row["resolution_status"] for row in graph_rows(store, "analysis_graph_edges", "WHERE edge_type = 'CALLS'")}
    candidates = graph_rows(store, "analysis_graph_resolution_candidates")

    assert "MULTIPLE_CANDIDATES" in statuses
    assert "INTERFACE_TARGET" in statuses
    assert len(candidates) >= 3


def test_resolver_uses_receiver_field_evidence_for_same_name_methods(tmp_path):
    content = "\n".join(f"// line {index}" for index in range(1, 12)) + "\n"
    store, _, _ = build_inventory(tmp_path, content=content)
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count,
        nodes=[
            {"localId": "svcType", "nodeKind": "TYPE", "name": "OrderService", "qualifiedName": "OrderService", "lineStart": 1, "lineEnd": 1, "confidence": 0.95, "metadata": {}},
            {"localId": "repoField", "nodeKind": "FIELD", "name": "orderRepository", "qualifiedName": "OrderService.orderRepository", "parentLocalId": "svcType", "lineStart": 2, "lineEnd": 2, "confidence": 0.95, "metadata": {"typeName": "OrderRepository", "receiverName": "orderRepository"}},
            {"localId": "svcMethod", "nodeKind": "CALLABLE", "name": "getById", "qualifiedName": "OrderService.getById", "parentLocalId": "svcType", "lineStart": 3, "lineEnd": 3, "confidence": 0.95, "metadata": {}},
            {"localId": "orderRepo", "nodeKind": "CALLABLE", "name": "findById", "qualifiedName": "OrderRepository.findById", "lineStart": 4, "lineEnd": 4, "confidence": 0.95, "metadata": {}},
            {"localId": "userRepo", "nodeKind": "CALLABLE", "name": "findById", "qualifiedName": "UserRepository.findById", "lineStart": 5, "lineEnd": 5, "confidence": 0.95, "metadata": {}},
            {"localId": "ticketRepo", "nodeKind": "CALLABLE", "name": "findById", "qualifiedName": "TicketRepository.findById", "lineStart": 6, "lineEnd": 6, "confidence": 0.95, "metadata": {}},
        ],
        edges=[
            {"localId": "call", "edgeType": "CALLS", "fromLocalId": "svcMethod", "toLocalId": None, "unresolvedTarget": {"name": "orderRepository.findById", "kindHint": "CALLABLE"}, "lineStart": 7, "lineEnd": 7, "confidence": 0.9, "metadata": {}},
        ]))

    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), analyzer)["jobId"])
    edge = graph_rows(store, "analysis_graph_edges", "WHERE edge_type = 'CALLS'")[0]
    target = graph_rows(store, "analysis_graph_nodes", "WHERE id = ?", (edge["to_node_id"],))[0]
    candidates = graph_rows(store, "analysis_graph_resolution_candidates", "WHERE edge_id = ?", (edge["id"],))

    assert edge["resolution_status"] == "RESOLVED"
    assert target["qualified_name"] == "OrderRepository.findById"
    assert target["qualified_name"] != "UserRepository.findById"
    assert len(candidates) == 3


def test_resolver_preserves_ambiguity_when_same_name_method_evidence_is_missing(tmp_path):
    content = "\n".join(f"// line {index}" for index in range(1, 10)) + "\n"
    store, _, _ = build_inventory(tmp_path, content=content)
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count,
        nodes=[
            {"localId": "caller", "nodeKind": "CALLABLE", "name": "getById", "qualifiedName": "OrderService.getById", "lineStart": 1, "lineEnd": 1, "confidence": 0.95, "metadata": {}},
            {"localId": "orderRepo", "nodeKind": "CALLABLE", "name": "findById", "qualifiedName": "OrderRepository.findById", "lineStart": 2, "lineEnd": 2, "confidence": 0.95, "metadata": {}},
            {"localId": "userRepo", "nodeKind": "CALLABLE", "name": "findById", "qualifiedName": "UserRepository.findById", "lineStart": 3, "lineEnd": 3, "confidence": 0.95, "metadata": {}},
            {"localId": "ticketRepo", "nodeKind": "CALLABLE", "name": "findById", "qualifiedName": "TicketRepository.findById", "lineStart": 4, "lineEnd": 4, "confidence": 0.95, "metadata": {}},
        ],
        edges=[
            {"localId": "call", "edgeType": "CALLS", "fromLocalId": "caller", "toLocalId": None, "unresolvedTarget": {"name": "findById", "kindHint": "CALLABLE"}, "lineStart": 5, "lineEnd": 5, "confidence": 0.9, "metadata": {}},
        ]))

    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), analyzer)["jobId"])
    edge = graph_rows(store, "analysis_graph_edges", "WHERE edge_type = 'CALLS'")[0]

    assert edge["resolution_status"] == "MULTIPLE_CANDIDATES"
    assert edge["to_node_id"] is None


def test_resolver_does_not_resolve_single_same_name_method_without_supporting_evidence(tmp_path):
    content = "\n".join(f"// line {index}" for index in range(1, 6)) + "\n"
    store, _, _ = build_inventory(tmp_path, content=content)
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count,
        nodes=[
            {"localId": "caller", "nodeKind": "CALLABLE", "name": "getById", "qualifiedName": "OrderService.getById", "lineStart": 1, "lineEnd": 1, "confidence": 0.95, "metadata": {}},
            {"localId": "repo", "nodeKind": "CALLABLE", "name": "findById", "qualifiedName": "OrderRepository.findById", "lineStart": 2, "lineEnd": 2, "confidence": 0.95, "metadata": {}},
        ],
        edges=[
            {"localId": "call", "edgeType": "CALLS", "fromLocalId": "caller", "toLocalId": None, "unresolvedTarget": {"name": "findById", "kindHint": "CALLABLE"}, "lineStart": 3, "lineEnd": 3, "confidence": 0.9, "metadata": {}},
        ]))

    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), analyzer)["jobId"])
    edge = graph_rows(store, "analysis_graph_edges", "WHERE edge_type = 'CALLS'")[0]

    assert edge["resolution_status"] == "UNRESOLVED"
    assert edge["to_node_id"] is None


def test_services_status_facts_count_uses_trusted_projected_facts_only(tmp_path):
    store, _, _ = build_inventory(tmp_path, content="// no declarations\n\n")
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count,
        nodes=[
            {"localId": "trusted", "nodeKind": "CALLABLE", "name": "trusted", "qualifiedName": "A.trusted", "lineStart": 2, "lineEnd": 2, "confidence": 0.9, "metadata": {}},
            {"localId": "rejected", "nodeKind": "CALLABLE", "name": "rejected", "qualifiedName": "A.rejected", "lineStart": 50, "lineEnd": 50, "confidence": 0.9, "metadata": {}},
        ]))
    runner = AnalysisJobRunner(store, app_config(tmp_path))

    wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    service = AnalysisStore(store.db_path).service_status(None, analyzer.name, analyzer.version, store.status())["services"][0]

    assert service["facts"]["symbolCount"] == 1
    assert service["diagnostics"][0]["stage"] == "CANDIDATE_VALIDATE"


def test_fixture_flow_slice_keeps_find_by_id_and_search_by_status_separate(tmp_path):
    content = """class Flows {
  void getById() {}
  void serviceGetById() {}
  void findById() {}
  void searchByStatus() {}
  void serviceSearchByStatus() {}
  void findByStatus() {}
}
"""
    store, _, _ = build_inventory(tmp_path, content=content)
    analyzer = GraphStubAnalyzer(lambda payload, line_count: graph_response(payload, line_count,
        nodes=[
            {"localId": "getById", "nodeKind": "CALLABLE", "name": "getById", "qualifiedName": "OrderController.getById", "lineStart": 2, "lineEnd": 2, "confidence": 0.95, "metadata": {}},
            {"localId": "svcGet", "nodeKind": "CALLABLE", "name": "getById", "qualifiedName": "OrderService.getById", "lineStart": 3, "lineEnd": 3, "confidence": 0.95, "metadata": {}},
            {"localId": "repoFind", "nodeKind": "CALLABLE", "name": "findById", "qualifiedName": "OrderRepository.findById", "lineStart": 4, "lineEnd": 4, "confidence": 0.95, "metadata": {}},
            {"localId": "search", "nodeKind": "CALLABLE", "name": "searchByStatus", "qualifiedName": "OrderSearchController.searchByStatus", "lineStart": 5, "lineEnd": 5, "confidence": 0.95, "metadata": {}},
            {"localId": "svcSearch", "nodeKind": "CALLABLE", "name": "searchByStatus", "qualifiedName": "OrderSearchService.searchByStatus", "lineStart": 6, "lineEnd": 6, "confidence": 0.95, "metadata": {}},
            {"localId": "repoStatus", "nodeKind": "CALLABLE", "name": "findByStatus", "qualifiedName": "OrderRepository.findByStatus", "lineStart": 7, "lineEnd": 7, "confidence": 0.95, "metadata": {}},
        ],
        edges=[
            {"localId": "e1", "edgeType": "CALLS", "fromLocalId": "getById", "toLocalId": "svcGet", "lineStart": 2, "lineEnd": 2, "confidence": 0.95, "metadata": {}},
            {"localId": "e2", "edgeType": "CALLS", "fromLocalId": "svcGet", "toLocalId": "repoFind", "lineStart": 3, "lineEnd": 3, "confidence": 0.95, "metadata": {}},
            {"localId": "e3", "edgeType": "CALLS", "fromLocalId": "search", "toLocalId": "svcSearch", "lineStart": 5, "lineEnd": 5, "confidence": 0.95, "metadata": {}},
            {"localId": "e4", "edgeType": "CALLS", "fromLocalId": "svcSearch", "toLocalId": "repoStatus", "lineStart": 6, "lineEnd": 6, "confidence": 0.95, "metadata": {}},
        ]))
    runner = AnalysisJobRunner(store, app_config(tmp_path))

    wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    analysis_store = AnalysisStore(store.db_path)
    by_id_slice = analysis_store.graph_slice(graph_node_id(store, "OrderController.getById"), max_depth=3, edge_types=["CALLS"])
    search_slice = analysis_store.graph_slice(graph_node_id(store, "OrderSearchController.searchByStatus"), max_depth=3, edge_types=["CALLS"])
    by_id_names = {node["qualifiedName"] for node in by_id_slice["nodes"]}
    search_names = {node["qualifiedName"] for node in search_slice["nodes"]}

    assert "OrderRepository.findById" in by_id_names
    assert "OrderRepository.findByStatus" not in by_id_names
    assert "OrderRepository.findByStatus" in search_names
    assert "OrderRepository.findById" not in search_names


def test_no_source_file_mutation(tmp_path):
    store, _, service = build_inventory(tmp_path)
    source = service / "src/main/java/example/ObjectHandler.java"
    before = source.read_text(encoding="utf-8")
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    assert source.read_text(encoding="utf-8") == before


def test_no_production_domain_hardcoded_synonyms():
    src = Path("infrastructure/knowledge/services/knowledge-service/src/knowledge_service")
    banned = ["_AUTH_QUERY", "site creation", "авторизація"]
    combined = "\n".join(path.read_text(encoding="utf-8") for path in src.rglob("*.py"))

    assert all(term not in combined for term in banned)


def test_no_production_file_format_classification_hardcodes():
    src = Path("infrastructure/knowledge/services/knowledge-service/src/knowledge_service")
    banned = [
        "pom.xml",
        "build.gradle",
        "settings.gradle",
        "package.json",
        "pnpm-lock.yaml",
        "yarn.lock",
        "package-lock.json",
        "application.yml",
        "bootstrap.yml",
    ]
    combined = "\n".join(path.read_text(encoding="utf-8") for path in src.rglob("*.py"))

    assert all(term not in combined for term in banned)


class FakeRunner:
    def start(self, request):
        return {"jobId": "job-1", "status": "QUEUED", "message": "Knowledge analysis job queued"}

    def stop(self, job_id):
        return {"jobId": job_id, "status": "STOP_REQUESTED", "message": "Knowledge analysis stop requested"}


def configure_api(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    monkeypatch.setattr(main, "analysis_runner", FakeRunner())
    return store


def post_json(path, payload):
    import asyncio
    return asyncio.run(asgi_json("POST", path, payload))


def get_json(path):
    import asyncio
    return asyncio.run(asgi_json("GET", path, None))


async def asgi_json(method, path, payload):
    body = json.dumps(payload or {}).encode("utf-8")
    messages = []
    received = False

    async def receive():
        nonlocal received
        if received:
            return {"type": "http.disconnect"}
        received = True
        return {"type": "http.request", "body": body, "more_body": False}

    async def send(message):
        messages.append(message)

    raw_path, _, query = path.partition("?")
    scope = {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": method,
        "path": raw_path,
        "raw_path": raw_path.encode("utf-8"),
        "query_string": query.encode("utf-8"),
        "headers": [(b"content-type", b"application/json"), (b"accept", b"application/json")],
        "client": ("testclient", 50000),
        "server": ("testserver", 80),
        "scheme": "http",
    }
    await main.app(scope, receive, send)
    status = next(message["status"] for message in messages if message["type"] == "http.response.start")
    response_body = b"".join(message.get("body", b"") for message in messages if message["type"] == "http.response.body")
    return {"status": status, "json": json.loads(response_body.decode("utf-8") or "{}")}


def test_analysis_api_build_proxies_to_runner(tmp_path, monkeypatch):
    configure_api(tmp_path, monkeypatch)

    result = post_json("/api/v1/knowledge/analysis/build", {"sourceIds": ["edge-gateway"], "concurrency": 1})

    assert result["status"] == 200
    assert result["json"]["jobId"] == "job-1"


def test_analysis_api_refreshes_inventory_before_queueing_job(tmp_path, monkeypatch):
    create_source_config(tmp_path)
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    monkeypatch.setattr(main, "analysis_runner", FakeRunner())

    result = post_json("/api/v1/knowledge/analysis/build", {"sourceIds": ["edge-gateway"], "concurrency": 1})

    status = store.status()
    rows, _ = store.search_rows(["edge-gateway"], [])
    assert result["status"] == 200
    assert status["status"] == "READY"
    assert status["fileCount"] == 1
    assert len(rows) == 1


def test_analysis_api_job_status_endpoint(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    AnalysisStore(store.db_path).create_job({
        "jobId": "job-2",
        "status": "RUNNING",
        "startedAt": "now",
        "sourceCount": 1,
        "fileCount": 1,
    })

    result = get_json("/api/v1/knowledge/analysis/jobs/job-2")

    assert result["json"]["status"] == "RUNNING"


def test_analysis_api_status_files_symbols_relations(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    status = get_json("/api/v1/knowledge/analysis/status")
    files = get_json("/api/v1/knowledge/analysis/files?sourceId=edge-gateway")
    symbols = get_json("/api/v1/knowledge/analysis/symbols?role=HTTP_HANDLER")
    relations = get_json("/api/v1/knowledge/analysis/relations?relation=CONTAINS")

    assert status["json"]["symbolCount"] == 2
    assert files["json"]["total"] == 1
    assert symbols["json"]["symbols"][0]["roles"][0]["role"] == "HTTP_HANDLER"
    assert symbols["json"]["symbols"][0]["graphNodeId"]
    assert symbols["json"]["symbols"][0]["factStatus"] == "TRUSTED"
    assert relations["json"]["relations"][0]["relation"] == "CONTAINS"
    assert relations["json"]["relations"][0]["graphEdgeId"]
    assert relations["json"]["relations"][0]["resolutionStatus"] == "RESOLVED"


def test_analysis_api_stop_job(tmp_path, monkeypatch):
    configure_api(tmp_path, monkeypatch)

    result = post_json("/api/v1/knowledge/analysis/jobs/job-1/stop", {})

    assert result["status"] == 200
    assert result["json"]["status"] == "STOP_REQUESTED"


def test_status_api_separates_coverage_and_freshness_without_running_ai(tmp_path, monkeypatch):
    store, _, service = build_inventory(tmp_path)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service / "src/main/java/example/ObjectHandler.java").write_text("public class ObjectHandler { void changed() {} }\n", encoding="utf-8")
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)

    class FailingRunner:
        def start(self, request):
            raise AssertionError("status must not run AI analysis")

    monkeypatch.setattr(main, "analysis_runner", FailingRunner())

    result = get_json("/api/v1/knowledge/status")

    assert result["status"] == 200
    assert result["json"]["coverage"]["scannedFiles"] == 1
    assert result["json"]["coverage"]["eligibleFiles"] == 1
    assert result["json"]["freshness"]["status"] == "OUTDATED"
    assert result["json"]["freshness"]["modifiedFiles"] == 1


def test_analysis_api_exposes_failed_file_diagnostics_and_progress(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path, extra_files={
        "src/main/java/example/SecondHandler.java": "public class SecondHandler {}\n",
    })
    cfg = app_config_with_retries(tmp_path, 1)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    monkeypatch.setattr(main, "analysis_runner", FakeRunner())
    analyzer = StubAnalyzer(outcomes=[
        KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad", attempt=1),
        lambda payload, line_count: valid_graph_response(payload, line_count),
    ])
    wait_job(store, AnalysisJobRunner(store, cfg).start(AnalysisBuildRequest(), analyzer)["jobId"])

    status = get_json("/api/v1/knowledge/analysis/status")
    files = get_json("/api/v1/knowledge/analysis/files?status=FAILED")

    assert status["json"]["lastCompletedAt"]
    assert files["json"]["total"] == 1
    assert files["json"]["files"][0]["lastErrorCode"] == "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"
    assert files["json"]["files"][0]["lastRawResponsePreview"] == "{bad"
    assert {item["code"] for item in files["json"]["files"][0]["diagnostics"]} >= {"ANALYSIS_AI_INVALID_JSON", "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"}


def test_services_status_returns_inventory_analysis_and_facts_counts(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    wait_job(store, AnalysisJobRunner(store, cfg).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    result = get_json("/api/v1/knowledge/services/status")
    service = result["json"]["services"][0]

    assert result["status"] == 200
    assert service["sourceId"] == "edge-gateway"
    assert service["label"] == "Edge Gateway"
    assert service["inventory"]["eligibleFileCount"] == 1
    assert service["analysis"]["inventoryFileCount"] == 1
    assert service["analysis"]["analyzedFileCount"] == 1
    assert service["analysis"]["percent"] == 100.0
    assert service["facts"]["symbolCount"] == 2
    assert service["facts"]["relationCount"] == 1


def test_services_status_can_embed_selected_service_details(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    wait_job(store, AnalysisJobRunner(store, cfg).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    result = get_json("/api/v1/knowledge/services/status?detailsSourceId=edge-gateway")
    service = result["json"]["services"][0]

    assert result["status"] == 200
    assert service["sourceId"] == "edge-gateway"
    assert service["details"]["symbols"]["total"] == 2
    assert service["details"]["relations"]["total"] == 1
    assert service["details"]["failures"]["total"] == 0


def test_services_status_uses_current_content_hash_for_analyzed_and_stale(tmp_path, monkeypatch):
    store, config, service_root = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    wait_job(store, AnalysisJobRunner(store, cfg).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service_root / "src/main/java/example/ObjectHandler.java").write_text("public class ObjectHandler { void changed() {} }\n", encoding="utf-8")
    InventoryBuilder(load_source_config(config), store).build([], [])

    result = get_json("/api/v1/knowledge/services/status")
    service = result["json"]["services"][0]

    assert service["analysis"]["inventoryFileCount"] == 1
    assert service["analysis"]["analyzedFileCount"] == 0
    assert service["analysis"]["staleFileCount"] == 1
    assert service["analysis"]["status"] == "OUTDATED"


def test_services_status_reports_failed_files_separately_and_groups_diagnostics(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config_with_retries(tmp_path, 1)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    analyzer = StubAnalyzer(outcomes=[
        KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad", attempt=1),
    ])
    wait_job(store, AnalysisJobRunner(store, cfg).start(AnalysisBuildRequest(), analyzer)["jobId"])

    result = get_json("/api/v1/knowledge/services/status")
    service = result["json"]["services"][0]

    assert service["analysis"]["analyzedFileCount"] == 0
    assert service["analysis"]["processedFileCount"] == 1
    assert service["analysis"]["failedFileCount"] == 1
    assert service["analysis"]["pendingFileCount"] == 0
    assert service["analysis"]["percent"] == 100.0
    assert {item["code"]: item["count"] for item in service["diagnostics"]}["ANALYSIS_AI_INVALID_JSON"] == 1


def test_services_status_missing_inventory_returns_zero_counts(tmp_path, monkeypatch):
    config = create_source_config(tmp_path)
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)

    result = get_json("/api/v1/knowledge/services/status")
    service = result["json"]["services"][0]

    assert load_source_config(config)
    assert service["inventory"]["eligibleFileCount"] == 0
    assert service["analysis"]["inventoryFileCount"] == 0
    assert service["facts"]["symbolCount"] == 0
    assert service["facts"]["relationCount"] == 0


def test_analysis_store_drops_legacy_fact_tables(tmp_path):
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    with store._connect() as conn:
        for table in ("symbol_tokens", "edges", "symbols", "file_extraction_state", "fact_builds"):
            conn.execute(f"CREATE TABLE {table} (id INTEGER)")

    store.init()

    with store._connect() as conn:
        tables = {
            row["name"]
            for row in conn.execute("SELECT name FROM sqlite_master WHERE type = 'table'").fetchall()
        }
    assert not {"symbol_tokens", "edges", "symbols", "file_extraction_state", "fact_builds"} & tables
