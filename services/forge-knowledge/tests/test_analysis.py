
import asyncio
import hashlib
import json
import logging
import os
import sqlite3
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from pathlib import Path

import httpx
import pytest

os.environ.setdefault("KNOWLEDGE_STORE_PATH", "/tmp/forge-ai-knowledge-test-main.sqlite")

REPO_ROOT = Path(__file__).resolve().parents[3]
POLICY_PATH = REPO_ROOT / "config" / "knowledge" / "analysis-policy.yaml"

from knowledge_service import main
from knowledge_service.analysis_client import OllamaAnalysisClient
from knowledge_service.analysis_graph_contract import GraphContractProvider, contract_payload
from knowledge_service.analysis_policy import EXTRACTOR_MODE_FILE_ANCHOR_ONLY
from knowledge_service.analysis_policy_loader import load_analysis_policy
from knowledge_service.analysis_response_parser import MAX_RAW_PREVIEW_CHARS
from knowledge_service.analysis_progress import CurrentFileTargetProgressTracker
from knowledge_service.analysis_schema import AnalysisBuildRequest, RetryFailedAnalysisRequest
from knowledge_service.analysis_service import AnalysisSupervisor
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.analyzer_runtime import AnalyzerPolicyRuntimeResolver, AnalyzerRuntime, ExtractorRegistry, ExtractorResult
from knowledge_service.anchor_enrichment import AnchorAwareGraphValidator
from knowledge_service.config import AppConfig
from knowledge_service.context_schema import ContextRequest
from knowledge_service.context_service import ContextService
from knowledge_service.embedding_provider import FakeDeterministicEmbeddingProvider
from knowledge_service.errors import KnowledgeError
from knowledge_service.freshness_service import KnowledgeFreshnessService
from knowledge_service.graph_analysis import GraphAnalysisEngine
from knowledge_service.graph_schema import BoundaryDescriptor, BoundaryFact, GraphAnalysisResult, GraphClaim, GraphEdge, GraphEvidenceRef, GraphNode
from knowledge_service.graph_state_repository import GRAPH_STATE_FAILED, GraphStateRepository
from knowledge_service.inventory_builder import InventoryBuilder
from knowledge_service.bootstrap import KnowledgeDependencies
from knowledge_service.inventory_file_resolver import InventoryFileResolver
from knowledge_service.inventory_refresh import AsyncInventoryScheduler, InventoryRefreshService
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.java_parser_adapter import JavaParserAdapter
from knowledge_service.knowledge_query_schema import KnowledgeQueryMatchedNode, KnowledgeQueryRequest
from knowledge_service.knowledge_query_service import build_knowledge_query_service
from knowledge_service.local_flow_unit_engine import LocalFlowRootOrigin, LocalFlowUnitEngine
from knowledge_service.local_flow_unit_store import LocalFlowUnitGraphRepository
from knowledge_service.overview_projection import read_overview, refresh_overview_for_sources
from knowledge_service.semantic_builder import SemanticBuildConfig, SemanticIndexBuilder
from knowledge_service.semantic_index import SemanticIndexStore
from knowledge_service.snippet_extractor import SnippetExtractor
from knowledge_service.source_config import load_source_config
from knowledge_service.source_graph_finalizer import CrossSourceGraphResolver, SourceGraphFinalizer
from knowledge_service.storage_operations import StorageOperations
from knowledge_service.structural_analysis import StaticGraphMaterializer
from knowledge_service.structural_model import StructuralFileMetadata
from knowledge_service.target_enrichment import (
    BEGIN_INPUT_MARKER,
    END_INPUT_MARKER,
    TARGET_INPUT_SCHEMA_VERSION,
    TARGET_REQUEST_KIND,
)

main.app_config = None
main.store = None
main.analysis_supervisor = None


def knowledge_query_request(query_text: str) -> KnowledgeQueryRequest:
    return KnowledgeQueryRequest(
        queryText=query_text,
        intent="AUTO",
        answerLanguage="en",
        includeTests=False,
        maxFlows=10,
    )


def knowledge_query_payload(query_text: str):
    return {
        "queryText": query_text,
        "intent": "AUTO",
        "answerLanguage": "en",
        "includeTests": False,
        "maxFlows": 10,
    }


class StubAnalyzer:
    name = "ai-file-analyzer"
    version = "1"

    def __init__(self, result=None, fail=False, block_event=None, bad_response_attempts=0, outcomes=None):
        self.result = result or GraphAnalysisResult()
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


class ClosingTrackingAnalyzer(StubAnalyzer):
    def __init__(self, result=None, fail=False, block_event=None, bad_response_attempts=0, outcomes=None):
        super().__init__(result=result, fail=fail, block_event=block_event, bad_response_attempts=bad_response_attempts, outcomes=outcomes)
        self.close_calls = 0
        self.aclose_calls = 0

    def close(self):
        self.close_calls += 1

    async def aclose(self):
        self.aclose_calls += 1


class AsyncBlockingClosingAnalyzer:
    name = "ai-file-analyzer"
    version = "1"

    def __init__(self, started: asyncio.Event, release: asyncio.Event):
        self.started = started
        self.release = release
        self.calls = 0
        self.close_calls = 0
        self.aclose_calls = 0

    async def analyze(self, payload, line_count, repair_prompt=None):
        del payload, line_count, repair_prompt
        self.calls += 1
        self.started.set()
        await self.release.wait()
        return GraphAnalysisResult()

    def close(self):
        self.close_calls += 1

    async def aclose(self):
        self.aclose_calls += 1


class CapturingGraphAnalyzer(StubAnalyzer):
    def __init__(self, result=None, fail=False):
        super().__init__(result=result or GraphAnalysisResult(), fail=fail)
        self.payloads = []

    def analyze(self, payload, line_count, repair_prompt=None):
        self.payloads.append(payload)
        return super().analyze(payload, line_count, repair_prompt)


REALISTIC_WORKFLOW_YAML = """name: Deploy on comment
on:
  issue_comment:
    types: [created]
jobs:
  deploy:
    if: contains(github.event.comment.body, '/deploy')
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Build
        run: mvn -q package
      - name: Deploy
        uses: ./.github/actions/service-deploy-run
"""


REALISTIC_ACTION_YAML = """name: Service Deploy Run
description: Deploys a service from a reusable action
inputs:
  service-name:
    required: true
runs:
  using: composite
  steps:
    - shell: bash
      run: ./scripts/deploy-service.sh "$INPUT_SERVICE_NAME"
"""


REALISTIC_POM_XML = """<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.sitionix</groupId>
  <artifactId>workspaceaggregationservice-sox</artifactId>
  <packaging>jar</packaging>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
"""


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

    async def _wait_for_terminal_job(self, job_id):
        if not job_id:
            return
        for _ in range(120):
            job = self.analysis_store.job(job_id)
            if job["status"] in {"COMPLETED", "FAILED", "STOPPED"}:
                return
            await asyncio.sleep(0.025)
        raise AssertionError("job did not finish")

    async def _close_test_owned_analyzer(self, analyzer):
        if isinstance(analyzer, OllamaAnalysisClient):
            await analyzer.aclose()

    def start(self, request, analyzer=None):
        if getattr(analyzer, "block_event", None) is not None:
            self._ensure_background()
            return self._run_background(self.supervisor.start(request, analyzer))

        async def run():
            supervisor = self._new_supervisor()
            await supervisor.start_lifespan()
            try:
                response = await supervisor.start(request, analyzer)
                queue = supervisor._queue
                if queue is not None and getattr(analyzer, "block_event", None) is None:
                    await queue.join()
                await self._wait_for_terminal_job(response.get("jobId"))
                return response
            finally:
                await self._close_test_owned_analyzer(analyzer)
                await supervisor.shutdown()

        return asyncio.run(run())

    def retry_failed(self, request, analyzer=None):
        async def run():
            supervisor = self._new_supervisor()
            await supervisor.start_lifespan()
            try:
                response = await supervisor.retry_failed(request, analyzer)
                queue = supervisor._queue
                if queue is not None:
                    await queue.join()
                await self._wait_for_terminal_job(response.get("jobId"))
                return response
            finally:
                await self._close_test_owned_analyzer(analyzer)
                await supervisor.shutdown()

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


