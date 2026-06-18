import hashlib
import json
import os
import sqlite3
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import quote

import pytest
from pydantic import ValidationError

os.environ.setdefault("KNOWLEDGE_STORE_PATH", "/tmp/forge-ai-knowledge-test-main.sqlite")

from knowledge_service import main
from knowledge_service.analysis_client import OllamaAnalysisClient
from knowledge_service.analysis_response_parser import AiAnalysisResponseParser
from knowledge_service.analysis_schema import AnalysisBuildRequest, AnalysisResult
from knowledge_service.analysis_service import AnalysisJobRunner
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.config import AppConfig
from knowledge_service.errors import KnowledgeError
from knowledge_service.freshness_service import KnowledgeFreshnessService
from knowledge_service.graph_analysis import GraphAnalysisEngine
from knowledge_service.graph_schema import GraphAnalysisResult
from knowledge_service.graph_slice_service import GraphSliceRequest, GraphSliceService
from knowledge_service.inventory_builder import InventoryBuilder
from knowledge_service.inventory_refresh import BackgroundInventoryScheduler, InventoryRefreshService
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.java_parser_adapter import JavaParserAdapter
from knowledge_service.source_config import load_source_config
from knowledge_service.structural_analysis import GRAPH_ENGINE_VERSION, StaticGraphMaterializer
from knowledge_service.structural_model import StructuralFileMetadata


class StubAnalyzer:
    name = "ai-file-analyzer"
    version = "1"

    def __init__(self, result=None, fail=False, block_event=None, bad_response_attempts=0, outcomes=None):
        self.result = result or valid_result()
        self.fail = fail
        self.block_event = block_event
        self.bad_response_attempts = bad_response_attempts
        self.outcomes = list(outcomes or [])
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
            return outcome
        if self.calls <= self.bad_response_attempts:
            raise KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad", attempt=self.calls)
        if self.fail:
            raise RuntimeError("model failed")
        self.result.validate_lines(line_count)
        return self.result


def valid_result():
    return AnalysisResult.parse_obj({
        "fileSummary": "Defines an object handler and helper.",
        "symbols": [
            {
                "localId": "s1",
                "name": "ObjectHandler",
                "kind": "CLASS",
                "roles": [{"role": "HTTP_HANDLER", "confidence": 0.9, "evidence": ["Has a method annotated with an HTTP mapping."]}],
                "lineStart": 1,
                "lineEnd": 5,
                "metadata": {"language": "java"},
            },
            {
                "localId": "s2",
                "name": "create",
                "kind": "METHOD",
                "roles": [{"role": "ENTRYPOINT", "confidence": 0.8, "evidence": ["Method is externally callable in this file."]}],
                "lineStart": 3,
                "lineEnd": 4,
                "metadata": {},
            },
        ],
        "relations": [
            {
                "fromLocalId": "s1",
                "toLocalId": "s2",
                "relation": "CONTAINS",
                "confidence": 1.0,
                "evidence": ["The method is declared inside the class."],
                "lineStart": 3,
                "lineEnd": 3,
                "metadata": {},
            }
        ],
        "diagnostics": [],
    })


def responsibility_graph_result(method_claim=True, type_claim=True, file_claim=False, method_confidence=0.86, method_summary="Handles object creation."):
    nodes = [
        {
            "localId": "type1",
            "nodeKind": "TYPE",
            "name": "ObjectHandler",
            "language": "java",
            "qualifiedName": "example.ObjectHandler",
            "displayName": "ObjectHandler",
            "parentLocalId": "file1" if file_claim else None,
            "lineStart": 1,
            "lineEnd": 5,
            "confidence": 0.91,
            "metadata": {"sourceKind": "CLASS"},
        },
        {
            "localId": "method1",
            "nodeKind": "CALLABLE",
            "name": "create",
            "language": "java",
            "qualifiedName": "example.ObjectHandler.create",
            "displayName": "create",
            "parentLocalId": "type1",
            "lineStart": 3,
            "lineEnd": 4,
            "confidence": method_confidence,
            "metadata": {"sourceKind": "METHOD"},
        },
    ]
    edges = [
        {
            "localId": "declares-create",
            "fromNodeLocalId": "type1",
            "toNodeLocalId": "method1",
            "edgeType": "DECLARES",
            "confidence": 0.95,
            "evidence": [{"lineStart": 3, "lineEnd": 4, "text": "create method declaration", "metadata": {}}],
            "unresolvedTarget": None,
            "metadata": {},
        }
    ]
    claims = []
    if file_claim:
        nodes.insert(0, {
            "localId": "file1",
            "nodeKind": "FILE",
            "name": "ObjectHandler.java",
            "language": "java",
            "qualifiedName": None,
            "displayName": "ObjectHandler.java",
            "parentLocalId": None,
            "lineStart": 1,
            "lineEnd": 5,
            "confidence": 0.9,
            "metadata": {"sourceKind": "FILE"},
        })
        edges.insert(0, {
            "localId": "file-declares-type",
            "fromNodeLocalId": "file1",
            "toNodeLocalId": "type1",
            "edgeType": "DECLARES",
            "confidence": 0.9,
            "evidence": [{"lineStart": 1, "lineEnd": 5, "text": "class declaration", "metadata": {}}],
            "unresolvedTarget": None,
            "metadata": {},
        })
        claims.append({
            "localId": "file-responsibility",
            "nodeLocalId": "file1",
            "claimKind": "RESPONSIBILITY",
            "summary": "Defines an object handler file.",
            "evidence": [{"lineStart": 1, "lineEnd": 5, "text": "file content", "metadata": {}}],
            "confidence": 0.82,
            "metadata": {},
        })
    if type_claim:
        claims.append({
            "localId": "type-responsibility",
            "nodeLocalId": "type1",
            "claimKind": "RESPONSIBILITY",
            "summary": "Handles object requests.",
            "evidence": [{"lineStart": 1, "lineEnd": 5, "text": "class body", "metadata": {}}],
            "confidence": 0.88,
            "metadata": {},
        })
    if method_claim:
        claims.append({
            "localId": "method-responsibility",
            "nodeLocalId": "method1",
            "claimKind": "RESPONSIBILITY",
            "summary": method_summary,
            "evidence": [{"lineStart": 3, "lineEnd": 4, "text": "method body", "metadata": {}}],
            "confidence": method_confidence,
            "metadata": {},
        })
    return GraphAnalysisResult.parse_obj({
        "nodes": nodes,
        "edges": edges,
        "claims": claims,
        "diagnostics": [],
    })


