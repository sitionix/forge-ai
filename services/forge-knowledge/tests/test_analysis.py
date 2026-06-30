import hashlib
import asyncio
import json
import os
import sqlite3
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest
from pydantic import ValidationError

os.environ.setdefault("KNOWLEDGE_STORE_PATH", "/tmp/forge-ai-knowledge-test-main.sqlite")

from knowledge_service import main
from knowledge_service.analysis_client import OllamaAnalysisClient
from knowledge_service.analysis_response_parser import AiAnalysisResponseParser
from knowledge_service.analysis_schema import AnalysisBuildRequest, AnalysisResult, RetryFailedAnalysisRequest
from knowledge_service.analysis_service import AnalysisSupervisor
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.config import AppConfig
from knowledge_service.context_schema import ContextRequest
from knowledge_service.context_service import ContextService
from knowledge_service.embedding_provider import FakeDeterministicEmbeddingProvider
from knowledge_service.errors import KnowledgeError
from knowledge_service.freshness_service import KnowledgeFreshnessService
from knowledge_service.graph_analysis import GraphAnalysisEngine
from knowledge_service.graph_schema import GraphAnalysisResult
from knowledge_service.inventory_builder import InventoryBuilder
from knowledge_service.inventory_refresh import AsyncInventoryScheduler, InventoryRefreshService
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.java_parser_adapter import JavaParserAdapter
from knowledge_service.overview_projection import refresh_overview_for_sources
from knowledge_service.semantic_builder import SemanticBuildConfig, SemanticIndexBuilder
from knowledge_service.semantic_index import SemanticIndexStore
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


class SupervisorHarness:
    def __init__(self, store, config):
        self.store = store
        self.config = config
        self.analysis_store = AnalysisStore(store.db_path)
        self.supervisor = AnalysisSupervisor(store, config)
        self._loop = None
        self._thread = None

    def _new_supervisor(self):
        self.supervisor = AnalysisSupervisor(self.store, self.config)
        self.supervisor.analysis_store = self.analysis_store
        return self.supervisor

    def _ensure_background(self):
        if self._loop is not None:
            return
        self._loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._loop.run_forever, name="test-analysis-supervisor", daemon=True)
        self._thread.start()
        self._run_background(self.supervisor.start_lifespan())

    def _run_background(self, coroutine):
        return asyncio.run_coroutine_threadsafe(coroutine, self._loop).result(timeout=5)

    def start(self, request, analyzer=None):
        if getattr(analyzer, "block_event", None) is not None:
            self._ensure_background()
            return self._run_background(self.supervisor.start(request, analyzer))

        async def run():
            supervisor = self._new_supervisor()
            await supervisor.start_lifespan()
            response = await supervisor.start(request, analyzer)
            queue = supervisor._queue
            if queue is not None and getattr(analyzer, "block_event", None) is None:
                await queue.join()
            await supervisor.shutdown()
            return response

        return asyncio.run(run())

    def retry_failed(self, request, analyzer=None):
        async def run():
            supervisor = self._new_supervisor()
            await supervisor.start_lifespan()
            response = await supervisor.retry_failed(request, analyzer)
            queue = supervisor._queue
            if queue is not None:
                await queue.join()
            await supervisor.shutdown()
            return response

        return asyncio.run(run())

    def stop(self, job_id):
        if self._loop is not None:
            return self._run_background(self.supervisor.stop(job_id))

        async def run():
            return await self.supervisor.stop(job_id)

        return asyncio.run(run())

    def shutdown(self):
        if self._loop is not None:
            self._run_background(self.supervisor.shutdown())
            self._loop.call_soon_threadsafe(self._loop.stop)
            self._thread.join(timeout=5)
            self._loop.close()
            self._loop = None
            self._thread = None
            return

        async def run():
            await self.supervisor.shutdown()

        asyncio.run(run())


def valid_result():
    return AnalysisResult.parse_obj(
        {
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
        }
    )


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
        nodes.insert(
            0,
            {
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
            },
        )
        edges.insert(
            0,
            {
                "localId": "file-declares-type",
                "fromNodeLocalId": "file1",
                "toNodeLocalId": "type1",
                "edgeType": "DECLARES",
                "confidence": 0.9,
                "evidence": [{"lineStart": 1, "lineEnd": 5, "text": "class declaration", "metadata": {}}],
                "unresolvedTarget": None,
                "metadata": {},
            },
        )
        claims.append(
            {
                "localId": "file-responsibility",
                "nodeLocalId": "file1",
                "claimKind": "RESPONSIBILITY",
                "summary": "Defines an object handler file.",
                "evidence": [{"lineStart": 1, "lineEnd": 5, "text": "file content", "metadata": {}}],
                "confidence": 0.82,
                "metadata": {},
            }
        )
    if type_claim:
        claims.append(
            {
                "localId": "type-responsibility",
                "nodeLocalId": "type1",
                "claimKind": "RESPONSIBILITY",
                "summary": "Handles object requests.",
                "evidence": [{"lineStart": 1, "lineEnd": 5, "text": "class body", "metadata": {}}],
                "confidence": 0.88,
                "metadata": {},
            }
        )
    if method_claim:
        claims.append(
            {
                "localId": "method-responsibility",
                "nodeLocalId": "method1",
                "claimKind": "RESPONSIBILITY",
                "summary": method_summary,
                "evidence": [{"lineStart": 3, "lineEnd": 4, "text": "method body", "metadata": {}}],
                "confidence": method_confidence,
                "metadata": {},
            }
        )
    return GraphAnalysisResult.parse_obj(
        {
            "nodes": nodes,
            "edges": edges,
            "claims": claims,
            "diagnostics": [],
        }
    )


def shared_evidence_graph_result():
    return GraphAnalysisResult.parse_obj(
        {
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
        }
    )