def responsibility_graph_result(method_claim=True, type_claim=True, file_claim=False, method_confidence=0.86, method_summary="Handles object creation."):
    file_id = "edge-gateway|src/main/java/example/ObjectHandler.java|FILE"
    type_id = "edge-gateway|src/main/java/example/ObjectHandler.java|TYPE|ObjectHandler"
    method_id = "edge-gateway|src/main/java/example/ObjectHandler.java|CALLABLE|ObjectHandler|create|create()"
    claims = []
    if file_claim:
        claims.append(
            {
                "localId": "file-responsibility",
                "nodeLocalId": file_id,
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
                "nodeLocalId": type_id,
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
                "nodeLocalId": method_id,
                "claimKind": "RESPONSIBILITY",
                "summary": method_summary,
                "evidence": [{"lineStart": 3, "lineEnd": 4, "text": "method body", "metadata": {}}],
                "confidence": method_confidence,
                "metadata": {},
            }
        )
    return GraphAnalysisResult.parse_obj(
        {
            "nodes": [],
            "edges": [],
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
                    "resolutionStatus": "RESOLVED",
                    "confidence": 1.0,
                    "evidence": [{"lineStart": 4, "lineEnd": 4, "text": "helper(); helper();", "metadata": {"evidenceKind": "CALLSITE"}}],
                    "metadata": {"factOrigin": "STATIC", "flowDomain": "CODE", "methodName": "helper"},
                },
                {
                    "localId": "same-callsite",
                    "fromNodeLocalId": "method1",
                    "toNodeLocalId": "method2",
                    "edgeType": "CALLS",
                    "resolutionStatus": "RESOLVED",
                    "confidence": 1.0,
                    "evidence": [{"lineStart": 4, "lineEnd": 4, "text": "helper(); helper();", "metadata": {"evidenceKind": "CALLSITE"}}],
                    "metadata": {"factOrigin": "STATIC", "flowDomain": "CODE", "methodName": "helper"},
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


def materialize_graph_for_test(
    result,
    content=None,
    file_id=1,
    relative_path="src/main/java/example/EmailVerificationLinkClientImpl.java",
    source_id="edge-gateway",
):
    content = (
        content
        or "class EmailVerificationLinkClientImpl {\n  WebClient client;\n  String createLink() {\n    helper(); helper();\n  }\n  void helper() {}\n}\n"
    )
    row = {
        "id": file_id,
        "source_id": source_id,
        "relative_path": relative_path,
        "content_hash": hashlib.sha256(content.encode("utf-8")).hexdigest(),
    }
    return GraphAnalysisEngine().materialize(row, "job-1", "test-analyzer", "1", result, content.splitlines())


def test_graph_analysis_preserves_typed_http_operation_edge_metadata():
    graph = materialize_graph_for_test(
        GraphAnalysisResult(
            nodes=[
                GraphNode(localId="caller", nodeKind="CALLABLE", name="Caller.run", lineStart=1, lineEnd=1, confidence=1.0),
                GraphNode(localId="target", nodeKind="CALLABLE", name="Target.run", lineStart=1, lineEnd=1, confidence=1.0),
            ],
            edges=[
                GraphEdge(
                    localId="caller-target",
                    fromNodeLocalId="caller",
                    toNodeLocalId="target",
                    edgeType="CALLS",
                    resolutionStatus="RESOLVED",
                    confidence=1.0,
                    evidence=[GraphEvidenceRef(lineStart=1, lineEnd=1, text="http call")],
                    metadata={
                        "transportKind": "HTTP",
                        "httpMethod": "POST",
                        "routeTemplate": "/api/v1/registrations",
                        "operationIdentity": "HTTP POST /api/v1/registrations",
                        "interfaceIdentity": "AuthRegistrationApi.register",
                        "requestContractIdentity": "RegistrationRequest",
                        "responseContractIdentity": "RegistrationResponse",
                        "targetServiceIdentity": "auth-service",
                    },
                )
            ],
        ),
        content="class Caller {}\n",
    )

    metadata = graph["edges"][0]["metadata"]
    assert metadata["transportKind"] == "HTTP"
    assert metadata["httpMethod"] == "POST"
    assert metadata["routeTemplate"] == "/api/v1/registrations"
    assert metadata["operationIdentity"] == "HTTP POST /api/v1/registrations"
    assert metadata["interfaceIdentity"] == "AuthRegistrationApi.register"
    assert metadata["requestContractIdentity"] == "RegistrationRequest"
    assert metadata["responseContractIdentity"] == "RegistrationResponse"
    assert metadata["targetServiceIdentity"] == "auth-service"


def _static_java_graph_result_for_test(content: str, file_id: int, relative_path: str, source_id: str = "edge-gateway"):
    file_metadata = StructuralFileMetadata(
        source_id=source_id,
        inventory_file_id=file_id,
        relative_path=relative_path,
        language="java",
        flow_domain="CODE",
        content_hash=hashlib.sha256(content.encode("utf-8")).hexdigest(),
        line_count=len(content.splitlines()),
        decode_policy="utf-8:replace",
    )
    structural = JavaParserAdapter().parse(content, file_metadata)
    return StaticGraphMaterializer().to_graph(structural)


def _materialize_static_java_for_test(content: str, file_id: int, relative_path: str, source_id: str = "edge-gateway"):
    return materialize_graph_for_test(
        _static_java_graph_result_for_test(content, file_id, relative_path, source_id),
        content=content,
        file_id=file_id,
        relative_path=relative_path,
        source_id=source_id,
    )


def _materialize_static_plus_enrichment_for_test(
    enrichment: GraphAnalysisResult,
    *,
    content: str,
    file_id: int,
    relative_path: str,
):
    static_graph = _static_java_graph_result_for_test(content, file_id, relative_path)
    merged = AnchorAwareGraphValidator().merge(static_graph, enrichment, len(content.splitlines()))
    return materialize_graph_for_test(
        merged,
        content=content,
        file_id=file_id,
        relative_path=relative_path,
    )


def graph_state_for_test(
    content=None,
    relative_path="src/main/java/example/EmailVerificationLinkClientImpl.java",
    source_id="edge-gateway",
    flow_domain="CODE",
):
    content = content or "class EmailVerificationLinkClientImpl {}\n"
    return {
        "source_id": source_id,
        "relative_path": relative_path,
        "content_hash": hashlib.sha256(content.encode("utf-8")).hexdigest(),
        "analyzer_name": "test-analyzer",
        "analyzer_version": "1",
        "flow_domain": flow_domain,
        "status": "ANALYZED",
        "analyzed_at": "now",
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


def override_inventory_classification(store, relative_path, *, flow_domain, language=None, extension=None):
    assignments = ["flow_domain = ?"]
    params = [flow_domain]
    if language is not None:
        assignments.append("language = ?")
        params.append(language)
    if extension is not None:
        assignments.append("extension = ?")
        params.append(extension)
    params.append(relative_path)
    with sqlite3.connect(store.db_path) as conn:
        cursor = conn.execute(f"UPDATE files SET {', '.join(assignments)} WHERE relative_path = ?", params)
        assert cursor.rowcount == 1


def runtime_row(relative_path, content, *, flow_domain="UNKNOWN", language=None, file_id=1, root=None):
    extension = Path(relative_path).suffix.lower()
    return {
        "id": file_id,
        "source_id": "edge-gateway",
        "source_path": str(root or "/workspace/edge-gateway"),
        "absolute_path": str((root or Path("/workspace/edge-gateway")) / relative_path),
        "relative_path": relative_path,
        "display_name": "Edge Gateway",
        "group_name": "edge",
        "tags_json": '["java"]',
        "extension": extension,
        "language": language or extension.lstrip(".") or "unknown",
        "flow_domain": flow_domain,
        "size_bytes": len(content.encode("utf-8")),
        "content_hash": hashlib.sha256(content.encode("utf-8")).hexdigest(),
        "decode_policy": "utf-8:replace",
    }


async def run_runtime(relative_path, content, *, policy=None, analyzer=None, registry=None, flow_domain="UNKNOWN", language=None):
    loaded_policy = policy or load_analysis_policy(POLICY_PATH)
    runtime = AnalyzerRuntime(loaded_policy, extractor_registry=registry)
    runtime_analyzer = analyzer or CapturingGraphAnalyzer()
    row = runtime_row(relative_path, content, flow_domain=flow_domain, language=language)
    result = await runtime.execute(row, {}, content.splitlines(), runtime_analyzer, runtime_retry)
    return result, runtime_analyzer


async def runtime_retry(analyzer, payload, line_count):
    result = analyzer.analyze(payload, line_count)
    return (
        result,
        [],
        {
            "attempt_count": 1,
            "last_attempt_at": "now",
            "last_error_code": None,
            "last_error_message": None,
            "last_raw_response_preview": None,
        },
    )


def payload_top_level_shape():
    return {
        "sourceId",
        "relativePath",
        "targetRef",
        "targetKind",
        "requestKind",
        "schemaVersion",
        "budgetChars",
        "llmInput",
        "analysisPolicy",
        "_refToStableKey",
        "_stableKeyToRef",
        "_refToKind",
        "_targetIndex",
        "_targetCount",
    }


def current_graph_nodes(store, source_id="edge-gateway", flow_domain="CODE", page_size=100):
    analysis_store = AnalysisStore(store.db_path)
    manifest = analysis_store.graph_manifest(source_id, flow_domain)
    nodes = []
    cursor = None
    while manifest["graphId"]:
        page = analysis_store.graph_nodes(manifest["graphRevision"], cursor, page_size, source_id, flow_domain)
        nodes.extend(page["items"])
        if page["complete"]:
            break
        cursor = page["nextCursor"]
    return manifest, nodes


def current_graph_edges(store, source_id="edge-gateway", flow_domain="CODE", page_size=100):
    analysis_store = AnalysisStore(store.db_path)
    manifest = analysis_store.graph_manifest(source_id, flow_domain)
    edges = []
    cursor = None
    while manifest["graphId"]:
        page = analysis_store.graph_edges(manifest["graphRevision"], cursor, page_size, source_id, flow_domain)
        edges.extend(page["items"])
        if page["complete"]:
            break
        cursor = page["nextCursor"]
    return manifest, edges


def current_graph_node_detail_by_name(store, name, source_id="edge-gateway", flow_domain="CODE", include_evidence=False):
    manifest, nodes = current_graph_nodes(store, source_id, flow_domain)
    node = next(item for item in nodes if item["name"] == name)
    return AnalysisStore(store.db_path).graph_node_detail(manifest["graphRevision"], node["id"], source_id, include_evidence)["item"]


def current_graph_fact_counts(db_path, source_id="edge-gateway"):
    with sqlite3.connect(db_path) as conn:
        return {
            "nodes": conn.execute("SELECT COUNT(*) FROM analysis_graph_nodes WHERE source_id = ?", (source_id,)).fetchone()[0],
            "edges": conn.execute("SELECT COUNT(*) FROM analysis_graph_edges WHERE source_id = ?", (source_id,)).fetchone()[0],
            "claims": conn.execute("SELECT COUNT(*) FROM analysis_graph_claims WHERE source_id = ?", (source_id,)).fetchone()[0],
            "evidence": conn.execute("SELECT COUNT(*) FROM analysis_graph_evidence WHERE source_id = ?", (source_id,)).fetchone()[0],
            "diagnostics": conn.execute("SELECT COUNT(*) FROM analysis_graph_diagnostics WHERE source_id = ?", (source_id,)).fetchone()[0],
        }


def semantic_cache_counts(db_path, source_id="edge-gateway"):
    with sqlite3.connect(db_path) as conn:
        return {
            "semantic_documents": conn.execute("SELECT COUNT(*) FROM semantic_documents WHERE source_id = ?", (source_id,)).fetchone()[0],
            "semantic_vectors": conn.execute("SELECT COUNT(*) FROM semantic_vectors WHERE source_id = ?", (source_id,)).fetchone()[0],
        }


def semantic_document_texts(db_path, source_id="edge-gateway"):
    with sqlite3.connect(db_path) as conn:
        return [
            row[0]
            for row in conn.execute(
                "SELECT text FROM semantic_documents WHERE source_id = ? ORDER BY node_id",
                (source_id,),
            ).fetchall()
        ]


def build_semantic_cache(db_path, source_id="edge-gateway"):
    return SemanticIndexBuilder(
        db_path,
        FakeDeterministicEmbeddingProvider(dimension=8),
        config=SemanticBuildConfig(batch_size=10),
    ).build([source_id], force=True)


def graph_facts_for_path(db_path, relative_path, source_id="edge-gateway"):
    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        return {
            "nodes": conn.execute(
                """
                SELECT *
                FROM analysis_graph_nodes
                WHERE source_id = ?
                  AND relative_path = ?
                ORDER BY node_kind, id
                """,
                (source_id, relative_path),
            ).fetchall(),
            "claims": conn.execute(
                """
                SELECT claim.*
                FROM analysis_graph_claims claim
                JOIN analysis_graph_nodes node
                  ON node.source_id = claim.source_id
                 AND node.id = claim.node_id
                WHERE claim.source_id = ?
                  AND node.relative_path = ?
                ORDER BY claim.id
                """,
                (source_id, relative_path),
            ).fetchall(),
            "evidence": conn.execute(
                """
                SELECT *
                FROM analysis_graph_evidence
                WHERE source_id = ?
                  AND relative_path = ?
                ORDER BY id
                """,
                (source_id, relative_path),
            ).fetchall(),
            "diagnostics": conn.execute(
                """
                SELECT *
                FROM analysis_graph_diagnostics
                WHERE source_id = ?
                  AND relative_path = ?
                ORDER BY code
                """,
                (source_id, relative_path),
            ).fetchall(),
        }


def job_file_diagnostics(db_path, job_id, relative_path="src/main/java/example/ObjectHandler.java"):
    with sqlite3.connect(db_path) as conn:
        row = conn.execute(
            """
            SELECT status, diagnostics_json
            FROM analysis_job_files
            WHERE job_id = ?
              AND relative_path = ?
            """,
            (job_id, relative_path),
        ).fetchone()
    assert row is not None
    return row[0], json.loads(row[1] or "[]")


def _llm_input_from_prompt(prompt: str):
    start = prompt.index(BEGIN_INPUT_MARKER) + len(BEGIN_INPUT_MARKER)
    end = prompt.index(END_INPUT_MARKER, start)
    return json.loads(prompt[start:end].strip())


def _contains_key(value, key_name):
    if isinstance(value, dict):
        return key_name in value or any(_contains_key(item, key_name) for item in value.values())
    if isinstance(value, list):
        return any(_contains_key(item, key_name) for item in value)
    return False


def _capturing_ollama_client(captured, response_factory):
    async def handler(request):
        body = json.loads(request.content.decode("utf-8"))
        captured.append(body)
        llm_input = _llm_input_from_prompt(body["prompt"])
        response = response_factory(llm_input)
        if isinstance(response, str):
            response_text = response
        else:
            response_text = json.dumps(response)
        return httpx.Response(200, json={"response": response_text})

    return OllamaAnalysisClient(
        "http://127.0.0.1:11434",
        "model",
        32768,
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )


def _empty_target_response(llm_input):
    return {
        "claims": [],
        "boundaries": [],
    }


def _target_key(llm_input):
    target = llm_input["targetAnchor"]
    return target.get("kind"), target.get("name")


def _captured_target_calls(captured, *, kind="FILE", name="ObjectHandler.java"):
    result = []
    for body in captured:
        llm_input = _llm_input_from_prompt(body["prompt"])
        if _target_key(llm_input) == (kind, name):
            result.append({"body": body, "prompt": body["prompt"], "llmInput": llm_input})
    return result


def _is_feedback_prompt(prompt):
    return "Target-anchor validation feedback retry." in prompt


def _provider_request_events(store, job_id, *, relative_path="src/main/java/example/ObjectHandler.java"):
    return [
        event
        for event in AnalysisStore(store.db_path).runtime_events(job_id=job_id, relative_path=relative_path, limit=1000)["events"]
        if event["eventType"] == "PROVIDER_REQUEST"
    ]


def _provider_request_events_for_target(store, job_id, target_ref, *, relative_path="src/main/java/example/ObjectHandler.java"):
    return [
        event
        for event in _provider_request_events(store, job_id, relative_path=relative_path)
        if event["metadata"].get("targetRef") == target_ref
    ]


def _validation_errors_from_prompt(prompt):
    marker = "Structured validationErrors:\n"
    if marker not in prompt:
        return []
    raw = prompt.split(marker, 1)[1].split("\nReturn corrected JSON only.", 1)[0]
    return json.loads(raw)


def _previous_attempt_number_from_prompt(prompt):
    marker = "Previous attempt number: "
    if marker not in prompt:
        return None
    tail = prompt.split(marker, 1)[1]
    return int(tail.split(".", 1)[0])


def _invalid_inverted_response(summary="attempt one inverted"):
    return {
        "claims": [
            {
                "claimKind": "RESPONSIBILITY",
                "summary": summary,
                "evidence": [{"lineStart": 3, "lineEnd": 2}],
            }
        ],
        "boundaries": [],
    }


def _invalid_boundary_response(summary="attempt two boundary"):
    return {
        "claims": [
            {
                "claimKind": "RESPONSIBILITY",
                "summary": summary,
                "evidence": [{"lineStart": 3, "lineEnd": 3}],
            }
        ],
        "boundaries": [
            {
                "role": "PROVIDED",
                "evidence": [{"lineStart": 3, "lineEnd": 3}],
                "descriptors": [],
            }
        ],
    }


def _invalid_boundary_only_response(summary="attempt two boundary"):
    return {
        "claims": [],
        "boundaries": [
            {
                "role": "PROVIDED",
                "evidence": [{"lineStart": 1, "lineEnd": 1}],
                "descriptors": [],
            }
        ],
    }


def _target_sequence_response(target_key, responses):
    counts = {}

    def response(llm_input):
        key = _target_key(llm_input)
        counts[key] = counts.get(key, 0) + 1
        if key == target_key:
            index = counts[key] - 1
            if index < len(responses):
                selected = responses[index]
                if callable(selected):
                    return selected(llm_input)
                return selected
        return _empty_target_response(llm_input)

    response.counts = counts
    return response


def _nested_flow_response(llm_input):
    target = llm_input["targetAnchor"]
    response = _empty_target_response(llm_input)
    if target["kind"] != "CALLABLE":
        return response
    evidence = [{"lineStart": target["lineStart"], "lineEnd": target["lineStart"]}]
    response["claims"].append(
        {
            "claimKind": "RESPONSIBILITY",
            "summary": f"Handles {target['name']} flow.",
            "evidence": evidence,
        }
    )
    return response


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
                    "flow_domain": "CODE",
                    "status": status,
                    "analyzed_at": "now",
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
    analysis_store.create_job_files("old-job", [failed_row], {int(failed_row["id"]): "CODE"})
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


def test_analysis_store_rebuilds_incompatible_graph_diagnostics_schema(tmp_path):
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
        assert {"source_id", "analysis_file_id", "file_id", "relative_path", "content_hash"}.issubset(columns)
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
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_diagnostics WHERE code = 'OLD_DIAGNOSTIC'").fetchone()[0] == 0

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
    bad_graph["nodes"].append(dict(bad_graph["nodes"][0], stable_key="edge-gateway|bad.yml|FILE|duplicate"))

    with pytest.raises(KnowledgeError) as raised:
        store.replace_file_graph_analysis(
            1,
            {
                "source_id": "edge-gateway",
                "relative_path": "bad.yml",
                "content_hash": "hash",
                "analyzer_name": "ai-file-analyzer",
                "analyzer_version": "1",
                "flow_domain": "WORKFLOW",
                "status": "ANALYZED",
                "analyzed_at": "now",
                "diagnostics": [],
            },
            bad_graph,
        )

    assert raised.value.code == "ANALYSIS_GRAPH_STORE_FAILED"
    assert raised.value.details["stage"] == "GRAPH_STORE"
    assert raised.value.details["table"] == "analysis_graph_nodes"
    assert raised.value.details["operation"] == "insert_nodes"
    assert "UNIQUE constraint failed: analysis_graph_nodes" in raised.value.details["sqliteMessage"]
    with sqlite3.connect(store.db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_nodes").fetchone()[0] == 0


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


def test_analysis_store_current_schema_initializes_empty_db_idempotently(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    store = AnalysisStore(db_path)

    store.init()
    store.init()

    with sqlite3.connect(db_path) as conn:
        tables = {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type = 'table'").fetchall()}

    assert {"analysis_jobs", "analysis_graph_state", "analysis_graph_nodes", "analysis_graph_edges"}.issubset(tables)


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
            "status": "FAILED",
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


def boundary_graph_result_for_test(*, flow_domain="CODE"):
    evidence = GraphEvidenceRef(lineStart=1, lineEnd=1, text="void run() { remote.create(); }", metadata={"evidenceKind": "BOUNDARY"})
    return GraphAnalysisResult(
        nodes=[
            GraphNode(
                localId="handler",
                nodeKind="CALLABLE",
                name="Handler.run",
                qualifiedName="example.Handler.run",
                lineStart=1,
                lineEnd=1,
                confidence=1.0,
                metadata={"factOrigin": "STATIC", "flowDomain": flow_domain},
            )
        ],
        boundaries=[
            BoundaryFact(
                localId="boundary-shared",
                nodeLocalId="handler",
                role="REQUIRED",
                origin="STATIC",
                confidence=0.91,
                flowDomain=flow_domain,
                evidence=[evidence],
                descriptors=[
                    BoundaryDescriptor(path="custom.scalar", value=" Alpha ", origin="STATIC", confidence=0.91, evidence=[evidence]),
                    BoundaryDescriptor(path="custom.list", value=["one", 2], origin="STATIC", confidence=0.91, evidence=[evidence]),
                    BoundaryDescriptor(
                        path="custom.object",
                        value={"flag": True, "nested": {"id": "A-1"}},
                        origin="STATIC",
                        confidence=0.91,
                        evidence=[evidence],
                    ),
                    BoundaryDescriptor(path="custom.boolean", value=True, origin="STATIC", confidence=0.91, evidence=[evidence]),
                    BoundaryDescriptor(path="operation.name", value="first", origin="STATIC", confidence=0.91, evidence=[evidence]),
                ],
            ),
            BoundaryFact(
                localId="boundary-shared",
                nodeLocalId="handler",
                role="REQUIRED",
                origin="LLM",
                confidence=0.77,
                flowDomain=flow_domain,
                evidence=[evidence],
                descriptors=[
                    BoundaryDescriptor(path="operation.name", value="second", origin="LLM", confidence=0.77, evidence=[evidence]),
                    BoundaryDescriptor(path="arbitrary.deep.key", value="kept", origin="LLM", confidence=0.77, evidence=[evidence]),
                ],
            ),
        ],
    )


def test_boundary_facts_persist_arbitrary_descriptors_without_transport_columns(tmp_path):
    content = "class Handler { void run() { remote.create(); } }\n"
    graph = materialize_graph_for_test(boundary_graph_result_for_test(), content=content)
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()

    store.replace_file_graph_analysis(1, graph_state_for_test(content), graph)

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        boundary_columns = {row["name"] for row in conn.execute("PRAGMA table_info(analysis_graph_boundaries)").fetchall()}
        descriptor_columns = {row["name"] for row in conn.execute("PRAGMA table_info(analysis_graph_boundary_descriptors)").fetchall()}
        boundary = conn.execute("SELECT * FROM analysis_graph_boundaries").fetchone()
        descriptors = conn.execute(
            """
            SELECT descriptor_path, value_type, value_json, origin
            FROM analysis_graph_boundary_descriptors
            ORDER BY descriptor_path, origin, value_json
            """
        ).fetchall()
        index_rows = conn.execute(
            """
            SELECT descriptor_path, value_type, normalized_scalar_value
            FROM analysis_graph_boundary_descriptor_index
            ORDER BY descriptor_path, normalized_scalar_value
            """
        ).fetchall()
        evidence_count = conn.execute("SELECT COUNT(*) FROM analysis_graph_boundary_evidence").fetchone()[0]
        descriptor_evidence_count = conn.execute("SELECT COUNT(*) FROM analysis_graph_boundary_descriptor_evidence").fetchone()[0]

    forbidden_columns = {"method", "route", "topic", "schedule", "service_name", "client_class", "controller_class"}
    assert not (boundary_columns & forbidden_columns)
    assert not (descriptor_columns & forbidden_columns)
    assert boundary is not None
    envelope = json.loads(boundary["descriptor_json"])
    assert len(envelope) == 7
    assert json.loads(next(row["value_json"] for row in descriptors if row["descriptor_path"] == "custom.list")) == ["one", 2]
    assert json.loads(next(row["value_json"] for row in descriptors if row["descriptor_path"] == "custom.object")) == {
        "flag": True,
        "nested": {"id": "A-1"},
    }
    assert {json.loads(row["value_json"]) for row in descriptors if row["descriptor_path"] == "operation.name"} == {"first", "second"}
    assert {(row["descriptor_path"], row["origin"]) for row in descriptors if row["descriptor_path"] == "operation.name"} == {
        ("operation.name", "STATIC"),
        ("operation.name", "LLM"),
    }
    assert ("arbitrary.deep.key", "STRING", "kept") in {
        (row["descriptor_path"], row["value_type"], row["normalized_scalar_value"]) for row in index_rows
    }
    assert ("custom.boolean", "BOOLEAN", "true") in {
        (row["descriptor_path"], row["value_type"], row["normalized_scalar_value"]) for row in index_rows
    }
    assert ("custom.list[0]", "STRING", "one") in {
        (row["descriptor_path"], row["value_type"], row["normalized_scalar_value"]) for row in index_rows
    }
    assert ("custom.object.nested.id", "STRING", "a-1") in {
        (row["descriptor_path"], row["value_type"], row["normalized_scalar_value"]) for row in index_rows
    }
    assert evidence_count == 2
    assert descriptor_evidence_count >= len(descriptors)


def test_llm_boundary_lifecycle_fields_are_backend_authoritative(tmp_path):
    content = "class Handler { void run() { remote.create(); } }\n"
    evidence = GraphEvidenceRef(lineStart=1, lineEnd=1, text="void run() { remote.create(); }")
    static_graph = GraphAnalysisResult(
        nodes=[
            GraphNode(
                localId="handler",
                nodeKind="CALLABLE",
                name="Handler.run",
                lineStart=1,
                lineEnd=1,
                confidence=1.0,
                metadata={"factOrigin": "STATIC", "flowDomain": "WORKFLOW"},
            )
        ]
    )
    enrichment = GraphAnalysisResult(
        boundaries=[
            BoundaryFact(
                localId="llm-forged",
                nodeLocalId="handler",
                role="REQUIRED",
                origin="DERIVED",
                confidence=0.2,
                status="TRUSTED",
                flowDomain="TEST",
                metadata={"factOrigin": "STATIC", "status": "TRUSTED", "flowDomain": "TEST"},
                evidence=[evidence],
                descriptors=[
                    BoundaryDescriptor(
                        path="call.enabled",
                        value=True,
                        valueType="STRING",
                        origin="STATIC",
                        confidence=0.2,
                        evidence=[evidence],
                    )
                ],
            )
        ]
    )
    merged = AnchorAwareGraphValidator().merge(static_graph, enrichment, len(content.splitlines()))
    graph = materialize_graph_for_test(merged, content=content)
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    store.replace_file_graph_analysis(1, graph_state_for_test(content, flow_domain="WORKFLOW"), graph)

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        boundary = conn.execute("SELECT fact_origin, status, flow_domain, metadata_json FROM analysis_graph_boundaries").fetchone()
        descriptor = conn.execute("SELECT origin, value_type, value_json FROM analysis_graph_boundary_descriptors").fetchone()
        envelope = json.loads(boundary["metadata_json"])

    assert boundary["fact_origin"] == "LLM"
    assert boundary["status"] == "CANDIDATE"
    assert boundary["flow_domain"] == "WORKFLOW"
    assert envelope["boundaryIdentity"].startswith("LLM_BOUNDARY:")
    assert descriptor["origin"] == "LLM"
    assert descriptor["value_type"] == "BOOLEAN"
    assert json.loads(descriptor["value_json"]) is True


def test_high_confidence_rejected_llm_boundary_persists_candidate(tmp_path):
    content = "class Handler { void run() { remote.create(); } }\n// outside owner\n"
    outside_owner = GraphEvidenceRef(lineStart=2, lineEnd=2, text="// outside owner")
    static_graph = GraphAnalysisResult(
        nodes=[
            GraphNode(
                localId="handler",
                nodeKind="CALLABLE",
                name="Handler.run",
                lineStart=1,
                lineEnd=1,
                confidence=1.0,
                metadata={"factOrigin": "STATIC", "flowDomain": "WORKFLOW"},
            )
        ]
    )
    enrichment = GraphAnalysisResult(
        boundaries=[
            BoundaryFact(
                localId="llm-rejected",
                nodeLocalId="handler",
                role="REQUIRED",
                origin="LLM",
                confidence=0.95,
                evidence=[outside_owner],
                descriptors=[
                    BoundaryDescriptor(path="call.method", value="create", origin="LLM", confidence=0.95, evidence=[outside_owner]),
                ],
            )
        ]
    )
    merged = AnchorAwareGraphValidator().merge(static_graph, enrichment, len(content.splitlines()))
    graph = materialize_graph_for_test(merged, content=content)
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    store.replace_file_graph_analysis(1, graph_state_for_test(content, flow_domain="WORKFLOW"), graph)

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        boundary = conn.execute(
            "SELECT status, rejection_reason, fact_origin, flow_domain, metadata_json FROM analysis_graph_boundaries"
        ).fetchone()
        descriptor = conn.execute("SELECT origin, status FROM analysis_graph_boundary_descriptors").fetchone()

    assert boundary["status"] == "CANDIDATE"
    assert boundary["rejection_reason"] == "ANALYSIS_GRAPH_BOUNDARY_EVIDENCE_OUTSIDE_OWNER"
    assert boundary["fact_origin"] == "LLM"
    assert boundary["flow_domain"] == "WORKFLOW"
    metadata = json.loads(boundary["metadata_json"])
    assert metadata["lifecycleContributions"][0]["rejectionReason"] == "ANALYSIS_GRAPH_BOUNDARY_EVIDENCE_OUTSIDE_OWNER"
    assert descriptor["origin"] == "LLM"
    assert descriptor["status"] == "CANDIDATE"


def test_boundary_merge_identity_preserves_conflicts_types_evidence_and_order_stability(tmp_path):
    content = "void first() { remote.create(); }\nvoid second() { // llm evidence }\nvoid third() { other.create(); }"
    first_evidence = GraphEvidenceRef(lineStart=1, lineEnd=1, text="void first() { remote.create(); }")
    second_evidence = GraphEvidenceRef(lineStart=2, lineEnd=2, text="void second() { // llm evidence }")
    third_evidence = GraphEvidenceRef(lineStart=3, lineEnd=3, text="void third() { other.create(); }")

    def result(boundaries):
        return GraphAnalysisResult(
            nodes=[
                GraphNode(
                    localId="handler",
                    nodeKind="CALLABLE",
                    name="Handler.run",
                    lineStart=1,
                    lineEnd=3,
                    confidence=1.0,
                    metadata={"factOrigin": "STATIC", "flowDomain": "WORKFLOW"},
                )
            ],
            boundaries=boundaries,
        )

    static_boundary = BoundaryFact(
        localId="static-a",
        identity="boundary:a",
        nodeLocalId="handler",
        role="REQUIRED",
        origin="STATIC",
        confidence=0.91,
        evidence=[first_evidence],
        descriptors=[
            BoundaryDescriptor(path="operation.name", value="first", origin="STATIC", confidence=0.91, evidence=[first_evidence]),
            BoundaryDescriptor(path="operation.name", value="first", origin="STATIC", confidence=0.91, evidence=[first_evidence]),
            BoundaryDescriptor(path="custom.boolean", value=True, valueType="STRING", origin="STATIC", confidence=0.91, evidence=[first_evidence]),
            BoundaryDescriptor(path="custom.number", value=7, origin="STATIC", confidence=0.91, evidence=[first_evidence]),
            BoundaryDescriptor(
                path="custom.object",
                value={"enabled": False, "nested": {"id": "A-1"}},
                origin="STATIC",
                confidence=0.91,
                evidence=[first_evidence],
            ),
            BoundaryDescriptor(path="custom.list", value=["one", 2], origin="STATIC", confidence=0.91, evidence=[first_evidence]),
        ],
    )
    llm_boundary = BoundaryFact(
        localId="llm-a",
        identity="boundary:a",
        nodeLocalId="handler",
        role="REQUIRED",
        origin="LLM",
        confidence=0.95,
        status="CANDIDATE",
        metadata={
            "boundaryIdentity": "boundary:a",
            "lifecycleSource": "BACKEND_VALIDATION",
            "status": "CANDIDATE",
            "rejectionReason": "ANALYSIS_GRAPH_BOUNDARY_EVIDENCE_OUTSIDE_OWNER",
            "flowDomain": "TEST",
            "nonAuthoritative": "ignored",
        },
        evidence=[second_evidence],
        descriptors=[
            BoundaryDescriptor(path="operation.name", value="second", origin="LLM", confidence=0.95, evidence=[second_evidence]),
            BoundaryDescriptor(path="payload.kind", value="event", origin="LLM", confidence=0.95, evidence=[second_evidence]),
            BoundaryDescriptor(path="payload.kind", value="event", origin="LLM", confidence=0.95, evidence=[second_evidence]),
        ],
    )
    independent_boundary = BoundaryFact(
        localId="static-b",
        identity="boundary:b",
        nodeLocalId="handler",
        role="REQUIRED",
        origin="STATIC",
        confidence=0.91,
        evidence=[third_evidence],
        descriptors=[
            BoundaryDescriptor(path="operation.name", value="third", origin="STATIC", confidence=0.91, evidence=[third_evidence]),
        ],
    )
    first_graph = materialize_graph_for_test(result([static_boundary, llm_boundary, independent_boundary]), content=content)
    second_graph = materialize_graph_for_test(result([llm_boundary, static_boundary, independent_boundary]), content=content)

    def persisted_rows(db_path):
        with sqlite3.connect(db_path) as conn:
            conn.row_factory = sqlite3.Row
            return {
                "boundaries": [
                    dict(row)
                    for row in conn.execute(
                        """
                        SELECT id, stable_key, role, confidence, status, rejection_reason,
                               fact_origin, flow_domain, metadata_json, descriptor_json
                        FROM analysis_graph_boundaries
                        ORDER BY id
                        """
                    )
                ],
                "descriptors": [
                    dict(row)
                    for row in conn.execute(
                        """
                        SELECT boundary_id, descriptor_path, value_type, value_json, origin, status
                        FROM analysis_graph_boundary_descriptors
                        ORDER BY boundary_id, descriptor_path, origin, value_json
                        """
                    )
                ],
                "index": [
                    dict(row)
                    for row in conn.execute(
                        """
                        SELECT descriptor_path, value_type, normalized_scalar_value
                        FROM analysis_graph_boundary_descriptor_index
                        ORDER BY descriptor_path, value_type, normalized_scalar_value
                        """
                    )
                ],
                "boundaryEvidence": [
                    dict(row)
                    for row in conn.execute("SELECT boundary_id, evidence_id FROM analysis_graph_boundary_evidence ORDER BY boundary_id, evidence_id")
                ],
                "descriptorEvidence": [
                    dict(row)
                    for row in conn.execute(
                        "SELECT descriptor_id, evidence_id FROM analysis_graph_boundary_descriptor_evidence ORDER BY descriptor_id, evidence_id"
                    )
                ],
            }

    first_store = AnalysisStore(tmp_path / "first.sqlite")
    second_store = AnalysisStore(tmp_path / "second.sqlite")
    first_store.init()
    second_store.init()
    first_store.replace_file_graph_analysis(1, graph_state_for_test(content, flow_domain="WORKFLOW"), first_graph)
    first_store.replace_file_graph_analysis(1, graph_state_for_test(content, flow_domain="WORKFLOW"), first_graph)
    second_store.replace_file_graph_analysis(1, graph_state_for_test(content, flow_domain="WORKFLOW"), second_graph)
    first_rows = persisted_rows(first_store.db_path)
    second_rows = persisted_rows(second_store.db_path)

    assert first_rows == second_rows
    assert len(first_rows["boundaries"]) == 2
    assert {row["stable_key"] for row in first_rows["boundaries"]} == {"boundary:a", "boundary:b"}
    merged_boundary = next(row for row in first_rows["boundaries"] if row["stable_key"] == "boundary:a")
    assert merged_boundary["fact_origin"] == "STATIC"
    assert merged_boundary["status"] == "CANDIDATE"
    assert merged_boundary["rejection_reason"] == "ANALYSIS_GRAPH_BOUNDARY_EVIDENCE_OUTSIDE_OWNER"
    assert merged_boundary["flow_domain"] == "WORKFLOW"
    merged_metadata = json.loads(merged_boundary["metadata_json"])
    assert merged_metadata["originContributors"] == ["LLM", "STATIC"]
    assert "nonAuthoritative" not in merged_metadata
    assert any(
        contribution.get("rejectionReason") == "ANALYSIS_GRAPH_BOUNDARY_EVIDENCE_OUTSIDE_OWNER"
        for contribution in merged_metadata["lifecycleContributions"]
    )
    merged_boundary_id = next(row["id"] for row in first_rows["boundaries"] if row["stable_key"] == "boundary:a")
    merged_descriptors = [row for row in first_rows["descriptors"] if row["boundary_id"] == merged_boundary_id]
    assert len(merged_descriptors) == 7
    assert {json.loads(row["value_json"]) for row in merged_descriptors if row["descriptor_path"] == "operation.name"} == {"first", "second"}
    assert {(row["descriptor_path"], row["origin"]) for row in merged_descriptors if row["descriptor_path"] == "operation.name"} == {
        ("operation.name", "STATIC"),
        ("operation.name", "LLM"),
    }
    assert {row["status"] for row in merged_descriptors if row["origin"] == "LLM"} == {"CANDIDATE"}
    assert {row["status"] for row in merged_descriptors if row["origin"] == "STATIC"} == {"TRUSTED"}
    envelope = json.loads(merged_boundary["descriptor_json"])
    assert {(item["path"], item["origin"], item["valueType"]) for item in envelope if item["path"] == "operation.name"} == {
        ("operation.name", "LLM", "STRING"),
        ("operation.name", "STATIC", "STRING"),
    }
    assert ("custom.boolean", "BOOLEAN", "true") in {
        (row["descriptor_path"], row["value_type"], row["normalized_scalar_value"]) for row in first_rows["index"]
    }
    assert ("custom.number", "NUMBER", "7") in {
        (row["descriptor_path"], row["value_type"], row["normalized_scalar_value"]) for row in first_rows["index"]
    }
    assert ("custom.object.enabled", "BOOLEAN", "false") in {
        (row["descriptor_path"], row["value_type"], row["normalized_scalar_value"]) for row in first_rows["index"]
    }
    assert ("custom.list[1]", "NUMBER", "2") in {
        (row["descriptor_path"], row["value_type"], row["normalized_scalar_value"]) for row in first_rows["index"]
    }
    assert first_rows["boundaryEvidence"]
    assert first_rows["descriptorEvidence"]


def test_local_flow_unit_repository_load_boundaries_preserves_generic_facts_and_currentness(tmp_path):
    content = "class Handler { void run() { remote.create(); } }\n"
    relative_path = "src/main/java/example/ObjectHandler.java"
    inventory_store, _, _ = build_inventory(tmp_path, content=content)
    graph = materialize_graph_for_test(boundary_graph_result_for_test(), content=content, relative_path=relative_path)
    store = AnalysisStore(inventory_store.db_path)
    store.replace_file_graph_analysis(1, graph_state_for_test(content, relative_path), graph)
    node_id = graph["nodes"][0]["id"]
    key = ("edge-gateway", "edge-gateway:query-current-facts", node_id)
    repo = LocalFlowUnitGraphRepository(store)

    loaded = repo.load_boundaries({key}, include_tests=False)
    assert len(loaded) == 1
    current_key, facts = next(iter(loaded.items()))

    assert len(facts) == 1
    assert current_key[0] == key[0]
    assert current_key[2] == key[2]
    assert {fact.role for fact in facts} == {"REQUIRED"}
    assert {fact.provenance for fact in facts} == {"STATIC"}
    descriptor_values = {
        (descriptor.path, json.dumps(descriptor.value, sort_keys=True))
        for fact in facts
        for descriptor in fact.descriptors
    }
    assert ("custom.object", json.dumps({"flag": True, "nested": {"id": "A-1"}}, sort_keys=True)) in descriptor_values
    assert ("operation.name", json.dumps("first")) in descriptor_values
    assert ("operation.name", json.dumps("second")) in descriptor_values
    assert all(fact.evidence for fact in facts)
    assert all(descriptor.evidence for fact in facts for descriptor in fact.descriptors)

    with sqlite3.connect(store.db_path) as conn:
        conn.execute("UPDATE files SET content_hash = 'changed' WHERE source_id = 'edge-gateway'")

    assert repo.load_boundaries({key}, include_tests=False) == {}

    test_inventory_store, _, _ = build_inventory(tmp_path / "test-domain", content=content)
    test_store = AnalysisStore(test_inventory_store.db_path)
    test_store.replace_file_graph_analysis(1, graph_state_for_test(content, relative_path, flow_domain="TEST"), graph)
    with sqlite3.connect(test_store.db_path) as conn:
        conn.execute("UPDATE files SET flow_domain = 'TEST' WHERE source_id = 'edge-gateway'")
    test_repo = LocalFlowUnitGraphRepository(test_store)

    assert test_repo.load_boundaries({key}, include_tests=False) == {}
    loaded_tests = test_repo.load_boundaries({key}, include_tests=True)
    assert len(next(iter(loaded_tests.values()))) == 1


def test_representative_static_analysis_persists_temporary_boundary_flow_sides(tmp_path):
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    cases = [
        (
            "registration-bff",
            1,
            "src/main/java/example/RegistrationBff.java",
            """
class RegistrationBff {
  private final AuthGateway auth;
  void createUser(Object request) {
    auth.createUser(request);
  }
}
""".strip()
            + "\n",
        ),
        (
            "auth-registration",
            2,
            "src/main/java/example/RegistrationController.java",
            """
import org.springframework.web.bind.annotation.PostMapping;

class RegistrationController {
  @PostMapping("/registrations")
  void createUser(Object request) {}
}
""".strip()
            + "\n",
        ),
        (
            "login-bff",
            3,
            "src/main/java/example/LoginBff.java",
            """
class LoginBff {
  private final AuthGateway auth;
  void login(Object request) {
    auth.login(request);
  }
}
""".strip()
            + "\n",
        ),
        (
            "auth-login",
            4,
            "src/main/java/example/LoginController.java",
            """
import org.springframework.web.bind.annotation.PostMapping;

class LoginController {
  @PostMapping("/login")
  void login(Object request) {}
}
""".strip()
            + "\n",
        ),
        (
            "refresh-bff",
            5,
            "src/main/java/example/RefreshBff.java",
            """
class RefreshBff {
  private final AuthGateway auth;
  void refreshAccessToken(Object request) {
    auth.refreshAccessToken(request);
  }
}
""".strip()
            + "\n",
        ),
        (
            "auth-refresh",
            6,
            "src/main/java/example/RefreshController.java",
            """
import org.springframework.web.bind.annotation.PostMapping;

class RefreshController {
  @PostMapping("/token/refresh")
  void refreshAccessToken(Object request) {}
}
""".strip()
            + "\n",
        ),
        (
            "site-bff",
            7,
            "src/main/java/example/SiteBff.java",
            """
class SiteBff {
  private final SiteGateway sites;
  void createSite(Object request) {
    sites.createSite(request);
  }
}
""".strip()
            + "\n",
        ),
        (
            "site-service",
            8,
            "src/main/java/example/SiteController.java",
            """
import org.springframework.web.bind.annotation.PostMapping;

class SiteController {
  @PostMapping("/sites")
  void createSite(Object request) {}
}
""".strip()
            + "\n",
        ),
        (
            "event-producer",
            9,
            "src/main/java/example/UserEventProducer.java",
            """
class UserEventProducer {
  private final EventGateway events;
  void publishUserCreated(Object event) {
    events.publish(event);
  }
}
""".strip()
            + "\n",
        ),
        (
            "event-consumer",
            10,
            "src/main/java/example/UserEventListener.java",
            """
import org.springframework.kafka.annotation.KafkaListener;

class UserEventListener {
  @KafkaListener(topics = "users.created")
  void consume(String payload) {}
}
""".strip()
            + "\n",
        ),
        (
            "scheduled-worker",
            11,
            "src/main/java/example/ScheduledWorker.java",
            """
import org.springframework.scheduling.annotation.Scheduled;

class ScheduledWorker {
  @Scheduled(fixedDelayString = "${jobs.refresh-ms}")
  void runJob() {}
}
""".strip()
            + "\n",
        ),
        (
            "health-service",
            12,
            "src/main/java/example/HealthController.java",
            """
import org.springframework.web.bind.annotation.GetMapping;

class HealthController {
  @GetMapping("/health")
  String health() {
    return "ok";
  }
}
""".strip()
            + "\n",
        ),
    ]

    for source_id, file_id, relative_path, content in cases:
        graph = _materialize_static_java_for_test(content, file_id, relative_path, source_id)
        store.replace_file_graph_analysis(file_id, graph_state_for_test(content, relative_path, source_id), graph)

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        boundary_rows = conn.execute("SELECT id, source_id, role FROM analysis_graph_boundaries ORDER BY source_id, id").fetchall()
        descriptor_rows = conn.execute(
            """
            SELECT boundary_id, descriptor_path, value_json
            FROM analysis_graph_boundary_descriptors
            ORDER BY boundary_id, descriptor_path
            """
        ).fetchall()
        boundary_evidence_count = conn.execute(
            "SELECT COUNT(DISTINCT boundary_id) FROM analysis_graph_boundary_evidence"
        ).fetchone()[0]
        descriptor_evidence_count = conn.execute(
            "SELECT COUNT(DISTINCT descriptor_id) FROM analysis_graph_boundary_descriptor_evidence"
        ).fetchone()[0]

    descriptors_by_boundary = {}
    for row in descriptor_rows:
        descriptors_by_boundary.setdefault(row["boundary_id"], {})[row["descriptor_path"]] = json.loads(row["value_json"])

    def has_boundary(source_id, role, expected):
        return any(
            row["source_id"] == source_id
            and row["role"] == role
            and all(descriptors_by_boundary.get(row["id"], {}).get(path) == value for path, value in expected.items())
            for row in boundary_rows
        )

    assert has_boundary("registration-bff", "REQUIRED", {"call.method": "createUser", "call.receiverTypeHint": "AuthGateway"})
    assert has_boundary("auth-registration", "PROVIDED", {"http.method": "POST", "http.route": "/registrations"})
    assert has_boundary("login-bff", "REQUIRED", {"call.method": "login", "call.receiverTypeHint": "AuthGateway"})
    assert has_boundary("auth-login", "PROVIDED", {"http.method": "POST", "http.route": "/login"})
    assert has_boundary("refresh-bff", "REQUIRED", {"call.method": "refreshAccessToken", "call.receiverTypeHint": "AuthGateway"})
    assert has_boundary("auth-refresh", "PROVIDED", {"http.method": "POST", "http.route": "/token/refresh"})
    assert has_boundary("site-bff", "REQUIRED", {"call.method": "createSite", "call.receiverTypeHint": "SiteGateway"})
    assert has_boundary("site-service", "PROVIDED", {"http.method": "POST", "http.route": "/sites"})
    assert has_boundary("event-producer", "REQUIRED", {"call.method": "publish", "call.receiverTypeHint": "EventGateway"})
    assert has_boundary("event-consumer", "PROVIDED", {"messaging.topic": "users.created"})
    assert has_boundary("scheduled-worker", "PROVIDED", {"provided.kind": "SCHEDULED"})
    assert has_boundary("health-service", "PROVIDED", {"http.method": "GET", "http.route": "/health"})
    assert boundary_evidence_count == len(boundary_rows)
    assert descriptor_evidence_count == len(descriptor_rows)
    assert all(
        "http.route" not in descriptors
        for row in boundary_rows
        if row["source_id"] == "scheduled-worker"
        for descriptors in [descriptors_by_boundary[row["id"]]]
    )


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


def test_current_graph_replace_marks_source_dirty_until_finalized(tmp_path):
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
            "diagnostics": [],
            "mode": "FULL",
        }
    )
    store.replace_file_graph_analysis(1, state, first)
    store.finalize_source_graph("edge-gateway")
    assert store.graph_manifest("edge-gateway", "CODE")["totalNodeCount"] == len(first["nodes"])
    store.update_job("job-1", {"status": "COMPLETED", "completedAt": "done"})
    first_manifest = store.graph_manifest("edge-gateway", "CODE")
    assert first_manifest["graphId"]
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
            "diagnostics": [],
            "mode": "FULL",
        }
    )
    store.replace_file_graph_analysis(1, state, failed)
    store.update_job("job-2", {"status": "FAILED", "completedAt": "failed"})
    dirty_manifest = store.graph_manifest("edge-gateway", "CODE")
    assert dirty_manifest["graphId"] is None
    assert "edge-gateway" in store.dirty_graph_source_ids(["edge-gateway"])

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
            "diagnostics": [],
            "mode": "FULL",
        }
    )
    with pytest.raises(KnowledgeError):
        store.replace_file_graph_analysis(1, state, third)
    third_manifest = store.graph_manifest("edge-gateway", "CODE")
    assert third_manifest["graphId"] is None
    assert third_manifest["totalNodeCount"] == 0
    with sqlite3.connect(store.db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_nodes WHERE source_id = 'edge-gateway'").fetchone()[0] == len(first["nodes"])


def test_graph_storage_rebuilds_incompatible_primary_key_tables(tmp_path):
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
        columns = {row["name"]: row for row in conn.execute("PRAGMA table_info(analysis_graph_nodes)").fetchall()}
        pk = {name: row["pk"] for name, row in columns.items() if row["pk"]}
        assert pk == {"id": 1}
        assert {"source_id", "analysis_file_id", "file_id", "relative_path", "content_hash"}.issubset(columns)
        assert "metadata_json" not in columns
        tables = {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type = 'table'").fetchall()}
        assert not any(name.startswith("graph_") for name in tables)
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_nodes").fetchone()[0] == 0


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


def test_source_graph_finalizer_invokes_injected_resolver():
    class FakeStore:
        def __init__(self):
            self.conn = object()
            self.init_calls = 0

        def init(self):
            self.init_calls += 1

        def _write_with_busy_retry(self, write):
            write(self.conn)

        def mark_source_graph_failed(self, source_id, exc):
            raise AssertionError(f"unexpected finalization failure for {source_id}: {exc}")

    class FakeStateRepository:
        def __init__(self):
            self.status_calls = []

        def set_status_conn(self, conn, source_id, status, updated_at, diagnostics=None):
            self.status_calls.append((conn, source_id, status, updated_at, diagnostics))

    class FakeResolver:
        def __init__(self):
            self.calls = []

        def finalize_source(self, conn, source_id, created_at):
            self.calls.append((conn, source_id, created_at))

    store = FakeStore()
    state_repository = FakeStateRepository()
    resolver = FakeResolver()

    SourceGraphFinalizer(store, state_repository=state_repository, resolver=resolver).finalize_source_graph("edge-gateway")

    assert store.init_calls == 1
    assert len(state_repository.status_calls) == 1
    assert state_repository.status_calls[0][0] is store.conn
    assert state_repository.status_calls[0][1] == "edge-gateway"
    assert state_repository.status_calls[0][2] == "FINALIZING"
    assert state_repository.status_calls[0][4] is None
    assert resolver.calls == [(store.conn, "edge-gateway", state_repository.status_calls[0][3])]


def test_analysis_store_finalization_delegates_once(monkeypatch, tmp_path):
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    calls = []

    class FakeFinalizer:
        def __init__(self, delegated_store):
            self.delegated_store = delegated_store

        def finalize_source_graph(self, source_id):
            calls.append((self.delegated_store, source_id))

    monkeypatch.setattr("knowledge_service.analysis_store.SourceGraphFinalizer", FakeFinalizer)

    store.finalize_source_graph("edge-gateway")

    assert calls == [(store, "edge-gateway")]


def test_graph_state_repository_mark_failed_persists_failed_state(tmp_path):
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()

    GraphStateRepository(store).mark_failed(
        "edge-gateway",
        {"code": "SOURCE_GRAPH_FINALIZATION_FAILED", "message": "boom"},
        "2026-07-15T00:00:00+00:00",
    )

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        row = conn.execute(
            """
            SELECT status, diagnostics_json
            FROM analysis_graph_state
            WHERE source_id = ?
            """,
            ("edge-gateway",),
        ).fetchone()

    assert row is not None
    assert row["status"] == GRAPH_STATE_FAILED
    assert json.loads(row["diagnostics_json"]) == [{"code": "SOURCE_GRAPH_FINALIZATION_FAILED", "message": "boom"}]


def test_graph_state_repository_mark_failed_logs_when_persistence_fails(caplog, monkeypatch):
    class FailingStore:
        def _write_with_busy_retry(self, write):
            raise sqlite3.OperationalError("database is locked")

    monkeypatch.setattr(logging.getLogger("knowledge_service"), "propagate", True)
    caplog.set_level(logging.WARNING, logger="knowledge_service.graph_state_repository")

    GraphStateRepository(FailingStore()).mark_failed(
        "edge-gateway",
        {"code": "SOURCE_GRAPH_FINALIZATION_FAILED", "message": "boom"},
        "2026-07-15T00:00:00+00:00",
    )

    assert "Failed to persist graph FAILED state for source edge-gateway" in caplog.text


def test_source_graph_finalization_runs_once_and_resolves_many_calls_without_sql_n_plus_one(monkeypatch, tmp_path):
    service_store, _, _ = build_inventory_with_file_count(tmp_path / "service-finalize", 3)
    runner = SupervisorHarness(service_store, app_config(tmp_path / "service-finalize"))
    service_finalize_calls = []

    def record_service_finalize(source_id):
        service_finalize_calls.append(source_id)

    monkeypatch.setattr(runner.analysis_store, "finalize_source_graph", record_service_finalize)

    final = wait_job(service_store, runner.start(AnalysisBuildRequest(force=True), StubAnalyzer(GraphAnalysisResult()))["jobId"])

    assert final["status"] == "COMPLETED"
    assert final["processedFileCount"] == 3
    assert service_finalize_calls == ["edge-gateway"]

    call_count = 120
    caller_body = "\n".join("    worker.handle();" for _ in range(call_count))
    caller = f"""package example;

class Dispatcher {{
  private final Worker worker;

  void dispatch() {{
{caller_body}
  }}
}}
"""
    worker = """package example;

class Worker {
  void handle() {}
}
"""
    store = AnalysisStore(tmp_path / "resolver-finalize.sqlite")
    store.init()
    original_finalize = CrossSourceGraphResolver.finalize_source
    original_connect = store._connect
    finalize_calls = []
    trace_active = {"value": False}
    traced_statements = []

    def traced_finalize(self, conn, source_id, created_at):
        finalize_calls.append(source_id)
        trace_active["value"] = True
        try:
            return original_finalize(self, conn, source_id, created_at)
        finally:
            trace_active["value"] = False

    def traced_connect(*args, **kwargs):
        conn = original_connect(*args, **kwargs)
        conn.set_trace_callback(lambda statement: traced_statements.append(statement) if trace_active["value"] else None)
        return conn

    monkeypatch.setattr(CrossSourceGraphResolver, "finalize_source", traced_finalize)
    monkeypatch.setattr(store, "_connect", traced_connect)

    store.replace_file_graph_analysis(
        1,
        graph_state_for_test(caller, "src/main/java/example/Dispatcher.java"),
        _materialize_static_java_for_test(caller, 1, "src/main/java/example/Dispatcher.java"),
    )
    store.replace_file_graph_analysis(
        2,
        graph_state_for_test(worker, "src/main/java/example/Worker.java"),
        _materialize_static_java_for_test(worker, 2, "src/main/java/example/Worker.java"),
    )

    assert finalize_calls == []

    store.finalize_source_graph("edge-gateway")
    first_edges = _resolved_call_edges_for_test(store.db_path)
    select_count = sum(1 for statement in traced_statements if statement.lstrip().upper().startswith("SELECT"))

    assert finalize_calls == ["edge-gateway"]
    assert len(first_edges) == call_count
    assert all(edge["resolution_status"] == "RESOLVED" and edge["to_node_id"] for edge in first_edges)
    assert select_count < call_count

    traced_statements.clear()
    store.finalize_source_graph("edge-gateway")
    second_edges = _resolved_call_edges_for_test(store.db_path)

    assert finalize_calls == ["edge-gateway", "edge-gateway"]
    assert second_edges == first_edges


def test_dirty_source_finalizes_after_supervisor_recovery_with_unchanged_files(tmp_path):
    content = "package example;\npublic class ObjectHandler { public void handle() {} }\n"
    store, _, _ = build_inventory(tmp_path, content=content)
    row = store.search_rows([], [])[0][0]
    analysis_store = AnalysisStore(store.db_path)
    state = graph_state_for_test(content, row["relative_path"])
    state["analyzer_name"] = StubAnalyzer.name
    state["analyzer_version"] = StubAnalyzer.version
    analysis_store.replace_file_graph_analysis(
        int(row["id"]),
        state,
        _materialize_static_java_for_test(content, int(row["id"]), row["relative_path"]),
    )

    assert analysis_store.graph_manifest("edge-gateway", "CODE")["graphId"] is None
    assert analysis_store.dirty_graph_source_ids(["edge-gateway"]) == ["edge-gateway"]

    analyzer = StubAnalyzer()
    runner = SupervisorHarness(store, app_config(tmp_path))
    response = runner.start(AnalysisBuildRequest(force=False), analyzer)
    final = wait_job(store, response["jobId"])
    recovered_store = AnalysisStore(store.db_path)

    assert final["status"] == "COMPLETED"
    assert analyzer.calls == 0
    assert recovered_store.dirty_graph_source_ids(["edge-gateway"]) == []
    assert recovered_store.graph_manifest("edge-gateway", "CODE")["graphId"]


def _resolved_call_edges_for_test(db_path):
    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        return [
            dict(row)
            for row in conn.execute(
                """
                SELECT id, from_node_id, to_node_id, resolution_status, metadata_json
                FROM analysis_graph_edges
                WHERE source_id = 'edge-gateway'
                  AND edge_type = 'CALLS'
                ORDER BY id
                """
            ).fetchall()
        ]


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
    assert "UNIQUE constraint failed: analysis_graph_evidence" in raised.value.details["sqliteMessage"]
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


def test_resolver_uses_first_class_arity_for_overloaded_cross_file_calls(tmp_path):
    controller = """package example;

class Controller {
  private final TicketMapper mapper;

  TicketDto handle(Ticket ticket) {
    return mapper.toApi(ticket);
  }
}

class Ticket {}
class TicketDto {}
"""
    mapper = """package example;

class TicketMapper {
  TicketDto toApi() { return new TicketDto(); }
  TicketDto toApi(Ticket ticket) { return new TicketDto(); }
}

class Ticket {}
class TicketDto {}
"""
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    store.replace_file_graph_analysis(
        1,
        graph_state_for_test(controller, "src/main/java/example/Controller.java"),
        _materialize_static_java_for_test(controller, 1, "src/main/java/example/Controller.java"),
    )
    store.replace_file_graph_analysis(
        2,
        graph_state_for_test(mapper, "src/main/java/example/TicketMapper.java"),
        _materialize_static_java_for_test(mapper, 2, "src/main/java/example/TicketMapper.java"),
    )
    store.finalize_source_graph("edge-gateway")

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        edge = conn.execute(
            """
            SELECT e.resolution_status, e.to_node_id, e.argument_count, e.metadata_json,
                   target.name AS target_name, target.parameter_count
            FROM analysis_graph_edges e
            JOIN analysis_graph_nodes target ON target.id = e.to_node_id
            WHERE e.edge_type = 'CALLS'
              AND e.source_id = 'edge-gateway'
              AND e.argument_count = 1
              AND json_extract(e.metadata_json, '$.methodName') = 'toApi'
            """
        ).fetchone()
        assert edge is not None
        assert edge["resolution_status"] == "RESOLVED"
        assert edge["target_name"] == "toApi"
        assert edge["parameter_count"] == 1
        metadata = json.loads(edge["metadata_json"])
        assert metadata.get("argumentCount") is None
        assert metadata["resolutionReason"] == "FIELD_TYPE_HINT"
        assert "unresolvedReason" not in metadata


def test_resolver_upgrades_cross_file_method_reference_without_stale_unresolved_metadata(tmp_path):
    repository = """package example;

import java.util.Optional;
import java.util.UUID;

class Repo {
  private final Repository repository;
  private final SiteInfraMapper siteInfraMapper;

  Optional<Site> findById(UUID id) {
    return repository.findById(id).map(this.siteInfraMapper::asSite);
  }
}

interface Repository {
  Optional<SiteEntity> findById(UUID id);
}

class Site {}
class SiteEntity {}
"""
    mapper = """package example;

interface SiteInfraMapper {
  Site asSite(SiteEntity entity);
}

class SiteInfraMapperImpl implements SiteInfraMapper {
  public Site asSite(SiteEntity entity) {
    return new Site();
  }
}

class Site {}
class SiteEntity {}
"""
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    store.replace_file_graph_analysis(
        1,
        graph_state_for_test(repository, "src/main/java/example/Repo.java"),
        _materialize_static_java_for_test(repository, 1, "src/main/java/example/Repo.java"),
    )
    store.replace_file_graph_analysis(
        2,
        graph_state_for_test(mapper, "src/main/java/example/SiteInfraMapper.java"),
        _materialize_static_java_for_test(mapper, 2, "src/main/java/example/SiteInfraMapper.java"),
    )
    store.finalize_source_graph("edge-gateway")

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        edge = conn.execute(
            """
            SELECT e.resolution_status, e.to_node_id, e.argument_count, e.metadata_json,
                   e.unresolved_target_json, target.name AS target_name, target.qualified_name AS target_qualified_name, ev.line_start, ev.excerpt
            FROM analysis_graph_edges e
            JOIN analysis_graph_nodes target ON target.id = e.to_node_id
            JOIN analysis_graph_edge_evidence ee ON ee.edge_id = e.id
            JOIN analysis_graph_evidence ev ON ev.id = ee.evidence_id
            WHERE e.edge_type = 'CALLS'
              AND e.source_id = 'edge-gateway'
              AND json_extract(e.metadata_json, '$.methodName') = 'asSite'
              AND json_extract(e.metadata_json, '$.callKind') = 'FIELD_METHOD_REFERENCE'
            """
        ).fetchone()
        assert edge is not None
        assert edge["resolution_status"] == "RESOLVED"
        assert edge["target_name"] == "asSite"
        assert edge["target_qualified_name"] == "example.SiteInfraMapperImpl.asSite"
        assert edge["argument_count"] is None
        assert edge["unresolved_target_json"] is None
        assert edge["line_start"] == 11
        assert edge["excerpt"] == "this.siteInfraMapper::asSite"
        metadata = json.loads(edge["metadata_json"])
        assert metadata["receiverText"] == "this.siteInfraMapper"
        assert metadata["receiverTypeHint"] == "SiteInfraMapper"
        assert metadata["targetTypeText"] == "SiteInfraMapper"
        assert metadata["resolutionReason"] == "INTERFACE_IMPLEMENTATION_DISPATCH"
        assert "unresolvedReason" not in metadata


def test_resolver_dispatches_interface_calls_to_unique_implementation(tmp_path):
    content = """package example;

import org.springframework.web.bind.annotation.RestController;

@RestController
class Controller implements GeneratedApi {
  private final UseCase useCase;

  @Override
  public Response handle(Request request) {
    return useCase.execute(new Command());
  }
}

interface GeneratedApi {
  Response handle(Request request);
}

interface UseCase {
  Response execute(Command command);
}

class UseCaseImpl implements UseCase {
  private final Repository repository;

  public Response execute(Command command) {
    return repository.save(command);
  }
}

interface Repository {
  Response save(Command command);
}

class RepositoryImpl implements Repository {
  public Response save(Command command) {
    return new Response();
  }
}

class Request {}
class Command {}
class Response {}
"""
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    store.replace_file_graph_analysis(
        1,
        graph_state_for_test(content, "src/main/java/example/Controller.java"),
        _materialize_static_java_for_test(content, 1, "src/main/java/example/Controller.java"),
    )
    store.finalize_source_graph("edge-gateway")

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            """
            SELECT caller.qualified_name AS caller, target.qualified_name AS target, e.resolution_status, e.metadata_json
            FROM analysis_graph_edges e
            JOIN analysis_graph_nodes caller ON caller.id = e.from_node_id
            LEFT JOIN analysis_graph_nodes target ON target.id = e.to_node_id
            WHERE e.source_id = 'edge-gateway'
              AND e.edge_type = 'CALLS'
              AND json_extract(e.metadata_json, '$.methodName') IN ('execute', 'save')
            ORDER BY caller.qualified_name, target.qualified_name
            """
        ).fetchall()

    targets = {(row["caller"], row["target"]): row for row in rows}
    assert ("example.Controller.handle", "example.UseCaseImpl.execute") in targets
    assert ("example.UseCaseImpl.execute", "example.RepositoryImpl.save") in targets
    assert all(row["resolution_status"] == "RESOLVED" for row in targets.values())
    assert all(json.loads(row["metadata_json"])["resolutionReason"] == "INTERFACE_IMPLEMENTATION_DISPATCH" for row in targets.values())


def test_resolver_revisits_unresolved_interface_calls_when_implementation_arrives_later(tmp_path):
    api_content = """package example;

class Controller {
  private final UseCase useCase;

  Response handle(Command command) {
    return useCase.execute(command);
  }
}

interface UseCase {
  Response execute(Command command);
}

class Command {}
class Response {}
"""
    implementation_content = """package example;

class UseCaseImpl implements UseCase {
  public Response execute(Command command) {
    return new Response();
  }
}

class Command {}
class Response {}
"""
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    store.replace_file_graph_analysis(
        1,
        graph_state_for_test(api_content, "src/main/java/example/Controller.java"),
        _materialize_static_java_for_test(api_content, 1, "src/main/java/example/Controller.java"),
    )
    store.finalize_source_graph("edge-gateway")

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        unresolved = conn.execute(
            """
            SELECT e.resolution_status, e.to_node_id, e.unresolved_target_json, e.metadata_json
            FROM analysis_graph_edges e
            JOIN analysis_graph_nodes caller ON caller.id = e.from_node_id
            WHERE e.source_id = 'edge-gateway'
              AND e.edge_type = 'CALLS'
              AND caller.qualified_name = 'example.Controller.handle'
              AND json_extract(e.metadata_json, '$.methodName') = 'execute'
            """
        ).fetchone()
    assert unresolved is not None
    assert unresolved["resolution_status"] == "UNRESOLVED"
    assert unresolved["to_node_id"] is None
    assert json.loads(unresolved["unresolved_target_json"])["interfaceType"] == "example.UseCase"

    store.replace_file_graph_analysis(
        2,
        graph_state_for_test(implementation_content, "src/main/java/example/UseCaseImpl.java"),
        _materialize_static_java_for_test(implementation_content, 2, "src/main/java/example/UseCaseImpl.java"),
    )
    store.finalize_source_graph("edge-gateway")

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        resolved = conn.execute(
            """
            SELECT target.qualified_name AS target, e.resolution_status, e.unresolved_target_json, e.metadata_json
            FROM analysis_graph_edges e
            JOIN analysis_graph_nodes caller ON caller.id = e.from_node_id
            LEFT JOIN analysis_graph_nodes target ON target.id = e.to_node_id
            WHERE e.source_id = 'edge-gateway'
              AND e.edge_type = 'CALLS'
              AND caller.qualified_name = 'example.Controller.handle'
              AND json_extract(e.metadata_json, '$.methodName') = 'execute'
            """
        ).fetchone()

    assert resolved is not None
    assert resolved["target"] == "example.UseCaseImpl.execute"
    assert resolved["resolution_status"] == "RESOLVED"
    assert resolved["unresolved_target_json"] is None
    assert json.loads(resolved["metadata_json"])["resolutionReason"] == "INTERFACE_IMPLEMENTATION_DISPATCH"

    store.replace_file_graph_analysis(
        2,
        graph_state_for_test(implementation_content, "src/main/java/example/UseCaseImpl.java"),
        _materialize_static_java_for_test(implementation_content, 2, "src/main/java/example/UseCaseImpl.java"),
    )
    store.finalize_source_graph("edge-gateway")

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        refreshed = conn.execute(
            """
            SELECT target.qualified_name AS target, e.resolution_status, e.unresolved_target_json, e.metadata_json
            FROM analysis_graph_edges e
            JOIN analysis_graph_nodes caller ON caller.id = e.from_node_id
            LEFT JOIN analysis_graph_nodes target ON target.id = e.to_node_id
            WHERE e.source_id = 'edge-gateway'
              AND e.edge_type = 'CALLS'
              AND caller.qualified_name = 'example.Controller.handle'
              AND json_extract(e.metadata_json, '$.methodName') = 'execute'
            """
        ).fetchone()

    assert refreshed is not None
    assert refreshed["target"] == "example.UseCaseImpl.execute"
    assert refreshed["resolution_status"] == "RESOLVED"
    assert refreshed["unresolved_target_json"] is None
    assert json.loads(refreshed["metadata_json"])["resolutionReason"] == "INTERFACE_IMPLEMENTATION_DISPATCH"


def test_resolver_retains_all_interface_implementation_branches(tmp_path):
    content = """package example;

class Controller {
  private final UseCase useCase;

  Response handle(Command command) {
    return useCase.execute(command);
  }
}

interface UseCase {
  Response execute(Command command);
}

class FirstUseCase implements UseCase {
  public Response execute(Command command) { return new Response(); }
}

class SecondUseCase implements UseCase {
  public Response execute(Command command) { return new Response(); }
}

class Command {}
class Response {}
"""
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    store.replace_file_graph_analysis(
        1,
        graph_state_for_test(content, "src/main/java/example/Controller.java"),
        _materialize_static_java_for_test(content, 1, "src/main/java/example/Controller.java"),
    )
    store.finalize_source_graph("edge-gateway")

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            """
            SELECT target.qualified_name AS target, e.resolution_status, e.metadata_json
            FROM analysis_graph_edges e
            JOIN analysis_graph_nodes caller ON caller.id = e.from_node_id
            LEFT JOIN analysis_graph_nodes target ON target.id = e.to_node_id
            WHERE e.source_id = 'edge-gateway'
              AND e.edge_type = 'CALLS'
              AND caller.qualified_name = 'example.Controller.handle'
              AND json_extract(e.metadata_json, '$.methodName') = 'execute'
            ORDER BY target.qualified_name
            """
        ).fetchall()

    assert [row["target"] for row in rows] == ["example.FirstUseCase.execute", "example.SecondUseCase.execute"]
    assert all(row["resolution_status"] == "RESOLVED" for row in rows)
    assert all(json.loads(row["metadata_json"])["resolutionReason"] == "INTERFACE_IMPLEMENTATION_DISPATCH" for row in rows)