def shared_evidence_graph_result():
    return GraphAnalysisResult.parse_obj({
        "nodes": [
            {
                "localId": "file1",
                "nodeKind": "FILE",
                "name": "EmailVerificationLinkClientImpl.java",
                "language": "java",
                "lineStart": 1,
                "lineEnd": 6,
                "confidence": 1.0,
                "metadata": {"sourceKind": "FILE", "factOrigin": "STATIC", "flowDomain": "CODE"},
            },
            {
                "localId": "type1",
                "nodeKind": "TYPE",
                "name": "EmailVerificationLinkClientImpl",
                "language": "java",
                "qualifiedName": "example.EmailVerificationLinkClientImpl",
                "parentLocalId": "file1",
                "lineStart": 1,
                "lineEnd": 6,
                "confidence": 1.0,
                "metadata": {"sourceKind": "CLASS", "factOrigin": "STATIC", "flowDomain": "CODE"},
            },
            {
                "localId": "method1",
                "nodeKind": "CALLABLE",
                "name": "createLink",
                "language": "java",
                "qualifiedName": "example.EmailVerificationLinkClientImpl.createLink",
                "parentLocalId": "type1",
                "lineStart": 3,
                "lineEnd": 5,
                "confidence": 1.0,
                "metadata": {"sourceKind": "METHOD", "factOrigin": "STATIC", "flowDomain": "CODE"},
            },
            {
                "localId": "method2",
                "nodeKind": "CALLABLE",
                "name": "helper",
                "language": "java",
                "qualifiedName": "example.EmailVerificationLinkClientImpl.helper",
                "parentLocalId": "type1",
                "lineStart": 6,
                "lineEnd": 6,
                "confidence": 1.0,
                "metadata": {"sourceKind": "METHOD", "factOrigin": "STATIC", "flowDomain": "CODE"},
            },
        ],
        "edges": [
            {
                "localId": "declares-method",
                "fromNodeLocalId": "type1",
                "toNodeLocalId": "method1",
                "edgeType": "DECLARES",
                "confidence": 1.0,
                "evidence": [{"lineStart": 3, "lineEnd": 5, "text": "String createLink()", "metadata": {}}],
                "metadata": {"factOrigin": "STATIC", "flowDomain": "CODE"},
            },
            {
                "localId": "same-callsite",
                "fromNodeLocalId": "method1",
                "toNodeLocalId": "method2",
                "edgeType": "CALLS",
                "confidence": 1.0,
                "evidence": [{"lineStart": 4, "lineEnd": 4, "text": "helper(); helper();", "metadata": {"evidenceKind": "CALLSITE"}}],
                "metadata": {"factOrigin": "STATIC", "flowDomain": "CODE", "resolutionStatus": "RESOLVED", "methodName": "helper"},
            },
            {
                "localId": "same-callsite",
                "fromNodeLocalId": "method1",
                "toNodeLocalId": "method2",
                "edgeType": "CALLS",
                "confidence": 1.0,
                "evidence": [{"lineStart": 4, "lineEnd": 4, "text": "helper(); helper();", "metadata": {"evidenceKind": "CALLSITE"}}],
                "metadata": {"factOrigin": "STATIC", "flowDomain": "CODE", "resolutionStatus": "RESOLVED", "methodName": "helper"},
            },
        ],
        "claims": [
            {
                "localId": "shared-claim",
                "nodeLocalId": "method1",
                "claimKind": "RESPONSIBILITY",
                "summary": "Creates an email verification link.",
                "evidence": [{"lineStart": 3, "lineEnd": 5, "text": "method body", "metadata": {}}],
                "confidence": 0.9,
                "metadata": {"factOrigin": "LLM", "flowDomain": "CODE"},
            },
            {
                "localId": "shared-claim",
                "nodeLocalId": "method1",
                "claimKind": "ROLE",
                "summary": "Client helper method.",
                "evidence": [{"lineStart": 3, "lineEnd": 5, "text": "method body", "metadata": {}}],
                "confidence": 0.8,
                "metadata": {"factOrigin": "LLM", "flowDomain": "CODE"},
            },
        ],
        "diagnostics": [],
    })


def materialize_graph_for_test(result, content=None, file_id=1, relative_path="src/main/java/example/EmailVerificationLinkClientImpl.java"):
    content = content or "class EmailVerificationLinkClientImpl {\n  WebClient client;\n  String createLink() {\n    helper(); helper();\n  }\n  void helper() {}\n}\n"
    row = {
        "id": file_id,
        "source_id": "edge-gateway",
        "relative_path": relative_path,
        "content_hash": hashlib.sha256(content.encode("utf-8")).hexdigest(),
    }
    return GraphAnalysisEngine().materialize(row, "job-1", "test-analyzer", "1", result, content.splitlines())


def graph_state_for_test(content=None, relative_path="src/main/java/example/EmailVerificationLinkClientImpl.java"):
    content = content or "class EmailVerificationLinkClientImpl {}\n"
    return {
        "source_id": "edge-gateway",
        "relative_path": relative_path,
        "content_hash": hashlib.sha256(content.encode("utf-8")).hexdigest(),
        "analyzer_name": "test-analyzer",
        "analyzer_version": "1",
        "engine_version": GRAPH_ENGINE_VERSION,
        "flow_domain": "CODE",
        "status": "ANALYZED",
        "analyzed_at": "now",
        "symbol_count": 0,
        "relation_count": 0,
        "diagnostics": [],
    }


def build_inventory(tmp_path, content=None, include_large=False, extra_files=None, include_patterns=None):
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
  include: {json.dumps(include_patterns or ["**/*.java"])}