def materialize_graph_for_test(result, content=None, file_id=1, relative_path="src/main/java/example/EmailVerificationLinkClientImpl.java"):
    content = (
        content
        or "class EmailVerificationLinkClientImpl {\n  WebClient client;\n  String createLink() {\n    helper(); helper();\n  }\n  void helper() {}\n}\n"
    )
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
        """services:
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


def current_graph_nodes(store, source_id="edge-gateway", flow_domain="CODE", page_size=100):
    analysis_store = AnalysisStore(store.db_path)
    manifest = analysis_store.graph_snapshot_manifest(source_id, flow_domain)
    nodes = []
    cursor = None
    while manifest["snapshotId"]:
        page = analysis_store.graph_snapshot_nodes(manifest["graphRevision"], cursor, page_size, source_id, flow_domain)
        nodes.extend(page["items"])
        if page["complete"]:
            break
        cursor = page["nextCursor"]
    return manifest, nodes


def current_graph_edges(store, source_id="edge-gateway", flow_domain="CODE", page_size=100):
    analysis_store = AnalysisStore(store.db_path)
    manifest = analysis_store.graph_snapshot_manifest(source_id, flow_domain)
    edges = []
    cursor = None
    while manifest["snapshotId"]:
        page = analysis_store.graph_snapshot_edges(manifest["graphRevision"], cursor, page_size, source_id, flow_domain)
        edges.extend(page["items"])
        if page["complete"]:
            break
        cursor = page["nextCursor"]
    return manifest, edges


def current_graph_node_detail_by_name(store, name, source_id="edge-gateway", flow_domain="CODE", include_evidence=False):
    manifest, nodes = current_graph_nodes(store, source_id, flow_domain)
    node = next(item for item in nodes if item["name"] == name)
    return AnalysisStore(store.db_path).graph_snapshot_node_detail(manifest["graphRevision"], node["id"], source_id, include_evidence)["item"]


def build_inventory_with_file_count(tmp_path, file_count):
    extra_files = {f"src/main/java/example/Generated{index}.java": f"public class Generated{index} {{}}\n" for index in range(1, file_count)}
    return build_inventory(tmp_path, extra_files=extra_files)


def seed_analysis_file_statuses(db_path, statuses):
    analysis_store = AnalysisStore(db_path)
    analysis_store.init()
    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        files = conn.execute("SELECT id, source_id, relative_path, content_hash FROM files ORDER BY id LIMIT ?", (len(statuses),)).fetchall()
        assert len(files) == len(statuses)
        for file_row, status in zip(files, statuses):
            analysis_store._upsert_file(
                conn,
                file_row["id"],
                {
                    "source_id": file_row["source_id"],
                    "relative_path": file_row["relative_path"],
                    "content_hash": file_row["content_hash"],
                    "analyzer_name": StubAnalyzer.name,
                    "analyzer_version": StubAnalyzer.version,
                    "engine_version": GRAPH_ENGINE_VERSION,
                    "flow_domain": "CODE",
                    "status": status,
                    "analyzed_at": "now",
                    "symbol_count": 0,
                    "relation_count": 0,
                    "diagnostics": [],
                    "last_error_code": "ANALYSIS_FILE_FAILED" if status == "FAILED" else None,
                    "last_error_message": "failed" if status == "FAILED" else None,
                },
            )
        refresh_overview_for_sources(conn, sorted({row["source_id"] for row in files}))


def test_retry_failed_noops_when_no_current_failed_files(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    seed_analysis_file_statuses(store.db_path, ["ANALYZED"])
    runner = SupervisorHarness(store, app_config(tmp_path))

    response = runner.retry_failed(RetryFailedAnalysisRequest(sourceIds=["edge-gateway"]), StubAnalyzer())

    assert response["result"] == "NO_FAILED_FILES"
    assert response["selectedFileCount"] == 0
    assert response["selection"] == "FAILED_ONLY"
    assert AnalysisStore(store.db_path).active_job() is None


def test_retry_failed_selects_only_current_failed_files_and_preserves_history(tmp_path):
    store, _, _ = build_inventory(
        tmp_path,
        extra_files={
            "src/main/java/example/AlreadyGood.java": "public class AlreadyGood {}\n",
        },
    )
    seed_analysis_file_statuses(store.db_path, ["FAILED", "ANALYZED"])
    analysis_store = AnalysisStore(store.db_path)
    failed_row = analysis_store.current_failed_inventory_rows(["edge-gateway"])[0]
    analysis_store.create_job(
        {
            "jobId": "old-job",
            "mode": "FULL",
            "status": "COMPLETED",
            "startedAt": "old",
            "completedAt": "old",
            "sourceCount": 1,
            "fileCount": 1,
            "processedFileCount": 1,
            "failedFileCount": 1,
            "sourceIds": ["edge-gateway"],
        }
    )
    analysis_store.create_job_files("old-job", [failed_row], {int(failed_row["id"]): "CODE"}, GRAPH_ENGINE_VERSION)
    analysis_store.update_job_file("old-job", int(failed_row["id"]), "FAILED", diagnostics=[{"code": "ANALYSIS_FILE_FAILED"}], completed=True)
    runner = SupervisorHarness(store, app_config(tmp_path))

    response = runner.retry_failed(RetryFailedAnalysisRequest(sourceIds=["edge-gateway"]), StubAnalyzer())
    accepted_state = response["analysisState"]
    final = wait_job(store, response["jobId"])

    assert response["selection"] == "FAILED_ONLY"
    assert response["selectedFileCount"] == 1
    assert response["failureCodeBreakdown"] == {"ANALYSIS_FILE_FAILED": 1}
    assert accepted_state["failedFiles"] == 0
    assert accepted_state["pendingFiles"] == 1
    assert accepted_state["completedFiles"] == 1
    assert final["mode"] == "FAILED_ONLY"
    assert final["fileCount"] == 1
    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        attempts = conn.execute(
            "SELECT job_id, status FROM analysis_job_files WHERE inventory_file_id = ? ORDER BY job_id",
            (failed_row["id"],),
        ).fetchall()
        current_failed = conn.execute("SELECT COUNT(*) FROM analysis_files WHERE status = 'FAILED'").fetchone()[0]
    assert {row["job_id"]: row["status"] for row in attempts}["old-job"] == "FAILED"
    assert {row["job_id"]: row["status"] for row in attempts}[response["jobId"]] in {"ANALYZED", "ANALYZED_WITH_DIAGNOSTICS"}
    assert current_failed == 0


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
        assert columns["snapshot_id"]["pk"] == 1
        assert columns["id"]["pk"] == 2
        conn.execute("""
            INSERT INTO analysis_graph_diagnostics(
                id, snapshot_id, job_id, source_id, severity, stage, code, message, metadata_json, created_at
            )
            VALUES ('diagnostic:text-id', 'legacy:edge-gateway', 'job-1', 'edge-gateway', 'WARN', 'STRUCTURAL_PARSE',
                    'STRUCTURAL_PARSER_NOT_AVAILABLE', 'No parser.', '{}', 'now')
        """)
        assert conn.execute("SELECT COUNT(*) FROM sources").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM files").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_diagnostics").fetchone()[0] == 2
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_diagnostics WHERE code = 'OLD_DIAGNOSTIC'").fetchone()[0] == 1
        assert conn.execute("SELECT 1 FROM analysis_schema_migrations WHERE version = 4").fetchone()

    AnalysisStore(db_path).init()

    with sqlite3.connect(db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_diagnostics WHERE id = 'diagnostic:text-id'").fetchone()[0] == 1


def test_graph_persistence_failure_is_reported_as_graph_store_error(tmp_path):
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    bad_graph = {
        "nodes": [
            {
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
            }
        ],
        "edges": [],
        "claims": [],
        "evidence": [],
        "diagnostics": [],
    }

    with pytest.raises(KnowledgeError) as raised:
        store.replace_file_graph_analysis(
            1,
            {
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
            },
            bad_graph,
        )

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


def test_analysis_store_uses_wal_and_configurable_busy_timeout(tmp_path):
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()

    with sqlite3.connect(store.db_path) as conn:
        assert conn.execute("PRAGMA journal_mode").fetchone()[0].lower() == "wal"

    with store._connect(busy_timeout_ms=123) as conn:
        assert conn.execute("PRAGMA busy_timeout").fetchone()[0] == 123


def test_mark_file_retries_transient_sqlite_lock(monkeypatch, tmp_path):
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    original_delete = store._delete_file_analysis
    calls = {"count": 0}

    def flaky_delete(conn, file_id):
        calls["count"] += 1
        if calls["count"] == 1:
            raise sqlite3.OperationalError("database is locked")
        return original_delete(conn, file_id)

    monkeypatch.setattr("knowledge_service.analysis_store.GRAPH_STORE_LOCK_RETRY_DELAYS_SECONDS", (0,))
    monkeypatch.setattr(store, "_delete_file_analysis", flaky_delete)

    store.mark_file(
        42,
        {
            "source_id": "svc",
            "relative_path": "src/main/java/example/ObjectHandler.java",
            "content_hash": "hash",
            "analyzer_name": "ai-file-analyzer",
            "analyzer_version": "1",
            "engine_version": GRAPH_ENGINE_VERSION,
            "status": "FAILED",
            "symbol_count": 0,
            "relation_count": 0,
            "diagnostics": [{"code": "ANALYSIS_FILE_FAILED", "message": "failed"}],
        },
    )

    assert calls["count"] == 2
    with sqlite3.connect(store.db_path) as conn:
        row = conn.execute("SELECT status, last_error_code FROM analysis_files WHERE file_id = 42").fetchone()
    assert row == ("FAILED", None)


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


def test_graph_snapshots_publish_atomically_and_keep_previous_snapshot_immutable(tmp_path):
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    content = "class EmailVerificationLinkClientImpl { void createLink() { helper(); } void helper() {} }\n"
    base_graph = materialize_graph_for_test(shared_evidence_graph_result(), content=content)
    state = graph_state_for_test(content)

    first = json.loads(json.dumps(base_graph))
    for rows in first.values():
        for row in rows:
            row["job_id"] = "job-1"
    store.create_job(
        {
            "jobId": "job-1",
            "status": "RUNNING",
            "startedAt": "now",
            "completedAt": None,
            "sourceCount": 1,
            "fileCount": 1,
            "processedFileCount": 0,
            "failedFileCount": 0,
            "sourceIds": ["edge-gateway"],
            "symbolCount": 0,
            "relationCount": 0,
            "diagnostics": [],
            "engineVersion": GRAPH_ENGINE_VERSION,
            "mode": "FULL",
        }
    )
    store.replace_file_graph_analysis(1, state, first)
    assert store.graph_snapshot_manifest("edge-gateway", "CODE")["totalNodeCount"] == 0
    store.update_job("job-1", {"status": "COMPLETED", "completedAt": "done"})
    first_manifest = store.graph_snapshot_manifest("edge-gateway", "CODE")
    assert first_manifest["snapshotId"] == "job-1:edge-gateway"
    assert first_manifest["totalNodeCount"] == len(first["nodes"])

    failed = json.loads(json.dumps(base_graph))
    for rows in failed.values():
        for row in rows:
            row["job_id"] = "job-2"
    store.create_job(
        {
            "jobId": "job-2",
            "status": "RUNNING",
            "startedAt": "now",
            "completedAt": None,
            "sourceCount": 1,
            "fileCount": 1,
            "processedFileCount": 0,
            "failedFileCount": 0,
            "sourceIds": ["edge-gateway"],
            "symbolCount": 0,
            "relationCount": 0,
            "diagnostics": [],
            "engineVersion": GRAPH_ENGINE_VERSION,
            "mode": "FULL",
        }
    )
    store.replace_file_graph_analysis(1, state, failed)
    store.update_job("job-2", {"status": "FAILED", "completedAt": "failed"})
    assert store.graph_snapshot_manifest("edge-gateway", "CODE")["snapshotId"] == first_manifest["snapshotId"]
    with sqlite3.connect(store.db_path) as conn:
        assert conn.execute("SELECT state FROM graph_snapshots WHERE snapshot_id = 'job-2:edge-gateway'").fetchone()[0] == "FAILED"

    third = json.loads(json.dumps(base_graph))
    third["nodes"] = third["nodes"][:-1]
    for rows in third.values():
        for row in rows:
            row["job_id"] = "job-3"
    store.create_job(
        {
            "jobId": "job-3",
            "status": "RUNNING",
            "startedAt": "now",
            "completedAt": None,
            "sourceCount": 1,
            "fileCount": 1,
            "processedFileCount": 0,
            "failedFileCount": 0,
            "sourceIds": ["edge-gateway"],
            "symbolCount": 0,
            "relationCount": 0,
            "diagnostics": [],
            "engineVersion": GRAPH_ENGINE_VERSION,
            "mode": "FULL",
        }
    )
    with pytest.raises(KnowledgeError):
        store.replace_file_graph_analysis(1, state, third)
    third_manifest = store.graph_snapshot_manifest("edge-gateway", "CODE")
    assert third_manifest["snapshotId"] == first_manifest["snapshotId"]
    assert third_manifest["totalNodeCount"] == len(first["nodes"])
    with sqlite3.connect(store.db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_nodes WHERE snapshot_id = 'job-1:edge-gateway'").fetchone()[0] == len(first["nodes"])
        assert conn.execute("SELECT state FROM graph_snapshots WHERE snapshot_id = 'job-2:edge-gateway'").fetchone()[0] == "FAILED"


def test_graph_snapshot_migration_rebuilds_old_primary_key_tables(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            """
            CREATE TABLE analysis_graph_nodes (
                id TEXT PRIMARY KEY,
                job_id TEXT NOT NULL,
                source_id TEXT NOT NULL,
                inventory_file_id INTEGER,
                analysis_file_id INTEGER,
                stable_key TEXT NOT NULL,
                node_kind TEXT NOT NULL,
                language TEXT,
                name TEXT NOT NULL,
                qualified_name TEXT,
                display_name TEXT,
                parent_node_id TEXT,
                line_start INTEGER,
                line_end INTEGER,
                confidence REAL NOT NULL,
                status TEXT NOT NULL,
                metadata_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                fact_origin TEXT,
                flow_domain TEXT
            )
            """
        )
        conn.execute(
            """
            INSERT INTO analysis_graph_nodes(
                id, job_id, source_id, stable_key, node_kind, name, confidence, status, metadata_json, created_at, fact_origin, flow_domain
            )
            VALUES ('shared-node', 'legacy-job', 'edge-gateway', 'legacy:node', 'TYPE', 'LegacyNode', 0.8, 'TRUSTED', '{}', 'now', 'STATIC', 'CODE')
            """
        )

    store = AnalysisStore(db_path)
    store.init()

    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        pk = {row["name"]: row["pk"] for row in conn.execute("PRAGMA table_info(analysis_graph_nodes)").fetchall() if row["pk"]}
        assert pk == {"snapshot_id": 1, "id": 2}
        assert (
            conn.execute("SELECT snapshot_id FROM graph_current_snapshots WHERE source_id = 'edge-gateway'").fetchone()["snapshot_id"] == "legacy:edge-gateway"
        )
        conn.execute("PRAGMA foreign_keys = ON")
        conn.execute(
            """
            INSERT INTO graph_snapshots(snapshot_id, source_id, job_id, state, created_at, published_at, manifest_json)
            VALUES ('job-new:edge-gateway', 'edge-gateway', 'job-new', 'BUILDING', 'now', NULL, '{}')
            """
        )
        conn.execute(
            """
            INSERT INTO analysis_graph_nodes(
                id, snapshot_id, job_id, source_id, stable_key, node_kind, name, confidence, status, metadata_json, created_at, fact_origin, flow_domain
            )
            VALUES ('shared-node', 'job-new:edge-gateway', 'job-new', 'edge-gateway', 'new:node', 'TYPE', 'NewNode', 0.9, 'TRUSTED', '{}', 'now', 'STATIC', 'CODE')
            """
        )
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_nodes WHERE id = 'shared-node'").fetchone()[0] == 2


def test_graph_store_retries_transient_sqlite_lock(monkeypatch, tmp_path):
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    content = "class EmailVerificationLinkClientImpl { void createLink() { helper(); } void helper() {} }\n"
    graph = materialize_graph_for_test(shared_evidence_graph_result(), content=content)
    state = graph_state_for_test(content)
    original = store._replace_file_graph_analysis_once
    calls = {"count": 0}

    def flaky_replace(*args, **kwargs):
        calls["count"] += 1
        if calls["count"] == 1:
            raise KnowledgeError(
                "ANALYSIS_GRAPH_STORE_FAILED",
                "Graph persistence failed while writing analysis_graph_nodes.",
                stage="GRAPH_STORE",
                severity="ERROR",
                table="analysis_graph_nodes",
                operation="insert_nodes",
                exceptionType="OperationalError",
                sqliteMessage="database is locked",
            )
        return original(*args, **kwargs)

    monkeypatch.setattr("knowledge_service.analysis_store.GRAPH_STORE_LOCK_RETRY_DELAYS_SECONDS", (0,))
    monkeypatch.setattr(store, "_replace_file_graph_analysis_once", flaky_replace)

    store.replace_file_graph_analysis(1, state, graph)

    assert calls["count"] == 2
    with sqlite3.connect(store.db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_nodes").fetchone()[0] == len(graph["nodes"])


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
    assert (
        "UNIQUE constraint failed: analysis_graph_evidence.source_id, analysis_graph_evidence.snapshot_id, analysis_graph_evidence.id"
        in raised.value.details["sqliteMessage"]
    )
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
        """services:
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
    runner = SupervisorHarness(store, app_config(tmp_path, max_file_chars=150))

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
    runner = SupervisorHarness(store, app_config(tmp_path))
    analyzer = StubAnalyzer()
    wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    second = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])

    assert second["fileCount"] == 0
    assert second["processedFiles"] == 0
    assert _legacy_skipped_unchanged_key() not in second
    assert analyzer.calls == 1