def test_resolver_keeps_missing_interface_implementation_as_terminal_unresolved_call(tmp_path):
    content = """package example;

class Service {
  private final GeneratedMapper mapper;

  Dto map(Entity entity) {
    return mapper.asDto(entity);
  }
}

interface GeneratedMapper {
  Dto asDto(Entity entity);
}

class Entity {}
class Dto {}
"""
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    store.replace_file_graph_analysis(
        1,
        graph_state_for_test(content, "src/main/java/example/Service.java"),
        _materialize_static_java_for_test(content, 1, "src/main/java/example/Service.java"),
    )
    store.finalize_source_graph("edge-gateway")

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        edge = conn.execute(
            """
            SELECT e.resolution_status, e.to_node_id, e.unresolved_target_json, e.metadata_json
            FROM analysis_graph_edges e
            JOIN analysis_graph_nodes caller ON caller.id = e.from_node_id
            WHERE e.source_id = 'edge-gateway'
              AND e.edge_type = 'CALLS'
              AND caller.qualified_name = 'example.Service.map'
              AND json_extract(e.metadata_json, '$.methodName') = 'asDto'
            """
        ).fetchone()

    assert edge is not None
    assert edge["resolution_status"] == "UNRESOLVED"
    assert edge["to_node_id"] is None
    assert json.loads(edge["unresolved_target_json"])["qualifiedName"] == "example.GeneratedMapper.asDto"
    metadata = json.loads(edge["metadata_json"])
    assert metadata["resolutionReason"] == "INTERFACE_IMPLEMENTATION_NOT_FOUND"
    assert metadata["unresolvedReason"] == "NO_ANALYZED_IMPLEMENTATION"