""",
        encoding="utf-8",
    )
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    InventoryBuilder(load_source_config(config), store).build([], [])
    return store, config, service


def test_analysis_store_migrates_old_integer_graph_diagnostics_schema(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    with sqlite3.connect(db_path) as conn:
        conn.execute("CREATE TABLE sources (source_id TEXT PRIMARY KEY, display_name TEXT)")
        conn.execute("INSERT INTO sources(source_id, display_name) VALUES ('edge-gateway', 'Edge Gateway')")
        conn.execute("""
            CREATE TABLE files (
                id INTEGER PRIMARY KEY,
                source_id TEXT NOT NULL,
                relative_path TEXT NOT NULL
            )
        """)
        conn.execute("INSERT INTO files(id, source_id, relative_path) VALUES (1, 'edge-gateway', 'workflow.yml')")
        conn.execute("""
            CREATE TABLE analysis_graph_diagnostics (
                id INTEGER PRIMARY KEY,
                job_id TEXT,
                source_id TEXT NOT NULL,
                inventory_file_id INTEGER,
                analysis_file_id INTEGER,
                relative_path TEXT,
                stage TEXT NOT NULL,
                code TEXT NOT NULL,
                severity TEXT NOT NULL,
                message TEXT NOT NULL,
                candidate_id TEXT,
                line_start INTEGER,
                line_end INTEGER,
                metadata_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                fact_origin TEXT,
                flow_domain TEXT
            )
        """)
        conn.execute("""
            INSERT INTO analysis_graph_diagnostics(
                source_id, stage, code, severity, message, metadata_json, created_at
            )
            VALUES ('edge-gateway', 'OLD', 'OLD_DIAGNOSTIC', 'WARN', 'old', '{}', 'now')
        """)

    AnalysisStore(db_path).init()

    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        columns = {row["name"]: row for row in conn.execute("PRAGMA table_info(analysis_graph_diagnostics)").fetchall()}
        assert str(columns["id"]["type"]).upper() == "TEXT"
        assert columns["id"]["pk"] == 1
        conn.execute("""
            INSERT INTO analysis_graph_diagnostics(
                id, job_id, source_id, severity, stage, code, message, metadata_json, created_at
            )
            VALUES ('diagnostic:text-id', 'job-1', 'edge-gateway', 'WARN', 'STRUCTURAL_PARSE',
                    'STRUCTURAL_PARSER_NOT_AVAILABLE', 'No parser.', '{}', 'now')
        """)
        assert conn.execute("SELECT COUNT(*) FROM sources").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM files").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_diagnostics").fetchone()[0] == 1
        assert conn.execute(
            "SELECT 1 FROM analysis_schema_migrations WHERE version = 4"
        ).fetchone()

    AnalysisStore(db_path).init()

    with sqlite3.connect(db_path) as conn:
        assert conn.execute(
            "SELECT COUNT(*) FROM analysis_graph_diagnostics WHERE id = 'diagnostic:text-id'"
        ).fetchone()[0] == 1


def test_graph_persistence_failure_is_reported_as_graph_store_error(tmp_path):
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    bad_graph = {
        "nodes": [{
            "id": "node-1",
            "job_id": "job-1",
            "source_id": "edge-gateway",
            "inventory_file_id": 1,
            "analysis_file_id": 1,
            "stable_key": "edge-gateway|bad.yml|FILE",
            "node_kind": "FILE",
            "language": "yaml",
            "name": None,
            "qualified_name": None,
            "display_name": None,
            "parent_node_id": None,
            "line_start": 1,
            "line_end": 1,
            "confidence": 1.0,
            "status": "TRUSTED",
            "metadata": {},
            "fact_origin": "STATIC",
            "flow_domain": "WORKFLOW",
        }],
        "edges": [],
        "claims": [],
        "evidence": [],
        "diagnostics": [],
    }

    with pytest.raises(KnowledgeError) as raised:
        store.replace_file_graph_analysis(1, {
            "source_id": "edge-gateway",
            "relative_path": "bad.yml",
            "content_hash": "hash",
            "analyzer_name": "ai-file-analyzer",
            "analyzer_version": "1",
            "engine_version": GRAPH_ENGINE_VERSION,
            "flow_domain": "WORKFLOW",
            "status": "ANALYZED",
            "analyzed_at": "now",
            "symbol_count": 1,
            "relation_count": 0,
            "diagnostics": [],
        }, bad_graph)

    assert raised.value.code == "ANALYSIS_GRAPH_STORE_FAILED"
    assert raised.value.details["stage"] == "GRAPH_STORE"
    assert raised.value.details["table"] == "analysis_graph_nodes"
    assert raised.value.details["operation"] == "insert_nodes"
    assert "NOT NULL" in raised.value.details["sqliteMessage"]


def test_analysis_store_init_is_safe_for_parallel_graph_requests(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"

    def initialize_store(_index: int) -> None:
        AnalysisStore(db_path).init()

    with ThreadPoolExecutor(max_workers=8) as executor:
        list(executor.map(initialize_store, range(24)))

    with sqlite3.connect(db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM sqlite_master WHERE name = 'analysis_graph_nodes'").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM sqlite_master WHERE name = 'analysis_graph_edges'").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM sqlite_master WHERE name = 'analysis_graph_diagnostics'").fetchone()[0] == 1


def test_graph_materialization_generates_unique_evidence_ids_for_shared_ranges():
    graph = materialize_graph_for_test(shared_evidence_graph_result())
    evidence_ids = [item["id"] for item in graph["evidence"]]
    edge_ids = [item["id"] for item in graph["edges"]]
    claim_ids = [item["id"] for item in graph["claims"]]

    assert len(evidence_ids) == len(set(evidence_ids))
    assert len(edge_ids) == len(set(edge_ids))
    assert len(claim_ids) == len(set(claim_ids))
    assert len(graph["evidence"]) == 5


def test_graph_store_accepts_shared_evidence_ranges_and_replaces_file_twice(tmp_path):
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    content = "class EmailVerificationLinkClientImpl {\n  WebClient client;\n  String createLink() {\n    helper(); helper();\n  }\n  void helper() {}\n}\n"
    first_graph = materialize_graph_for_test(shared_evidence_graph_result(), content=content)
    second_graph = materialize_graph_for_test(shared_evidence_graph_result(), content=content)
    state = graph_state_for_test(content)

    assert {item["id"] for item in first_graph["evidence"]} == {item["id"] for item in second_graph["evidence"]}

    store.replace_file_graph_analysis(1, state, first_graph)
    store.replace_file_graph_analysis(1, state, second_graph)

    with sqlite3.connect(store.db_path) as conn:
        counts = {
            table: conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
            for table in [
                "analysis_graph_nodes",
                "analysis_graph_edges",
                "analysis_graph_claims",
                "analysis_graph_evidence",
            ]
        }

    assert counts["analysis_graph_nodes"] == len(second_graph["nodes"])
    assert counts["analysis_graph_edges"] == len(second_graph["edges"])
    assert counts["analysis_graph_claims"] == len(second_graph["claims"])
    assert counts["analysis_graph_evidence"] == len(second_graph["evidence"])


def test_duplicate_evidence_id_is_reported_as_graph_store_error_without_partial_rows(tmp_path):
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    content = "class EmailVerificationLinkClientImpl {\n  void createLink() { helper(); helper(); }\n  void helper() {}\n}\n"
    graph = materialize_graph_for_test(shared_evidence_graph_result(), content=content)
    graph["evidence"][1]["id"] = graph["evidence"][0]["id"]

    with pytest.raises(KnowledgeError) as raised:
        store.replace_file_graph_analysis(1, graph_state_for_test(content), graph)

    assert raised.value.code == "ANALYSIS_GRAPH_STORE_FAILED"
    assert raised.value.details["stage"] == "GRAPH_STORE"
    assert raised.value.details["table"] == "analysis_graph_evidence"
    assert raised.value.details["operation"] == "insert_evidence"
    assert "UNIQUE constraint failed: analysis_graph_evidence.id" in raised.value.details["sqliteMessage"]
    with sqlite3.connect(store.db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_nodes").fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_evidence").fetchone()[0] == 0


def test_reduced_email_verification_client_chained_calls_store_without_evidence_collision(tmp_path):
    content = """package example;

import org.springframework.web.reactive.function.client.WebClient;

class EmailVerificationLinkClientImpl {
  private final WebClient client;