def test_static_fallback_analysis_with_unchanged_hash_is_not_retried_without_force(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    first = wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(fail=True))["jobId"])
    retry_analyzer = StubAnalyzer()

    second = wait_job(store, runner.start(AnalysisBuildRequest(), retry_analyzer)["jobId"])
    analyzed = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)

    assert first["failedFiles"] == 0
    assert second["fileCount"] == 0
    assert second["processedFiles"] == 0
    assert second["failedFiles"] == 0
    assert retry_analyzer.calls == 0
    assert analyzed["total"] == 1


def test_unchanged_file_lookup_batches_large_inventory(tmp_path):
    extra_files = {
        f"src/main/java/example/Generated{i:03d}Handler.java": "public class GeneratedHandler {\n  public void create() {\n  }\n\n}\n" for i in range(405)
    }
    store, _, _ = build_inventory(tmp_path, extra_files=extra_files)
    rows, _ = store.search_rows([], [])
    analysis_store = AnalysisStore(store.db_path)
    for row in rows:
        analysis_store.mark_file(
            row["id"],
            {
                "source_id": row["source_id"],
                "relative_path": row["relative_path"],
                "content_hash": row["content_hash"],
                "analyzer_name": StubAnalyzer.name,
                "analyzer_version": StubAnalyzer.version,
                "engine_version": GRAPH_ENGINE_VERSION,
                "status": "ANALYZED",
                "symbol_count": 0,
                "relation_count": 0,
                "diagnostics": [],
            },
        )

    unchanged_ids = analysis_store.unchanged_file_ids(rows, StubAnalyzer.name, StubAnalyzer.version, GRAPH_ENGINE_VERSION)

    assert unchanged_ids == {row["id"] for row in rows}


def test_analysis_max_files_uses_stable_inventory_order(tmp_path):
    store, _, _ = build_inventory(
        tmp_path,
        extra_files={
            "src/main/java/example/AaaHandler.java": "public class AaaHandler {\n  public void create() {\n  }\n\n}\n",
        },
    )
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(maxFiles=1, force=True), StubAnalyzer())["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)

    assert files["total"] == 1
    assert files["files"][0]["relativePath"] == "src/main/java/example/AaaHandler.java"


def test_analysis_max_files_applies_after_current_files_are_filtered(tmp_path):
    store, _, _ = build_inventory(
        tmp_path,
        extra_files={
            "src/main/java/example/AaaHandler.java": "public class AaaHandler {\n  public void create() {\n  }\n\n}\n",
        },
    )
    runner = SupervisorHarness(store, app_config(tmp_path))
    analyzer = StubAnalyzer()

    first = wait_job(store, runner.start(AnalysisBuildRequest(maxFiles=1), analyzer)["jobId"])
    second = wait_job(store, runner.start(AnalysisBuildRequest(maxFiles=1), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)

    assert first["fileCount"] == 1
    assert second["fileCount"] == 1
    assert second["processedFiles"] == 1
    assert _legacy_skipped_unchanged_key() not in second
    assert analyzer.calls == 2
    assert files["total"] == 2
    assert [item["relativePath"] for item in files["files"]] == [
        "src/main/java/example/AaaHandler.java",
        "src/main/java/example/ObjectHandler.java",
    ]


def test_per_file_guard_skips_current_file_if_candidate_filter_misses_it(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    first_analyzer = StubAnalyzer()
    wait_job(store, runner.start(AnalysisBuildRequest(), first_analyzer)["jobId"])
    second_analyzer = StubAnalyzer()
    runner.analysis_store.unchanged_file_ids = lambda rows, analyzer_name, analyzer_version, engine_version=None: set()

    second = wait_job(store, runner.start(AnalysisBuildRequest(), second_analyzer)["jobId"])

    assert second["fileCount"] == 1
    assert second["processedFiles"] == 1
    assert first_analyzer.calls == 1
    assert second_analyzer.calls == 0
    with sqlite3.connect(store.db_path) as conn:
        status = conn.execute(
            "SELECT status FROM analysis_job_files WHERE job_id = ?",
            (second["jobId"],),
        ).fetchone()[0]
    assert status == "SKIPPED_UNCHANGED"


def test_changed_file_reanalyzed_and_previous_analysis_removed(tmp_path):
    store, config, service = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    _, first_nodes = current_graph_nodes(store)
    (service / "src/main/java/example/ObjectHandler.java").write_text("public class ObjectHandler {\n  public void updated() {}\n}\n", encoding="utf-8")
    InventoryBuilder(load_source_config(config), store).build([], [])
    wait_job(
        store,
        runner.start(
            AnalysisBuildRequest(),
            StubAnalyzer(
                AnalysisResult.parse_obj(
                    {
                        "fileSummary": "Updated.",
                        "symbols": [
                            {
                                "localId": "s3",
                                "name": "updated",
                                "kind": "METHOD",
                                "roles": [{"role": "UTILITY", "confidence": 0.5, "evidence": ["Method exists."]}],
                                "lineStart": 2,
                                "lineEnd": 2,
                                "metadata": {},
                            }
                        ],
                        "relations": [],
                        "diagnostics": [],
                    }
                )
            ),
        )["jobId"],
    )
    _, second_nodes = current_graph_nodes(store)

    assert len(first_nodes) == 3
    assert len(second_nodes) == 3
    assert "updated" in {node["name"] for node in second_nodes}
    assert "create" not in {node["name"] for node in second_nodes}


def test_freshness_up_to_date_after_completed_scan_with_unchanged_files(tmp_path):
    store, config, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    freshness = KnowledgeFreshnessService(load_source_config(config), store).check()

    assert AnalysisStore(store.db_path).status()["scannedFileCount"] == 1
    assert freshness["status"] == "UP_TO_DATE"
    assert freshness["newFiles"] == 0
    assert freshness["modifiedFiles"] == 0
    assert freshness["deletedFiles"] == 0


def test_freshness_outdated_when_scanned_file_modified(tmp_path):
    store, config, service = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service / "src/main/java/example/ObjectHandler.java").write_text("public class ObjectHandler { void updated() {} }\n", encoding="utf-8")

    freshness = KnowledgeFreshnessService(load_source_config(config), store).check()

    assert freshness["status"] == "OUTDATED"
    assert freshness["modifiedFiles"] == 1
    assert freshness["affectedScannedFiles"] == 1


def test_freshness_outdated_when_scanned_file_deleted(tmp_path):
    store, config, service = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service / "src/main/java/example/ObjectHandler.java").unlink()

    freshness = KnowledgeFreshnessService(load_source_config(config), store).check()

    assert freshness["status"] == "OUTDATED"
    assert freshness["deletedFiles"] == 1
    assert freshness["affectedScannedFiles"] == 1


def test_freshness_outdated_when_new_eligible_file_added(tmp_path):
    store, config, service = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service / "src/main/java/example/SecondHandler.java").write_text("public class SecondHandler {}\n", encoding="utf-8")

    freshness = KnowledgeFreshnessService(load_source_config(config), store).check()

    assert freshness["status"] == "OUTDATED"
    assert freshness["newFiles"] == 1
    assert freshness["affectedScannedFiles"] == 0