def test_resolver_does_not_fake_success_for_same_arity_overloads(tmp_path):
    controller = """package example;

class Controller {
  private final TicketMapper mapper;

  TicketDto handle(Ticket ticket) {
    return mapper.toApi(ticket);
  }
}

class Ticket {}
class TicketDto {}
"""
    mapper = """package example;

class TicketMapper {
  TicketDto toApi(Ticket ticket) { return new TicketDto(); }
  TicketDto toApi(String ticketId) { return new TicketDto(); }
}

class Ticket {}
class TicketDto {}
"""
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    store.replace_file_graph_analysis(
        1,
        graph_state_for_test(controller, "src/main/java/example/Controller.java"),
        _materialize_static_java_for_test(controller, 1, "src/main/java/example/Controller.java"),
    )
    store.replace_file_graph_analysis(
        2,
        graph_state_for_test(mapper, "src/main/java/example/TicketMapper.java"),
        _materialize_static_java_for_test(mapper, 2, "src/main/java/example/TicketMapper.java"),
    )
    store.finalize_source_graph("edge-gateway")

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        edge = conn.execute(
            """
            SELECT resolution_status, to_node_id, argument_count, metadata_json
            FROM analysis_graph_edges
            WHERE edge_type = 'CALLS'
              AND source_id = 'edge-gateway'
              AND argument_count = 1
              AND json_extract(metadata_json, '$.methodName') = 'toApi'
            """
        ).fetchone()
        assert edge is not None
        assert edge["resolution_status"] == "MULTIPLE_CANDIDATES"
        assert edge["to_node_id"] is None
        metadata = json.loads(edge["metadata_json"])
        assert "argumentCount" not in metadata
    assert "resolverSignals" not in metadata


def test_cross_source_interface_override_uses_exact_fqn_and_typed_signature(tmp_path):
    interface_source = """package generated.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/tasks")
public interface TaskApi {
  @PostMapping("/handle")
  ResponseDTO handle(RequestDTO request);
}

class RequestDTO {}
class ResponseDTO {}
"""
    implementation_source = """package service.impl;

import generated.api.TaskApi;
import generated.api.RequestDTO;
import generated.api.ResponseDTO;

public class TaskController implements TaskApi {
  @Override
  public ResponseDTO handle(RequestDTO request) {
    return null;
  }

  public ResponseDTO handle(String request) {
    return null;
  }
}

class TaskWorkflow {
  private final generated.api.TaskApi api;

  TaskWorkflow(generated.api.TaskApi api) {
    this.api = api;
  }

  public ResponseDTO run(RequestDTO request) {
    return api.handle(request);
  }
}
"""
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    store.replace_file_graph_analysis(
        1,
        graph_state_for_test(interface_source, "target/generated-sources/src/main/java/generated/api/TaskApi.java", "app-afesox"),
        _materialize_static_java_for_test(
            interface_source,
            1,
            "target/generated-sources/src/main/java/generated/api/TaskApi.java",
            "app-afesox",
        ),
    )
    store.finalize_source_graph("app-afesox")
    store.replace_file_graph_analysis(
        2,
        graph_state_for_test(implementation_source, "src/main/java/service/impl/TaskController.java", "task-service"),
        _materialize_static_java_for_test(
            implementation_source,
            2,
            "src/main/java/service/impl/TaskController.java",
            "task-service",
        ),
    )
    store.finalize_source_graph("task-service")
    store.finalize_source_graph("task-service")

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        implements_edge = conn.execute(
            """
            SELECT e.resolution_status, target.source_id AS target_source, target.qualified_name AS target_name
            FROM analysis_graph_edges e
            JOIN analysis_graph_nodes target ON target.id = e.to_node_id
            WHERE e.source_id = 'task-service'
              AND e.edge_type = 'IMPLEMENTS'
            """
        ).fetchone()
        override_edges = conn.execute(
            """
            SELECT impl.qualified_name AS implementation_method,
                   impl.parameter_types_json AS implementation_params,
                   iface.source_id AS interface_source,
                   iface.qualified_name AS interface_method,
                   iface.parameter_types_json AS interface_params
            FROM analysis_graph_edges e
            JOIN analysis_graph_nodes impl ON impl.id = e.from_node_id
            JOIN analysis_graph_nodes iface ON iface.id = e.to_node_id
            WHERE e.source_id = 'task-service'
              AND e.edge_type = 'OVERRIDES'
            ORDER BY impl.signature
            """
        ).fetchall()
        inherited_claims = conn.execute(
            """
            SELECT node.qualified_name, node.parameter_types_json, claim.entrypoint_kind,
                   claim.entrypoint_http_method, claim.entrypoint_route, claim.entrypoint_interface_method,
                   claim.entrypoint_execution_kind
            FROM analysis_graph_claims claim
            JOIN analysis_graph_nodes node ON node.id = claim.node_id
            WHERE claim.source_id = 'task-service'
              AND claim.claim_kind = 'ENTRYPOINT_HINT'
              AND claim.status = 'DERIVED'
            ORDER BY node.signature
            """
        ).fetchall()
        inherited_boundaries = conn.execute(
            """
            SELECT boundary.id AS boundary_id, boundary.status, boundary.fact_origin,
                   boundary.descriptor_json, descriptor.id AS descriptor_id,
                   descriptor.origin AS descriptor_origin, descriptor.descriptor_path,
                   descriptor.value_type, descriptor.value_json
            FROM analysis_graph_boundaries boundary
            JOIN analysis_graph_nodes node ON node.id = boundary.node_id
            JOIN analysis_graph_boundary_descriptors descriptor ON descriptor.boundary_id = boundary.id
            WHERE boundary.source_id = 'task-service'
              AND node.qualified_name = 'service.impl.TaskController.handle'
              AND boundary.status = 'DERIVED'
            ORDER BY descriptor.descriptor_path
            """
        ).fetchall()
        inherited_indexes = conn.execute(
            """
            SELECT idx.descriptor_path, idx.value_type, idx.normalized_scalar_value
            FROM analysis_graph_boundary_descriptor_index idx
            JOIN analysis_graph_boundaries boundary ON boundary.id = idx.boundary_id
            JOIN analysis_graph_nodes node ON node.id = boundary.node_id
            WHERE boundary.source_id = 'task-service'
              AND node.qualified_name = 'service.impl.TaskController.handle'
              AND boundary.status = 'DERIVED'
            ORDER BY idx.descriptor_path, idx.value_type, idx.normalized_scalar_value
            """
        ).fetchall()
        inherited_boundary_evidence_count = conn.execute(
            """
            SELECT COUNT(*)
            FROM analysis_graph_boundary_evidence evidence
            JOIN analysis_graph_boundaries boundary ON boundary.id = evidence.boundary_id
            JOIN analysis_graph_nodes node ON node.id = boundary.node_id
            WHERE boundary.source_id = 'task-service'
              AND node.qualified_name = 'service.impl.TaskController.handle'
              AND boundary.status = 'DERIVED'
            """
        ).fetchone()[0]
        inherited_descriptor_evidence_count = conn.execute(
            """
            SELECT COUNT(*)
            FROM analysis_graph_boundary_descriptor_evidence evidence
            JOIN analysis_graph_boundary_descriptors descriptor ON descriptor.id = evidence.descriptor_id
            JOIN analysis_graph_boundaries boundary ON boundary.id = descriptor.boundary_id
            JOIN analysis_graph_nodes node ON node.id = boundary.node_id
            WHERE boundary.source_id = 'task-service'
              AND node.qualified_name = 'service.impl.TaskController.handle'
              AND boundary.status = 'DERIVED'
            """
        ).fetchone()[0]
        dispatch_edge = conn.execute(
            """
            SELECT caller.qualified_name AS caller_method,
                   target.source_id AS target_source,
                   target.qualified_name AS target_method,
                   e.resolution_status,
                   e.metadata_json
            FROM analysis_graph_edges e
            JOIN analysis_graph_nodes caller ON caller.id = e.from_node_id
            JOIN analysis_graph_nodes target ON target.id = e.to_node_id
            WHERE e.source_id = 'task-service'
              AND e.edge_type = 'CALLS'
              AND caller.qualified_name = 'service.impl.TaskWorkflow.run'
              AND json_extract(e.metadata_json, '$.methodName') = 'handle'
            """
        ).fetchone()
        interface_claim = conn.execute(
            """
            SELECT claim.entrypoint_execution_kind
            FROM analysis_graph_claims claim
            JOIN analysis_graph_nodes node ON node.id = claim.node_id
            WHERE claim.source_id = 'app-afesox'
              AND node.qualified_name = 'generated.api.TaskApi.handle'
              AND claim.claim_kind = 'ENTRYPOINT_HINT'
            """
        ).fetchone()

    assert implements_edge is not None
    assert implements_edge["resolution_status"] == "RESOLVED"
    assert implements_edge["target_source"] == "app-afesox"
    assert implements_edge["target_name"] == "generated.api.TaskApi"
    assert len(override_edges) == 1
    assert override_edges[0]["implementation_method"] == "service.impl.TaskController.handle"
    assert json.loads(override_edges[0]["implementation_params"]) == ["RequestDTO"]
    assert override_edges[0]["interface_source"] == "app-afesox"
    assert override_edges[0]["interface_method"] == "generated.api.TaskApi.handle"
    assert json.loads(override_edges[0]["interface_params"]) == ["RequestDTO"]
    assert len(inherited_claims) == 1
    assert inherited_claims[0]["qualified_name"] == "service.impl.TaskController.handle"
    assert json.loads(inherited_claims[0]["parameter_types_json"]) == ["RequestDTO"]
    assert inherited_claims[0]["entrypoint_kind"] == "HTTP"
    assert inherited_claims[0]["entrypoint_http_method"] == "POST"
    assert inherited_claims[0]["entrypoint_route"] == "/tasks/handle"
    assert inherited_claims[0]["entrypoint_interface_method"] == "generated.api.TaskApi.handle"
    assert inherited_claims[0]["entrypoint_execution_kind"] == "EXECUTABLE"
    assert inherited_boundaries
    assert len({row["boundary_id"] for row in inherited_boundaries}) == 1
    assert len({(row["descriptor_path"], row["value_json"]) for row in inherited_boundaries}) == len(inherited_boundaries)
    assert {row["status"] for row in inherited_boundaries} == {"DERIVED"}
    assert {row["fact_origin"] for row in inherited_boundaries} == {"DERIVED"}
    assert {row["descriptor_origin"] for row in inherited_boundaries} == {"DERIVED"}
    def inferred_value_type(value):
        if value is None:
            return "NULL"
        if isinstance(value, bool):
            return "BOOLEAN"
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return "NUMBER"
        if isinstance(value, str):
            return "STRING"
        if isinstance(value, list):
            return "LIST"
        return "OBJECT"

    assert all(row["value_type"] == inferred_value_type(json.loads(row["value_json"])) for row in inherited_boundaries)
    descriptor_envelope = json.loads(inherited_boundaries[0]["descriptor_json"])
    assert descriptor_envelope
    assert {item["origin"] for item in descriptor_envelope} == {"DERIVED"}
    assert all(item["valueType"] == inferred_value_type(item["value"]) for item in descriptor_envelope)
    inherited_boundary_descriptors = {row["descriptor_path"]: json.loads(row["value_json"]) for row in inherited_boundaries}
    assert inherited_boundary_descriptors["http.method"] == "POST"
    assert inherited_boundary_descriptors["http.route"] == "/tasks/handle"
    assert ("http.method", "STRING", "post") in {
        (row["descriptor_path"], row["value_type"], row["normalized_scalar_value"]) for row in inherited_indexes
    }
    assert ("http.route", "STRING", "/tasks/handle") in {
        (row["descriptor_path"], row["value_type"], row["normalized_scalar_value"]) for row in inherited_indexes
    }
    assert inherited_boundary_evidence_count > 0
    assert inherited_descriptor_evidence_count >= len(inherited_boundaries)
    assert interface_claim is not None
    assert interface_claim["entrypoint_execution_kind"] == "CONTRACT_DECLARATION"
    assert dispatch_edge is not None
    assert dispatch_edge["resolution_status"] == "RESOLVED"
    assert dispatch_edge["target_source"] == "task-service"
    assert dispatch_edge["target_method"] == "service.impl.TaskController.handle"
    dispatch_metadata = json.loads(dispatch_edge["metadata_json"])
    assert dispatch_metadata["interfaceMethod"] == "generated.api.TaskApi.handle"
    assert dispatch_metadata["resolutionReason"] == "INTERFACE_IMPLEMENTATION_DISPATCH"


def test_cross_source_graph_resolver_can_be_unit_tested_directly(tmp_path):
    interface_source = """package generated.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/sites")
public interface SiteApi {
  @GetMapping("/{id}")
  String getSite(String id);
}
"""
    implementation_source = """package service.impl;

import generated.api.SiteApi;

public class SiteController implements SiteApi {
  @Override
  public String getSite(String id) {
    return id;
  }
}
"""
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    store.replace_file_graph_analysis(
        1,
        graph_state_for_test(interface_source, "target/generated-sources/src/main/java/generated/api/SiteApi.java", "app-afesox"),
        _materialize_static_java_for_test(
            interface_source,
            1,
            "target/generated-sources/src/main/java/generated/api/SiteApi.java",
            "app-afesox",
        ),
    )
    store.replace_file_graph_analysis(
        2,
        graph_state_for_test(implementation_source, "src/main/java/service/impl/SiteController.java", "site-service"),
        _materialize_static_java_for_test(
            implementation_source,
            2,
            "src/main/java/service/impl/SiteController.java",
            "site-service",
        ),
    )
    resolver = CrossSourceGraphResolver(store)

    def finalize(conn):
        resolver.finalize_source(conn, "app-afesox", "2026-07-15T00:00:00+00:00")
        resolver.finalize_source(conn, "site-service", "2026-07-15T00:00:01+00:00")

    store._write_with_busy_retry(finalize)

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        override_edge = conn.execute(
            """
            SELECT impl.qualified_name AS implementation_method,
                   iface.qualified_name AS interface_method
            FROM analysis_graph_edges edge
            JOIN analysis_graph_nodes impl ON impl.id = edge.from_node_id
            JOIN analysis_graph_nodes iface ON iface.id = edge.to_node_id
            WHERE edge.source_id = 'site-service'
              AND edge.edge_type = 'OVERRIDES'
            """
        ).fetchone()
        inherited_claim = conn.execute(
            """
            SELECT node.qualified_name, claim.entrypoint_http_method, claim.entrypoint_route,
                   claim.entrypoint_interface_method, claim.entrypoint_execution_kind
            FROM analysis_graph_claims claim
            JOIN analysis_graph_nodes node ON node.id = claim.node_id
            WHERE claim.source_id = 'site-service'
              AND claim.claim_kind = 'ENTRYPOINT_HINT'
              AND claim.status = 'DERIVED'
            """
        ).fetchone()

    assert override_edge is not None
    assert override_edge["implementation_method"] == "service.impl.SiteController.getSite"
    assert override_edge["interface_method"] == "generated.api.SiteApi.getSite"
    assert inherited_claim is not None
    assert inherited_claim["qualified_name"] == "service.impl.SiteController.getSite"
    assert inherited_claim["entrypoint_http_method"] == "GET"
    assert inherited_claim["entrypoint_route"] == "/sites/{id}"
    assert inherited_claim["entrypoint_interface_method"] == "generated.api.SiteApi.getSite"
    assert inherited_claim["entrypoint_execution_kind"] == "EXECUTABLE"


def test_cross_source_incoming_edge_is_not_traversed_by_local_flow_explorer(tmp_path):
    app_source = """package app.afesox;

public class SiteUseCase {
  public void createSite() {
  }
}
"""
    service_source = """package service.api;

import app.afesox.SiteUseCase;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/sites")
public class SiteController {
  private final SiteUseCase useCase;

  @PostMapping
  public void create() {
    useCase.createSite();
  }
}
"""
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    InventoryStore(store.db_path).init()
    store.init()
    now = "now"
    with sqlite3.connect(store.db_path) as conn:
        for source_id, display_name in (("app-afesox", "App AFESOX"), ("site-service", "Site Service")):
            conn.execute(
                """
                INSERT OR REPLACE INTO sources(source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at)
                VALUES (?, ?, 'test', '.', 1, '[]', '{}', ?)
                """,
                (source_id, display_name, now),
            )
        for file_id, source_id, relative_path, content in (
            (1, "app-afesox", "src/main/java/app/afesox/SiteUseCase.java", app_source),
            (2, "site-service", "src/main/java/service/api/SiteController.java", service_source),
        ):
            conn.execute(
                """
                INSERT OR REPLACE INTO files(
                    id, source_id, source_path, absolute_path, relative_path, extension, language, flow_domain,
                    size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at
                )
                VALUES (?, ?, '.', '.', ?, '.java', 'java', 'CODE', ?, ?, ?, ?, 'utf-8:replace', ?)
                """,
                (
                    file_id,
                    source_id,
                    relative_path,
                    len(content.encode("utf-8")),
                    hashlib.sha256(content.encode("utf-8")).hexdigest(),
                    now,
                    len(content.splitlines()),
                    now,
                ),
            )
    store.replace_file_graph_analysis(
        1,
        graph_state_for_test(app_source, "src/main/java/app/afesox/SiteUseCase.java", "app-afesox"),
        _materialize_static_java_for_test(app_source, 1, "src/main/java/app/afesox/SiteUseCase.java", "app-afesox"),
    )
    store.finalize_source_graph("app-afesox")
    store.replace_file_graph_analysis(
        2,
        graph_state_for_test(service_source, "src/main/java/service/api/SiteController.java", "site-service"),
        _materialize_static_java_for_test(service_source, 2, "src/main/java/service/api/SiteController.java", "site-service"),
    )
    store.finalize_source_graph("site-service")

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        app_state = conn.execute("SELECT graph_id, content_identity FROM analysis_graph_state WHERE source_id = 'app-afesox'").fetchone()
        app_callable = conn.execute(
            """
            SELECT id, stable_key, node_kind, display_name, qualified_name
            FROM analysis_graph_nodes
            WHERE source_id = 'app-afesox'
              AND qualified_name = 'app.afesox.SiteUseCase.createSite'
            """
        ).fetchone()
        call_edge = conn.execute(
            """
            SELECT caller.source_id AS caller_source,
                   caller.qualified_name AS caller_method,
                   target.source_id AS target_source,
                   target.qualified_name AS target_method,
                   edge.resolution_status
            FROM analysis_graph_edges edge
            JOIN analysis_graph_nodes caller ON caller.source_id = edge.source_id AND caller.id = edge.from_node_id
            JOIN analysis_graph_nodes target ON target.id = edge.to_node_id
            WHERE edge.edge_type = 'CALLS'
              AND target.qualified_name = 'app.afesox.SiteUseCase.createSite'
            """
        ).fetchone()

    assert app_state is not None
    assert app_callable is not None
    assert call_edge is not None
    assert call_edge["caller_source"] == "site-service"
    assert call_edge["target_source"] == "app-afesox"
    assert call_edge["resolution_status"] == "RESOLVED"

    anchor = KnowledgeQueryMatchedNode(
        sourceId="app-afesox",
        nodeId=app_callable["id"],
        stableKey=app_callable["stable_key"],
        nodeKind=app_callable["node_kind"],
        label=app_callable["display_name"],
        qualifiedName=app_callable["qualified_name"],
        score=1.0,
        matchReasons=["EXACT"],
        graphId=app_state["graph_id"],
        graphRevision=app_state["content_identity"],
    )
    result = LocalFlowUnitEngine(LocalFlowUnitGraphRepository(store)).build([anchor], include_tests=False)

    assert len(result.local_units) == 1
    unit = result.local_units[0]
    assert unit.roots[0].node.source_id == "app-afesox"
    assert unit.roots[0].node.qualified_name == "app.afesox.SiteUseCase.createSite"
    assert unit.roots[0].origin is LocalFlowRootOrigin.INFERRED_ROOT
    assert {node.source_id for node in unit.execution_nodes} == {"app-afesox"}
    assert unit.execution_transitions == ()
    assert unit.topology_boundaries == ()
    with sqlite3.connect(store.db_path) as conn:
        plan_rows = conn.execute(
            """
            EXPLAIN QUERY PLAN
            SELECT e.id
            FROM analysis_graph_edges e
            WHERE e.edge_type = ?
              AND e.status IN (?)
              AND e.to_node_id IN (?)
            ORDER BY e.to_node_id, e.relative_path, e.id
            """,
            ("CALLS", "TRUSTED", app_callable["id"]),
        ).fetchall()
    plan = "\n".join(str(row[-1]) for row in plan_rows)
    assert "idx_analysis_graph_edges_incoming_lookup" in plan
    assert "SCAN analysis_graph_edges" not in plan
    assert "SCAN e" not in plan