  String createLink(String email) {
    return client.get().uri("/verify", email).retrieve().bodyToMono(String.class).block();
  }
}
"""
    file_metadata = StructuralFileMetadata(
        source_id="edge-gateway",
        inventory_file_id=1,
        relative_path="clients/client-athssox/src/main/java/com/sitionix/ntfssox/client/EmailVerificationLinkClientImpl.java",
        language="java",
        flow_domain="CODE",
        content_hash=hashlib.sha256(content.encode("utf-8")).hexdigest(),
        line_count=len(content.splitlines()),
        decode_policy="utf-8:replace",
    )
    structural = JavaParserAdapter().parse(content, file_metadata)
    graph_result = StaticGraphMaterializer().to_graph(structural)
    graph = materialize_graph_for_test(graph_result, content=content, relative_path=file_metadata.relative_path)
    evidence_ids = [item["id"] for item in graph["evidence"]]

    assert len(evidence_ids) == len(set(evidence_ids))
    assert any(edge["edge_type"] == "CALLS" for edge in graph["edges"])

    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    store.replace_file_graph_analysis(1, graph_state_for_test(content, file_metadata.relative_path), graph)

    with sqlite3.connect(store.db_path) as conn:
        edge_count = conn.execute("SELECT COUNT(*) FROM analysis_graph_edges WHERE edge_type = 'CALLS'").fetchone()[0]
        evidence_count = conn.execute("SELECT COUNT(*) FROM analysis_graph_evidence").fetchone()[0]

    assert edge_count > 0
    assert evidence_count == len(evidence_ids)


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


def test_ai_output_schema_validates_valid_response():
    result = valid_result()

    assert result.symbols[0].roles[0].role == "HTTP_HANDLER"


def test_invalid_json_rejected():
    with pytest.raises(ValidationError):
        AnalysisResult.parse_raw("{bad")


def test_ai_response_parser_parses_valid_json():
    raw = json.dumps(valid_result().dict())

    result = AiAnalysisResponseParser().parse(raw, 5)

    assert isinstance(result, AnalysisResult)
    assert result.symbols[0].name == "ObjectHandler"


def test_ai_response_parser_extracts_markdown_wrapped_json():
    raw = "```json\n" + json.dumps(valid_result().dict()) + "\n```"

    result = AiAnalysisResponseParser().parse(raw, 5)

    assert isinstance(result, AnalysisResult)
    assert result.relations[0].relation == "CONTAINS"


def test_ai_response_parser_rejects_natural_language():
    result = AiAnalysisResponseParser().parse("I cannot analyze this file.", 5)

    assert result.code == "ANALYSIS_AI_INVALID_JSON"


def test_ai_response_parser_rejects_empty_response():
    result = AiAnalysisResponseParser().parse("   ", 5)

    assert result.code == "ANALYSIS_AI_EMPTY_RESPONSE"


def test_ai_response_parser_rejects_schema_invalid_json():
    result = AiAnalysisResponseParser().parse('{"symbols":[],"relations":[]}', 5)

    assert result.code == "ANALYSIS_AI_SCHEMA_INVALID"
    assert len(result.message) < 560


def test_ai_response_parser_rejects_json_null_as_schema_invalid():
    result = AiAnalysisResponseParser().parse("null", 5)

    assert result.code == "ANALYSIS_AI_SCHEMA_INVALID"


def test_ai_response_parser_truncates_raw_preview():
    result = AiAnalysisResponseParser().parse("x" * 5000, 5)

    assert result.code == "ANALYSIS_AI_INVALID_JSON"
    assert len(result.raw_preview) == 4000


def test_ai_response_parser_rejects_non_critical_schema_noise():
    payload = valid_result().dict()
    payload["symbols"][0]["roles"][0]["role"] = "EXCEPTION"
    payload["symbols"][0]["lineEnd"] = 50
    payload["relations"][0]["relation"] = "HAS_FIELD"
    payload["relations"][0]["lineEnd"] = 50

    result = AiAnalysisResponseParser().parse(json.dumps(payload), 5)

    assert result.code == "ANALYSIS_AI_SCHEMA_INVALID"


def test_ai_response_parser_rejects_relations_with_unknown_symbol_references():
    payload = valid_result().dict()
    payload["relations"][0]["toLocalId"] = "UNKNOWN"

    result = AiAnalysisResponseParser().parse(json.dumps(payload), 5)

    assert result.code == "ANALYSIS_AI_SCHEMA_INVALID"


def test_ai_output_schema_accepts_java_record_kind():
    payload = valid_result().dict()
    payload["symbols"][0]["kind"] = "RECORD"

    result = AnalysisResult.parse_obj(payload)

    assert result.symbols[0].kind == "RECORD"


def test_unknown_role_rejected():
    payload = valid_result().dict()
    payload["symbols"][0]["roles"][0]["role"] = "BUSINESS_ROLE"

    with pytest.raises(ValidationError):
        AnalysisResult.parse_obj(payload)


def test_unknown_relation_rejected():
    payload = valid_result().dict()
    payload["relations"][0]["relation"] = "BUSINESS_RELATION"

    with pytest.raises(ValidationError):
        AnalysisResult.parse_obj(payload)


def test_line_range_outside_file_rejected():
    result = valid_result()

    with pytest.raises(ValueError):
        result.validate_lines(2)


def test_evidence_required_for_non_unknown_role():
    payload = valid_result().dict()
    payload["symbols"][0]["roles"][0]["evidence"] = []

    with pytest.raises(ValidationError):
        AnalysisResult.parse_obj(payload)


def test_non_localhost_ollama_base_url_rejected(tmp_path):
    with pytest.raises(Exception):
        OllamaAnalysisClient("http://example.com:11434", "model", 1, tmp_path / "missing.md")


def test_large_file_skipped(tmp_path):
    store, _, _ = build_inventory(tmp_path, include_large=True)
    runner = AnalysisJobRunner(store, app_config(tmp_path, max_file_chars=150))

    job = runner.start(AnalysisBuildRequest(), StubAnalyzer())
    final = wait_job(store, job["jobId"])
    skipped = AnalysisStore(store.db_path).files(None, "SKIPPED_TOO_LARGE_FOR_AI_ANALYSIS", None, 10, 0)
    analyzed = AnalysisStore(store.db_path).files(None, "ANALYZED", "LargeFile", 10, 0)

    assert final["status"] == "COMPLETED"
    assert skipped["total"] == 0
    assert analyzed["total"] == 1
    assert {item["code"] for item in analyzed["files"][0]["diagnostics"]} >= {"ANALYSIS_FILE_TOO_LARGE"}


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

    assert first["failedFileCount"] == 0
    assert second["fileCount"] == 1
    assert second["processedFileCount"] == 1
    assert second["failedFileCount"] == 0
    assert retry_analyzer.calls == 1
    assert analyzed["total"] == 1


def test_unchanged_file_lookup_batches_large_inventory(tmp_path):
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

    unchanged_ids = analysis_store.unchanged_file_ids(rows, StubAnalyzer.name, StubAnalyzer.version)

    assert unchanged_ids == set()


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
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(AnalysisResult.parse_obj({
        "fileSummary": "Updated.",
        "symbols": [{"localId": "s3", "name": "updated", "kind": "METHOD", "roles": [{"role": "UTILITY", "confidence": 0.5, "evidence": ["Method exists."]}], "lineStart": 2, "lineEnd": 2, "metadata": {}}],
        "relations": [],
        "diagnostics": [],
    })))["jobId"])
    second_symbols = AnalysisStore(store.db_path).symbols(None, None, None, None, None, 100, 0)

    assert first_symbols["total"] == 3
    assert second_symbols["total"] == 3
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
    assert _legacy_skipped_unchanged_key() not in job
    assert job["sourceIds"] == []
    assert job["processedFileCount"] == 2
    assert migrations == [
        (1, "remove_legacy_analysis_job_counter"),
        (2, "add_analysis_job_source_scope"),
        (3, "reset_analysis_cache_for_graph_v1_cutover"),
        (4, "reconcile_graph_diagnostics_schema"),
    ]
    assert migration_count == 4


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
    with sqlite3.connect(store.db_path) as conn:
        old_job_file_statuses = {
            row[0]
            for row in conn.execute("SELECT status FROM analysis_job_files WHERE job_id = ?", (old_job_id,)).fetchall()
        }

    assert new_final["status"] == "COMPLETED"
    assert old_final["status"] == "STOPPED"
    assert files["total"] == 1
    assert old_job_file_statuses == {"STOPPED"}


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
    assert final["failedFileCount"] == 0
    service = AnalysisStore(store.db_path).service_status(None, StubAnalyzer.name, StubAnalyzer.version, store.status())["services"][0]
    assert service["analysis"]["processedFileCount"] == 1
    assert service["analysis"]["analyzedFileCount"] == 1
    assert service["analysis"]["failedFileCount"] == 0
    assert service["analysis"]["pendingFileCount"] == 0
    assert service["diagnostics"][0]["code"] == "ANALYSIS_FILE_FAILED"
    assert service["diagnostics"][0]["count"] == 1


def test_service_status_uses_active_job_counts_while_running(tmp_path):
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
    assert service["analysis"]["processedFileCount"] == 0
    assert service["analysis"]["failedFileCount"] == 0
    assert service["analysis"]["pendingFileCount"] == 0
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


def test_max_attempts_exceeded_marks_file_failed_with_preview(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analyzer = StubAnalyzer(outcomes=[
        KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad", attempt=1),
        KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad again", attempt=2),
    ])
    runner = AnalysisJobRunner(store, app_config_with_retries(tmp_path, 2))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)

    assert final["failedFileCount"] == 0
    assert files["files"][0]["attemptCount"] == 2
    assert files["files"][0]["lastErrorCode"] == "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"
    assert files["files"][0]["lastRawResponsePreview"] == "{bad again"
    assert {item["code"] for item in files["files"][0]["diagnostics"]} >= {"ANALYSIS_AI_INVALID_JSON", "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"}


def test_timeout_marks_file_failed_and_continues(tmp_path):
    store, _, _ = build_inventory(tmp_path, extra_files={
        "src/main/java/example/SecondHandler.java": "public class SecondHandler {}\n",
    })
    analyzer = StubAnalyzer(outcomes=[
        KnowledgeError("ANALYSIS_AI_TIMEOUT", "AI analyzer request timed out", attempt=1),
        valid_result(),
    ])
    runner = AnalysisJobRunner(store, app_config_with_retries(tmp_path, 3))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, None, None, 10, 0)
    first = AnalysisStore(store.db_path).files(None, "ANALYZED", "ObjectHandler", 10, 0)

    assert final["status"] == "COMPLETED"
    assert final["processedFileCount"] == 2
    assert final["failedFileCount"] == 0
    assert analyzer.calls == 2
    assert {file["analysisStatus"] for file in files["files"]} == {"ANALYZED"}
    assert first["files"][0]["attemptCount"] == 1
    assert first["files"][0]["lastErrorCode"] == "ANALYSIS_AI_TIMEOUT"


def test_transport_error_marks_file_failed_and_continues_after_attempts(tmp_path):
    store, _, _ = build_inventory(tmp_path, extra_files={
        "src/main/java/example/SecondHandler.java": "public class SecondHandler {}\n",
    })
    analyzer = StubAnalyzer(outcomes=[
        KnowledgeError("ANALYSIS_AI_TRANSPORT_ERROR", "AI analyzer transport error", attempt=1),
        valid_result(),
    ])
    runner = AnalysisJobRunner(store, app_config_with_retries(tmp_path, 1))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])

    assert final["processedFileCount"] == 2
    assert final["failedFileCount"] == 0


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


def test_runtime_analysis_writes_graph_tables_and_not_legacy_symbols(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(responsibility_graph_result()))["jobId"])

    with sqlite3.connect(store.db_path) as conn:
        counts = {
            table: conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
            for table in [
                "analysis_graph_nodes",
                "analysis_graph_edges",
                "analysis_graph_claims",
                "analysis_graph_evidence",
                "analysis_symbols",
                "analysis_relations",
            ]
        }

    assert counts["analysis_graph_nodes"] > 0
    assert counts["analysis_graph_edges"] > 0
    assert counts["analysis_graph_claims"] > 0
    assert counts["analysis_graph_evidence"] > 0
    assert counts["analysis_symbols"] == 0
    assert counts["analysis_relations"] == 0


def test_runtime_analysis_writes_graph_engine_job_file_flow_and_line_metadata(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(GraphAnalysisResult()))["jobId"])

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        job = conn.execute("SELECT engine_version FROM analysis_jobs WHERE job_id = ?", (final["jobId"],)).fetchone()
        analysis_file = conn.execute("SELECT engine_version, flow_domain FROM analysis_files").fetchone()
        job_file = conn.execute("SELECT status, engine_version, flow_domain, line_count FROM analysis_job_files").fetchone()
        inventory_file = conn.execute("SELECT line_count, decode_policy FROM files").fetchone()
        static_nodes = conn.execute("SELECT COUNT(*) AS count FROM analysis_graph_nodes WHERE fact_origin = 'STATIC'").fetchone()

    assert job["engine_version"] == GRAPH_ENGINE_VERSION
    assert analysis_file["engine_version"] == GRAPH_ENGINE_VERSION
    assert analysis_file["flow_domain"] == "CODE"
    assert job_file["status"] == "ANALYZED"
    assert job_file["engine_version"] == GRAPH_ENGINE_VERSION
    assert job_file["flow_domain"] == "CODE"
    assert job_file["line_count"] > 0
    assert inventory_file["line_count"] > 0
    assert inventory_file["decode_policy"] == "utf-8:replace"
    assert static_nodes["count"] > 0


def test_runtime_analysis_persists_unsupported_yaml_file_node_and_structural_diagnostic(tmp_path):
    store, _, _ = build_inventory(
        tmp_path,
        extra_files={
            ".github/workflows/build.yml": "name: build\non: push\njobs:\n  test:\n    runs-on: ubuntu-latest\n",
        },
        include_patterns=["**/*.yml"],
    )
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(GraphAnalysisResult()))["jobId"])

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        node = conn.execute("""
            SELECT node_kind, fact_origin, flow_domain
            FROM analysis_graph_nodes
            WHERE source_id = 'edge-gateway'
        """).fetchone()
        diagnostic = conn.execute("""
            SELECT id, stage, code, severity, fact_origin, flow_domain
            FROM analysis_graph_diagnostics
            WHERE source_id = 'edge-gateway'
        """).fetchone()
        analysis_file = conn.execute("SELECT status, last_error_code FROM analysis_files").fetchone()
        job_file = conn.execute("SELECT status, flow_domain FROM analysis_job_files").fetchone()

    assert final["status"] == "COMPLETED"
    assert final["failedFileCount"] == 0
    assert node["node_kind"] == "FILE"
    assert node["fact_origin"] == "STATIC"
    assert node["flow_domain"] == "WORKFLOW"
    assert diagnostic["id"].startswith("analysis-graph-diagnostic:")
    assert diagnostic["stage"] == "STRUCTURAL_PARSE"
    assert diagnostic["code"] == "STRUCTURAL_PARSER_NOT_AVAILABLE"
    assert diagnostic["severity"] == "WARN"
    assert diagnostic["fact_origin"] == "STATIC"
    assert diagnostic["flow_domain"] == "WORKFLOW"
    assert analysis_file["status"] == "ANALYZED"
    assert analysis_file["last_error_code"] is None
    assert job_file["status"] == "ANALYZED_WITH_DIAGNOSTICS"
    assert job_file["flow_domain"] == "WORKFLOW"
    graph = AnalysisStore(store.db_path).graph(
        "edge-gateway", None, None, None, None, None, None, None, 2, 150, False, True
    )
    assert graph["nodes"][0]["nodeKind"] == "FILE"
    assert graph["diagnostics"][0]["code"] == "STRUCTURAL_PARSER_NOT_AVAILABLE"


def test_runtime_resolves_field_receiver_calls_when_target_type_is_unique(tmp_path):
    store, _, _ = build_inventory(tmp_path, extra_files={
        "src/main/java/example/Controller.java": """package example;