def test_analyze_refreshes_inventory_and_restores_freshness(tmp_path):
    store, config, service = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
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
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service / "src/main/java/example/ObjectHandler.java").unlink()

    result = InventoryRefreshService(app_config(tmp_path), store).build([], [])
    files = AnalysisStore(store.db_path).files(None, None, None, 10, 0)
    graph_manifest = AnalysisStore(store.db_path).graph_snapshot_manifest("edge-gateway", "CODE")

    assert result["fileCount"] == 0
    assert files["total"] == 0
    assert graph_manifest["totalNodeCount"] == 3
    assert graph_manifest["totalEdgeCount"] == 3


def test_inventory_refresh_makes_new_files_available_for_next_analysis(tmp_path):
    store, _, service = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
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
    assert final["processedFiles"] == 1
    assert analyzer.calls == 1
    assert files["total"] == 2


def test_inventory_refresh_blocked_while_analysis_running(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    AnalysisStore(store.db_path).create_job(
        {
            "jobId": "job-running",
            "status": "RUNNING",
            "startedAt": "now",
            "sourceCount": 1,
            "fileCount": 1,
            "processedFileCount": 0,
            "failedFileCount": 0,
        }
    )

    with pytest.raises(KnowledgeError) as exc:
        InventoryRefreshService(app_config(tmp_path), store).build([], [])

    assert exc.value.code == "INVENTORY_BUILD_BLOCKED_BY_ANALYSIS"


def test_background_inventory_scheduler_skips_while_analysis_running(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    AnalysisStore(store.db_path).create_job(
        {
            "jobId": "job-running",
            "status": "RUNNING",
            "startedAt": "now",
            "sourceCount": 1,
            "fileCount": 1,
            "processedFileCount": 0,
            "failedFileCount": 0,
        }
    )
    refresh = InventoryRefreshService(app_config(tmp_path), store)
    scheduler = AsyncInventoryScheduler(refresh, app_config(tmp_path))

    state = asyncio.run(scheduler.run_once())

    assert state["status"] == "SKIPPED"
    assert state["lastErrorCode"] == "INVENTORY_BUILD_BLOCKED_BY_ANALYSIS"
    assert state["skipCount"] == 1


def test_background_inventory_refresh_skips_only_running_source(tmp_path):
    store, _, first, second = build_two_service_inventory(tmp_path)
    AnalysisStore(store.db_path).create_job(
        {
            "jobId": "job-running",
            "status": "RUNNING",
            "startedAt": "now",
            "sourceCount": 1,
            "fileCount": 1,
            "processedFileCount": 0,
            "failedFileCount": 0,
            "currentSourceId": "first-service",
            "sourceIds": ["first-service"],
        }
    )
    (first / "src" / "First.java").unlink()
    (second / "src" / "Second.java").unlink()
    (second / "src" / "SecondNew.java").write_text("class SecondNew {}\n", encoding="utf-8")
    scheduler = AsyncInventoryScheduler(InventoryRefreshService(app_config(tmp_path), store), app_config(tmp_path))

    state = asyncio.run(scheduler.run_once())
    first_files = store.files("first-service", None, None, 10, 0)
    second_files = store.files("second-service", None, None, 10, 0)

    assert state["status"] == "READY"
    assert state["runCount"] == 1
    assert [item["relativePath"] for item in first_files["files"]] == ["src/First.java"]
    assert [item["relativePath"] for item in second_files["files"]] == ["src/SecondNew.java"]


def test_background_job_returns_id_and_updates_progress(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))

    response = runner.start(AnalysisBuildRequest(), StubAnalyzer())
    final = wait_job(store, response["jobId"])

    assert response["status"] == "QUEUED"
    assert final["processedFiles"] == 1


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
    assert "mode" in columns
    assert _legacy_skipped_unchanged_key() not in job
    assert job["sourceIds"] == []
    assert job["processedFiles"] == 2
    assert migrations == [
        (1, "remove_legacy_analysis_job_counter"),
        (2, "add_analysis_job_source_scope"),
        (3, "reset_analysis_cache_for_graph_v1_cutover"),
        (4, "reconcile_graph_diagnostics_schema"),
        (5, "add_analysis_job_mode"),
        (6, "add_immutable_graph_snapshots"),
    ]
    assert migration_count == 6


def test_stop_analysis_releases_active_slot_and_prevents_old_file_write(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analysis_store = AnalysisStore(store.db_path)
    analysis_store.create_job(
        {
            "jobId": "old-job",
            "status": "RUNNING",
            "startedAt": "now",
            "sourceCount": 1,
            "fileCount": 1,
            "processedFileCount": 0,
            "failedFileCount": 0,
            "sourceIds": ["edge-gateway"],
        }
    )
    stop = analysis_store.request_stop("old-job")
    analysis_store.stop_incomplete_job_files("old-job")
    analysis_store.update_job("old-job", {"status": "STOPPED", "completedAt": "now"})
    assert stop["status"] == "STOP_REQUESTED"
    assert analysis_store.active_job() is None

    runner = SupervisorHarness(store, app_config(tmp_path))
    new_job_id = runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"]
    new_final = wait_job(store, new_job_id)
    old_final = analysis_store.job("old-job")
    files = analysis_store.files(None, "ANALYZED", None, 10, 0)

    assert new_final["status"] == "COMPLETED"
    assert old_final["status"] == "STOPPED"
    assert files["total"] == 1


def _legacy_skipped_unchanged_key():
    return "skipped" + "UnchangedFileCount"


def test_one_active_job_rule_enforced(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    AnalysisStore(store.db_path).create_job(
        {
            "jobId": "job-running",
            "status": "RUNNING",
            "startedAt": "now",
            "sourceCount": 1,
            "fileCount": 1,
            "processedFileCount": 0,
            "failedFileCount": 0,
            "sourceIds": ["edge-gateway"],
        }
    )
    runner = SupervisorHarness(store, app_config(tmp_path))

    response = runner.start(AnalysisBuildRequest(), StubAnalyzer())
    recovered = AnalysisStore(store.db_path).job("job-running")
    final = wait_job(store, response["jobId"])

    assert recovered["status"] == "FAILED"
    assert final["status"] == "COMPLETED"


def test_failed_ai_file_does_not_crash_whole_service(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(fail=True))["jobId"])

    assert final["status"] == "COMPLETED"
    assert final["processedFiles"] == 1
    assert final["failedFiles"] == 0
    service = AnalysisStore(store.db_path).service_status(None)["sources"][0]
    assert service["analysis"]["processedFiles"] == 1
    assert service["analysis"]["succeededFiles"] == 1
    assert service["analysis"]["failedFiles"] == 0
    assert service["analysis"]["pendingFiles"] == 0
    assert "diagnostics" not in service
    assert "diagnosticsSummary" not in service
    assert "message" not in json.dumps(service)


def test_service_status_uses_max_active_progress_without_double_counting(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    analysis_store = AnalysisStore(store.db_path)
    analysis_store.create_job(
        {
            "jobId": "job-running",
            "status": "RUNNING",
            "startedAt": "now",
            "sourceCount": 1,
            "fileCount": 1,
            "processedFileCount": 1,
            "failedFileCount": 0,
            "currentSourceId": "edge-gateway",
            "currentRelativePath": "src/main/java/example/ObjectHandler.java",
        }
    )

    service = analysis_store.service_status(None)["sources"][0]

    assert service["analysis"]["status"] == "RUNNING"
    assert service["analysis"]["processedFiles"] == 1
    assert service["analysis"]["failedFiles"] == 0
    assert service["analysis"]["pendingFiles"] == 0
    assert "currentRelativePath" not in service["analysis"]


def test_service_status_active_job_shape_is_minimal(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analysis_store = AnalysisStore(store.db_path)
    analysis_store.create_job(
        {
            "jobId": "job-running",
            "status": "RUNNING",
            "startedAt": "2026-01-01T00:00:00+00:00",
            "sourceCount": 1,
            "fileCount": 1,
            "processedFileCount": 0,
            "failedFileCount": 0,
            "currentSourceId": "edge-gateway",
            "currentRelativePath": "src/main/java/example/ObjectHandler.java",
            "lastProgressAt": "2026-01-01T00:00:00+00:00",
        }
    )

    status = analysis_store.service_status(None)
    service = status["sources"][0]

    assert status["activeJob"] == {
        "jobId": "job-running",
        "sourceId": "edge-gateway",
        "status": "RUNNING",
        "mode": "FULL",
        "selectedFileCount": 1,
        "processedFileCount": 0,
        "failedFileCount": 0,
        "currentRelativePath": "src/main/java/example/ObjectHandler.java",
    }
    assert service["analysis"]["status"] == "RUNNING"
    assert service["analysis"]["totalFiles"] == 1
    assert service["analysis"]["processedFiles"] == 0
    assert set(status["activeJob"]) == {
        "jobId",
        "sourceId",
        "status",
        "mode",
        "selectedFileCount",
        "processedFileCount",
        "failedFileCount",
        "currentRelativePath",
    }
    assert "stalled" not in service["analysis"]


def test_bad_ai_json_is_retried_before_file_fails(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analyzer = StubAnalyzer(bad_response_attempts=1)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 2))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)

    assert final["status"] == "COMPLETED"
    assert final["failedFiles"] == 0
    assert analyzer.calls == 2
    assert analyzer.repair_prompts
    assert files["total"] == 1
    assert {item["code"] for item in files["files"][0]["diagnostics"]} >= {"ANALYSIS_AI_INVALID_JSON", "ANALYSIS_AI_RETRY_SUCCEEDED"}


def test_max_attempts_exceeded_marks_file_failed_with_preview(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analyzer = StubAnalyzer(
        outcomes=[
            KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad", attempt=1),
            KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad again", attempt=2),
        ]
    )
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 2))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)

    assert final["failedFiles"] == 0
    assert files["files"][0]["attemptCount"] == 2
    assert files["files"][0]["lastErrorCode"] == "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"
    assert files["files"][0]["lastRawResponsePreview"] == "{bad again"
    assert {item["code"] for item in files["files"][0]["diagnostics"]} >= {"ANALYSIS_AI_INVALID_JSON", "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"}