def test_resolver_does_not_fallback_when_first_class_arity_mismatches(tmp_path):
    controller = """package example;

class Controller {
  private final TicketMapper mapper;

  TicketDto handle(Ticket ticket) {
    return mapper.toApi(ticket);
  }
}

class Ticket {}
class TicketDto {}
"""
    mapper = """package example;

class TicketMapper {
  TicketDto toApi() { return new TicketDto(); }
}

class TicketDto {}
"""
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    store.replace_file_graph_analysis(
        1,
        graph_state_for_test(controller, "src/main/java/example/Controller.java"),
        _materialize_static_java_for_test(controller, 1, "src/main/java/example/Controller.java"),
    )
    store.replace_file_graph_analysis(
        2,
        graph_state_for_test(mapper, "src/main/java/example/TicketMapper.java"),
        _materialize_static_java_for_test(mapper, 2, "src/main/java/example/TicketMapper.java"),
    )

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        edge = conn.execute(
            """
            SELECT resolution_status, to_node_id, argument_count, metadata_json
            FROM analysis_graph_edges
            WHERE edge_type = 'CALLS'
              AND source_id = 'edge-gateway'
              AND argument_count = 1
              AND json_extract(metadata_json, '$.methodName') = 'toApi'
            """
        ).fetchone()
        assert edge is not None
        assert edge["resolution_status"] == "UNRESOLVED"
        assert edge["to_node_id"] is None
        metadata = json.loads(edge["metadata_json"])
        assert "argumentCount" not in metadata
        assert "parameters" not in metadata


def test_resolver_does_not_use_metadata_method_name_as_target_source(tmp_path):
    controller = """package example;

class Controller {
  private final TicketMapper mapper;

  TicketDto handle(Ticket ticket) {
    return mapper.toApi(ticket);
  }
}

class Ticket {}
class TicketDto {}
"""
    mapper = """package example;

class TicketMapper {
  TicketDto toApi(Ticket ticket) { return new TicketDto(); }
}

class Ticket {}
class TicketDto {}
"""
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    store.replace_file_graph_analysis(
        1,
        graph_state_for_test(controller, "src/main/java/example/Controller.java"),
        _materialize_static_java_for_test(controller, 1, "src/main/java/example/Controller.java"),
    )

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        edge = conn.execute(
            """
            SELECT id, unresolved_target_json
            FROM analysis_graph_edges
            WHERE edge_type = 'CALLS'
              AND source_id = 'edge-gateway'
              AND to_node_id IS NULL
              AND json_extract(metadata_json, '$.methodName') = 'toApi'
            """
        ).fetchone()
        assert edge is not None
        unresolved_target = json.loads(edge["unresolved_target_json"])
        unresolved_target.pop("name", None)
        conn.execute(
            "UPDATE analysis_graph_edges SET unresolved_target_json = ? WHERE id = ?",
            (json.dumps(unresolved_target), edge["id"]),
        )

    store.replace_file_graph_analysis(
        2,
        graph_state_for_test(mapper, "src/main/java/example/TicketMapper.java"),
        _materialize_static_java_for_test(mapper, 2, "src/main/java/example/TicketMapper.java"),
    )

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        edge = conn.execute(
            """
            SELECT resolution_status, to_node_id, unresolved_target_json, metadata_json
            FROM analysis_graph_edges
            WHERE edge_type = 'CALLS'
              AND source_id = 'edge-gateway'
              AND json_extract(metadata_json, '$.methodName') = 'toApi'
            """
        ).fetchone()

    assert edge is not None
    assert json.loads(edge["metadata_json"])["methodName"] == "toApi"
    assert "name" not in json.loads(edge["unresolved_target_json"])
    assert edge["resolution_status"] == "UNRESOLVED"
    assert edge["to_node_id"] is None


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


def test_non_localhost_ollama_base_url_rejected(tmp_path):
    with pytest.raises(KnowledgeError):
        OllamaAnalysisClient("http://example.com:11434", "model", 32768)


def test_ollama_prompt_renders_minimal_target_input_only():
    policy = load_analysis_policy(POLICY_PATH)
    provider = GraphContractProvider(policy=policy)
    contract = provider.resolve("src/Foo.java", "class Foo {}\n")
    llm_input = {
        "schemaVersion": TARGET_INPUT_SCHEMA_VERSION,
        "requestKind": TARGET_REQUEST_KIND,
        "file": {
            "sourceId": "edge-gateway",
            "relativePath": "src/Foo.java",
            "language": "java",
            "lineCount": 1,
                "contentLines": [{"line": 1, "text": "class Foo {}"}],
        },
        "targetAnchor": {"kind": "FILE", "name": "Foo.java", "qualifiedName": None, "lineStart": 1, "lineEnd": 1},
        "contextAnchors": [],
        "allowedValues": {
            "claimKind": list(contract.allowed_claim_kinds),
        },
        "responseShape": {"claims": []},
    }
    payload = {
        "sourceId": "edge-gateway",
        "relativePath": "src/Foo.java",
        "targetRef": "F1",
        "targetKind": "FILE",
        "requestKind": TARGET_REQUEST_KIND,
        "analysisPolicy": contract_payload(contract),
        "llmInput": llm_input,
    }
    client = OllamaAnalysisClient("http://127.0.0.1:11434", "model", 32768)
    try:
        prompt = client._prompt(payload, contract=contract)
    finally:
        asyncio.run(client.aclose())
    rendered_input = _llm_input_from_prompt(prompt)

    assert '"analysisPolicy"' not in prompt
    assert BEGIN_INPUT_MARKER in prompt
    assert END_INPUT_MARKER in prompt
    assert rendered_input["requestKind"] == TARGET_REQUEST_KIND
    assert rendered_input["file"]["contentLines"]
    assert rendered_input["targetAnchor"]["kind"] == "FILE"
    assert set(rendered_input["allowedValues"]) == {"claimKind"}
    assert rendered_input["contextAnchors"] == []
    assert "anchorRegistry" not in rendered_input
    assert "edgeOptions" not in rendered_input
    assert "endpointRules" not in rendered_input
    assert "staticAnchors" not in rendered_input
    assert "callsites" not in rendered_input
    assert not _contains_key(rendered_input, "stableKey")
    response_shape = rendered_input["responseShape"]
    for forbidden in (
        "schemaVersion",
        "localId",
        "targetRef",
        "fromRef",
        "resolutionStatus",
        "confidence",
        "diagnostics",
        "targetStableKey",
        "fromStableKey",
        "toStableKey",
        "knowledge.graph.enrichment.v1",
    ):
        assert not _contains_key(response_shape, forbidden)


def test_large_llm_required_file_fails_without_partial_graph(tmp_path):
    large_content = "x" * (load_analysis_policy(POLICY_PATH).defaults.max_file_chars + 1)
    store, _, _ = build_inventory(
        tmp_path,
        extra_files={"src/main/java/example/LargeFile.java": large_content},
    )
    runner = SupervisorHarness(store, app_config(tmp_path))

    job = runner.start(AnalysisBuildRequest(), StubAnalyzer())
    final = wait_job(store, job["jobId"])
    skipped = AnalysisStore(store.db_path).files(None, "SKIPPED_TOO_LARGE_FOR_AI_ANALYSIS", None, 10, 0)
    failed = AnalysisStore(store.db_path).files(None, "FAILED", "LargeFile", 10, 0)
    facts = graph_facts_for_path(store.db_path, "src/main/java/example/LargeFile.java")

    assert final["status"] == "COMPLETED"
    assert final["failedFiles"] == 1
    assert skipped["total"] == 0
    assert failed["total"] == 1
    assert failed["files"][0]["lastErrorCode"] == "ANALYSIS_FILE_TOO_LARGE"
    assert len(facts["nodes"]) == 0


def test_unchanged_file_not_picked_by_new_job(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    analyzer = StubAnalyzer()
    wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    first_call_count = analyzer.calls
    second = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])

    assert second["fileCount"] == 0
    assert second["processedFiles"] == 0
    assert _legacy_skipped_unchanged_key() not in second
    assert analyzer.calls == first_call_count


def test_failed_llm_analysis_with_unchanged_hash_is_retried_without_force(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    first = wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer(fail=True))["jobId"])
    retry_analyzer = StubAnalyzer()

    second = wait_job(store, runner.start(AnalysisBuildRequest(), retry_analyzer)["jobId"])
    analyzed = AnalysisStore(store.db_path).files(None, "ANALYZED", None, 10, 0)
    failed = AnalysisStore(store.db_path).files(None, "FAILED", None, 10, 0)

    assert first["failedFiles"] == 1
    assert second["fileCount"] == 1
    assert second["processedFiles"] == 1
    assert second["failedFiles"] == 0
    assert retry_analyzer.calls > 0
    assert analyzed["total"] == 1
    assert failed["total"] == 0


def test_failed_retry_preserves_existing_graph_semantic_cache_and_query_results(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    build_semantic_cache(store.db_path)
    before_graph = current_graph_fact_counts(store.db_path)
    before_semantic = semantic_cache_counts(store.db_path)
    assert before_graph["nodes"] > 0
    assert before_graph["edges"] > 0
    assert before_graph["claims"] > 0
    assert before_graph["evidence"] > 0
    assert before_semantic["semantic_documents"] > 0
    assert before_semantic["semantic_vectors"] > 0

    async def fail_runtime(self, row, metadata, content_lines, analyzer, analyze_with_retry):
        raise RuntimeError("runtime failed")

    monkeypatch.setattr(AnalyzerRuntime, "execute", fail_runtime)
    failed = wait_job(store, runner.start(AnalysisBuildRequest(force=True), StubAnalyzer())["jobId"])
    status, diagnostics = job_file_diagnostics(store.db_path, failed["jobId"])

    assert failed["failedFiles"] == 1
    assert status == "FAILED"
    assert {diagnostic["code"] for diagnostic in diagnostics} >= {"ANALYSIS_FILE_FAILED"}
    assert current_graph_fact_counts(store.db_path) == before_graph
    assert semantic_cache_counts(store.db_path) == before_semantic
    with sqlite3.connect(store.db_path) as conn:
        row = conn.execute("SELECT status FROM analysis_files WHERE source_id = 'edge-gateway'").fetchone()
    assert row[0] == "ANALYZED"

    query_service = build_knowledge_query_service(
        AnalysisStore(store.db_path),
        app_config(tmp_path),
        embedding_provider=FakeDeterministicEmbeddingProvider(dimension=8),
    )
    response = query_service.query(knowledge_query_request("ObjectHandler create"))

    assert response.status == "OK"
    assert response.matchedNodes


def test_failed_unreadable_file_preserves_existing_graph_facts(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    build_semantic_cache(store.db_path)
    before_graph = current_graph_fact_counts(store.db_path)
    before_semantic = semantic_cache_counts(store.db_path)

    monkeypatch.setattr(SnippetExtractor, "read_lines", lambda self, absolute_path, source_path: None)
    failed = wait_job(store, runner.start(AnalysisBuildRequest(force=True), StubAnalyzer())["jobId"])
    status, diagnostics = job_file_diagnostics(store.db_path, failed["jobId"])

    assert failed["failedFiles"] == 1
    assert status == "FAILED"
    assert {diagnostic["code"] for diagnostic in diagnostics} == {"FILE_UNREADABLE"}
    assert current_graph_fact_counts(store.db_path) == before_graph
    assert semantic_cache_counts(store.db_path) == before_semantic
    with sqlite3.connect(store.db_path) as conn:
        row = conn.execute("SELECT status FROM analysis_files WHERE source_id = 'edge-gateway'").fetchone()
    assert row[0] == "ANALYZED"


def test_failed_exception_path_preserves_existing_graph_facts(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    build_semantic_cache(store.db_path)
    before_graph = current_graph_fact_counts(store.db_path)
    before_semantic = semantic_cache_counts(store.db_path)

    async def fail_runtime(self, row, metadata, content_lines, analyzer, analyze_with_retry):
        raise RuntimeError("runtime failed")

    monkeypatch.setattr(AnalyzerRuntime, "execute", fail_runtime)
    failed = wait_job(store, runner.start(AnalysisBuildRequest(force=True), StubAnalyzer())["jobId"])
    status, diagnostics = job_file_diagnostics(store.db_path, failed["jobId"])

    assert failed["failedFiles"] == 1
    assert status == "FAILED"
    assert {diagnostic["code"] for diagnostic in diagnostics} >= {"ANALYSIS_FILE_FAILED"}
    assert current_graph_fact_counts(store.db_path) == before_graph
    assert semantic_cache_counts(store.db_path) == before_semantic


def test_failed_first_time_analysis_records_failure_without_graph_facts(tmp_path, monkeypatch):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))

    async def fail_runtime(self, row, metadata, content_lines, analyzer, analyze_with_retry):
        raise RuntimeError("runtime failed")

    monkeypatch.setattr(AnalyzerRuntime, "execute", fail_runtime)
    failed = wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    status, diagnostics = job_file_diagnostics(store.db_path, failed["jobId"])

    assert failed["failedFiles"] == 1
    assert status == "FAILED"
    assert {diagnostic["code"] for diagnostic in diagnostics} >= {"ANALYSIS_FILE_FAILED"}
    assert current_graph_fact_counts(store.db_path) == {"nodes": 0, "edges": 0, "claims": 0, "evidence": 0, "diagnostics": 0}
    assert semantic_cache_counts(store.db_path) == {"semantic_documents": 0, "semantic_vectors": 0}
    with sqlite3.connect(store.db_path) as conn:
        row = conn.execute("SELECT status FROM analysis_files WHERE source_id = 'edge-gateway'").fetchone()
    assert row[0] == "FAILED"
    assert len(AnalysisStore(store.db_path).current_failed_inventory_rows(["edge-gateway"])) == 1


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
                "status": "ANALYZED",
                "diagnostics": [],
            },
        )

    unchanged_ids = analysis_store.unchanged_file_ids(rows, StubAnalyzer.name, StubAnalyzer.version)

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
    assert analyzer.calls > 1
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
    runner.analysis_store.unchanged_file_ids = lambda rows, analyzer_name, analyzer_version: set()

    second = wait_job(store, runner.start(AnalysisBuildRequest(), second_analyzer)["jobId"])

    assert second["fileCount"] == 1
    assert second["processedFiles"] == 1
    assert first_analyzer.calls > 0
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
            StubAnalyzer(GraphAnalysisResult()),
        )["jobId"],
    )
    _, second_nodes = current_graph_nodes(store)

    assert len(first_nodes) == 3
    assert len(second_nodes) == 3
    assert "updated" in {node["name"] for node in second_nodes}
    assert "create" not in {node["name"] for node in second_nodes}


def test_changed_hash_cleanup_deletes_old_graph_and_semantic_facts(tmp_path):
    store, config, service = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    build_semantic_cache(store.db_path)
    assert current_graph_fact_counts(store.db_path)["nodes"] > 0
    assert semantic_cache_counts(store.db_path)["semantic_documents"] > 0

    (service / "src/main/java/example/ObjectHandler.java").write_text("public class ObjectHandler { void changed() {} }\n", encoding="utf-8")
    InventoryBuilder(load_source_config(config), store).build([], [])
    AnalysisStore(store.db_path).cleanup_stale_files(["edge-gateway"])

    assert current_graph_fact_counts(store.db_path) == {"nodes": 0, "edges": 0, "claims": 0, "evidence": 0, "diagnostics": 0}
    assert semantic_cache_counts(store.db_path) == {"semantic_documents": 0, "semantic_vectors": 0}
    state = AnalysisStore(store.db_path).current_analysis_state(["edge-gateway"])
    assert state["pendingFiles"] == 1
    assert state["succeededFiles"] == 0


def test_successful_reanalysis_replaces_graph_facts_and_marks_semantic_pending(tmp_path):
    store, _, _ = build_inventory(tmp_path, content="public class ObjectHandler {\n  public void create() {\n  }\n}\n")
    rows, _ = store.search_rows([], [])
    file_row = rows[0]
    analysis_store = AnalysisStore(store.db_path)
    content = Path(file_row["absolute_path"]).read_text(encoding="utf-8")
    first = _materialize_static_plus_enrichment_for_test(
        responsibility_graph_result(file_claim=True),
        content=content,
        file_id=int(file_row["id"]),
        relative_path=file_row["relative_path"],
    )
    state = graph_state_for_test(content, file_row["relative_path"])
    state["source_id"] = file_row["source_id"]
    state["content_hash"] = file_row["content_hash"]
    analysis_store.replace_file_graph_analysis(int(file_row["id"]), state, first)
    build_semantic_cache(store.db_path)
    before_semantic = semantic_cache_counts(store.db_path)
    assert before_semantic["semantic_documents"] > 0

    second = _materialize_static_plus_enrichment_for_test(
        responsibility_graph_result(method_claim=False, type_claim=False, file_claim=False),
        content=content,
        file_id=int(file_row["id"]),
        relative_path=file_row["relative_path"],
    )
    analysis_store.replace_file_graph_analysis(int(file_row["id"]), state, second)

    after_graph = current_graph_fact_counts(store.db_path)
    assert after_graph["nodes"] == len(second["nodes"])
    assert after_graph["edges"] == len(second["edges"])
    assert after_graph["claims"] == 0
    assert semantic_cache_counts(store.db_path) == {"semantic_documents": 0, "semantic_vectors": 0}
    semantic_state = SemanticIndexStore(store.db_path).status_for_source("edge-gateway")
    assert semantic_state.status.value in {"PENDING", "STALE"}


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
    graph_manifest = AnalysisStore(store.db_path).graph_manifest("edge-gateway", "CODE")

    assert result["fileCount"] == 0
    assert files["total"] == 0
    assert graph_manifest["totalNodeCount"] == 0
    assert graph_manifest["totalEdgeCount"] == 0


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
    assert analyzer.calls > 0
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


def test_caller_supplied_analysis_client_not_closed_after_successful_job(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    analyzer = ClosingTrackingAnalyzer()

    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])

    assert final["status"] == "COMPLETED"
    assert analyzer.calls > 0
    assert analyzer.close_calls == 0
    assert analyzer.aclose_calls == 0


def test_caller_supplied_analysis_client_not_closed_after_failed_job(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    analyzer = ClosingTrackingAnalyzer(fail=True)

    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])

    assert final["status"] == "COMPLETED"
    assert final["failedFiles"] == 1
    assert analyzer.close_calls == 0
    assert analyzer.aclose_calls == 0


def test_caller_supplied_analysis_client_not_closed_after_cancelled_job(tmp_path):
    async def run():
        store, _, _ = build_inventory(tmp_path)
        supervisor = AnalysisSupervisor(store, app_config(tmp_path))
        await supervisor.start_lifespan()
        started = asyncio.Event()
        release = asyncio.Event()
        analyzer = AsyncBlockingClosingAnalyzer(started, release)
        response = await supervisor.start(AnalysisBuildRequest(), analyzer)
        await asyncio.wait_for(started.wait(), timeout=2)
        await supervisor.stop(response["jobId"])
        queue = supervisor._queue
        if queue is not None:
            await asyncio.wait_for(queue.join(), timeout=2)
        await supervisor.shutdown()
        return store, response["jobId"], analyzer

    store, job_id, analyzer = asyncio.run(run())
    final = wait_job(store, job_id)

    assert final["status"] == "STOPPED"
    assert analyzer.calls == 1
    assert analyzer.close_calls == 0
    assert analyzer.aclose_calls == 0