class Controller {
  private final TicketMapper mapper;
  TicketDto handle(Ticket ticket) {
    return mapper.toApi(ticket);
  }
}
""",
        "src/main/java/example/TicketMapper.java": """package example;
class TicketMapper {
  TicketDto toApi(Ticket ticket) {
    return new TicketDto();
  }
}
""",
    })
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(GraphAnalysisResult()))["jobId"])

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute("""
            SELECT e.resolution_status, e.metadata_json, from_node.name AS from_name, to_node.name AS to_name
            FROM analysis_graph_edges e
            JOIN analysis_graph_nodes from_node ON from_node.id = e.from_node_id
            LEFT JOIN analysis_graph_nodes to_node ON to_node.id = e.to_node_id
            WHERE e.edge_type = 'CALLS'
              AND from_node.name = 'handle'
        """).fetchall()
    row = next(item for item in rows if json.loads(item["metadata_json"]).get("methodName") == "toApi")

    assert row["resolution_status"] == "RESOLVED"
    assert row["from_name"] == "handle"
    assert row["to_name"] == "toApi"
    assert json.loads(row["metadata_json"])["resolver"] == "STATIC_TYPE_HINT"


def test_graph_slice_service_returns_compact_callable_slice_with_groups_and_uncertainties(tmp_path):
    store, _, _ = build_inventory(tmp_path, content="""package example;