def test_timeout_marks_file_failed_and_continues(tmp_path):
    store, _, _ = build_inventory(
        tmp_path,
        extra_files={
            "src/main/java/example/SecondHandler.java": "public class SecondHandler {}\n",
        },
    )
    analyzer = StubAnalyzer(
        outcomes=[
            KnowledgeError("ANALYSIS_AI_TIMEOUT", "AI analyzer request timed out", attempt=1),
            valid_result(),
        ]
    )
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 3))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, None, None, 10, 0)
    first = AnalysisStore(store.db_path).files(None, "ANALYZED", "ObjectHandler", 10, 0)

    assert final["status"] == "COMPLETED"
    assert final["processedFiles"] == 2
    assert final["failedFiles"] == 0
    assert analyzer.calls == 2
    assert {file["analysisStatus"] for file in files["files"]} == {"ANALYZED"}
    assert first["files"][0]["attemptCount"] == 1
    assert first["files"][0]["lastErrorCode"] == "ANALYSIS_AI_TIMEOUT"


def test_transport_error_marks_file_failed_and_continues_after_attempts(tmp_path):
    store, _, _ = build_inventory(
        tmp_path,
        extra_files={
            "src/main/java/example/SecondHandler.java": "public class SecondHandler {}\n",
        },
    )
    analyzer = StubAnalyzer(
        outcomes=[
            KnowledgeError("ANALYSIS_AI_TRANSPORT_ERROR", "AI analyzer transport error", attempt=1),
            valid_result(),
        ]
    )
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 1))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])

    assert final["processedFiles"] == 2
    assert final["failedFiles"] == 0