def test_same_caller_supplied_analysis_client_can_be_reused_for_sequential_jobs(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    analyzer = ClosingTrackingAnalyzer()

    first = wait_job(store, runner.start(AnalysisBuildRequest(force=True), analyzer)["jobId"])
    first_call_count = analyzer.calls
    second = wait_job(store, runner.start(AnalysisBuildRequest(force=True), analyzer)["jobId"])

    assert first["status"] == "COMPLETED"
    assert second["status"] == "COMPLETED"
    assert first_call_count > 0
    assert analyzer.calls > first_call_count
    assert analyzer.close_calls == 0
    assert analyzer.aclose_calls == 0


def test_supervisor_configured_analysis_provider_closes_on_shutdown(tmp_path):
    async def run():
        store, _, _ = build_inventory(tmp_path)
        analyzer = ClosingTrackingAnalyzer()
        supervisor = AnalysisSupervisor(store, app_config(tmp_path), analysis_provider=analyzer)
        await supervisor.start_lifespan()
        await supervisor.shutdown()
        return analyzer

    analyzer = asyncio.run(run())

    assert analyzer.close_calls == 0
    assert analyzer.aclose_calls == 1


def test_analysis_jobs_incompatible_schema_is_recreated_without_lifecycle_rows(tmp_path):
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
                diagnostics_json TEXT NOT NULL
            )
        """)
        conn.execute("""
            INSERT INTO analysis_jobs(
                job_id, status, source_count, file_count, processed_file_count,
                skipped_unchanged_file_count, failed_file_count, diagnostics_json
            )
            VALUES ('job-old', 'COMPLETED', 1, 2, 2, 1, 0, '[]')
        """)

    store = AnalysisStore(db_path)
    store.init()
    with sqlite3.connect(db_path) as conn:
        columns = {row[1] for row in conn.execute("PRAGMA table_info(analysis_jobs)").fetchall()}
    job = store.job("job-old")
    store.init()

    assert "skipped_unchanged_file_count" not in columns
    assert "source_ids_json" in columns
    assert "mode" in columns
    assert job is None


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
    assert final["failedFiles"] == 1
    service = read_overview(store.db_path)["sources"][0]
    assert service["analysis"]["processedFiles"] == 1
    assert service["analysis"]["succeededFiles"] == 0
    assert service["analysis"]["failedFiles"] == 1
    assert service["analysis"]["pendingFiles"] == 0
    assert AnalysisStore(store.db_path).files(None, "FAILED", None, 10, 0)["total"] == 1


def test_overview_projection_uses_max_active_progress_without_double_counting(tmp_path):
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

    service = read_overview(store.db_path)["sources"][0]

    assert service["analysis"]["status"] == "RUNNING"
    assert service["analysis"]["processedFiles"] == 1
    assert service["analysis"]["failedFiles"] == 0
    assert service["analysis"]["pendingFiles"] == 0
    assert "currentRelativePath" not in service["analysis"]


def test_overview_projection_active_job_shape_is_minimal(tmp_path):
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

    status = read_overview(store.db_path)
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
    assert analyzer.calls > 1
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
    files = AnalysisStore(store.db_path).files(None, "FAILED", None, 10, 0)

    assert final["failedFiles"] == 1
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
            GraphAnalysisResult(),
        ]
    )
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 3))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])
    files = AnalysisStore(store.db_path).files(None, None, None, 10, 0)
    first = AnalysisStore(store.db_path).files(None, "FAILED", "ObjectHandler", 10, 0)

    assert final["status"] == "COMPLETED"
    assert final["processedFiles"] == 2
    assert final["failedFiles"] == 1
    assert analyzer.calls > 1
    assert {file["analysisStatus"] for file in files["files"]} == {"ANALYZED", "FAILED"}
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
            GraphAnalysisResult(),
        ]
    )
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 1))
    final = wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])

    assert final["processedFiles"] == 2
    assert final["failedFiles"] == 1


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
    analysis_store.create_job_files("job-running", [row], {int(row["id"]): "CODE"})
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
    analysis_store.create_job_files("job-failed", [row], {int(row["id"]): "CODE"})
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
    handler = next(item for item in nodes if item["name"] == "create")
    declaration = next(item for item in edges if item["edgeType"] == "DECLARES")
    node_detail = analysis_store.graph_node_detail(manifest["graphRevision"], handler["id"], "edge-gateway", True)
    edge_detail = analysis_store.graph_edge_detail(manifest["graphRevision"], declaration["id"], "edge-gateway", True)

    assert any(claim["claimKind"] == "ENTRYPOINT_HINT" for claim in node_detail["item"]["claims"])
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

    assert counts["analysis_graph_nodes"] > 0
    assert counts["analysis_graph_edges"] > 0
    assert counts["analysis_graph_claims"] > 0
    assert counts["analysis_graph_evidence"] > 0


def test_runtime_analysis_writes_job_file_flow_and_line_metadata(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    analyzer = StubAnalyzer(GraphAnalysisResult())
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), analyzer)["jobId"])

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        analysis_file = conn.execute("SELECT flow_domain FROM analysis_files").fetchone()
        job_file = conn.execute("SELECT status, flow_domain, line_count FROM analysis_job_files").fetchone()
        inventory_file = conn.execute("SELECT line_count, decode_policy FROM files").fetchone()
        static_nodes = conn.execute("SELECT COUNT(*) AS count FROM analysis_graph_nodes WHERE fact_origin = 'STATIC'").fetchone()

    assert analysis_file["flow_domain"] == "CODE"
    assert job_file["status"] == "ANALYZED"
    assert job_file["flow_domain"] == "CODE"
    assert job_file["line_count"] > 0
    assert inventory_file["line_count"] > 0
    assert inventory_file["decode_policy"] == "utf-8:replace"
    assert static_nodes["count"] > 0



@pytest.mark.parametrize(
    ("relative_path", "content", "expected_format", "expected_extractor", "expected_policy"),
    [
        ("src/main/java/example/ObjectHandler.java", "package example;\npublic class ObjectHandler { void create() {} }\n", "java", "java_ast", "parser_assisted_graph_enrichment"),
        ("config/service.yaml", "name: edge\nsettings:\n  enabled: true\n", "yaml", "structured_text_light", "text_graph_enrichment"),
        ("models/service.xml", "<project>\n  <artifactId>edge-core</artifactId>\n</project>\n", "xml", "structured_text_light", "text_graph_enrichment"),
        ("docs/README.md", "# Edge Gateway\n\nHandles routing notes.\n", "markdown", "document_heading_light", "text_graph_enrichment"),
    ],
)
def test_analyzer_runtime_builds_unified_payload_shape(relative_path, content, expected_format, expected_extractor, expected_policy):
    _, analyzer = asyncio.run(run_runtime(relative_path, content))
    payload = analyzer.payloads[0]
    llm_input = payload["llmInput"]

    assert set(payload) == payload_top_level_shape()
    assert payload["requestKind"] == TARGET_REQUEST_KIND
    assert llm_input["requestKind"] == TARGET_REQUEST_KIND
    assert llm_input["file"]["contentLines"][0]["line"] == 1
    assert llm_input["file"]["contentLines"][0]["text"] == content.splitlines()[0]
    assert "contextAnchors" in llm_input
    assert llm_input["targetAnchor"]["kind"]
    assert payload["targetRef"]
    assert payload["analysisPolicy"]["formatId"] == expected_format
    assert payload["analysisPolicy"]["extractorId"] == expected_extractor
    assert payload["analysisPolicy"]["policyId"] == expected_policy
    assert payload["analysisPolicy"]["sourceView"] == "contentLines"
    assert payload["analysisPolicy"]["llmMode"] != "none"
    assert "fileType" not in payload
    assert "flowDomain" not in llm_input
    assert "content" not in payload
    assert "anchorRegistry" not in llm_input
    assert "staticAnchors" not in llm_input
    assert "callsites" not in llm_input
    assert not _contains_key(llm_input, "stableKey")
    assert not _contains_key(llm_input, "ref")


def test_analyzer_runtime_routing_is_yaml_driven_and_unknown_extension_fails():
    policy = load_analysis_policy(POLICY_PATH)
    resolver = AnalyzerPolicyRuntimeResolver(policy)

    cases = [
        ("src/Foo.java", "java_ast", "parser_assisted_graph_enrichment"),
        ("config/service.yaml", "structured_text_light", "text_graph_enrichment"),
        ("README.md", "document_heading_light", "text_graph_enrichment"),
    ]
    for relative_path, extractor_id, policy_id in cases:
        context = resolver.resolve(runtime_row(relative_path, "content\n"), {}, ["content"])
        assert context.policy_resolution.extractor_id == extractor_id
        assert context.policy_resolution.policy_id == policy_id

    with pytest.raises(KnowledgeError) as exc:
        resolver.resolve(runtime_row("archive.unknown", "content\n"), {}, ["content"])

    assert exc.value.code == "UNSUPPORTED_FORMAT"
    assert exc.value.details["unsupportedBehavior"]["unsupportedFormat"] == "fail_file"


@pytest.mark.parametrize("flow_domain", ["WORKFLOW", "CONFIG", "BUILD"])
def test_legacy_flow_domain_does_not_control_llm_eligibility(flow_domain):
    _, analyzer = asyncio.run(run_runtime("config/service.yaml", "name: edge\n", flow_domain=flow_domain, language="yaml"))
    payload = analyzer.payloads[0]

    assert analyzer.calls == 1
    assert payload["analysisPolicy"]["extractorId"] == "structured_text_light"
    assert payload["llmInput"]["file"]["relativePath"] == "config/service.yaml"
    assert "flowDomain" not in payload
    assert "flowDomain" not in payload["llmInput"]


def test_parser_unsupported_text_file_is_not_skipped_by_old_logic():
    _, analyzer = asyncio.run(run_runtime("docs/operations.txt", "Operations note for handoff.\n", flow_domain="DOC", language="text"))
    payload = analyzer.payloads[0]

    assert analyzer.calls == 1
    assert payload["analysisPolicy"]["formatId"] == "markdown"
    assert payload["analysisPolicy"]["extractorId"] == "document_heading_light"


def test_java_extractor_output_is_used_as_static_anchors():
    _, analyzer = asyncio.run(
        run_runtime(
            "src/main/java/example/ObjectHandler.java",
            "package example;\npublic class ObjectHandler {\n  public void create() { helper(); }\n  private void helper() {}\n}\n",
            language="java",
        )
    )
    llm_input = analyzer.payloads[0]["llmInput"]
    static_anchors = [llm_input["targetAnchor"], *llm_input["contextAnchors"]]
    anchors = static_anchors
    kinds = {item["kind"] for item in anchors}

    assert "staticAnchors" not in analyzer.payloads[0]["llmInput"]
    assert "callsites" not in analyzer.payloads[0]["llmInput"]
    assert "FILE" in kinds
    assert {"TYPE", "CALLABLE"}.issubset(kinds)
    assert any(item["kind"] == "CALLABLE" for item in anchors)


def test_analyzer_captured_ollama_requests_are_minimal_target_anchor_json(tmp_path):
    content = """package example;

class NestedCallFlow {
  private final WorkspaceRepository repository;

  public WorkspaceDto getWorkspace(String id) {
    Workspace workspace = loadWorkspace(id);
    validateWorkspace(workspace);
    return mapWorkspace(workspace);
  }

  private Workspace loadWorkspace(String id) {
    return repository.findById(id).orElseThrow();
  }

  private void validateWorkspace(Workspace workspace) {
    ensureActive(workspace);
  }

  private void ensureActive(Workspace workspace) {
    if (!workspace.active()) {
      throw new IllegalStateException();
    }
  }

  private WorkspaceDto mapWorkspace(Workspace workspace) {
    return WorkspaceDto.from(workspace);
  }
}
"""
    store, _, _ = build_inventory(tmp_path, content=content)
    captured = []
    client = _capturing_ollama_client(captured, _nested_flow_response)
    runner = SupervisorHarness(store, app_config(tmp_path))

    final = wait_job(store, runner.start(AnalysisBuildRequest(), client)["jobId"])
    asyncio.run(client.aclose())

    inputs = [_llm_input_from_prompt(body["prompt"]) for body in captured]
    target_names = {item["targetAnchor"]["name"] for item in inputs}
    public_request = next(item for item in inputs if item["targetAnchor"]["name"] == "getWorkspace")
    public_context_names = {item["name"] for item in public_request["contextAnchors"]}
    facts = graph_facts_for_path(store.db_path, "src/main/java/example/ObjectHandler.java")

    assert final["status"] == "COMPLETED"
    assert "getWorkspace" in target_names
    assert {"loadWorkspace", "validateWorkspace", "ensureActive", "mapWorkspace"}.issubset(target_names)
    assert {"loadWorkspace", "validateWorkspace", "ensureActive", "mapWorkspace"}.issubset(public_context_names)
    assert all(item["requestKind"] == TARGET_REQUEST_KIND for item in inputs)
    assert all(item["file"]["contentLines"] for item in inputs)
    assert all("contextAnchors" in item for item in inputs)
    assert all(item["targetAnchor"]["kind"] for item in inputs)
    for item in inputs:
        for forbidden in (
            "anchorRegistry",
            "ref",
            "staticAnchors",
            "callsites",
            "callsiteStableKey",
            "stableKey",
            "contractRefs",
            "tags",
            "tests",
            "ownsBusinessAreas",
            "domainKeywords",
            "parser",
            "factOrigin",
            "flowDomain",
            "resolutionReason",
            "unresolvedReason",
            "sliceDefaultVisibility",
        ):
            assert not _contains_key(item, forbidden)
    assert len(facts["claims"]) >= len([item for item in inputs if item["targetAnchor"]["kind"] == "CALLABLE"])


def test_analyzer_rejects_unknown_ref_response_without_persisting_invalid_facts(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    captured = []

    def invalid_response(_llm_input):
        return {
            "claims": [],
            "semanticEdges": [
                {
                    "edgeType": "REFERENCES",
                    "toRef": "M999",
                    "evidence": [{"lineStart": 1, "lineEnd": 1}],
                }
            ],
        }

    client = _capturing_ollama_client(captured, invalid_response)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 1))

    final = wait_job(store, runner.start(AnalysisBuildRequest(), client)["jobId"])
    asyncio.run(client.aclose())
    facts = graph_facts_for_path(store.db_path, "src/main/java/example/ObjectHandler.java")

    assert final["failedFiles"] == 1
    assert captured
    assert len(facts["nodes"]) == 0
    assert len(facts["claims"]) == 0


def test_validation_feedback_retry_prompt_keeps_minimal_target_input(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    captured = []
    attempts = {"count": 0}

    def flaky_response(llm_input):
        attempts["count"] += 1
        if attempts["count"] == 1:
            invalid_response = {
                "claims": [
                    {
                        "claimKind": "RESPONSIBILITY",
                        "summary": "Bad evidence range.",
                        "evidence": [{"lineStart": 55, "lineEnd": 46}],
                    }
                ]
            }
            return json.dumps(invalid_response)
        return _empty_target_response(llm_input)

    client = _capturing_ollama_client(captured, flaky_response)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 2))

    final = wait_job(store, runner.start(AnalysisBuildRequest(), client)["jobId"])
    asyncio.run(client.aclose())

    assert final["failedFiles"] == 0
    assert len(captured) >= 2
    retry_prompt = captured[1]["prompt"]
    retry_input = _llm_input_from_prompt(retry_prompt)
    assert BEGIN_INPUT_MARKER in retry_prompt
    assert retry_input["requestKind"] == TARGET_REQUEST_KIND
    assert "staticAnchors" not in retry_input
    assert "callsites" not in retry_input
    assert "Structured validationErrors:" in retry_prompt
    assert "EVIDENCE_RANGE_INVERTED" in retry_prompt
    assert '"lineStart": 55' in retry_prompt
    assert '"lineEnd": 46' in retry_prompt
    assert "lineStart <= lineEnd" in retry_prompt
    assert "ascending source order" in retry_prompt
    assert "lineStart must be the smaller/earlier line" in retry_prompt
    assert "lineEnd must be the larger/later line" in retry_prompt
    assert "For actual range 55-46" in retry_prompt
    assert "use lineStart=46 and lineEnd=55 only if those same lines materially support the claim" in retry_prompt
    assert "otherwise choose another valid evidence range inside the target" in retry_prompt
    assert "correctionHint" not in retry_prompt
    assert "Fix only the listed validation errors." in retry_prompt
    assert "Remove invalid fields" not in retry_prompt
    assert "semanticEdges are not accepted." not in retry_prompt
    assert "semanticEdges" not in retry_prompt
    assert "toRef" not in retry_prompt
    assert "topology" not in retry_prompt
    assert "COMMENT_ONLY" not in retry_prompt
    assert "CLOSING_BRACE_ONLY" not in retry_prompt
    assert "outside target" not in retry_prompt
    assert "edgeOptions" not in retry_prompt
    assert "endpointRules" not in retry_prompt


def test_validation_feedback_retry_preserves_all_attempt_validation_errors(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    captured = []

    def still_invalid_response(_llm_input):
        return json.dumps(
            {
                "claims": [
                    {
                        "claimKind": "RESPONSIBILITY",
                        "summary": "Still bad evidence.",
                        "evidence": [
                            {"lineStart": 3, "lineEnd": 2},
                            {"lineStart": 999, "lineEnd": 999},
                        ],
                    }
                ]
            }
        )

    client = _capturing_ollama_client(captured, still_invalid_response)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 2))

    final = wait_job(store, runner.start(AnalysisBuildRequest(), client)["jobId"])
    asyncio.run(client.aclose())
    files = AnalysisStore(store.db_path).files(None, "FAILED", None, 10, 0)

    assert final["failedFiles"] == 1
    assert len(captured) == 2
    retry_prompt = captured[1]["prompt"]
    assert "EVIDENCE_RANGE_INVERTED" in retry_prompt
    assert "EVIDENCE_RANGE_OUTSIDE_FILE" in retry_prompt
    diagnostics = files["files"][0]["diagnostics"]
    metadata = [item.get("metadata") for item in diagnostics if item.get("metadata")]
    validation_errors = [
        error
        for item in metadata
        for error in item.get("validationErrors", [])
    ]
    assert {item["code"] for item in validation_errors} >= {"EVIDENCE_RANGE_INVERTED", "EVIDENCE_RANGE_OUTSIDE_FILE"}


def test_target_retry_with_one_configured_attempt_does_not_build_feedback(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    captured = []
    response = _target_sequence_response(
        ("FILE", "ObjectHandler.java"),
        [_invalid_inverted_response("single attempt invalid")],
    )
    client = _capturing_ollama_client(captured, response)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 1))

    started = runner.start(AnalysisBuildRequest(), client)
    final = wait_job(store, started["jobId"])
    asyncio.run(client.aclose())

    file_calls = _captured_target_calls(captured)
    request_events = _provider_request_events_for_target(store, started["jobId"], "F1")
    assert final["failedFiles"] == 1
    assert len(file_calls) == 1
    assert not _is_feedback_prompt(file_calls[0]["prompt"])
    assert [event["metadata"]["attemptKind"] for event in request_events] == ["GENERATION"]
    assert request_events[0]["metadata"]["configuredMaxAttempts"] == 1
    assert request_events[0]["metadata"]["repairAttempt"] is False


def test_feedback_prompt_marks_complete_previous_response_without_truncation(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    captured = []
    invalid_response = _invalid_inverted_response("complete short invalid response")
    response = _target_sequence_response(
        ("FILE", "ObjectHandler.java"),
        [invalid_response, _empty_target_response],
    )
    client = _capturing_ollama_client(captured, response)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 2))

    started = runner.start(AnalysisBuildRequest(), client)
    final = wait_job(store, started["jobId"])
    asyncio.run(client.aclose())

    expected_response = json.dumps(invalid_response)
    file_calls = _captured_target_calls(captured)
    request_events = _provider_request_events_for_target(store, started["jobId"], "F1")
    retry_prompt = file_calls[1]["prompt"]
    retry_metadata = request_events[1]["metadata"]

    assert final["failedFiles"] == 0
    assert "Previous invalid response:\n" in retry_prompt
    assert "Previous invalid response preview:" not in retry_prompt
    assert "truncated for prompt safety" not in retry_prompt
    assert expected_response in retry_prompt
    assert retry_metadata["previousResponseAvailable"] is True
    assert retry_metadata["previousResponsePreviewTruncated"] is False
    assert retry_metadata["previousResponsePreviewLength"] == len(expected_response)
    assert retry_metadata["previousResponseLength"] == len(expected_response)


def test_feedback_prompt_marks_locally_truncated_previous_response_preview(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    captured = []
    omitted_tail_marker = "TAIL_MARKER_AFTER_LOCAL_PREVIEW_LIMIT"
    long_summary = (
        "locally truncated invalid response "
        + ("x" * (MAX_RAW_PREVIEW_CHARS + 256))
        + omitted_tail_marker
    )
    invalid_response = _invalid_inverted_response(long_summary)
    response = _target_sequence_response(
        ("FILE", "ObjectHandler.java"),
        [invalid_response, _empty_target_response],
    )
    client = _capturing_ollama_client(captured, response)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 2))

    started = runner.start(AnalysisBuildRequest(), client)
    final = wait_job(store, started["jobId"])
    asyncio.run(client.aclose())

    expected_response = json.dumps(invalid_response)
    assert len(expected_response) > MAX_RAW_PREVIEW_CHARS
    file_calls = _captured_target_calls(captured)
    request_events = _provider_request_events_for_target(store, started["jobId"], "F1")
    retry_prompt = file_calls[1]["prompt"]
    retry_metadata = request_events[1]["metadata"]

    assert final["failedFiles"] == 0
    assert "Previous invalid response preview:\n" in retry_prompt
    assert "The previous response was truncated for prompt safety." in retry_prompt
    assert "Use the validation errors and the available preview to produce a complete corrected response." in retry_prompt
    assert expected_response[:MAX_RAW_PREVIEW_CHARS] in retry_prompt
    assert omitted_tail_marker not in retry_prompt
    assert retry_metadata["previousResponseAvailable"] is True
    assert retry_metadata["previousResponsePreviewTruncated"] is True
    assert retry_metadata["previousResponsePreviewLength"] == MAX_RAW_PREVIEW_CHARS
    assert retry_metadata["previousResponseLength"] == len(expected_response)
    assert "previousProviderResponseTruncated" not in retry_metadata


def test_target_retry_chains_validation_feedback_for_three_configured_attempts(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    captured = []
    response = _target_sequence_response(
        ("FILE", "ObjectHandler.java"),
        [
            _invalid_inverted_response("attempt one inverted"),
            _invalid_boundary_response("attempt two boundary"),
            _empty_target_response,
        ],
    )
    client = _capturing_ollama_client(captured, response)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 3))

    started = runner.start(AnalysisBuildRequest(), client)
    final = wait_job(store, started["jobId"])
    asyncio.run(client.aclose())

    file_calls = _captured_target_calls(captured)
    type_calls = _captured_target_calls(captured, kind="TYPE", name="ObjectHandler")
    request_events = _provider_request_events_for_target(store, started["jobId"], "F1")
    attempt2_errors = _validation_errors_from_prompt(file_calls[1]["prompt"])
    attempt3_errors = _validation_errors_from_prompt(file_calls[2]["prompt"])

    assert final["failedFiles"] == 0
    assert len(file_calls) == 3
    assert not _is_feedback_prompt(file_calls[0]["prompt"])
    assert _is_feedback_prompt(file_calls[1]["prompt"])
    assert _is_feedback_prompt(file_calls[2]["prompt"])
    assert _previous_attempt_number_from_prompt(file_calls[1]["prompt"]) == 1
    assert _previous_attempt_number_from_prompt(file_calls[2]["prompt"]) == 2
    assert "attempt one inverted" in file_calls[1]["prompt"]
    assert "attempt two boundary" in file_calls[2]["prompt"]
    assert "attempt one inverted" not in file_calls[2]["prompt"]
    assert {item["code"] for item in attempt2_errors} == {"EVIDENCE_RANGE_INVERTED"}
    assert {item["code"] for item in attempt3_errors} == {"BOUNDARY_DESCRIPTORS_MISSING"}
    assert type_calls and not _is_feedback_prompt(type_calls[0]["prompt"])
    assert [event["metadata"]["attemptKind"] for event in request_events] == [
        "GENERATION",
        "FEEDBACK_REPAIR",
        "FEEDBACK_REPAIR",
    ]
    assert [event["metadata"].get("previousAttemptNumber") for event in request_events] == [None, 1, 2]
    assert [event["metadata"]["previousFailureCodes"] for event in request_events[1:]] == [
        ["EVIDENCE_RANGE_INVERTED"],
        ["BOUNDARY_DESCRIPTORS_MISSING"],
    ]
    assert len({event["metadata"]["promptHash"] for event in request_events}) == 3


def test_validation_then_transport_failure_next_attempt_is_provider_retry(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    captured = []
    invalid_response = _invalid_inverted_response("attempt one validation failure")

    def transport_failure(_llm_input):
        raise httpx.ConnectError("connection failed")

    response = _target_sequence_response(
        ("FILE", "ObjectHandler.java"),
        [invalid_response, transport_failure, _empty_target_response],
    )
    client = _capturing_ollama_client(captured, response)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 3))

    started = runner.start(AnalysisBuildRequest(), client)
    final = wait_job(store, started["jobId"])
    asyncio.run(client.aclose())

    file_calls = _captured_target_calls(captured)
    request_events = _provider_request_events_for_target(store, started["jobId"], "F1")
    attempt2_errors = _validation_errors_from_prompt(file_calls[1]["prompt"])

    assert final["failedFiles"] == 0
    assert len(file_calls) == 3
    assert not _is_feedback_prompt(file_calls[0]["prompt"])
    assert _is_feedback_prompt(file_calls[1]["prompt"])
    assert not _is_feedback_prompt(file_calls[2]["prompt"])
    assert "attempt one validation failure" in file_calls[1]["prompt"]
    assert {item["code"] for item in attempt2_errors} == {"EVIDENCE_RANGE_INVERTED"}
    assert "Previous invalid response:" not in file_calls[2]["prompt"]
    assert "Structured validationErrors:" not in file_calls[2]["prompt"]
    assert [event["metadata"]["attemptKind"] for event in request_events] == [
        "GENERATION",
        "FEEDBACK_REPAIR",
        "PROVIDER_RETRY",
    ]
    assert [event["metadata"].get("previousAttemptNumber") for event in request_events] == [None, 1, 2]
    assert request_events[2]["metadata"]["previousFailureCodes"] == ["ANALYSIS_AI_TRANSPORT_ERROR"]
    assert request_events[2]["metadata"]["previousResponseAvailable"] is False
    assert request_events[2]["metadata"]["repairAttempt"] is False
    assert {event["metadata"]["targetRef"] for event in request_events} == {"F1"}
    assert request_events[2]["metadata"]["promptHash"] == request_events[0]["metadata"]["promptHash"]


def test_transport_then_validation_failure_next_attempt_is_feedback_repair(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    captured = []
    invalid_response = _invalid_inverted_response("attempt two validation failure")

    def transport_failure(_llm_input):
        raise httpx.ConnectError("connection failed")

    response = _target_sequence_response(
        ("FILE", "ObjectHandler.java"),
        [transport_failure, invalid_response, _empty_target_response],
    )
    client = _capturing_ollama_client(captured, response)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 3))

    started = runner.start(AnalysisBuildRequest(), client)
    final = wait_job(store, started["jobId"])
    asyncio.run(client.aclose())

    file_calls = _captured_target_calls(captured)
    request_events = _provider_request_events_for_target(store, started["jobId"], "F1")
    attempt3_errors = _validation_errors_from_prompt(file_calls[2]["prompt"])

    assert final["failedFiles"] == 0
    assert len(file_calls) == 3
    assert not _is_feedback_prompt(file_calls[0]["prompt"])
    assert not _is_feedback_prompt(file_calls[1]["prompt"])
    assert _is_feedback_prompt(file_calls[2]["prompt"])
    assert "Previous invalid response:" not in file_calls[1]["prompt"]
    assert "attempt two validation failure" in file_calls[2]["prompt"]
    assert "connection failed" not in file_calls[2]["prompt"]
    assert {item["code"] for item in attempt3_errors} == {"EVIDENCE_RANGE_INVERTED"}
    assert [event["metadata"]["attemptKind"] for event in request_events] == [
        "GENERATION",
        "PROVIDER_RETRY",
        "FEEDBACK_REPAIR",
    ]
    assert [event["metadata"].get("previousAttemptNumber") for event in request_events] == [None, 1, 2]
    assert request_events[1]["metadata"]["previousFailureCodes"] == ["ANALYSIS_AI_TRANSPORT_ERROR"]
    assert request_events[1]["metadata"]["previousResponseAvailable"] is False
    assert request_events[2]["metadata"]["previousFailureCodes"] == ["EVIDENCE_RANGE_INVERTED"]
    assert request_events[2]["metadata"]["previousResponseAvailable"] is True
    assert request_events[2]["metadata"]["repairAttempt"] is True
    assert {event["metadata"]["targetRef"] for event in request_events} == {"F1"}
    assert request_events[1]["metadata"]["promptHash"] == request_events[0]["metadata"]["promptHash"]
    assert request_events[2]["metadata"]["promptHash"] != request_events[1]["metadata"]["promptHash"]


def test_target_retry_chains_validation_feedback_for_five_configured_attempts(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    captured = []
    invalid_responses = [_invalid_inverted_response(f"attempt {index} invalid") for index in range(1, 5)]
    response = _target_sequence_response(
        ("FILE", "ObjectHandler.java"),
        [*invalid_responses, _empty_target_response],
    )
    client = _capturing_ollama_client(captured, response)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 5))

    started = runner.start(AnalysisBuildRequest(), client)
    final = wait_job(store, started["jobId"])
    asyncio.run(client.aclose())

    file_calls = _captured_target_calls(captured)
    request_events = _provider_request_events_for_target(store, started["jobId"], "F1")
    assert final["failedFiles"] == 0
    assert len(file_calls) == 5
    assert not _is_feedback_prompt(file_calls[0]["prompt"])
    assert all(_is_feedback_prompt(call["prompt"]) for call in file_calls[1:])
    for index, call in enumerate(file_calls[1:], start=2):
        assert _previous_attempt_number_from_prompt(call["prompt"]) == index - 1
        assert f"attempt {index - 1} invalid" in call["prompt"]
        if index > 2:
            assert f"attempt {index - 2} invalid" not in call["prompt"]
    assert [event["metadata"]["attemptKind"] for event in request_events] == [
        "GENERATION",
        "FEEDBACK_REPAIR",
        "FEEDBACK_REPAIR",
        "FEEDBACK_REPAIR",
        "FEEDBACK_REPAIR",
    ]
    assert [event["metadata"].get("previousAttemptNumber") for event in request_events] == [None, 1, 2, 3, 4]
    assert all(event["metadata"]["configuredMaxAttempts"] == 5 for event in request_events)


def test_target_retry_uses_feedback_for_nine_of_ten_configured_attempts(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    captured = []
    response = _target_sequence_response(
        ("FILE", "ObjectHandler.java"),
        [_invalid_inverted_response(f"attempt {index} invalid") for index in range(1, 11)],
    )
    client = _capturing_ollama_client(captured, response)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 10))

    started = runner.start(AnalysisBuildRequest(), client)
    final = wait_job(store, started["jobId"])
    asyncio.run(client.aclose())

    file_calls = _captured_target_calls(captured)
    request_events = _provider_request_events_for_target(store, started["jobId"], "F1")
    assert final["failedFiles"] == 1
    assert len(file_calls) == 10
    assert sum(1 for call in file_calls if not _is_feedback_prompt(call["prompt"])) == 1
    assert sum(1 for call in file_calls if _is_feedback_prompt(call["prompt"])) == 9
    assert [_previous_attempt_number_from_prompt(call["prompt"]) for call in file_calls[1:]] == list(range(1, 10))
    assert [event["metadata"]["attemptKind"] for event in request_events].count("GENERATION") == 1
    assert [event["metadata"]["attemptKind"] for event in request_events].count("FEEDBACK_REPAIR") == 9
    assert all(event["metadata"]["configuredMaxAttempts"] == 10 for event in request_events)


def test_target_retry_forwards_all_structured_validation_errors(tmp_path):
    content = """public class ObjectHandler {
  public void create() {
  }
}
"""
    store, _, _ = build_inventory(tmp_path, content=content)
    captured = []
    counts = {}

    def response(llm_input):
        key = _target_key(llm_input)
        counts[key] = counts.get(key, 0) + 1
        if key == ("CALLABLE", "create") and counts[key] == 1:
            return {
                "claims": [
                    {
                        "claimKind": "RESPONSIBILITY",
                        "summary": "multi-error callable response",
                        "evidence": [
                            {"lineStart": 3, "lineEnd": 2},
                            {"lineStart": 3, "lineEnd": 3},
                        ],
                    }
                ],
                "boundaries": [
                    {
                        "role": "PROVIDED",
                        "evidence": [{"lineStart": 2, "lineEnd": 2}],
                        "descriptors": [],
                    }
                ],
            }
        return _empty_target_response(llm_input)

    client = _capturing_ollama_client(captured, response)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 2))

    started = runner.start(AnalysisBuildRequest(), client)
    final = wait_job(store, started["jobId"])
    asyncio.run(client.aclose())

    callable_calls = _captured_target_calls(captured, kind="CALLABLE", name="create")
    request_events = _provider_request_events_for_target(store, started["jobId"], "M1")
    errors = _validation_errors_from_prompt(callable_calls[1]["prompt"])
    assert final["failedFiles"] == 0
    assert len(callable_calls) == 2
    assert {item["code"] for item in errors} >= {
        "EVIDENCE_RANGE_INVERTED",
        "EVIDENCE_NOT_MATERIAL",
        "BOUNDARY_DESCRIPTORS_MISSING",
    }
    assert any(item.get("actual") == {"lineStart": 3, "lineEnd": 2} for item in errors)
    assert any(item.get("actual", {}).get("lineClass") == "CLOSING_BRACE_ONLY" for item in errors)
    assert request_events[1]["metadata"]["previousFailureCodes"] == [
        "EVIDENCE_RANGE_INVERTED",
        "EVIDENCE_NOT_MATERIAL",
        "BOUNDARY_DESCRIPTORS_MISSING",
    ]


def test_target_retry_replaces_previous_failure_with_latest_validation_result(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    captured = []
    response = _target_sequence_response(
        ("FILE", "ObjectHandler.java"),
        [
            _invalid_inverted_response("attempt one inverted"),
            _invalid_boundary_response("attempt two boundary"),
            _empty_target_response,
        ],
    )
    client = _capturing_ollama_client(captured, response)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 3))

    started = runner.start(AnalysisBuildRequest(), client)
    final = wait_job(store, started["jobId"])
    asyncio.run(client.aclose())

    file_calls = _captured_target_calls(captured)
    attempt2_errors = _validation_errors_from_prompt(file_calls[1]["prompt"])
    attempt3_errors = _validation_errors_from_prompt(file_calls[2]["prompt"])
    assert final["failedFiles"] == 0
    assert {item["code"] for item in attempt2_errors} == {"EVIDENCE_RANGE_INVERTED"}
    assert {item["code"] for item in attempt3_errors} == {"BOUNDARY_DESCRIPTORS_MISSING"}
    assert "attempt two boundary" in file_calls[2]["prompt"]
    assert "attempt one inverted" not in file_calls[2]["prompt"]


def test_target_retry_feedback_state_is_isolated_between_targets(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    captured = []
    response = _target_sequence_response(
        ("FILE", "ObjectHandler.java"),
        [
            _invalid_inverted_response("file target invalid"),
            _empty_target_response,
        ],
    )
    client = _capturing_ollama_client(captured, response)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 3))

    started = runner.start(AnalysisBuildRequest(), client)
    final = wait_job(store, started["jobId"])
    asyncio.run(client.aclose())

    file_events = _provider_request_events_for_target(store, started["jobId"], "F1")
    type_events = _provider_request_events_for_target(store, started["jobId"], "T1")
    callable_events = _provider_request_events_for_target(store, started["jobId"], "M1")
    type_calls = _captured_target_calls(captured, kind="TYPE", name="ObjectHandler")
    callable_calls = _captured_target_calls(captured, kind="CALLABLE", name="create")
    assert final["failedFiles"] == 0
    assert [event["attempt"] for event in file_events] == [1, 2]
    assert [event["metadata"]["attemptKind"] for event in file_events] == ["GENERATION", "FEEDBACK_REPAIR"]
    assert [event["attempt"] for event in type_events] == [1]
    assert type_events[0]["metadata"]["attemptKind"] == "GENERATION"
    assert "previousAttemptNumber" not in type_events[0]["metadata"]
    assert type_calls and not _is_feedback_prompt(type_calls[0]["prompt"])
    assert [event["attempt"] for event in callable_events] == [1]
    assert callable_events[0]["metadata"]["attemptKind"] == "GENERATION"
    assert "previousAttemptNumber" not in callable_events[0]["metadata"]
    assert callable_calls and not _is_feedback_prompt(callable_calls[0]["prompt"])


def test_provider_failure_without_response_retries_without_output_repair_prompt(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    captured = []
    counts = {}

    def response(llm_input):
        key = _target_key(llm_input)
        counts[key] = counts.get(key, 0) + 1
        if key == ("FILE", "ObjectHandler.java") and counts[key] == 1:
            raise httpx.ConnectError("connection failed")
        return _empty_target_response(llm_input)

    client = _capturing_ollama_client(captured, response)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 2))

    started = runner.start(AnalysisBuildRequest(), client)
    final = wait_job(store, started["jobId"])
    asyncio.run(client.aclose())

    file_calls = _captured_target_calls(captured)
    request_events = _provider_request_events_for_target(store, started["jobId"], "F1")
    assert final["failedFiles"] == 0
    assert len(file_calls) == 2
    assert not _is_feedback_prompt(file_calls[0]["prompt"])
    assert not _is_feedback_prompt(file_calls[1]["prompt"])
    assert "Previous invalid response:" not in file_calls[1]["prompt"]
    assert [event["metadata"]["attemptKind"] for event in request_events] == ["GENERATION", "PROVIDER_RETRY"]
    assert request_events[1]["metadata"]["previousFailureCodes"] == ["ANALYSIS_AI_TRANSPORT_ERROR"]
    assert request_events[1]["metadata"]["repairAttempt"] is False


def test_target_retry_terminal_exhaustion_reports_latest_attempt_failure(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    captured = []
    response = _target_sequence_response(
        ("FILE", "ObjectHandler.java"),
        [_invalid_inverted_response(f"attempt {index} invalid") for index in range(1, 6)],
    )
    client = _capturing_ollama_client(captured, response)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 5))

    started = runner.start(AnalysisBuildRequest(), client)
    final = wait_job(store, started["jobId"])
    asyncio.run(client.aclose())
    failed_files = AnalysisStore(store.db_path).files(None, "FAILED", None, 10, 0)["files"]

    file_calls = _captured_target_calls(captured)
    request_events = _provider_request_events_for_target(store, started["jobId"], "F1")
    last_feedback_errors = _validation_errors_from_prompt(file_calls[4]["prompt"])
    terminal = failed_files[0]
    diagnostics = terminal["diagnostics"]
    terminal_metadata = next(item["metadata"] for item in diagnostics if item["code"] == "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED")

    assert final["failedFiles"] == 1
    assert len(file_calls) == 5
    assert not _is_feedback_prompt(file_calls[0]["prompt"])
    assert all(_is_feedback_prompt(call["prompt"]) for call in file_calls[1:])
    assert "attempt 4 invalid" in file_calls[4]["prompt"]
    assert {item["code"] for item in last_feedback_errors} == {"EVIDENCE_RANGE_INVERTED"}
    assert [event["metadata"]["attemptKind"] for event in request_events] == [
        "GENERATION",
        "FEEDBACK_REPAIR",
        "FEEDBACK_REPAIR",
        "FEEDBACK_REPAIR",
        "FEEDBACK_REPAIR",
    ]
    assert terminal["attemptCount"] == 5
    assert terminal_metadata["attemptsPerformed"] == 5
    assert terminal_metadata["lastAttemptKind"] == "FEEDBACK_REPAIR"
    assert terminal_metadata["lastFailureCode"] == "ANALYSIS_AI_SCHEMA_INVALID"
    assert terminal_metadata["targetRef"] == "F1"
    assert [item["code"] for item in terminal_metadata["lastValidationErrors"]] == ["EVIDENCE_RANGE_INVERTED"]


def test_feedback_prompt_uses_authoritative_response_contract_for_boundary_targets(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    captured = []
    response = _target_sequence_response(
        ("FILE", "ObjectHandler.java"),
        [
            _invalid_boundary_response("boundary contract invalid"),
            _empty_target_response,
        ],
    )
    client = _capturing_ollama_client(captured, response)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 2))

    final = wait_job(store, runner.start(AnalysisBuildRequest(), client)["jobId"])
    asyncio.run(client.aclose())

    retry_prompt = _captured_target_calls(captured)[1]["prompt"]
    assert final["failedFiles"] == 0
    assert "Claims-only response shape" not in retry_prompt
    assert "Authoritative target response shape" in retry_prompt
    assert '"boundaries"' in retry_prompt
    assert '"descriptors"' in retry_prompt


def test_callsite_heavy_fluent_chain_prompt_does_not_include_raw_callsite_payload(tmp_path):
    content = """package example;