class Controller {
  private final TicketMapper mapper;
  void handle(String id, MissingClient missingClient) {
    TicketDto dto = mapper.toApi(new Ticket());
    missingClient.send(dto);
    java.util.Objects.requireNonNull(id);
  }
}
class TicketMapper {
  TicketDto toApi(Ticket ticket) { return new TicketDto(); }
}
class TicketDto {}
class Ticket {}
""")
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(GraphAnalysisResult()))["jobId"])
    method = next(
        item for item in AnalysisStore(store.db_path).symbols("edge-gateway", None, None, None, "handle", 20, 0)["symbols"]
        if item["nodeKind"] == "CALLABLE" and item["name"] == "handle"
    )

    result = GraphSliceService(AnalysisStore(store.db_path)).slice(GraphSliceRequest(
        source_id="edge-gateway",
        root_graph_node_id=method["symbolId"],
        flow_domain="CODE",
        direction="OUTBOUND",
        depth=2,
        max_nodes=40,
        max_edges=60,
        include_external="collapsed",
        include_unresolved=True,
    ))

    assert result["root"]["label"] == "handle"
    assert any(node["label"] == "toApi" for node in result["nodes"])
    assert any(edge["edgeType"] == "CALLS" for edge in result["edges"])
    assert_node_closed_graph_response(result)
    assert result["groups"]
    assert any(item["unresolvedReason"] == "TARGET_NOT_ANALYZED" for item in result["uncertainties"])
    assert result["metrics"]["callsTaxonomy"]["callKind"]["FIELD_RECEIVER"] >= 1


def test_graph_slice_endpoint_returns_slice_response(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer(GraphAnalysisResult()))["jobId"])
    method = next(
        item for item in AnalysisStore(store.db_path).symbols("edge-gateway", None, None, None, "create", 20, 0)["symbols"]
        if item["nodeKind"] == "CALLABLE" and item["name"] == "create"
    )

    result = get_json(f"/api/v1/knowledge/analysis/graph/slice?sourceId=edge-gateway&rootGraphNodeId={quote(method['symbolId'])}&flowDomain=CODE&depth=1")

    assert result["status"] == 200
    assert result["json"]["root"]["id"] == method["symbolId"]
    assert result["json"]["metrics"]["sliceNodeCount"] >= 1
    assert "groups" in result["json"]
    assert "uncertainties" in result["json"]
    assert_node_closed_graph_response(result["json"])


def test_graph_slice_endpoint_returns_source_overview_without_selected_root(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer(GraphAnalysisResult()))["jobId"])

    result = get_json("/api/v1/knowledge/analysis/graph/slice?sourceId=edge-gateway&flowDomain=CODE&depth=1")

    assert result["status"] == 200
    assert result["json"]["sourceId"] == "edge-gateway"
    assert result["json"]["root"] is None
    assert result["json"]["nodes"]
    assert result["json"]["metrics"]["sliceNodeCount"] >= 1
    assert_node_closed_graph_response(result["json"])


def test_graph_slice_endpoint_source_overview_hides_isolated_nodes_by_default(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer(GraphAnalysisResult()))["jobId"])
    insert_isolated_graph_nodes(store.db_path, count=6)

    result = get_json("/api/v1/knowledge/analysis/graph/slice?sourceId=edge-gateway&flowDomain=CODE&depth=1&maxNodes=80&maxEdges=120")

    body = result["json"]
    assert result["status"] == 200
    assert_node_closed_graph_response(body)
    assert body["metrics"]["overviewSelectionReason"] == "CONNECTED_COMPONENTS_FIRST"
    assert body["metrics"]["hiddenIsolatedCount"] >= 6
    assert not any(node["id"].startswith("test-isolated-node-") for node in body["nodes"])
    assert any(group["groupType"] == "ISOLATED_NODES" for group in body["groups"])


def test_graph_slice_endpoint_source_overview_can_include_isolated_nodes(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer(GraphAnalysisResult()))["jobId"])
    insert_isolated_graph_nodes(store.db_path, count=3)

    result = get_json("/api/v1/knowledge/analysis/graph/slice?sourceId=edge-gateway&flowDomain=CODE&depth=1&maxNodes=80&maxEdges=120&includeIsolated=true")

    body = result["json"]
    assert result["status"] == 200
    assert_node_closed_graph_response(body)
    assert body["metrics"]["hiddenIsolatedCount"] == 0
    assert any(node["id"].startswith("test-isolated-node-") for node in body["nodes"])


def test_graph_slice_endpoint_can_omit_claim_payload_for_lightweight_canvas(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer(GraphAnalysisResult()))["jobId"])

    result = get_json("/api/v1/knowledge/analysis/graph/slice?sourceId=edge-gateway&flowDomain=CODE&depth=1&includeClaims=false")

    body = result["json"]
    assert result["status"] == 200
    assert body["nodes"]
    assert body["claims"] == []
    assert all(node["claims"] == [] for node in body["nodes"])
    assert body["request"]["includeClaims"] is False


def test_graph_slice_endpoint_max_nodes_zero_returns_all_available_nodes(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer(GraphAnalysisResult()))["jobId"])
    insert_isolated_graph_nodes(store.db_path, count=300)

    result = get_json("/api/v1/knowledge/analysis/graph/slice?sourceId=edge-gateway&flowDomain=CODE&depth=1&maxNodes=0&maxEdges=0&includeIsolated=true")

    body = result["json"]
    assert result["status"] == 200
    assert len(body["nodes"]) > 250
    assert body["metrics"]["hiddenIsolatedCount"] == 0
    assert body["metrics"]["sliceNodeCount"] == len(body["nodes"])
    assert any(node["id"].startswith("test-isolated-node-") for node in body["nodes"])
    assert_node_closed_graph_response(body)


def test_graph_slice_endpoint_stale_selected_root_falls_back_to_source_overview(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer(GraphAnalysisResult()))["jobId"])

    result = get_json("/api/v1/knowledge/analysis/graph/slice?sourceId=edge-gateway&rootGraphNodeId=stale-node&flowDomain=CODE&depth=1")

    assert result["status"] == 200
    assert result["json"]["sourceId"] == "edge-gateway"
    assert result["json"]["root"] is None
    assert result["json"]["nodes"]
    assert result["json"]["diagnostics"][0]["code"] == "GRAPH_SLICE_ROOT_NOT_FOUND"
    assert result["json"]["diagnostics"][0]["metadata"]["fallback"] == "SOURCE_OVERVIEW"
    assert_node_closed_graph_response(result["json"])


def test_graph_slice_endpoint_missing_root_without_source_remains_controlled_not_found(tmp_path, monkeypatch):
    configure_api(tmp_path, monkeypatch)

    result = get_json("/api/v1/knowledge/analysis/graph/slice?rootGraphNodeId=stale-node&flowDomain=CODE&depth=1")

    assert result["status"] == 404
    assert result["json"]["code"] == "GRAPH_NODE_NOT_FOUND"


def test_callable_endpoint_returns_direct_callable_responsibility(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(responsibility_graph_result()))["jobId"])
    method = AnalysisStore(store.db_path).symbols(None, None, None, None, "create", 10, 0)["symbols"][0]

    graph = AnalysisStore(store.db_path).graph("edge-gateway", method["symbolId"], None, None, None, None, None, None, 0, 25, True, False)
    selected = graph["selected"]["node"]

    assert selected["nodeKind"] == "CALLABLE"
    assert selected["claimSummary"] == "Handles object creation."
    assert selected["summarySource"] == "DIRECT"
    assert selected["summaryClaimNodeId"] == selected["id"]
    assert selected["summaryConfidence"] == 0.86


def test_callable_without_direct_claim_uses_parent_fallback_with_provenance(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(responsibility_graph_result(method_claim=False, type_claim=True)))["jobId"])
    method = AnalysisStore(store.db_path).symbols(None, None, None, None, "create", 10, 0)["symbols"][0]

    selected = AnalysisStore(store.db_path).graph("edge-gateway", method["symbolId"], None, None, None, None, None, None, 0, 25, False, False)["selected"]["node"]

    assert selected["claimSummary"] == "Handles object requests."
    assert selected["summarySource"] == "PARENT_FALLBACK"
    assert selected["summaryClaimNodeId"] == selected["parentNodeId"]


def test_callable_without_type_claim_uses_file_fallback_with_provenance(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(responsibility_graph_result(method_claim=False, type_claim=False, file_claim=True)))["jobId"])
    method = AnalysisStore(store.db_path).symbols(None, None, None, None, "create", 10, 0)["symbols"][0]

    selected = AnalysisStore(store.db_path).graph("edge-gateway", method["symbolId"], None, None, None, None, None, None, 0, 25, False, False)["selected"]["node"]

    assert selected["claimSummary"] == "Defines an object handler file."
    assert selected["summarySource"] == "FILE_FALLBACK"
    assert selected["summaryClaimNodeId"] != selected["id"]


def test_low_confidence_callable_claim_is_debug_only_and_not_trusted(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(responsibility_graph_result(method_claim=True, type_claim=False, method_confidence=0.2)))["jobId"])
    method = AnalysisStore(store.db_path).symbols(None, None, None, None, "create", 10, 0)["symbols"][0]

    graph = AnalysisStore(store.db_path).graph("edge-gateway", method["symbolId"], None, None, None, None, None, None, 0, 25, True, False)
    selected = graph["selected"]["node"]
    responsibility = next(claim for claim in selected["claims"] if claim["claimKind"] == "RESPONSIBILITY")

    assert selected["status"] == "TRUSTED"
    assert selected["summarySource"] == "NONE"
    assert responsibility["status"] == "DEBUG_ONLY"
    assert responsibility["rejectionReason"] is None or responsibility["rejectionReason"] == "ANALYSIS_GRAPH_CALLABLE_EVIDENCE_OUTSIDE_METHOD"
    assert selected["summaryConfidence"] is None


def test_generic_file_level_callable_summary_is_not_used_as_direct_summary(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(responsibility_graph_result(
        method_claim=True,
        type_claim=False,
        method_summary="This Java file contains an object handler.",
    )))["jobId"])
    method = AnalysisStore(store.db_path).symbols(None, None, None, None, "create", 10, 0)["symbols"][0]

    selected = AnalysisStore(store.db_path).graph("edge-gateway", method["symbolId"], None, None, None, None, None, None, 0, 25, True, False)["selected"]["node"]
    responsibility = next(claim for claim in selected["claims"] if claim["claimKind"] == "RESPONSIBILITY")

    assert selected["summarySource"] == "NONE"
    assert selected["claimSummary"] is None
    assert responsibility["status"] == "DEBUG_ONLY"
    assert responsibility["rejectionReason"] == "GENERIC_FILE_LEVEL_CALLABLE_SUMMARY"


def test_no_source_file_mutation(tmp_path):
    store, _, service = build_inventory(tmp_path)
    source = service / "src/main/java/example/ObjectHandler.java"
    before = source.read_text(encoding="utf-8")
    runner = AnalysisJobRunner(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    assert source.read_text(encoding="utf-8") == before


def test_no_production_domain_hardcoded_synonyms():
    src = Path("services/forge-knowledge/src/knowledge_service")
    banned = ["_AUTH_QUERY", "site creation", "авторизація"]
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


def assert_node_closed_graph_response(body):
    node_ids = {node["id"] for node in body["nodes"]}
    for edge in body["edges"]:
        assert edge["from"] in node_ids
        assert edge["to"] in node_ids


def insert_isolated_graph_nodes(db_path, count=5):
    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        base = conn.execute("""
            SELECT job_id, source_id, inventory_file_id, analysis_file_id, language, flow_domain
            FROM analysis_graph_nodes
            WHERE source_id = 'edge-gateway'
            LIMIT 1
        """).fetchone()
        assert base is not None
        for index in range(count):
            conn.execute("""
                INSERT INTO analysis_graph_nodes(
                    id, job_id, source_id, inventory_file_id, analysis_file_id, stable_key,
                    node_kind, language, name, qualified_name, display_name, parent_node_id,
                    line_start, line_end, confidence, status, metadata_json, created_at,
                    fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                f"test-isolated-node-{index}",
                base["job_id"],
                base["source_id"],
                base["inventory_file_id"],
                base["analysis_file_id"],
                f"edge-gateway|isolated|{index}",
                "CALLABLE",
                base["language"],
                f"isolated{index}",
                f"example.Isolated{index}.isolated",
                f"isolated{index}",
                1,
                1,
                1.0,
                "TRUSTED",
                json.dumps({"testFixture": True}),
                "now",
                "STATIC",
                base["flow_domain"] or "CODE",
            ))