def test_last_progress_at_updates(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    assert final["lastProgressAt"]


def test_interrupted_running_jobs_are_marked_failed_on_startup_cleanup(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analysis_store = AnalysisStore(store.db_path)
    analysis_store.create_job(
        {
            "jobId": "job-running",
            "status": "RUNNING",
            "startedAt": "now",
            "sourceCount": 1,
            "fileCount": 1,
        }
    )
    row = store.search_rows([], [])[0][0]
    analysis_store.create_job_files("job-running", [row], {int(row["id"]): "CODE"}, GRAPH_ENGINE_VERSION)
    analysis_store.update_job_file("job-running", int(row["id"]), "RUNNING", started=True)

    analysis_store.mark_interrupted_jobs()
    job = analysis_store.job("job-running")
    with sqlite3.connect(store.db_path) as conn:
        job_file = conn.execute("SELECT status, completed_at, diagnostics_json FROM analysis_job_files WHERE job_id = 'job-running'").fetchone()

    assert job["status"] == "FAILED"
    assert job["currentSourceId"] is None
    assert job["diagnostics"][0]["code"] == "ANALYSIS_JOB_INTERRUPTED"
    assert job_file[0] == "FAILED"
    assert job_file[1] is not None
    assert "ANALYSIS_JOB_INTERRUPTED" in job_file[2]


def test_init_reconciles_orphan_running_job_files_for_inactive_jobs(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analysis_store = AnalysisStore(store.db_path)
    analysis_store.create_job(
        {
            "jobId": "job-failed",
            "status": "FAILED",
            "startedAt": "now",
            "sourceCount": 1,
            "fileCount": 1,
        }
    )
    row = store.search_rows([], [])[0][0]
    analysis_store.create_job_files("job-failed", [row], {int(row["id"]): "CODE"}, GRAPH_ENGINE_VERSION)
    analysis_store.update_job_file("job-failed", int(row["id"]), "RUNNING", started=True)
    AnalysisStore._initialized_paths.discard(str(store.db_path.resolve()))

    AnalysisStore(store.db_path).init()

    with sqlite3.connect(store.db_path) as conn:
        job_file = conn.execute("SELECT status, completed_at, diagnostics_json FROM analysis_job_files WHERE job_id = 'job-failed'").fetchone()

    assert job_file[0] == "FAILED"
    assert job_file[1] is not None
    assert "ANALYSIS_JOB_FILE_ORPHANED" in job_file[2]


def test_final_graph_detail_returns_claims_and_evidence(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    analysis_store = AnalysisStore(store.db_path)
    manifest, nodes = current_graph_nodes(store)
    _, edges = current_graph_edges(store)
    handler = next(item for item in nodes if item["name"] == "ObjectHandler")
    contains = next(item for item in edges if item["edgeType"] == "CONTAINS")
    node_detail = analysis_store.graph_snapshot_node_detail(manifest["graphRevision"], handler["id"], "edge-gateway", True)
    edge_detail = analysis_store.graph_snapshot_edge_detail(manifest["graphRevision"], contains["id"], "edge-gateway", True)

    assert any(claim["claimKind"] == "ROLE" and claim["summary"] == "HTTP_HANDLER" for claim in node_detail["item"]["claims"])
    assert edge_detail["item"]["evidence"]


def test_runtime_analysis_writes_graph_tables_and_not_legacy_symbols(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(responsibility_graph_result()))["jobId"])

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
        objects = {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type IN ('table', 'view', 'trigger', 'index')").fetchall()}

    assert counts["analysis_graph_nodes"] > 0
    assert counts["analysis_graph_edges"] > 0
    assert counts["analysis_graph_claims"] > 0
    assert counts["analysis_graph_evidence"] > 0
    assert "analysis_symbols" not in objects
    assert "analysis_relations" not in objects


def test_runtime_analysis_writes_graph_engine_job_file_flow_and_line_metadata(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analyzer = StubAnalyzer(GraphAnalysisResult())
    runner = SupervisorHarness(store, app_config(tmp_path))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])

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
    analyzer = StubAnalyzer(GraphAnalysisResult())
    runner = SupervisorHarness(store, app_config(tmp_path))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        node = conn.execute("""
            SELECT node_kind, fact_origin, flow_domain
            FROM analysis_graph_nodes
            WHERE source_id = 'edge-gateway'
        """).fetchone()
        diagnostics = conn.execute("""
            SELECT id, stage, code, severity, fact_origin, flow_domain
            FROM analysis_graph_diagnostics
            WHERE source_id = 'edge-gateway'
            ORDER BY code
        """).fetchall()
        analysis_file = conn.execute("SELECT status, last_error_code FROM analysis_files").fetchone()
        job_file = conn.execute("SELECT status, flow_domain FROM analysis_job_files").fetchone()
    diagnostic_codes = {row["code"] for row in diagnostics}

    assert final["status"] == "COMPLETED"
    assert final["failedFiles"] == 0
    assert analyzer.calls == 0
    assert node["node_kind"] == "FILE"
    assert node["fact_origin"] == "STATIC"
    assert node["flow_domain"] == "WORKFLOW"
    assert diagnostics[0]["id"].startswith("analysis-graph-diagnostic:")
    assert "STRUCTURAL_PARSER_NOT_AVAILABLE" in diagnostic_codes
    assert "ANALYSIS_AI_SKIPPED_UNSUPPORTED_STRUCTURE" in diagnostic_codes
    assert analysis_file["status"] == "ANALYZED"
    assert analysis_file["last_error_code"] is None
    assert job_file["status"] == "ANALYZED_WITH_DIAGNOSTICS"
    assert job_file["flow_domain"] == "WORKFLOW"
    _, nodes = current_graph_nodes(store, flow_domain=None)
    assert nodes[0]["nodeKind"] == "FILE"
    assert {item["code"] for item in AnalysisStore(store.db_path).diagnostics("edge-gateway", 10, 0)["diagnostics"]} >= {
        "STRUCTURAL_PARSER_NOT_AVAILABLE",
        "ANALYSIS_AI_SKIPPED_UNSUPPORTED_STRUCTURE",
    }


def test_runtime_resolves_field_receiver_calls_when_target_type_is_unique(tmp_path):
    store, _, _ = build_inventory(
        tmp_path,
        extra_files={
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
        },
    )
    runner = SupervisorHarness(store, app_config(tmp_path))
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


def test_callable_endpoint_returns_direct_callable_responsibility(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(responsibility_graph_result()))["jobId"])
    selected = current_graph_node_detail_by_name(store, "create", include_evidence=True)

    assert selected["nodeKind"] == "CALLABLE"
    assert selected["claimSummary"] == "Handles object creation."
    assert selected["summarySource"] == "DIRECT"
    assert selected["summaryClaimNodeId"] == selected["id"]
    assert selected["summaryConfidence"] == 0.86


def test_callable_without_direct_claim_uses_parent_fallback_with_provenance(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(responsibility_graph_result(method_claim=False, type_claim=True)))["jobId"])
    selected = current_graph_node_detail_by_name(store, "create")

    assert selected["claimSummary"] == "Handles object requests."
    assert selected["summarySource"] == "PARENT_FALLBACK"
    assert selected["summaryClaimNodeId"] == selected["parentNodeId"]


def test_callable_without_type_claim_uses_file_fallback_with_provenance(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(
        store, runner.start(AnalysisBuildRequest(), StubAnalyzer(responsibility_graph_result(method_claim=False, type_claim=False, file_claim=True)))["jobId"]
    )
    selected = current_graph_node_detail_by_name(store, "create")

    assert selected["claimSummary"] == "Defines an object handler file."
    assert selected["summarySource"] == "FILE_FALLBACK"
    assert selected["summaryClaimNodeId"] != selected["id"]


def test_low_confidence_callable_claim_is_debug_only_and_not_trusted(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(
        store,
        runner.start(AnalysisBuildRequest(), StubAnalyzer(responsibility_graph_result(method_claim=True, type_claim=False, method_confidence=0.2)))["jobId"],
    )
    selected = current_graph_node_detail_by_name(store, "create", include_evidence=True)
    responsibility = next(claim for claim in selected["claims"] if claim["claimKind"] == "RESPONSIBILITY")

    assert selected["status"] == "TRUSTED"
    assert selected["summarySource"] == "NONE"
    assert responsibility["status"] == "DEBUG_ONLY"
    assert responsibility["rejectionReason"] is None or responsibility["rejectionReason"] == "ANALYSIS_GRAPH_CALLABLE_EVIDENCE_OUTSIDE_METHOD"
    assert selected["summaryConfidence"] is None


def test_generic_file_level_callable_summary_is_not_used_as_direct_summary(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(
        store,
        runner.start(
            AnalysisBuildRequest(),
            StubAnalyzer(
                responsibility_graph_result(
                    method_claim=True,
                    type_claim=False,
                    method_summary="This Java file contains an object handler.",
                )
            ),
        )["jobId"],
    )
    selected = current_graph_node_detail_by_name(store, "create", include_evidence=True)
    responsibility = next(claim for claim in selected["claims"] if claim["claimKind"] == "RESPONSIBILITY")

    assert selected["summarySource"] == "NONE"
    assert selected["claimSummary"] is None
    assert responsibility["status"] == "DEBUG_ONLY"
    assert responsibility["rejectionReason"] == "GENERIC_FILE_LEVEL_CALLABLE_SUMMARY"


def test_no_source_file_mutation(tmp_path):
    store, _, service = build_inventory(tmp_path)
    source = service / "src/main/java/example/ObjectHandler.java"
    before = source.read_text(encoding="utf-8")
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    assert source.read_text(encoding="utf-8") == before


def test_no_production_domain_hardcoded_synonyms():
    src = Path("services/forge-knowledge/src/knowledge_service")
    banned = ["_AUTH_QUERY", "site creation", "авторизація"]
    combined = "\n".join(path.read_text(encoding="utf-8") for path in src.rglob("*.py"))

    assert all(term not in combined for term in banned)


class FakeRunner:
    async def start(self, request):
        return {"jobId": "job-1", "status": "QUEUED", "message": "Knowledge analysis job queued"}

    async def stop(self, job_id):
        return {"jobId": job_id, "status": "STOP_REQUESTED", "message": "Knowledge analysis stop requested"}


def configure_api(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    monkeypatch.setattr(main, "analysis_supervisor", FakeRunner())
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
            SELECT snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, language, flow_domain
            FROM analysis_graph_nodes
            WHERE source_id = 'edge-gateway'
            LIMIT 1
        """).fetchone()
        assert base is not None
        for index in range(count):
            conn.execute(
                """
                INSERT INTO analysis_graph_nodes(
                    id, job_id, source_id, inventory_file_id, analysis_file_id, stable_key,
                    snapshot_id,
                    node_kind, language, name, qualified_name, display_name, parent_node_id,
                    line_start, line_end, confidence, status, metadata_json, created_at,
                    fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
                (
                    f"test-isolated-node-{index}",
                    base["job_id"],
                    base["source_id"],
                    base["inventory_file_id"],
                    base["analysis_file_id"],
                    f"edge-gateway|isolated|{index}",
                    base["snapshot_id"],
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
                ),
            )


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
        conn.execute(
            """
            INSERT INTO analysis_graph_edges(
                id, job_id, source_id, inventory_file_id, analysis_file_id, from_node_id,
                snapshot_id,
                to_node_id, edge_type, resolution_status, confidence, evidence_id,
                unresolved_target_json, metadata_json, status, created_at, fact_origin, flow_domain
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?)
        """,
            (
                "test-unresolved-edge-without-endpoint",
                from_node["job_id"],
                from_node["source_id"],
                from_node["inventory_file_id"],
                from_node["analysis_file_id"],
                from_node["id"],
                from_node["snapshot_id"],
                "CALLS",
                "UNRESOLVED",
                1.0,
                json.dumps({"name": "MissingTarget", "methodName": "missing"}),
                json.dumps(
                    {
                        "methodName": "missing",
                        "unresolvedReason": "TARGET_NOT_ANALYZED",
                        "sliceDefaultVisibility": "SHOW_AS_UNCERTAINTY",
                    }
                ),
                "TRUSTED",
                "now",
                "STATIC",
                from_node["flow_domain"] or "CODE",
            ),
        )


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
    monkeypatch.setattr(main, "analysis_supervisor", FakeRunner())

    result = post_json("/api/v1/knowledge/analysis/build", {"sourceIds": ["edge-gateway"], "concurrency": 1})

    status = store.status()
    rows, _ = store.search_rows(["edge-gateway"], [])
    assert result["status"] == 200
    assert status["status"] == "READY"
    assert status["fileCount"] == 1
    assert len(rows) == 1


def test_analysis_api_job_status_endpoint(tmp_path, monkeypatch):
    store = configure_api(tmp_path, monkeypatch)
    AnalysisStore(store.db_path).create_job(
        {
            "jobId": "job-2",
            "status": "RUNNING",
            "startedAt": "now",
            "sourceCount": 1,
            "fileCount": 1,
        }
    )

    result = get_json("/api/v1/knowledge/analysis/jobs/job-2")

    assert result["json"]["status"] == "RUNNING"


def test_analysis_api_stop_job(tmp_path, monkeypatch):
    configure_api(tmp_path, monkeypatch)

    result = post_json("/api/v1/knowledge/analysis/jobs/job-1/stop", {})

    assert result["status"] == 200
    assert result["json"]["status"] == "STOP_REQUESTED"


def test_status_api_separates_coverage_and_freshness_without_running_ai(tmp_path, monkeypatch):
    store, _, service = build_inventory(tmp_path)
    wait_job(store, SupervisorHarness(store, app_config(tmp_path)).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service / "src/main/java/example/ObjectHandler.java").write_text("public class ObjectHandler { void changed() {} }\n", encoding="utf-8")
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)

    class FailingRunner:
        def start(self, request):
            raise AssertionError("status must not run AI analysis")

    monkeypatch.setattr(main, "analysis_supervisor", FailingRunner())

    default_result = get_json("/api/v1/knowledge/status")
    result = get_json("/api/v1/knowledge/status?includeFreshness=true")

    assert result["status"] == 200
    assert default_result["json"]["freshness"]["status"] == "UNKNOWN"
    assert result["json"]["coverage"]["scannedFiles"] == 1
    assert result["json"]["coverage"]["eligibleFiles"] == 1
    assert result["json"]["freshness"]["status"] == "OUTDATED"
    assert result["json"]["freshness"]["modifiedFiles"] == 1


def test_analysis_api_exposes_failed_file_diagnostics_and_progress(tmp_path, monkeypatch):
    store, _, _ = build_inventory(
        tmp_path,
        extra_files={
            "src/main/java/example/SecondHandler.java": "public class SecondHandler {}\n",
        },
    )
    cfg = app_config_with_retries(tmp_path, 1)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    monkeypatch.setattr(main, "analysis_supervisor", FakeRunner())
    analyzer = StubAnalyzer(
        outcomes=[
            KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad", attempt=1),
            valid_result(),
        ]
    )
    wait_job(store, SupervisorHarness(store, cfg).start(AnalysisBuildRequest(), analyzer)["jobId"])

    status = get_json("/api/v1/knowledge/analysis/status")
    files = get_json("/api/v1/knowledge/analysis/files?pathContains=ObjectHandler")

    assert status["json"]["lastCompletedAt"]
    assert files["json"]["total"] == 1
    assert files["json"]["files"][0]["lastErrorCode"] == "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"
    assert files["json"]["files"][0]["lastRawResponsePreview"] == "{bad"
    assert {item["code"] for item in files["json"]["files"][0]["diagnostics"]} >= {"ANALYSIS_AI_INVALID_JSON", "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"}


def test_semantic_index_new_db_migration_reports_missing_without_graph_facts(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    AnalysisStore(db_path).init()

    with sqlite3.connect(db_path) as conn:
        tables = {
            row[0]
            for row in conn.execute(
                """
                SELECT name
                FROM sqlite_master
                WHERE type = 'table'
                """
            ).fetchall()
        }
    status = SemanticIndexStore(db_path).status_for_source("edge-gateway").to_dict()

    assert {"semantic_index_state", "semantic_documents", "semantic_vectors"} <= tables
    assert status["status"] == "MISSING"
    assert status["totalNodeCount"] == 0
    assert status["indexedNodeCount"] == 0
    assert status["progressPercent"] == 0.0
    assert status["ready"] is False


def test_existing_analyzed_source_without_semantic_state_reports_pending(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    wait_job(store, SupervisorHarness(store, cfg).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    with sqlite3.connect(store.db_path) as conn:
        conn.execute("DELETE FROM semantic_index_state")

    service = get_json("/api/v1/knowledge/overview")["json"]["sources"][0]
    status = SemanticIndexStore(store.db_path).status_for_source("edge-gateway").to_dict()

    assert "semanticIndex" not in service
    assert service["analysis"]["semanticPercent"] == 0.0
    assert status["status"] == "PENDING"
    assert status["graphRevision"]
    assert status["totalNodeCount"] > 0
    assert status["indexedNodeCount"] == 0
    assert status["progressPercent"] == 0.0
    assert status["ready"] is False


def test_successful_analysis_completion_marks_semantic_index_pending(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config(tmp_path)

    wait_job(store, SupervisorHarness(store, cfg).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    state = SemanticIndexStore(store.db_path).get_state("edge-gateway")

    assert state["status"] == "PENDING"
    assert state["graph_revision"]
    assert state["total_node_count"] > 0
    assert state["indexed_node_count"] == 0


def test_reanalysis_after_ready_marks_semantic_index_stale(tmp_path):
    store, config, service_root = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    runner = SupervisorHarness(store, cfg)
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    semantic_store = SemanticIndexStore(store.db_path)
    initial = semantic_store.get_state("edge-gateway")
    semantic_store.mark_source_ready(
        "edge-gateway",
        initial["graph_revision"],
        initial["total_node_count"],
        initial["total_node_count"],
        embedding_model="test-embedding",
        embedding_dimension=8,
    )
    (service_root / "src/main/java/example/ObjectHandler.java").write_text(
        "public class ObjectHandler {\n  @PostMapping\n  public void update() {\n  }\n}\n",
        encoding="utf-8",
    )
    InventoryBuilder(load_source_config(config), store).build([], [])

    wait_job(store, runner.start(AnalysisBuildRequest(force=True), StubAnalyzer())["jobId"])
    state = semantic_store.get_state("edge-gateway")

    assert state["status"] == "STALE"
    assert state["graph_revision"] != initial["graph_revision"]
    assert state["indexed_node_count"] == 0


def test_failed_or_stopped_analysis_does_not_mark_semantic_pending(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analysis_store = AnalysisStore(store.db_path)
    for terminal_status in ("FAILED", "STOPPED"):
        job_id = f"job-{terminal_status.lower()}"
        analysis_store.create_job(
            {
                "jobId": job_id,
                "status": "RUNNING",
                "startedAt": "now",
                "sourceCount": 1,
                "fileCount": 1,
                "processedFileCount": 0,
                "failedFileCount": 0,
                "currentSourceId": "edge-gateway",
                "sourceIds": ["edge-gateway"],
            }
        )
        analysis_store.update_job(job_id, {"status": terminal_status, "completedAt": "now"})

    assert SemanticIndexStore(store.db_path).get_state("edge-gateway") is None
    assert SemanticIndexStore(store.db_path).status_for_source("edge-gateway").status.value == "MISSING"


def test_semantic_index_ready_progress_reports_100_percent(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    wait_job(store, SupervisorHarness(store, cfg).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    SemanticIndexBuilder(
        store.db_path,
        FakeDeterministicEmbeddingProvider(dimension=8),
        config=SemanticBuildConfig(batch_size=10),
    ).build(["edge-gateway"], force=True)

    service = get_json("/api/v1/knowledge/overview")["json"]["sources"][0]
    semantic = SemanticIndexStore(store.db_path).status_for_source("edge-gateway").to_dict()

    assert "semanticIndex" not in service
    assert service["analysis"]["semanticPercent"] == service["analysis"]["percent"] == 100.0
    assert semantic["status"] == "READY"
    assert semantic["ready"] is True
    assert semantic["indexedNodeCount"] == semantic["totalNodeCount"]
    assert semantic["progressPercent"] == 100.0


def test_semantic_index_partial_progress_reports_below_100_percent(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    wait_job(store, SupervisorHarness(store, cfg).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    semantic_store = SemanticIndexStore(store.db_path)
    state = semantic_store.get_state("edge-gateway")
    semantic_store.mark_source_building(
        "edge-gateway",
        state["graph_revision"],
        state["total_node_count"],
        indexed_node_count=1,
        build_id="build-1",
    )

    service = get_json("/api/v1/knowledge/overview")["json"]["sources"][0]
    semantic = SemanticIndexStore(store.db_path).status_for_source("edge-gateway").to_dict()

    assert "semanticIndex" not in service
    assert service["analysis"]["semanticPercent"] == 0.0
    assert semantic["status"] == "BUILDING"
    assert semantic["indexedNodeCount"] == 1
    assert 0.0 < semantic["progressPercent"] < 100.0
    assert semantic["ready"] is False


def test_knowledge_query_works_with_missing_pending_and_stale_semantic_index(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)

    missing = post_json("/api/v1/knowledge/query", {"query": "ObjectHandler"})
    assert missing["status"] == 200
    assert missing["json"]["status"] != "QUERY_FAILED"

    wait_job(store, SupervisorHarness(store, cfg).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    pending = post_json("/api/v1/knowledge/query", {"query": "ObjectHandler"})
    assert pending["status"] == 200
    assert pending["json"]["status"] in {"OK", "AMBIGUOUS"}

    semantic_store = SemanticIndexStore(store.db_path)
    state = semantic_store.get_state("edge-gateway")
    semantic_store.mark_source_stale("edge-gateway", f"{state['graph_revision']}:manual-stale", state["total_node_count"])
    stale = post_json("/api/v1/knowledge/query", {"query": "ObjectHandler"})
    assert stale["status"] == 200
    assert stale["json"]["status"] in {"OK", "AMBIGUOUS"}


def test_services_status_returns_inventory_analysis_and_facts_counts(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    wait_job(store, SupervisorHarness(store, cfg).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    result = get_json("/api/v1/knowledge/overview")
    service = result["json"]["sources"][0]

    assert result["status"] == 200
    assert service["sourceId"] == "edge-gateway"
    assert service["displayName"] == "Edge Gateway"
    assert set(service) == {
        "sourceId",
        "displayName",
        "group",
        "rootExists",
        "inventory",
        "factsProgress",
        "analysis",
        "activeJob",
        "updatedAt",
        "version",
    }
    assert service["inventory"]["fileCount"] == 1
    assert set(service["inventory"]) == {"status", "fileCount", "skippedCount"}
    assert service["factsProgress"] == {"completedCount": 1, "totalCount": 1, "percent": 100.0}
    assert service["analysis"]["totalFiles"] == 1
    assert service["analysis"]["succeededFiles"] == 1
    assert service["analysis"]["processedFiles"] == 1
    assert service["analysis"]["pendingFiles"] == 0
    assert service["analysis"]["status"] == "COMPLETED"
    assert service["analysis"]["percent"] == 100.0
    assert set(service["analysis"]) == {
        "status",
        "totalFiles",
        "processedFiles",
        "succeededFiles",
        "partialFiles",
        "failedFiles",
        "skippedFiles",
        "pendingFiles",
        "percent",
        "semanticPercent",
        "activeJobId",
        "activeJobMode",
        "activeJobSelectedFileCount",
        "activeJobProcessedFileCount",
        "activeJobFailedFileCount",
        "activeJobCurrentRelativePath",
    }
    assert service["analysis"]["semanticPercent"] == 0.0
    assert "semanticIndex" not in service


def test_services_status_is_stable_after_store_restart(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    wait_job(store, SupervisorHarness(store, cfg).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    first = get_json("/api/v1/knowledge/overview")
    restarted_store = InventoryStore(store.db_path)
    restarted_store.init()
    monkeypatch.setattr(main, "store", restarted_store)
    second = get_json("/api/v1/knowledge/overview")

    assert first["status"] == 200
    assert second["status"] == 200
    assert first["json"] == second["json"]


def test_services_status_repeated_responses_are_identical_without_db_changes(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    wait_job(store, SupervisorHarness(store, cfg).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    responses = [get_json("/api/v1/knowledge/overview")["json"] for _ in range(10)]

    assert all(response == responses[0] for response in responses)


def test_services_status_active_job_progress_is_not_double_counted(tmp_path, monkeypatch):
    store, _, _ = build_inventory_with_file_count(tmp_path, 113)
    seed_analysis_file_statuses(store.db_path, ["ANALYZED", "ANALYZED"])
    AnalysisStore(store.db_path).create_job(
        {
            "jobId": "job-running",
            "status": "RUNNING",
            "startedAt": "now",
            "sourceCount": 1,
            "fileCount": 113,
            "processedFileCount": 2,
            "failedFileCount": 0,
            "currentSourceId": "edge-gateway",
        }
    )
    monkeypatch.setattr(main, "app_config", app_config(tmp_path))
    monkeypatch.setattr(main, "store", store)

    service = get_json("/api/v1/knowledge/overview")["json"]["sources"][0]

    assert service["analysis"]["status"] == "RUNNING"
    assert service["analysis"]["totalFiles"] == 113
    assert service["analysis"]["succeededFiles"] == 2
    assert service["analysis"]["processedFiles"] == 2
    assert service["analysis"]["pendingFiles"] == 111
    assert service["analysis"]["percent"] == 1.8


def test_services_status_active_job_keeps_semantic_progress_visible(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    wait_job(store, SupervisorHarness(store, cfg).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    SemanticIndexBuilder(
        store.db_path,
        FakeDeterministicEmbeddingProvider(dimension=8),
        config=SemanticBuildConfig(batch_size=10),
    ).build(["edge-gateway"], force=True)
    AnalysisStore(store.db_path).create_job(
        {
            "jobId": "job-running",
            "status": "RUNNING",
            "startedAt": "now",
            "sourceCount": 1,
            "fileCount": 1,
            "processedFileCount": 0,
            "failedFileCount": 0,
            "currentSourceId": "edge-gateway",
            "sourceIds": ["edge-gateway"],
        }
    )

    service = get_json("/api/v1/knowledge/overview")["json"]["sources"][0]

    assert service["analysis"]["status"] == "RUNNING"
    assert "semanticIndex" not in service
    assert service["analysis"]["semanticPercent"] == service["analysis"]["percent"] == 100.0


def test_services_status_partial_completion_with_failures(tmp_path, monkeypatch):
    store, _, _ = build_inventory_with_file_count(tmp_path, 84)
    seed_analysis_file_statuses(store.db_path, ["ANALYZED"] * 69 + ["FAILED"] * 15)
    monkeypatch.setattr(main, "app_config", app_config(tmp_path))
    monkeypatch.setattr(main, "store", store)

    service = get_json("/api/v1/knowledge/overview")["json"]["sources"][0]

    assert service["analysis"]["processedFiles"] == 84
    assert service["analysis"]["pendingFiles"] == 0
    assert service["analysis"]["failedFiles"] == 15
    assert service["analysis"]["status"] == "PARTIAL"
    assert service["analysis"]["percent"] == 100.0


def test_services_status_completed_without_failures(tmp_path, monkeypatch):
    store, _, _ = build_inventory_with_file_count(tmp_path, 74)
    seed_analysis_file_statuses(store.db_path, ["ANALYZED"] * 74)
    monkeypatch.setattr(main, "app_config", app_config(tmp_path))
    monkeypatch.setattr(main, "store", store)

    service = get_json("/api/v1/knowledge/overview")["json"]["sources"][0]

    assert service["analysis"]["processedFiles"] == 74
    assert service["analysis"]["pendingFiles"] == 0
    assert service["analysis"]["failedFiles"] == 0
    assert service["analysis"]["status"] == "COMPLETED"


def test_services_status_does_not_report_outdated_for_persisted_analysis_cache(tmp_path, monkeypatch):
    store, config, service_root = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    wait_job(store, SupervisorHarness(store, cfg).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    (service_root / "src/main/java/example/ObjectHandler.java").write_text("public class ObjectHandler { void changed() {} }\n", encoding="utf-8")
    InventoryBuilder(load_source_config(config), store).build([], [])

    result = get_json("/api/v1/knowledge/overview")
    service = result["json"]["sources"][0]

    assert service["analysis"]["totalFiles"] == 1
    assert service["analysis"]["succeededFiles"] == 0
    assert service["analysis"]["pendingFiles"] == 1
    assert service["analysis"]["status"] == "NOT_ANALYZED"
    assert "staleFileCount" not in service["analysis"]
    assert "OUTDATED" not in json.dumps(result["json"])


def test_inventory_refresh_keeps_unchanged_files_without_reopening_content(tmp_path, monkeypatch):
    store, config, _ = build_inventory(tmp_path)
    before = store.snapshot_files(["edge-gateway"])[0]

    def fail_read_bytes(_path):
        raise AssertionError("unchanged inventory file was reopened")

    monkeypatch.setattr(Path, "read_bytes", fail_read_bytes)

    InventoryBuilder(load_source_config(config), store).build([], [])

    after = store.snapshot_files(["edge-gateway"])[0]
    assert after["id"] == before["id"]
    assert after["contentHash"] == before["contentHash"]


def test_inventory_refresh_processes_changed_file_with_one_stream(tmp_path, monkeypatch):
    store, config, service_root = build_inventory(tmp_path)
    changed_path = service_root / "src/main/java/example/ObjectHandler.java"
    changed_content = "public class ObjectHandler { void changed() {} }\n"
    changed_path.write_text(changed_content, encoding="utf-8")
    original_open = Path.open
    opened = []

    def fail_read_bytes(_path, *args, **kwargs):
        raise AssertionError("changed inventory file used read_bytes instead of the streaming pipeline")

    def count_changed_file_open(path, *args, **kwargs):
        if path == changed_path:
            opened.append(path)
        return original_open(path, *args, **kwargs)

    monkeypatch.setattr(Path, "read_bytes", fail_read_bytes)
    monkeypatch.setattr(Path, "open", count_changed_file_open)

    InventoryBuilder(load_source_config(config), store).build([], [])

    after = store.snapshot_files(["edge-gateway"])[0]
    assert after["relativePath"] == "src/main/java/example/ObjectHandler.java"
    assert after["contentHash"] == hashlib.sha256(changed_content.encode("utf-8")).hexdigest()
    assert len(opened) == 1


def test_context_query_uses_index_without_source_filesystem_reads(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path, content="public class ObjectHandler {\n  void createJarvisGateway() {}\n}\n")

    def fail_read_text(_path, *args, **kwargs):
        raise AssertionError("context query read source text")

    def fail_read_bytes(_path, *args, **kwargs):
        raise AssertionError("context query read source bytes")

    monkeypatch.setattr(Path, "read_text", fail_read_text)
    monkeypatch.setattr(Path, "read_bytes", fail_read_bytes)

    content = ContextService(store).context(ContextRequest(query="JarvisGateway", maxChars=1000, includeContent=True))
    metadata = ContextService(store).context(ContextRequest(query="JarvisGateway", maxChars=1000, includeContent=False))

    assert content["context"][0]["content"]
    assert metadata["context"][0]["content"] is None


def test_services_status_does_not_include_diagnostics_payload(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config_with_retries(tmp_path, 1)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    analyzer = StubAnalyzer(
        outcomes=[
            KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad", attempt=1),
        ]
    )
    wait_job(store, SupervisorHarness(store, cfg).start(AnalysisBuildRequest(), analyzer)["jobId"])

    result = get_json("/api/v1/knowledge/overview")
    service = result["json"]["sources"][0]

    assert service["analysis"]["succeededFiles"] == 1
    assert service["analysis"]["failedFiles"] == 0
    assert service["analysis"]["pendingFiles"] == 0
    encoded = json.dumps(result["json"])
    for key in ("diagnostics", "diagnosticsSummary", "examples", "message", "rawPreview", "details"):
        assert key not in encoded


def test_services_status_omits_diagnostic_messages_examples_and_active_job_diagnostics(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config_with_retries(tmp_path, 1)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    analyzer = StubAnalyzer(
        outcomes=[
            KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad", attempt=1),
        ]
    )
    job = wait_job(store, SupervisorHarness(store, cfg).start(AnalysisBuildRequest(), analyzer)["jobId"])
    AnalysisStore(store.db_path).create_job(
        {
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
            "diagnostics": [{"code": "ACTIVE_JOB_DETAIL", "message": "long active diagnostic"}],
        }
    )

    result = get_json("/api/v1/knowledge/overview")
    payload = result["json"]
    encoded = json.dumps(payload)

    assert job["status"] == "COMPLETED"
    assert payload["activeJob"] == {
        "jobId": "job-running",
        "sourceId": "edge-gateway",
        "status": "RUNNING",
        "mode": "FULL",
        "selectedFileCount": 1,
        "processedFileCount": 0,
        "failedFileCount": 0,
        "currentRelativePath": "src/main/java/example/ObjectHandler.java",
    }
    for key in ("diagnostics", "diagnosticsSummary", "examples", "message", "rawPreview", "details", "lastProgressAt"):
        assert key not in encoded


def test_services_status_size_does_not_grow_with_diagnostics(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    analysis_store = AnalysisStore(store.db_path)
    analysis_store.init()
    with sqlite3.connect(store.db_path) as conn:
        conn.executemany(
            """
                INSERT INTO analysis_graph_diagnostics(
                    id, snapshot_id, job_id, source_id, severity, stage, code, message, metadata_json, created_at
                )
                VALUES (?, 'job-1:edge-gateway', 'job-1', 'edge-gateway', 'ERROR', 'LLM_ENRICHMENT', 'ANALYSIS_AI_INVALID_JSON', ?, '{}', 'now')
                """,
            [(f"diag-{index}", "x" * 1000) for index in range(10000)],
        )
    monkeypatch.setattr(main, "app_config", app_config(tmp_path))
    monkeypatch.setattr(main, "store", store)

    result = get_json("/api/v1/knowledge/overview")
    encoded = json.dumps(result["json"])

    assert result["status"] == 200
    assert len(encoded) < 1400
    for key in ("diagnostics", "examples", "message", "rawPreview", "details"):
        assert key not in encoded


def test_services_status_query_failure_returns_error_not_zero_counters(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    monkeypatch.setattr(main, "app_config", app_config(tmp_path))
    monkeypatch.setattr(main, "store", store)

    def fail_overview(_db_path):
        raise sqlite3.OperationalError("forced query failure")

    monkeypatch.setattr(main, "read_overview", fail_overview)

    result = get_json("/api/v1/knowledge/overview")

    assert result["status"] == 500
    assert result["json"]["code"] == "KNOWLEDGE_DB_ERROR"
    assert "sources" not in result["json"]


def test_analysis_diagnostics_endpoint_returns_details_separately(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config_with_retries(tmp_path, 1)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)
    analyzer = StubAnalyzer(
        outcomes=[
            KnowledgeError("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", raw_preview="{bad", attempt=1),
        ]
    )
    wait_job(store, SupervisorHarness(store, cfg).start(AnalysisBuildRequest(), analyzer)["jobId"])

    result = get_json("/api/v1/knowledge/analysis/diagnostics?sourceId=edge-gateway&limit=1")

    assert result["status"] == 200
    assert result["json"]["total"] >= 1
    assert len(result["json"]["diagnostics"]) == 1
    assert result["json"]["diagnostics"][0]["message"]


def test_overview_missing_projection_returns_error_not_zero_counts(tmp_path, monkeypatch):
    config = create_source_config(tmp_path)
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    cfg = app_config(tmp_path)
    monkeypatch.setattr(main, "app_config", cfg)
    monkeypatch.setattr(main, "store", store)

    result = get_json("/api/v1/knowledge/overview")

    assert load_source_config(config)
    assert result["status"] == 500
    assert "sources" not in result["json"]


def test_analysis_store_drops_legacy_fact_tables(tmp_path):
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    with store._connect() as conn:
        for table in ("symbol_tokens", "edges", "symbols", "file_extraction_state", "fact_builds"):
            conn.execute(f"CREATE TABLE {table} (id INTEGER)")

    store.init()

    with store._connect() as conn:
        tables = {row["name"] for row in conn.execute("SELECT name FROM sqlite_master WHERE type = 'table'").fetchall()}
    assert not {"symbol_tokens", "edges", "symbols", "file_extraction_state", "fact_builds"} & tables