class FluentChainFixture {
  void heavy() {
    database.given()
      .workspace("one")
      .owner("user")
      .build()
      .persist();

    mockMvc.perform(get("/workspaces/{id}", "one")
        .header("X-Test", "true")
        .contentType(APPLICATION_JSON))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.id").value("one"))
      .andExpect(jsonPath("$.owner").value("user"));
  }
}
"""
    store, _, _ = build_inventory(tmp_path, content=content)
    captured = []
    client = _capturing_ollama_client(captured, _empty_target_response)
    runner = SupervisorHarness(store, app_config(tmp_path))

    final = wait_job(store, runner.start(AnalysisBuildRequest(), client)["jobId"])
    asyncio.run(client.aclose())

    assert final["failedFiles"] == 0
    assert captured
    for body in captured:
        prompt = body["prompt"]
        llm_input = _llm_input_from_prompt(prompt)
        assert len(prompt) < 30000
        assert "staticAnchors" not in llm_input
        assert "callsites" not in llm_input
        assert not _contains_key(llm_input, "callsiteStableKey")
        assert not _contains_key(llm_input, "receiverText")


def test_structured_text_light_emits_config_regions_only_when_policy_allows_them():
    _, analyzer = asyncio.run(run_runtime("config/service.yaml", "service:\n  endpoint: http://example\n", language="yaml"))
    llm_input = analyzer.payloads[0]["llmInput"]
    kinds = [llm_input["targetAnchor"]["kind"], *[item["kind"] for item in llm_input["contextAnchors"]]]

    assert "CONFIG" in kinds
    assert "staticAnchors" not in analyzer.payloads[0]["llmInput"]
    assert "callsites" not in analyzer.payloads[0]["llmInput"]


def test_structured_text_light_emits_only_file_anchor_when_config_not_allowed():
    policy = load_analysis_policy(POLICY_PATH)
    graph_profiles = dict(policy.graph_profiles)
    graph_profiles["structured_text_graph"] = replace(
        graph_profiles["structured_text_graph"],
        nodes=["FILE"],
        edges=[],
        claims=[],
    )
    policy = replace(policy, graph_profiles=graph_profiles)

    _, analyzer = asyncio.run(run_runtime("config/service.yaml", "service:\n  endpoint: http://example\n", policy=policy, language="yaml"))
    llm_input = analyzer.payloads[0]["llmInput"]

    assert llm_input["targetAnchor"]["kind"] == "FILE"
    assert llm_input["contextAnchors"] == []


def test_xml_structured_labels_produce_normalized_static_anchors_when_allowed():
    _, analyzer = asyncio.run(run_runtime("models/service.xml", "<project>\n  <artifactId>edge-core</artifactId>\n</project>\n", language="xml"))
    llm_input = analyzer.payloads[0]["llmInput"]
    static_anchors = [llm_input["targetAnchor"], *llm_input["contextAnchors"]]

    assert any(item["kind"] == "CONFIG" and item["name"] == "project" for item in static_anchors)


def test_invalid_extractor_output_fails_before_llm():
    policy = load_analysis_policy(POLICY_PATH)
    registry = ExtractorRegistry()

    def invalid_structured_output(context, extractor):
        graph = GraphAnalysisResult(
            nodes=[
                GraphNode(
                    localId="file",
                    nodeKind="FILE",
                    name="service.yaml",
                    lineStart=1,
                    lineEnd=1,
                    confidence=1.0,
                    metadata={"factOrigin": "STATIC"},
                ),
                GraphNode(
                    localId="workflow",
                    nodeKind="WORKFLOW",
                    name="workflow",
                    lineStart=1,
                    lineEnd=1,
                    confidence=1.0,
                    metadata={"factOrigin": "STATIC"},
                ),
            ]
        )
        return ExtractorResult(graph, extractor.id, extractor.implementation)

    registry._handlers["structured_text_parser"] = invalid_structured_output
    analyzer = CapturingGraphAnalyzer()

    with pytest.raises(KnowledgeError) as exc:
        asyncio.run(run_runtime("config/service.yaml", "name: edge\n", policy=policy, registry=registry, analyzer=analyzer, language="yaml"))

    assert exc.value.code == "ANALYSIS_EXTRACTOR_OUTPUT_INVALID"
    assert exc.value.details["stage"] == "STATIC_EXTRACTION"
    assert analyzer.calls == 0
    assert analyzer.payloads == []


class InvalidFallbackRegistry(ExtractorRegistry):
    def _java_static_parser(self, context, extractor):
        raise RuntimeError("forced parser failure")

    def _file_anchor_graph(self, context, extractor_id):
        graph = super()._file_anchor_graph(context, extractor_id)
        graph.nodes[0] = graph.nodes[0].copy(update={"nodeKind": "TYPE"})
        return graph


class InvalidMetadataExtractorRegistry(ExtractorRegistry):
    def _structured_text_light(self, context, extractor):
        result = super()._structured_text_light(context, extractor)
        result.graph_result.nodes[0].metadata["factOrigin"] = "BOGUS"
        return result


def _runtime_file_anchor(relative_path: str) -> str:
    return f"edge-gateway|{relative_path}|FILE"


def test_invalid_fallback_output_fails_before_llm():
    policy = load_analysis_policy(POLICY_PATH)
    registry = InvalidFallbackRegistry()
    analyzer = CapturingGraphAnalyzer()

    with pytest.raises(KnowledgeError) as exc:
        asyncio.run(
            run_runtime(
                "src/main/java/example/ObjectHandler.java",
                "package example;\npublic class ObjectHandler {}\n",
                policy=policy,
                registry=registry,
                analyzer=analyzer,
                language="java",
            )
        )

    assert exc.value.code == "ANALYSIS_EXTRACTOR_OUTPUT_INVALID"
    assert exc.value.details["extractorFallbackUsed"] is True
    assert exc.value.details["allowedValues"] == ["FILE"]
    assert analyzer.calls == 0


@pytest.mark.parametrize(
    ("result", "field", "actual"),
    [
        (
            GraphAnalysisResult(
                nodes=[
                    GraphNode(
                        localId="type1",
                        nodeKind="TYPE",
                        name="ReadmeType",
                        lineStart=1,
                        lineEnd=1,
                        confidence=0.8,
                        metadata={"factOrigin": "LLM"},
                    )
                ]
            ),
            "nodeKind",
            "TYPE",
        ),
        (
            GraphAnalysisResult(
                edges=[
                    GraphEdge(
                        localId="call1",
                        edgeType="CALLS",
                        fromNodeLocalId=_runtime_file_anchor("README.md"),
                        confidence=0.8,
                        evidence=[GraphEvidenceRef(lineStart=1, lineEnd=1)],
                        metadata={"factOrigin": "LLM"},
                    )
                ]
            ),
            "edges",
            "CALLS",
        ),
        (
            GraphAnalysisResult(
                claims=[
                    GraphClaim(
                        localId="config-claim",
                        claimKind="CONFIG_REFERENCE",
                        nodeLocalId=_runtime_file_anchor("README.md"),
                        summary="References configuration.",
                        confidence=0.8,
                        evidence=[GraphEvidenceRef(lineStart=1, lineEnd=1)],
                        metadata={"factOrigin": "LLM"},
                    )
                ]
            ),
            "claimKind",
            "CONFIG_REFERENCE",
        ),
    ],
)
def test_invalid_llm_enrichment_fails_before_materialization(result, field, actual):
    analyzer = CapturingGraphAnalyzer(result)

    with pytest.raises(KnowledgeError) as exc:
        asyncio.run(run_runtime("README.md", "# Service\n", analyzer=analyzer, language="markdown"))

    assert analyzer.calls == 1
    assert exc.value.code == "ANALYSIS_GRAPH_POLICY_VALIDATION_FAILED"
    assert exc.value.details["stage"] == "LLM_ENRICHMENT"
    assert exc.value.details["field"] == field
    assert exc.value.details["actual"] == actual


def test_file_anchor_fallback_works_only_when_policy_allows_it():
    policy = load_analysis_policy(POLICY_PATH)
    registry = ExtractorRegistry()
    registry._handlers.pop("document_heading_parser")

    _, analyzer = asyncio.run(run_runtime("README.md", "# Title\n", policy=policy, registry=registry))
    payload = analyzer.payloads[0]

    assert payload["llmInput"]["targetAnchor"]["kind"] == "FILE"
    assert payload["llmInput"]["contextAnchors"] == []


def test_required_file_anchor_fallback_mode_allows_file_anchor_fallback():
    policy = load_analysis_policy(POLICY_PATH)
    registry = ExtractorRegistry()
    registry._handlers.pop("java_static_parser")

    _, analyzer = asyncio.run(
        run_runtime(
            "src/main/java/example/ObjectHandler.java",
            "package example;\npublic class ObjectHandler {}\n",
            policy=policy,
            registry=registry,
            language="java",
        )
    )
    payload = analyzer.payloads[0]

    assert payload["analysisPolicy"]["extractorMode"] == "required_or_file_anchor_fallback"
    assert payload["llmInput"]["targetAnchor"]["kind"] == "FILE"
    assert payload["llmInput"]["contextAnchors"] == []


def test_missing_required_extractor_fails_explicitly_before_llm():
    policy = load_analysis_policy(POLICY_PATH)
    policies = dict(policy.policies)
    policies["parser_assisted_graph_enrichment"] = replace(
        policies["parser_assisted_graph_enrichment"],
        extractor_mode=EXTRACTOR_MODE_FILE_ANCHOR_ONLY,
    )
    strict_policy = replace(policy, policies=policies)
    registry = ExtractorRegistry()
    registry._handlers.pop("java_static_parser")
    analyzer = CapturingGraphAnalyzer()

    with pytest.raises(KnowledgeError) as exc:
        asyncio.run(
            run_runtime(
                "src/main/java/example/ObjectHandler.java",
                "package example;\npublic class ObjectHandler {}\n",
                policy=strict_policy,
                registry=registry,
                analyzer=analyzer,
            )
        )

    assert exc.value.code == "UNSUPPORTED_EXTRACTOR"
    assert analyzer.calls == 0


def test_fake_extractor_mode_with_fallback_substring_fails_closed():
    policy = load_analysis_policy(POLICY_PATH)
    policies = dict(policy.policies)
    policies["text_graph_enrichment"] = replace(policies["text_graph_enrichment"], extractor_mode="fallback_disabled")
    strict_policy = replace(policy, policies=policies)
    registry = ExtractorRegistry()
    registry._handlers.pop("document_heading_parser")
    analyzer = CapturingGraphAnalyzer()

    with pytest.raises(KnowledgeError) as exc:
        asyncio.run(run_runtime("README.md", "# Title\n", policy=strict_policy, registry=registry, analyzer=analyzer))

    assert exc.value.code == "ANALYSIS_POLICY_UNSUPPORTED_EXTRACTOR_MODE"
    assert analyzer.calls == 0


def test_fake_llm_mode_fails_closed_before_provider_call():
    policy = load_analysis_policy(POLICY_PATH)
    policies = dict(policy.policies)
    policies["text_graph_enrichment"] = replace(policies["text_graph_enrichment"], llm_mode="not_none")
    strict_policy = replace(policy, policies=policies)
    analyzer = CapturingGraphAnalyzer()

    with pytest.raises(KnowledgeError) as exc:
        asyncio.run(run_runtime("README.md", "# Title\n", policy=strict_policy, analyzer=analyzer))

    assert exc.value.code == "ANALYSIS_POLICY_UNSUPPORTED_LLM_MODE"
    assert analyzer.calls == 0


class FakeAnalysisStore:
    def __init__(self):
        self.replacements = []
        self.failed_attempts = []
        self.job_file_updates = []
        self.job_updates = []

    def cleanup_stale_files(self, source_ids):
        return None

    def unchanged_file_ids(self, rows, analyzer_name, analyzer_version):
        return set()

    def create_job_files(self, job_id, rows, flow_domain_by_file_id):
        return None

    def stop_requested(self, job_id):
        return False

    def update_job(self, job_id, patch):
        self.job_updates.append((job_id, patch))

    def unchanged(self, file_id, content_hash, analyzer_name, analyzer_version):
        return False

    def update_job_file(self, job_id, file_id, status, **kwargs):
        self.job_file_updates.append((job_id, file_id, status, kwargs))

    def replace_file_graph_analysis(self, file_id, state, graph):
        self.replacements.append((file_id, state, graph))

    def mark_file_failed_attempt(self, file_id, state):
        self.failed_attempts.append((file_id, state))

    def mark_file(self, file_id, state):
        self.failed_attempts.append((file_id, state))


class FakeInventoryStore:
    def __init__(self, db_path, rows):
        self.db_path = db_path
        self._rows = rows

    def search_rows(self, source_ids, groups):
        return self._rows, None


def supervisor_runtime_row(tmp_path, relative_path, content):
    root = tmp_path / "workspace" / "edge-gateway"
    path = root / relative_path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    row = runtime_row(relative_path, content, root=root)
    row["source_path"] = str(root)
    row["absolute_path"] = str(path)
    row["metadata_json"] = json.dumps({"absoluteRoot": str(root)})
    return row


def run_supervisor_with_fake_store(tmp_path, analyzer, row, runtime=None):
    inventory = FakeInventoryStore(tmp_path / "fake.sqlite", [row])
    supervisor = AnalysisSupervisor(inventory, app_config(tmp_path))
    if runtime is not None:
        supervisor.analyzer_runtime = runtime
    fake_store = FakeAnalysisStore()
    supervisor.analysis_store = fake_store
    asyncio.run(
        supervisor._run(
            "job-1",
            AnalysisBuildRequest(force=True),
            analyzer,
            selected_rows=[row],
            mode="FULL",
            job_files_precreated=True,
        )
    )
    return fake_store


def test_persistence_boundary_writes_only_final_graph_and_no_partial_extractor_facts(tmp_path):
    row = supervisor_runtime_row(tmp_path, "config/service.yaml", "name: edge\n")
    success_analyzer = CapturingGraphAnalyzer()
    success_store = run_supervisor_with_fake_store(tmp_path, success_analyzer, row)

    assert len(success_store.replacements) == 1
    assert success_store.failed_attempts == []
    assert success_store.replacements[0][2]["nodes"]
    assert "contextAnchors" in success_analyzer.payloads[0]["llmInput"]

    failing_analyzer = CapturingGraphAnalyzer(fail=True)
    failure_store = run_supervisor_with_fake_store(tmp_path, failing_analyzer, row)

    assert "contextAnchors" in failing_analyzer.payloads[0]["llmInput"]
    assert failure_store.replacements == []
    assert len(failure_store.failed_attempts) == 1
    assert failure_store.job_file_updates[-1][2] == "FAILED"


def test_persistence_boundary_invalid_extractor_does_not_call_llm_or_replace_graph(tmp_path):
    row = supervisor_runtime_row(tmp_path, "src/main/java/example/ObjectHandler.java", "package example;\npublic class ObjectHandler {}\n")
    policy = load_analysis_policy(POLICY_PATH)
    runtime = AnalyzerRuntime(policy, extractor_registry=InvalidFallbackRegistry())
    analyzer = CapturingGraphAnalyzer()

    failure_store = run_supervisor_with_fake_store(tmp_path, analyzer, row, runtime=runtime)

    assert analyzer.calls == 0
    assert failure_store.replacements == []
    assert len(failure_store.failed_attempts) == 1
    assert failure_store.failed_attempts[0][1]["last_error_code"] == "ANALYSIS_EXTRACTOR_OUTPUT_INVALID"
    assert failure_store.job_file_updates[-1][2] == "FAILED"


def test_persistence_boundary_invalid_extractor_metadata_does_not_call_llm_or_replace_graph(tmp_path):
    row = supervisor_runtime_row(tmp_path, "config/service.yaml", "name: edge\n")
    policy = load_analysis_policy(POLICY_PATH)
    runtime = AnalyzerRuntime(policy, extractor_registry=InvalidMetadataExtractorRegistry())
    analyzer = CapturingGraphAnalyzer()

    failure_store = run_supervisor_with_fake_store(tmp_path, analyzer, row, runtime=runtime)

    assert analyzer.calls == 0
    assert failure_store.replacements == []
    assert len(failure_store.failed_attempts) == 1
    assert failure_store.failed_attempts[0][1]["last_error_code"] == "ANALYSIS_EXTRACTOR_OUTPUT_INVALID"
    assert failure_store.job_file_updates[-1][2] == "FAILED"


def test_persistence_boundary_invalid_llm_enrichment_does_not_replace_graph(tmp_path):
    row = supervisor_runtime_row(tmp_path, "config/service.yaml", "name: edge\n")
    analyzer = CapturingGraphAnalyzer(
        GraphAnalysisResult(
            claims=[
                GraphClaim(
                    localId="claim-without-evidence",
                    claimKind="RESPONSIBILITY",
                    nodeLocalId=_runtime_file_anchor("config/service.yaml"),
                    summary="Describes the service.",
                    confidence=0.8,
                    evidence=[],
                    metadata={"factOrigin": "LLM"},
                )
            ]
        )
    )

    failure_store = run_supervisor_with_fake_store(tmp_path, analyzer, row)

    assert analyzer.calls == 1
    assert failure_store.replacements == []
    assert len(failure_store.failed_attempts) == 1
    assert failure_store.failed_attempts[0][1]["last_error_code"] == "ANALYSIS_GRAPH_POLICY_VALIDATION_FAILED"
    assert failure_store.job_file_updates[-1][2] == "FAILED"


def test_persistence_boundary_invalid_llm_metadata_does_not_replace_graph(tmp_path):
    row = supervisor_runtime_row(tmp_path, "config/service.yaml", "name: edge\n")
    analyzer = CapturingGraphAnalyzer(
        GraphAnalysisResult(
            nodes=[
                GraphNode(
                    localId="llm-file",
                    nodeKind="FILE",
                    name="service.yaml",
                    lineStart=1,
                    lineEnd=1,
                    confidence=0.8,
                    metadata={"factOrigin": "BOGUS"},
                )
            ]
        )
    )

    failure_store = run_supervisor_with_fake_store(tmp_path, analyzer, row)

    assert analyzer.calls == 1
    assert failure_store.replacements == []
    assert len(failure_store.failed_attempts) == 1
    assert failure_store.failed_attempts[0][1]["last_error_code"] == "ANALYSIS_GRAPH_POLICY_VALIDATION_FAILED"
    assert failure_store.job_file_updates[-1][2] == "FAILED"


def test_materialized_graph_fact_statuses_are_declared_by_policy():
    policy = load_analysis_policy(POLICY_PATH)
    declared_statuses = set(policy.graph.statuses)
    row = {
        "id": 1,
        "source_id": "edge-gateway",
        "relative_path": "config/service.yaml",
        "content_hash": "hash-1",
    }
    graph = GraphAnalysisResult(
        nodes=[
            GraphNode(localId="file", nodeKind="FILE", name="service.yaml", lineStart=1, lineEnd=1, confidence=1.0),
            GraphNode(localId="config", nodeKind="CONFIG", name="service", lineStart=1, lineEnd=1, confidence=0.5),
            GraphNode(localId="data", nodeKind="DATA", name="settings", lineStart=1, lineEnd=1, confidence=0.2),
        ],
        edges=[
            GraphEdge(
                localId="configures",
                edgeType="CONFIGURES",
                fromNodeLocalId="file",
                toNodeLocalId="config",
                confidence=0.5,
                evidence=[GraphEvidenceRef(lineStart=1, lineEnd=1)],
            )
        ],
        claims=[
            GraphClaim(
                localId="purpose",
                claimKind="RESPONSIBILITY",
                nodeLocalId="file",
                summary="Describes the service.",
                confidence=0.2,
                evidence=[GraphEvidenceRef(lineStart=1, lineEnd=1)],
            )
        ],
    )

    materialized = GraphAnalysisEngine().materialize(row, "job-1", "test", "1", graph, ["name: edge"])
    statuses = {item["status"] for key in ("nodes", "edges", "claims") for item in materialized[key]}

    assert "TRUSTED" in statuses
    assert "CANDIDATE" in statuses
    assert statuses <= declared_statuses


def test_graph_materializer_ignores_metadata_resolution_status():
    row = {
        "id": 1,
        "source_id": "edge-gateway",
        "relative_path": "src/main/java/example/Foo.java",
        "content_hash": "hash-1",
    }
    graph = GraphAnalysisResult(
        nodes=[
            GraphNode(localId="file", nodeKind="FILE", name="Foo.java", lineStart=1, lineEnd=1, confidence=1.0),
            GraphNode(localId="caller", nodeKind="CALLABLE", name="call", lineStart=1, lineEnd=1, confidence=1.0),
        ],
        edges=[
            GraphEdge(
                localId="legacy-call",
                edgeType="CALLS",
                fromNodeLocalId="caller",
                toNodeLocalId=None,
                confidence=0.8,
                evidence=[GraphEvidenceRef(lineStart=1, lineEnd=1, metadata={"evidenceKind": "CALLSITE"})],
                unresolvedTarget={"name": "missing", "kindHint": "CALLABLE"},
                metadata={"factOrigin": "STATIC", "resolutionStatus": "RESOLVED", "methodName": "missing"},
            )
        ],
    )

    materialized = GraphAnalysisEngine().materialize(row, "job-1", "test", "1", graph, ["missing();"])

    assert materialized["edges"][0]["resolution_status"] == "UNRESOLVED"
    assert materialized["edges"][0]["to_node_id"] is None
    assert "resolutionStatus" not in materialized["edges"][0]["metadata"]


def test_static_graph_edge_resolution_status_is_not_persisted_in_metadata_json(tmp_path):
    content = """package example;