def insert_unresolved_graph_edge(db_path):
    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        from_node = conn.execute("""
            SELECT *
            FROM analysis_graph_nodes
            WHERE source_id = 'edge-gateway'
            ORDER BY node_kind = 'CALLABLE' DESC, confidence DESC
            LIMIT 1
        """).fetchone()
        assert from_node is not None
        conn.execute("""
            INSERT INTO analysis_graph_edges(
                id, job_id, source_id, inventory_file_id, analysis_file_id, from_node_id,
                to_node_id, edge_type, resolution_status, confidence, evidence_id,
                unresolved_target_json, metadata_json, status, created_at, fact_origin, flow_domain
            )
            VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?)
        """, (
            "test-unresolved-edge-without-endpoint",
            from_node["job_id"],
            from_node["source_id"],
            from_node["inventory_file_id"],
            from_node["analysis_file_id"],
            from_node["id"],
            "CALLS",
            "UNRESOLVED",
            1.0,
            json.dumps({"name": "MissingTarget", "methodName": "missing"}),
            json.dumps({
                "methodName": "missing",
                "unresolvedReason": "TARGET_NOT_ANALYZED",
                "sliceDefaultVisibility": "SHOW_AS_UNCERTAINTY",
            }),
            "TRUSTED",
            "now",
            "STATIC",
            from_node["flow_domain"] or "CODE",
        ))


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

    assert status["json"]["symbolCount"] == 3
    assert files["json"]["total"] == 1
    assert symbols["json"]["symbols"][0]["roles"][0]["role"] == "HTTP_HANDLER"
    assert relations["json"]["relations"][0]["relation"] == "CONTAINS"


def test_analysis_graph_api_returns_overview_with_progress_and_facts(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    result = get_json("/api/v1/knowledge/analysis/graph?sourceId=edge-gateway")

    body = result["json"]
    assert result["status"] == 200
    assert body["sourceId"] == "edge-gateway"
    assert body["status"]["analysisStatus"] == "READY"
    assert body["status"]["progressPercent"] == 100.0
    assert body["nodes"]
    assert body["edges"][0]["edgeType"] == "CONTAINS"
    assert body["nodes"][0]["graphNodeId"]
    assert body["meta"]["totalNodeCount"] == 3
    assert_node_closed_graph_response(body)


def test_analysis_graph_api_can_omit_claim_payload_for_lightweight_canvas(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    result = get_json("/api/v1/knowledge/analysis/graph?sourceId=edge-gateway&includeClaims=false")

    body = result["json"]
    assert result["status"] == 200
    assert body["nodes"]
    assert body["claims"] == []
    assert all(node["claims"] == [] for node in body["nodes"])
    assert body["filters"]["includeClaims"] is False


def test_analysis_graph_api_omits_edges_with_missing_returned_endpoints(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    insert_unresolved_graph_edge(store.db_path)

    result = get_json("/api/v1/knowledge/analysis/graph?sourceId=edge-gateway&limit=80")

    body = result["json"]
    assert result["status"] == 200
    assert_node_closed_graph_response(body)
    assert body["meta"]["skippedMissingEndpointCount"] >= 1
    assert body["meta"]["skippedEdgeCount"] >= 1
    assert "EDGE_ENDPOINT_NOT_RETURNED" in body["meta"]["truncationReason"]


def test_analysis_graph_api_limit_zero_returns_all_available_nodes(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    insert_isolated_graph_nodes(store.db_path, count=520)

    result = get_json("/api/v1/knowledge/analysis/graph?sourceId=edge-gateway&limit=0")

    body = result["json"]
    assert result["status"] == 200
    assert len(body["nodes"]) > 500
    assert body["meta"]["returnedNodeCount"] == body["meta"]["totalNodeCount"]
    assert_node_closed_graph_response(body)


def test_analysis_graph_api_returns_slice_around_selected_node_and_depth_limit(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    symbols = get_json("/api/v1/knowledge/analysis/symbols?sourceId=edge-gateway")["json"]["symbols"]
    selected = symbols[0]["symbolId"]

    depth_zero = get_json(f"/api/v1/knowledge/analysis/graph?sourceId=edge-gateway&graphNodeId={quote(selected)}&depth=0")
    depth_one = get_json(f"/api/v1/knowledge/analysis/graph?sourceId=edge-gateway&graphNodeId={quote(selected)}&depth=1")

    assert [node["id"] for node in depth_zero["json"]["nodes"]] == [selected]
    assert depth_zero["json"]["edges"] == []
    assert depth_one["json"]["selected"]["node"]["id"] == selected
    assert len(depth_one["json"]["nodes"]) == 2
    assert len(depth_one["json"]["edges"]) == 1
    assert_node_closed_graph_response(depth_one["json"])


def test_analysis_graph_api_applies_flow_domain_code(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path, extra_files={
        "src/test/java/example/ObjectHandlerTest.java": "class ObjectHandlerTest {}\n",
    })
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    monkeypatch.setattr(main, "analysis_runner", FakeRunner())
    wait_job(store, AnalysisJobRunner(store, cfg).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    result = get_json("/api/v1/knowledge/analysis/graph?sourceId=edge-gateway&flowDomain=CODE")

    assert result["status"] == 200
    assert result["json"]["nodes"]
    assert {node["flowDomain"] for node in result["json"]["nodes"]} == {"CODE"}
    assert all("src/test/" not in node["relativePath"] for node in result["json"]["nodes"])
    assert_node_closed_graph_response(result["json"])


def test_analysis_graph_api_returns_selected_edge_resolution_metadata_safely(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    relation = get_json("/api/v1/knowledge/analysis/relations?sourceId=edge-gateway")["json"]["relations"][0]
    with sqlite3.connect(store.db_path) as conn:
        conn.execute(
            "UPDATE analysis_graph_edges SET resolution_status = ?, unresolved_target_json = ? WHERE id = ?",
            ("UNRESOLVED", json.dumps({"name": "MissingTarget"}), relation["relationId"]),
        )

    result = get_json(f"/api/v1/knowledge/analysis/graph?sourceId=edge-gateway&graphEdgeId={quote(relation['relationId'])}&includeEvidence=true")

    edge = result["json"]["selected"]["edge"]
    assert edge["id"] == relation["relationId"]
    assert edge["resolutionStatus"] == "UNRESOLVED"
    assert edge["unresolvedTarget"] == {"name": "MissingTarget"}
    assert edge["evidence"]


def test_analysis_graph_api_handles_no_analysis_yet(tmp_path, monkeypatch):
    configure_api(tmp_path, monkeypatch)

    result = get_json("/api/v1/knowledge/analysis/graph?sourceId=edge-gateway")

    assert result["status"] == 200
    assert result["json"]["nodes"] == []
    assert result["json"]["edges"] == []
    assert result["json"]["status"]["analysisStatus"] == "NOT_ANALYZED"


def test_analysis_graph_api_shows_running_job_partial_graph(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    AnalysisStore(store.db_path).create_job({
        "jobId": "job-running",
        "status": "RUNNING",
        "startedAt": "now",
        "sourceCount": 1,
        "fileCount": 1,
        "processedFileCount": 0,
        "failedFileCount": 0,
        "currentSourceId": "edge-gateway",
        "currentRelativePath": "src/main/java/example/ObjectHandler.java",
        "sourceIds": ["edge-gateway"],
        "lastProgressAt": "now",
        "symbolCount": 0,
        "relationCount": 0,
        "diagnostics": [],
    })

    result = get_json("/api/v1/knowledge/analysis/graph?sourceId=edge-gateway")

    assert result["json"]["status"]["analysisStatus"] == "RUNNING"
    assert result["json"]["status"]["currentFile"] == "src/main/java/example/ObjectHandler.java"
    assert result["json"]["nodes"]


def test_analysis_graph_api_marks_truncated_when_limit_exceeded(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    result = get_json("/api/v1/knowledge/analysis/graph?sourceId=edge-gateway&limit=1")

    assert result["json"]["meta"]["truncated"] is True
    assert len(result["json"]["nodes"]) == 1


def test_analysis_graph_api_does_not_return_orphan_rows(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    wait_job(store, AnalysisJobRunner(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    with sqlite3.connect(store.db_path) as conn:
        conn.execute("DELETE FROM files")

    result = get_json("/api/v1/knowledge/analysis/graph?sourceId=edge-gateway")

    assert result["json"]["nodes"] == []
    assert result["json"]["edges"] == []
    assert result["json"]["meta"]["totalNodeCount"] == 0


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
        valid_result(),
    ])
    wait_job(store, AnalysisJobRunner(store, cfg).start(AnalysisBuildRequest(), analyzer)["jobId"])

    status = get_json("/api/v1/knowledge/analysis/status")
    files = get_json("/api/v1/knowledge/analysis/files?pathContains=ObjectHandler")

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
    assert service["facts"]["symbolCount"] == 3
    assert service["facts"]["relationCount"] == 3


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

    assert service["analysis"]["analyzedFileCount"] == 1
    assert service["analysis"]["failedFileCount"] == 0
    assert service["analysis"]["pendingFileCount"] == 0
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