import java.util.List;

class Foo {
  void caller() {
    helper();
  }

  void helper() {}
}
"""
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.init()
    store.replace_file_graph_analysis(
        1,
        graph_state_for_test(content, "src/main/java/example/Foo.java"),
        _materialize_static_java_for_test(content, 1, "src/main/java/example/Foo.java"),
    )

    with sqlite3.connect(store.db_path) as conn:
        conn.row_factory = sqlite3.Row
        external_nodes = conn.execute(
            "SELECT COUNT(*) FROM analysis_graph_nodes WHERE node_kind = 'EXTERNAL'"
        ).fetchone()[0]
        import_edge = conn.execute(
            """
            SELECT resolution_status, to_node_id, unresolved_target_json, metadata_json
            FROM analysis_graph_edges
            WHERE edge_type = 'IMPORTS'
            """
        ).fetchone()
        call_edge = conn.execute(
            """
            SELECT resolution_status, to_node_id, metadata_json
            FROM analysis_graph_edges
            WHERE edge_type = 'CALLS'
            """
        ).fetchone()

    assert external_nodes == 0
    assert import_edge["resolution_status"] == "EXTERNAL_TARGET"
    assert import_edge["to_node_id"] is None
    assert json.loads(import_edge["unresolved_target_json"])["qualifiedName"] == "java.util.List"
    assert "resolutionStatus" not in json.loads(import_edge["metadata_json"])
    assert call_edge["resolution_status"] == "RESOLVED"
    assert call_edge["to_node_id"] is not None
    assert "resolutionStatus" not in json.loads(call_edge["metadata_json"])


def test_graph_materializer_flow_domain_uses_explicit_metadata_first():
    engine = GraphAnalysisEngine()
    row = {
        "id": 1,
        "source_id": "edge-gateway",
        "relative_path": "config/service.yaml",
        "content_hash": "hash-1",
        "flow_domain": "BUILD",
    }

    assert engine._flow_domain(row, {"flowDomain": "WORKFLOW"}) == "WORKFLOW"


def test_graph_materializer_flow_domain_defaults_when_metadata_is_unknown():
    engine = GraphAnalysisEngine()
    row = {
        "id": 1,
        "source_id": "edge-gateway",
        "relative_path": "config/service.yaml",
        "content_hash": "hash-1",
        "flow_domain": "UNKNOWN",
    }

    assert engine._flow_domain(row, {"flowDomain": "UNKNOWN"}) == "CODE"


def test_graph_materializer_flow_domain_uses_row_when_metadata_absent():
    engine = GraphAnalysisEngine()
    row = {
        "id": 1,
        "source_id": "edge-gateway",
        "relative_path": "config/service.yaml",
        "content_hash": "hash-1",
        "flow_domain": "CONFIG",
    }

    assert engine._flow_domain(row, {}) == "CONFIG"


@pytest.mark.parametrize(
    "relative_path",
    [
        ".github/workflows/build.yml",
        "pom.xml",
        "src/test/java/FooTest.java",
        "config/service.yaml",
    ],
)
def test_graph_materializer_flow_domain_does_not_route_by_path(relative_path):
    engine = GraphAnalysisEngine()
    row = {
        "id": 1,
        "source_id": "edge-gateway",
        "relative_path": relative_path,
        "content_hash": "hash-1",
    }

    assert engine._flow_domain(row, {}) == "CODE"


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
    assert "resolver" not in json.loads(row["metadata_json"])


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


def test_low_confidence_callable_claim_maps_to_candidate_status(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(
        store,
        runner.start(AnalysisBuildRequest(), StubAnalyzer(responsibility_graph_result(method_claim=True, type_claim=False, method_confidence=0.2)))["jobId"],
    )
    selected = current_graph_node_detail_by_name(store, "create", include_evidence=True)
    responsibility = next(claim for claim in selected["claims"] if claim["claimKind"] == "RESPONSIBILITY")

    assert selected["status"] == "TRUSTED"
    assert selected["summarySource"] == "DIRECT"
    assert responsibility["status"] == "CANDIDATE"
    assert responsibility["rejectionReason"] is None
    assert selected["summaryConfidence"] == 0.2


def test_generic_file_level_callable_summary_uses_declared_status(tmp_path):
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

    assert selected["summarySource"] == "DIRECT"
    assert selected["claimSummary"] == "This Java file contains an object handler."
    assert responsibility["status"] == "TRUSTED"
    assert responsibility["rejectionReason"] is None


def test_no_source_file_mutation(tmp_path):
    store, _, service = build_inventory(tmp_path)
    source = service / "src/main/java/example/ObjectHandler.java"
    before = source.read_text(encoding="utf-8")
    runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    assert source.read_text(encoding="utf-8") == before


def test_no_production_domain_hardcoded_synonyms():
    src = Path(__file__).resolve().parents[1] / "src" / "knowledge_service"
    banned = ["_AUTH_QUERY", "site creation", "авторизація"]
    combined = "\n".join(path.read_text(encoding="utf-8") for path in src.rglob("*.py"))

    assert all(term not in combined for term in banned)


def test_current_file_target_progress_tracker_lifecycle_clamps_and_isolates_entries():
    tracker = CurrentFileTargetProgressTracker()

    assert tracker.snapshot() == {"active": False, "entries": []}

    tracker.start_file("job-0", "svc-z", "src/Planning.java")
    planning_entry = tracker.snapshot()["entries"][0]
    assert planning_entry["totalTargets"] is None
    assert planning_entry["percent"] is None
    assert planning_entry["showTargetProgress"] is False

    tracker.set_total_targets("job-0", "svc-z", "src/Planning.java", 0)
    zero_entry = tracker.snapshot()["entries"][0]
    assert zero_entry["totalTargets"] == 0
    assert zero_entry["percent"] is None
    assert zero_entry["showTargetProgress"] is False

    tracker.set_total_targets("job-0", "svc-z", "src/Planning.java", 1)
    tracker.increment_completed("job-0", "svc-z", "src/Planning.java")
    one_entry = tracker.snapshot()["entries"][0]
    assert one_entry["totalTargets"] == 1
    assert one_entry["completedTargets"] == 1
    assert one_entry["percent"] is None
    assert one_entry["showTargetProgress"] is False

    tracker.clear_job("job-0")
    tracker.start_file("job-1", "svc-a", "src/A.java")
    tracker.set_total_targets("job-1", "svc-a", "src/A.java", 3)
    tracker.increment_completed("job-1", "svc-a", "src/A.java")
    tracker.increment_completed("job-1", "svc-a", "src/A.java")
    tracker.increment_completed("job-1", "svc-a", "src/A.java")
    tracker.increment_completed("job-1", "svc-a", "src/A.java")
    tracker.start_file("job-2", "svc-b", "src/B.java")
    tracker.set_total_targets("job-2", "svc-b", "src/B.java", 2)
    tracker.increment_completed("job-2", "svc-b", "src/B.java")

    snapshot = tracker.snapshot()
    entries = {(entry["jobId"], entry["sourceId"], entry["relativePath"]): entry for entry in snapshot["entries"]}

    assert snapshot["active"] is True
    assert entries[("job-1", "svc-a", "src/A.java")]["completedTargets"] == 3
    assert entries[("job-1", "svc-a", "src/A.java")]["totalTargets"] == 3
    assert entries[("job-1", "svc-a", "src/A.java")]["percent"] == 100.0
    assert entries[("job-1", "svc-a", "src/A.java")]["showTargetProgress"] is True
    assert entries[("job-2", "svc-b", "src/B.java")]["completedTargets"] == 1
    assert entries[("job-2", "svc-b", "src/B.java")]["percent"] == 50.0
    assert entries[("job-2", "svc-b", "src/B.java")]["showTargetProgress"] is True
    assert not any("stableKey" in entry or "prompt" in entry or "targetRef" in entry for entry in snapshot["entries"])

    tracker.clear_file("job-1", "svc-a", "src/A.java")
    assert [entry["jobId"] for entry in tracker.snapshot()["entries"]] == ["job-2"]

    tracker.start_file("job-3", "svc-c", "src/C.java")
    tracker.clear_sources(None)
    assert tracker.snapshot() == {"active": False, "entries": []}


class FakeRunner:
    async def start(self, request):
        return {"jobId": "job-1", "status": "QUEUED", "message": "Knowledge analysis job queued"}

    async def stop(self, job_id):
        return {"jobId": job_id, "status": "STOP_REQUESTED", "message": "Knowledge analysis stop requested"}

    def current_file_progress(self):
        return {"active": False, "entries": []}


def configure_api(tmp_path, monkeypatch):
    del monkeypatch
    store, _, _ = build_inventory(tmp_path)
    cfg = app_config(tmp_path)
    main.app_config = cfg
    main.store = store
    main.analysis_supervisor = FakeRunner()
    _sync_main_app_state_from_test_globals()
    return store


def _sync_main_app_state_from_test_globals():
    cfg = getattr(main, "app_config", None)
    store = getattr(main, "store", None)
    if cfg is None or store is None:
        return
    analysis_store = AnalysisStore(store.db_path)
    refresh = InventoryRefreshService(cfg, store)
    main.app.state.app_config = cfg
    main.app.state.knowledge_dependencies = KnowledgeDependencies(
        inventory_store=store,
        analysis_store=analysis_store,
        graph_store=analysis_store,
        source_resolver=InventoryFileResolver(store),
        analysis_provider=None,
        analysis_supervisor=getattr(main, "analysis_supervisor", None) or FakeRunner(),
        inventory_refresh=refresh,
        inventory_scheduler=AsyncInventoryScheduler(refresh, cfg),
        storage_operations=StorageOperations(store.db_path),
        generative_registry=None,
        generative_provider=None,
    )


def post_json(path, payload):
    import asyncio

    if path == "/api/v1/knowledge/query":
        return asyncio.run(asgi_threadpool_json("POST", path, payload))
    return asyncio.run(asgi_json("POST", path, payload))


def get_json(path):
    import asyncio

    return asyncio.run(asgi_json("GET", path, None))


async def asgi_threadpool_json(method, path, payload):
    import httpx

    async def await_with_wakeup(awaitable, *, timeout=2.0, interval=0.01):
        task = asyncio.create_task(awaitable)
        deadline = asyncio.get_running_loop().time() + timeout
        try:
            while not task.done():
                remaining = deadline - asyncio.get_running_loop().time()
                if remaining <= 0:
                    task.cancel()
                    raise asyncio.TimeoutError()
                await asyncio.sleep(min(interval, remaining))
            return await task
        finally:
            if not task.done():
                task.cancel()

    _sync_main_app_state_from_test_globals()
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=main.app), base_url="http://testserver") as client:
        response = await await_with_wakeup(client.request(method, path, json=payload or {}))
        return {"status": response.status_code, "json": response.json()}


def assert_node_closed_graph_response(body):
    node_ids = {node["id"] for node in body["nodes"]}
    for edge in body["edges"]:
        assert edge["fromNodeId"] in node_ids
        assert edge["toNodeId"] in node_ids


def insert_isolated_graph_nodes(db_path, count=5):
    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        base = conn.execute("""
            SELECT job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash, language, flow_domain
            FROM analysis_graph_nodes
            WHERE source_id = 'edge-gateway'
            LIMIT 1
        """).fetchone()
        assert base is not None
        for index in range(count):
            conn.execute(
                """
                INSERT INTO analysis_graph_nodes(
                    id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash, stable_key,
                    node_kind, language, name, qualified_name, display_name, parent_node_id,
                    line_start, line_end, confidence, status, created_at,
                    updated_at, fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
                (
                    f"test-isolated-node-{index}",
                    base["job_id"],
                    base["source_id"],
                    base["inventory_file_id"],
                    base["analysis_file_id"],
                    base["file_id"],
                    base["relative_path"],
                    base["content_hash"],
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
                    "now",
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
                id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                from_node_id, to_node_id, edge_type, resolution_status, confidence, unresolved_target_json,
                metadata_json, status, created_at, updated_at, fact_origin, flow_domain
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
            (
                "test-unresolved-edge-without-endpoint",
                from_node["job_id"],
                from_node["source_id"],
                from_node["inventory_file_id"],
                from_node["analysis_file_id"],
                from_node["file_id"],
                from_node["relative_path"],
                from_node["content_hash"],
                from_node["id"],
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
                "now",
                "STATIC",
                from_node["flow_domain"] or "CODE",
            ),
        )


async def asgi_json(method, path, payload):
    _sync_main_app_state_from_test_globals()
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


def test_current_file_progress_endpoint_returns_inactive_when_no_progress(tmp_path, monkeypatch):
    configure_api(tmp_path, monkeypatch)

    result = get_json("/api/v1/knowledge/analysis/current-file-progress")

    assert result["status"] == 200
    assert result["json"] == {"active": False, "entries": []}


def test_current_file_progress_endpoint_returns_sanitized_active_entry_during_analysis(tmp_path, monkeypatch):
    configure_api(tmp_path, monkeypatch)
    progress = {
        "active": True,
        "entries": [
            {
                "jobId": "job-1",
                "sourceId": "edge-gateway",
                "relativePath": "src/main/java/example/ObjectHandler.java",
                "totalTargets": 3,
                "completedTargets": 1,
                "percent": 33.33,
                "showTargetProgress": True,
                "status": "RUNNING",
                "updatedAt": "2026-07-08T12:00:00+00:00",
            }
        ],
    }
    progress["current"] = progress["entries"][0]

    class ActiveRunner(FakeRunner):
        def current_file_progress(self):
            return progress

    monkeypatch.setattr(main, "analysis_supervisor", ActiveRunner())

    result = get_json("/api/v1/knowledge/analysis/current-file-progress")
    payload = result["json"]

    assert payload["active"] is True
    entry = payload["entries"][0]
    assert entry["jobId"] == "job-1"
    assert entry["sourceId"] == "edge-gateway"
    assert entry["relativePath"].endswith("ObjectHandler.java")
    assert entry["totalTargets"] == 3
    assert entry["completedTargets"] == 1
    assert entry["showTargetProgress"] is True
    assert set(entry) == {
        "jobId",
        "sourceId",
        "relativePath",
        "totalTargets",
        "completedTargets",
        "percent",
        "showTargetProgress",
        "status",
        "updatedAt",
    }
    assert "stableKey" not in json.dumps(payload)
    assert "prompt" not in json.dumps(payload).lower()


def test_current_file_progress_clears_after_success_and_failure(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    success_runner = SupervisorHarness(store, app_config(tmp_path))
    wait_job(store, success_runner.start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])

    assert success_runner.supervisor.current_file_progress() == {"active": False, "entries": []}

    failing_runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 1))
    wait_job(
        store,
        failing_runner.start(
            AnalysisBuildRequest(force=True),
            StubAnalyzer(outcomes=[GraphAnalysisResult(), KnowledgeError("ANALYSIS_AI_INVALID_JSON", "bad target", raw_preview="{bad")]),
        )["jobId"],
    )

    assert failing_runner.supervisor.current_file_progress() == {"active": False, "entries": []}


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
            GraphAnalysisResult(),
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

    query_service = build_knowledge_query_service(AnalysisStore(store.db_path), cfg)

    missing = query_service.query(knowledge_query_request("ObjectHandler")).dict()
    assert missing["status"] != "QUERY_FAILED"

    wait_job(store, SupervisorHarness(store, cfg).start(AnalysisBuildRequest(), StubAnalyzer())["jobId"])
    pending = query_service.query(knowledge_query_request("ObjectHandler")).dict()
    assert pending["status"] in {"OK", "AMBIGUOUS"}

    semantic_store = SemanticIndexStore(store.db_path)
    state = semantic_store.get_state("edge-gateway")
    semantic_store.mark_source_stale("edge-gateway", f"{state['graph_revision']}:manual-stale", state["total_node_count"])
    stale = query_service.query(knowledge_query_request("ObjectHandler")).dict()
    assert stale["status"] in {"OK", "AMBIGUOUS"}


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
    before = store.stored_files(["edge-gateway"])[0]

    def fail_read_bytes(_path):
        raise AssertionError("unchanged inventory file was reopened")

    monkeypatch.setattr(Path, "read_bytes", fail_read_bytes)

    InventoryBuilder(load_source_config(config), store).build([], [])

    after = store.stored_files(["edge-gateway"])[0]
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

    after = store.stored_files(["edge-gateway"])[0]
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

    assert service["analysis"]["succeededFiles"] == 0
    assert service["analysis"]["failedFiles"] == 1
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
                    id, job_id, source_id, severity, stage, code, message, metadata_json, created_at
                )
                VALUES (?, 'job-1', 'edge-gateway', 'ERROR', 'LLM_ENRICHMENT', 'ANALYSIS_AI_INVALID_JSON',
                        ?, '{}', 'now')
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


def test_failed_analysis_details_are_exposed_on_files_endpoint_not_graph_diagnostics(tmp_path, monkeypatch):
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

    diagnostics = get_json("/api/v1/knowledge/analysis/diagnostics?sourceId=edge-gateway&limit=1")
    files = get_json("/api/v1/knowledge/analysis/files?status=FAILED&pathContains=ObjectHandler")

    assert diagnostics["status"] == 200
    assert diagnostics["json"]["total"] == 0
    assert files["status"] == 200
    assert files["json"]["total"] == 1
    assert files["json"]["files"][0]["lastErrorCode"] == "ANALYSIS_AI_MAX_ATTEMPTS_EXCEEDED"


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


def test_analysis_store_drops_graph_derived_tables_when_schema_is_outdated(tmp_path):
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    with store._connect() as conn:
        conn.execute("CREATE TABLE graph_obsolete_state (id INTEGER)")
        conn.execute("CREATE TABLE semantic_documents (document_id TEXT PRIMARY KEY, source_id TEXT, graph_marker TEXT)")

    store.init()

    with store._connect() as conn:
        tables = {row["name"] for row in conn.execute("SELECT name FROM sqlite_master WHERE type = 'table'").fetchall()}
    assert "graph_obsolete_state" not in tables
    assert "analysis_graph_state" in tables
