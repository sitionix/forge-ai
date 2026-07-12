import json
import sqlite3
import threading
import time
from pathlib import Path
from types import SimpleNamespace

import pytest

import knowledge_service.analysis_store as analysis_store_module
from knowledge_service.anchor_expansion_contract import (
    AnchorEntrypointHint,
    AnchorExpansionBundle,
    AnchorExpansionEdge,
    AnchorExpansionNode,
    AnchorExpansionRequest,
)
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.embedding_provider import EmbeddingProviderError
from knowledge_service.embedding_provider import FakeDeterministicEmbeddingProvider
from knowledge_service.flow_builder import (
    FlowBuilder,
    FlowGraphBundle,
    FlowGraphEdge,
    FlowGraphEvidence,
    FlowGraphNode,
    FlowGraphSourceScope,
    FlowStopReason,
    FlowUnit,
    FlowUnitKey,
    FlowUnitOrigin,
    flow_graph_bundle_to_public_bundle,
)
from knowledge_service.flow_explanations import (
    FLOW_EXPLANATION_LIMIT_REACHED,
    FLOW_EXPLANATION_VALIDATION_FAILED,
    FlowExplanationProviderResult,
    FlowExplanationService,
    FlowExplanationValidator,
    PackedFlowContext,
)
from knowledge_service.knowledge_query_schema import (
    KnowledgeQueryMatchedNode,
    KnowledgeQueryMatchedSource,
    KnowledgeQueryRequest,
    KnowledgeQueryResponse,
    KnowledgeQueryStatus,
)
from knowledge_service.knowledge_query_service import (
    AnchorExpansionReason,
    AnchorExpansionService,
    AnchorRole,
    CandidatePoolKind,
    EvidenceBundleBuilder,
    FlowPathExtractor,
    GraphSliceQueryService,
    KnowledgeQueryExecutionResult,
    KnowledgeQueryPolicy,
    KnowledgeQueryService,
    SourceScopeResolver,
    UnifiedAnchorSearcher,
    build_knowledge_query_service,
)
from knowledge_service.knowledge_search import (
    CandidateMerger,
    CandidateProvider,
    DeterministicCodeSearchEngine,
    SearchCandidate,
    SearchRunResult,
)
from knowledge_service.semantic_builder import SemanticBuildConfig, SemanticIndexBuilder
from knowledge_service.semantic_index import SemanticIndexStatus, SemanticIndexStore
from knowledge_service.semantic_search import (
    SemanticCandidateProvider,
    SemanticSearchConfig,
    SemanticVectorMatch,
    SemanticVectorSearchResult,
)
from semantic_test_support import seed_semantic_graph


VALID_QUERY_REQUEST = {
    "queryText": "Як створюється сайт?",
    "intent": "FLOW_EXPLANATION",
    "answerLanguage": "uk",
    "includeTests": False,
    "maxFlows": 10,
}

MINIMAL_QUERY_REQUEST = {
    "queryText": "Як створюється сайт?",
}


def query_request(query_text, *, intent="UNKNOWN", answer_language="en", include_tests=False, max_flows=10):
    return KnowledgeQueryRequest(
        queryText=query_text,
        intent=intent,
        answerLanguage=answer_language,
        includeTests=include_tests,
        maxFlows=max_flows,
    )


class FakeGraphStore:
    def __init__(
        self,
        *,
        candidates=None,
        hydration_candidates=None,
        nodes=None,
        edges=None,
        claims=None,
        evidence=None,
        graph_revision="graph-a",
        slice_error=False,
        adjacency_truncated=False,
    ):
        self.candidates = candidates if candidates is not None else []
        self.hydration_candidates = hydration_candidates if hydration_candidates is not None else self.candidates
        self.nodes = nodes if nodes is not None else default_nodes()
        self.edges = edges if edges is not None else default_edges()
        self.claims = claims if claims is not None else []
        self.evidence = evidence if evidence is not None else default_evidence()
        self.graph_revision = graph_revision
        self.slice_error = slice_error
        self.adjacency_truncated = adjacency_truncated
        self.source_searches = []
        self.hydration_searches = []
        self.expansion_queries = []
        self.graph_slice_requests = []
        self.adjacency_source_scopes = []
        self.adjacency_loads = 0

    def query_current_graph_sources(self):
        return [
            {
                "sourceId": "source-a",
                "displayName": "Source A",
                "graphId": "graph-a",
                "graphRevision": self.graph_revision,
                "nodeCount": 3,
                "edgeCount": 2,
            },
            {"sourceId": "source-b", "displayName": "Source B", "graphId": "graph-b", "graphRevision": "graph-b", "nodeCount": 1, "edgeCount": 0},
        ]

    def query_anchor_candidates(self, tokens, source_ids, limit):
        self.source_searches.append((tokens, source_ids, limit))
        return self.candidates

    def query_search_documents_by_node_ids(self, source_node_pairs, limit):
        self.hydration_searches.append((list(source_node_pairs), limit))
        requested = {(source_id, node_id) for source_id, node_id in source_node_pairs}
        hydrated = []
        for item in self.hydration_candidates:
            source_id = item.get("sourceId")
            node_id = item.get("id") or item.get("nodeId")
            if (source_id, node_id) not in requested:
                continue
            projected = dict(item)
            projected.setdefault("graphRevision", self.graph_revision)
            hydrated.append(projected)
        return hydrated[:limit]

    def query_anchor_expansion(self, source_node_pairs, max_per_anchor=30, max_total=200):
        requests = tuple(source_node_pairs)
        self.expansion_queries.append((requests, max_per_anchor, max_total))
        requested = set()
        for item in requests:
            assert isinstance(item, AnchorExpansionRequest)
            if item.source_id and item.graph_id and item.node_id:
                requested.add((str(item.source_id), str(item.graph_id), str(item.node_id)))

        structural_edges = []
        node_keys = set(requested)
        first_hop = set()
        for edge in sorted(self.edges, key=lambda item: (item.get("edgeType"), item.get("fromNodeId"), item.get("toNodeId") or "", item.get("id"))):
            if edge.get("edgeType") not in {"DECLARES", "USES_FIELD"}:
                continue
            from_key = (edge.get("sourceId"), edge.get("graphId") or "graph-a", edge.get("fromNodeId"))
            to_key = (edge.get("sourceId"), edge.get("graphId") or "graph-a", edge.get("toNodeId"))
            if from_key in requested or to_key in requested:
                structural_edges.append(dict(edge))
                for key in (from_key, to_key):
                    if key[2]:
                        node_keys.add(key)
                        if key not in requested:
                            first_hop.add(key)

        for edge in sorted(self.edges, key=lambda item: (item.get("edgeType"), item.get("fromNodeId"), item.get("toNodeId") or "", item.get("id"))):
            if edge.get("edgeType") != "DECLARES":
                continue
            from_key = (edge.get("sourceId"), edge.get("graphId") or "graph-a", edge.get("fromNodeId"))
            to_key = (edge.get("sourceId"), edge.get("graphId") or "graph-a", edge.get("toNodeId"))
            if from_key not in first_hop:
                continue
            structural_edges.append(dict(edge))
            for key in (from_key, to_key):
                if key[2]:
                    node_keys.add(key)

        by_key = {(node.get("sourceId"), node.get("graphId") or "graph-a", node.get("id")): node for node in self.nodes}
        nodes = []
        for key in sorted(node_keys):
            node = by_key.get(key)
            if not node:
                continue
            projected = dict(node)
            projected.setdefault("graphId", key[1])
            projected.setdefault("graphRevision", self.graph_revision)
            projected.setdefault("stableKey", projected.get("id"))
            nodes.append(projected)

        entrypoint_hints = []
        for claim in self.claims:
            if claim.get("claimKind") != "ENTRYPOINT_HINT":
                continue
            key = (claim.get("sourceId") or "source-a", claim.get("graphId") or "graph-a", claim.get("nodeId"))
            if key not in node_keys:
                continue
            entrypoint_hints.append(
                {
                    "sourceId": key[0],
                    "graphId": key[1],
                    "graphRevision": self.graph_revision,
                    "nodeId": key[2],
                    "claimId": claim.get("id"),
                }
            )
        return AnchorExpansionBundle(
            nodes=tuple(self._anchor_expansion_node(node) for node in nodes),
            edges=tuple(self._anchor_expansion_edge(edge) for edge in structural_edges),
            entrypoint_hints=tuple(self._anchor_entrypoint_hint(hint) for hint in entrypoint_hints),
            truncated=False,
        )

    def _anchor_expansion_node(self, node):
        return AnchorExpansionNode(
            source_id=str(node["sourceId"]),
            graph_id=str(node["graphId"]),
            graph_revision=str(node.get("graphRevision") or self.graph_revision),
            node_id=str(node["id"]),
            stable_key=str(node.get("stableKey") or node["id"]),
            node_kind=str(node["nodeKind"]),
            label=str(node["label"]),
            parent_node_id=node.get("parentNodeId"),
            relative_path=node.get("relativePath"),
            qualified_name=node.get("qualifiedName"),
            entrypoint=bool(node.get("entrypoint")),
        )

    def _anchor_expansion_edge(self, edge):
        return AnchorExpansionEdge(
            source_id=str(edge["sourceId"]),
            graph_id=str(edge["graphId"]),
            graph_revision=str(edge.get("graphRevision") or self.graph_revision),
            edge_id=str(edge["id"]),
            edge_type=str(edge["edgeType"]),
            from_node_id=str(edge["fromNodeId"]),
            to_node_id=str(edge["toNodeId"]),
        )

    def _anchor_entrypoint_hint(self, hint):
        return AnchorEntrypointHint(
            source_id=str(hint["sourceId"]),
            graph_id=str(hint["graphId"]),
            graph_revision=str(hint.get("graphRevision") or self.graph_revision),
            node_id=str(hint["nodeId"]),
            claim_id=str(hint["claimId"]),
        )

    def query_graph_slice(self, matched_nodes, depth):
        self.graph_slice_requests.append([dict(node) for node in matched_nodes])
        if self.slice_error:
            raise RuntimeError("slice failed")
        nodes = list(self.nodes)
        if matched_nodes and matched_nodes[0]["nodeId"] not in {node["id"] for node in nodes}:
            nodes = [
                {
                    "id": node["nodeId"],
                    "sourceId": node["sourceId"],
                    "graphId": node.get("graphId"),
                    "label": node["label"],
                }
                for node in matched_nodes
            ]
        return {
            "nodes": nodes,
            "edges": list(self.edges),
            "evidence": list(self.evidence),
            "unresolved": [],
            "external": [],
            "verifiedPaths": [],
        }

    def load_call_adjacency_for_sources(self, source_scopes, max_edges=2000, max_evidence=25):
        self.adjacency_loads += 1
        self.adjacency_source_scopes.append([dict(scope) for scope in source_scopes])
        scopes = {(scope["sourceId"], scope.get("graphId") or "graph-a") for scope in source_scopes}
        nodes = [dict(node) for node in self.nodes if (node.get("sourceId"), node.get("graphId")) in scopes]
        edges = [dict(edge) for edge in self.edges if (edge.get("sourceId"), edge.get("graphId")) in scopes]
        truncated = self.adjacency_truncated or len(edges) > max_edges
        edges = edges[:max_edges]
        evidence = [dict(item) for item in self.evidence[:max_evidence]]
        return {
            "nodes": nodes,
            "edges": edges,
            "evidence": evidence,
            "unresolved": [edge for edge in edges if edge.get("resolutionStatus") in {"UNRESOLVED", "EXTERNAL_TARGET"}],
            "external": [edge for edge in edges if edge.get("resolutionStatus") == "EXTERNAL_TARGET" or edge.get("external")],
            "verifiedPaths": [],
            "truncated": truncated,
        }


def service(store, policy=None):
    return KnowledgeQueryService(
        SourceScopeResolver(store),
        UnifiedAnchorSearcher(store),
        GraphSliceQueryService(store),
        FlowPathExtractor(store),
        EvidenceBundleBuilder(),
        policy or KnowledgeQueryPolicy(max_flow_paths=2),
    )


class StaticSemanticProvider(CandidateProvider):
    name = "SEMANTIC"

    def __init__(self, node_id):
        self.node_id = node_id
        self.last_diagnostics = []

    def search(self, query, documents, config):
        return [
            SearchCandidate(
                document=document,
                provider=self.name,
                reason="SEMANTIC_VECTOR_SIMILARITY",
                score=0.86,
                confidence="HIGH",
                priority=52,
            )
            for document in documents
            if document.node_id == self.node_id
        ]


class StaticVectorStore:
    def __init__(self, matches, *, scanned_count=1, diagnostics=None):
        self.matches = list(matches)
        self.scanned_count = scanned_count
        self.diagnostics = list(diagnostics or [])
        self.searches = []

    def search(self, query_vector, *, source_revisions, embedding_model):
        self.searches.append(
            {
                "queryVector": list(query_vector),
                "sourceRevisions": dict(source_revisions),
                "embeddingModel": embedding_model,
            }
        )
        return SemanticVectorSearchResult(
            matches=list(self.matches),
            diagnostics=list(self.diagnostics),
            scanned_count=self.scanned_count,
        )


class FailingEmbeddingProvider:
    model = "fake-semantic"

    def embed_texts(self, texts):
        raise EmbeddingProviderError("SEMANTIC_PROVIDER_UNAVAILABLE", "fake embedding failure")


class StaticDuplicateSearchEngine:
    def search(self, raw_query, documents, config):
        document = documents[0]
        return SearchRunResult(
            candidates=[],
            raw_candidates=[
                SearchCandidate(
                    document=document,
                    provider="ExactCandidateProvider",
                    reason="EXACT_NAME",
                    score=0.98,
                    confidence="HIGH",
                    priority=10,
                ),
                SearchCandidate(
                    document=document,
                    provider="LexicalCandidateProvider",
                    reason="LEXICAL_TOKEN_OVERLAP",
                    score=0.42,
                    confidence="LOW",
                    priority=42,
                ),
            ],
        )


class StaticExactSemanticDuplicateSearchEngine:
    def search(self, raw_query, documents, config):
        document = documents[0]
        return SearchRunResult(
            candidates=[],
            raw_candidates=[
                SearchCandidate(
                    document=document,
                    provider="ExactCandidateProvider",
                    reason="EXACT_NAME",
                    score=0.98,
                    confidence="HIGH",
                    priority=10,
                ),
                SearchCandidate(
                    document=document,
                    provider="SEMANTIC",
                    reason="SEMANTIC_VECTOR_SIMILARITY",
                    score=0.86,
                    confidence="HIGH",
                    priority=52,
                    metadata={"semanticDocumentId": "semantic-doc-shared", "similarity": 0.95, "embeddingModel": "fake"},
                ),
            ],
        )


class RankedPreviewOnlySearchEngine:
    def search(self, raw_query, documents, config):
        raw_candidates = [
            SearchCandidate(
                document=document,
                provider="ExactCandidateProvider",
                reason="EXACT_NAME",
                score=0.98,
                confidence="HIGH",
                priority=10,
            )
            for document in documents
        ]
        return SearchRunResult(
            candidates=CandidateMerger().merge(raw_candidates[:1]),
            raw_candidates=raw_candidates,
        )


class RecordingFlowExplanationProvider:
    def __init__(self, responses=None, delay_seconds=0.0):
        self.responses = list(responses or [])
        self.delay_seconds = delay_seconds
        self.calls = []

    def complete(self, llm_input, validation_errors=None, timeout_seconds=None):
        self.calls.append(
            {
                "llmInput": dict(llm_input),
                "validationErrors": list(validation_errors or []),
                "timeoutSeconds": timeout_seconds,
            }
        )
        if self.delay_seconds:
            time.sleep(self.delay_seconds)
        if self.responses:
            response = self.responses.pop(0)
            if isinstance(response, Exception):
                raise response
            if callable(response):
                response = response(llm_input, validation_errors)
        else:
            response = valid_flow_explanation(llm_input)
        return FlowExplanationProviderResult(raw_text=json.dumps(response, ensure_ascii=False), prompt_char_length=len(json.dumps(llm_input)))


def valid_flow_explanation(llm_input, validation_errors=None):
    def code(value):
        return f"`{value}`"

    step_refs = [step["stepRef"] for step in llm_input.get("steps", [])]
    transition_refs = [transition["transitionRef"] for transition in llm_input.get("transitions", [])]
    boundary_refs = [boundary["boundaryRef"] for boundary in llm_input.get("boundaries", [])]
    steps = [
        {
            "stepRef": step["stepRef"],
            "order": step["order"],
            "explanation": f"{code(step['symbol'])} moves the flow forward using the provided evidence.",
            "transitionRefs": [step["callToNext"]["transitionRef"]] if step.get("callToNext") else [],
            "evidenceRefs": [item["ref"] for item in step.get("evidence", [])],
        }
        for step in llm_input.get("steps", [])
    ]
    transitions = [
        {
            "transitionRef": transition["transitionRef"],
            "explanation": f"{code(transition['fromSymbol'])} leads to {code(transition['toSymbol'])} through this ordered transition.",
            "evidenceRefs": [item["ref"] for item in transition.get("evidence", [])],
        }
        for transition in llm_input.get("transitions", [])
    ]
    boundaries = [
        {
            "boundaryRef": boundary["boundaryRef"],
            "kind": boundary["kind"],
            "explanation": f"The flow stops at {boundary['kind']} using only the provided boundary fact.",
            "evidenceRefs": [item["ref"] for item in boundary.get("evidence", [])],
        }
        for boundary in llm_input.get("boundaries", [])
    ]
    symbols = [step["symbol"] for step in llm_input.get("steps", [])]
    return {
        "title": " -> ".join(symbols[:2]) or "Flow",
        "narrative": [
            {
                "text": "This flow is explained as an independent ordered path using only the packed facts.",
                "stepRefs": step_refs,
                "transitionRefs": transition_refs,
                "boundaryRefs": boundary_refs,
            },
            {
                "text": "The explanation follows the ordered transitions and repeated self-contained flow context without relying on another flow.",
                "stepRefs": step_refs,
                "transitionRefs": transition_refs,
                "boundaryRefs": boundary_refs,
            },
        ],
        "steps": steps,
        "transitions": transitions,
        "boundaries": boundaries,
    }


def execution_from_flow_result(result):
    return KnowledgeQueryExecutionResult(
        response=KnowledgeQueryResponse(
            queryId="query-test",
            status=KnowledgeQueryStatus.OK,
            intent="FLOW_EXPLANATION",
            matchedSources=[KnowledgeQueryMatchedSource(sourceId="source-a", displayName="Source A", score=1.0)],
            flowPaths=result.flow_paths,
        ),
        flow_units=result.flow_units,
    )


def candidate(**overrides):
    value = {
        "id": "node-gateway",
        "sourceId": "source-a",
        "graphId": "graph-a",
        "stableKey": "src/JarvisGateway.java|CALLABLE|JarvisGateway",
        "nodeKind": "CALLABLE",
        "name": "JarvisGateway",
        "label": "JarvisGateway",
        "qualifiedName": "example.JarvisGateway",
        "relativePath": "src/JarvisGateway.java",
        "summary": "Gateway into Jarvis orchestration.",
        "confidence": 1.0,
        "degree": 2,
    }
    value.update(overrides)
    return value


def matched_node(**overrides):
    value = candidate(**overrides)
    return KnowledgeQueryMatchedNode(
        sourceId=value["sourceId"],
        nodeId=value["id"],
        stableKey=value.get("stableKey") or value["id"],
        nodeKind=value["nodeKind"],
        label=value["label"],
        score=float(value.get("score", 0.98)),
        matchReasons=list(value.get("matchReasons") or ["EXACT_NAME"]),
        graphId=value.get("graphId"),
        graphRevision=value.get("graphRevision"),
        relativePath=value.get("relativePath"),
        qualifiedName=value.get("qualifiedName"),
    )


def graph_node(node_id, label, **overrides):
    value = {
        "id": node_id,
        "sourceId": "source-a",
        "graphId": "graph-a",
        "stableKey": node_id,
        "nodeKind": "CALLABLE",
        "label": label,
        "name": label,
    }
    value.update(overrides)
    return value


def graph_edge(edge_id, source, target=None, **overrides):
    value = {
        "id": edge_id,
        "sourceId": "source-a",
        "graphId": "graph-a",
        "fromNodeId": source,
        "toNodeId": target,
        "edgeType": "CALLS",
        "resolutionStatus": "RESOLVED" if target else "UNRESOLVED",
    }
    value.update(overrides)
    return value


def flow_graph_node(node_id, label, **overrides):
    value = {
        "source_id": "source-a",
        "graph_id": "graph-a",
        "graph_revision": "graph-a",
        "node_id": node_id,
        "stable_key": node_id,
        "node_kind": "CALLABLE",
        "label": label,
        "qualified_name": None,
        "relative_path": None,
        "entrypoint": False,
    }
    value.update(overrides)
    return FlowGraphNode(**value)


def flow_graph_edge(edge_id, source, target=None, **overrides):
    value = {
        "source_id": "source-a",
        "graph_id": "graph-a",
        "graph_revision": "graph-a",
        "edge_id": edge_id,
        "edge_type": "CALLS",
        "from_node_id": source,
        "to_node_id": target,
        "resolution_status": "RESOLVED" if target else "UNRESOLVED",
        "external": False,
        "unresolved_target": None,
        "evidence_ids": (),
    }
    value.update(overrides)
    return FlowGraphEdge(**value)


def default_nodes():
    return [
        graph_node("controller-create", "Controller.create"),
        graph_node("usecase-execute", "CreateUseCase.execute"),
        graph_node("repository-save", "Repository.save"),
    ]


def default_edges():
    return [
        graph_edge("calls-1", "controller-create", "usecase-execute"),
        graph_edge("calls-2", "usecase-execute", "repository-save"),
    ]


def default_evidence():
    return [
        {"id": "ev-1", "sourceId": "source-a", "edgeId": "calls-1", "relativePath": "src/Controller.java", "lineStart": 10, "lineEnd": 10},
        {"id": "ev-2", "sourceId": "source-a", "edgeId": "calls-2", "relativePath": "src/UseCase.java", "lineStart": 20, "lineEnd": 20},
    ]


def add_edge_evidence(
    db_path: Path,
    *,
    source_id: str,
    edge_id: str,
    evidence_id: str,
    relative_path: str,
    line_start: int,
    line_end=None,
    excerpt=None,
) -> None:
    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        template = conn.execute(
            """
            SELECT job_id, inventory_file_id, analysis_file_id, file_id, content_hash, created_at, updated_at
            FROM analysis_graph_evidence
            WHERE source_id = ?
            ORDER BY id
            LIMIT 1
            """,
            (source_id,),
        ).fetchone()
        assert template is not None
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_graph_evidence(
                id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                line_start, line_end, excerpt, excerpt_hash, evidence_kind, created_at, updated_at, fact_origin, flow_domain
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'EDGE', ?, ?, 'STATIC', 'CODE')
            """,
            (
                evidence_id,
                template["job_id"],
                source_id,
                template["inventory_file_id"],
                template["analysis_file_id"],
                template["file_id"],
                relative_path,
                template["content_hash"],
                line_start,
                line_end if line_end is not None else line_start,
                excerpt or f"excerpt-{evidence_id}",
                excerpt or f"excerpt-{evidence_id}",
                template["created_at"],
                template["updated_at"],
            ),
        )
        conn.execute(
            "INSERT OR IGNORE INTO analysis_graph_edge_evidence(edge_id, evidence_id) VALUES (?, ?)",
            (edge_id, evidence_id),
        )


def test_valid_query_plan_v2_request_passes():
    request = KnowledgeQueryRequest(**VALID_QUERY_REQUEST)

    assert request.queryText == "Як створюється сайт?"
    assert request.intent == "FLOW_EXPLANATION"
    assert request.answerLanguage == "uk"
    assert request.includeTests is False
    assert request.maxFlows == 10


def test_minimal_query_plan_v2_request_defaults_optional_controls():
    request = KnowledgeQueryRequest(**MINIMAL_QUERY_REQUEST)

    assert request.queryText == "Як створюється сайт?"
    assert request.intent == "UNKNOWN"
    assert request.answerLanguage == "en"
    assert request.includeTests is False
    assert request.maxFlows == 10


def test_null_and_blank_optional_controls_default_when_allowed():
    request = KnowledgeQueryRequest(
        **{
            **MINIMAL_QUERY_REQUEST,
            "intent": None,
            "answerLanguage": "   ",
            "includeTests": None,
            "maxFlows": None,
        }
    )

    assert request.intent == "UNKNOWN"
    assert request.answerLanguage == "en"
    assert request.includeTests is False
    assert request.maxFlows == 10


def test_answer_language_is_lowercase_normalized():
    request = KnowledgeQueryRequest(**{**VALID_QUERY_REQUEST, "answerLanguage": " UK "})

    assert request.answerLanguage == "uk"


def test_old_query_request_shape_is_rejected():
    old_payload = {"qu" + "ery": "Як створюється сайт?", "intent": "AU" + "TO"}

    with pytest.raises(ValueError):
        KnowledgeQueryRequest(**old_payload)


@pytest.mark.parametrize(
    "payload",
    [
        {key: value for key, value in VALID_QUERY_REQUEST.items() if key != "queryText"},
        {**VALID_QUERY_REQUEST, "queryText": "   "},
        {**VALID_QUERY_REQUEST, "intent": "AU" + "TO"},
        {**VALID_QUERY_REQUEST, "intent": "NOT_A_REAL_INTENT"},
        {**VALID_QUERY_REQUEST, "answerLanguage": 123},
        {**VALID_QUERY_REQUEST, "includeTests": "false"},
        {**VALID_QUERY_REQUEST, "maxFlows": "10"},
        {**VALID_QUERY_REQUEST, "maxFlows": 0},
        {**VALID_QUERY_REQUEST, "maxFlows": 999},
    ],
)
def test_query_plan_v2_required_field_strict_types_and_bounds(payload):
    with pytest.raises(ValueError):
        KnowledgeQueryRequest(**payload)


@pytest.mark.parametrize(
    "field,value",
    [
        ("query", "JarvisGateway"),
        ("semantic" + "Queries", ["flow search"]),
        ("literal" + "Identifiers", ["JarvisGateway"]),
        ("ent" + "ities", [{"name": "JarvisGateway"}]),
        ("from", "Controller"),
        ("to", "UseCase"),
        ("desired" + "Direction", "OUTBOUND"),
        ("sourceId", "source-a"),
        ("sourceIds", ["source-a"]),
        ("maxAnchors", 5),
        ("depth", 2),
        ("maxDepth", 3),
    ],
)
def test_public_retrieval_knobs_and_old_fields_are_not_accepted(field, value):
    payload = {**VALID_QUERY_REQUEST, field: value}
    with pytest.raises(ValueError):
        KnowledgeQueryRequest(**payload)


def test_auto_source_scope_resolves_all_current_graph_sources():
    store = FakeGraphStore(candidates=[candidate()])

    response = service(store).query(query_request("JarvisGateway"))

    assert response.status == "OK"
    assert store.source_searches[0][1] == ["source-a", "source-b"]
    assert response.coverage.searchedSourceCount == 2
    assert response.matchedNodes[0].sourceId == "source-a"


def test_knowledge_query_response_contract_remains_flow_oriented():
    response = service(FakeGraphStore(candidates=[candidate()])).query(query_request("JarvisGateway"))

    payload = response.dict()

    assert {"matchedSources", "matchedNodes", "flowPaths", "coverage", "diagnostics"} <= set(payload)
    assert "answer" not in payload
    assert payload["matchedNodes"][0]["nodeKind"] == "CALLABLE"
    assert "kind" not in payload["matchedNodes"][0]


def test_baseline_search_finds_by_node_name_stable_key_and_qualified_name():
    store = FakeGraphStore(
        candidates=[
            candidate(name="Different", label="Different", qualifiedName="example.JarvisGateway"),
            candidate(id="node-stable", name="Other", label="Other", qualifiedName="example.Other"),
        ]
    )

    response = service(store).query(query_request("JarvisGateway"))

    reasons = {reason for matched_node in response.matchedNodes for reason in matched_node.matchReasons}
    assert "QUALIFIED_NAME_MATCH" in reasons
    assert "STABLE_KEY_MATCH" in reasons


def test_candidate_pools_preserve_exact_match_when_semantic_supplements():
    store = FakeGraphStore(
        candidates=[
            candidate(id="site-controller", name="SiteController", label="SiteController", qualifiedName="app.SiteController"),
            candidate(
                id="semantic-site-service",
                name="CreateSiteService",
                label="CreateSiteService",
                qualifiedName="app.CreateSiteService",
                summary="Creates a site from a request.",
            ),
        ]
    )
    eligible_sources, _ = SourceScopeResolver(store).resolve()
    searcher = UnifiedAnchorSearcher(
        store,
        DeterministicCodeSearchEngine(extra_broad_providers=[StaticSemanticProvider("semantic-site-service")]),
    )

    result = searcher.search("SiteController", eligible_sources, KnowledgeQueryPolicy())

    assert any(node.nodeId == "site-controller" for node in result.pools[CandidatePoolKind.EXACT])
    assert any(node.nodeId == "semantic-site-service" for node in result.pools[CandidatePoolKind.SEMANTIC])
    assert result.all_candidates[0].nodeId == "site-controller"
    assert "SEMANTIC_VECTOR_SIMILARITY" not in result.all_candidates[0].matchReasons


def test_semantic_hit_outside_deterministic_document_set_is_hydrated(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    nodes = [
        {
            "id": "aaa-deterministic",
            "nodeKind": "CALLABLE",
            "name": "AardvarkAnchor",
            "qualified": "app.AardvarkAnchor",
            "path": "src/AardvarkAnchor.java",
        },
        {
            "id": "create-site-service",
            "nodeKind": "CALLABLE",
            "name": "CreateSiteService.create",
            "qualified": "app.CreateSiteService.create",
            "path": "src/CreateSiteService.java",
        },
    ]
    seed_semantic_graph(db_path, source_id="source-a", nodes=nodes)
    embedding_provider = FakeDeterministicEmbeddingProvider(model="fake-semantic")
    SemanticIndexBuilder(db_path, embedding_provider, config=SemanticBuildConfig(embedding_model=embedding_provider.model)).build(["source-a"], force=True)
    vector_store = StaticVectorStore(
        [
            SemanticVectorMatch(
                source_id="source-a",
                node_id="create-site-service",
                document_id="semantic-doc-create-site-service",
                similarity=0.97,
                document_type="NODE_CONTEXT",
            )
        ]
    )
    semantic_provider = SemanticCandidateProvider(
        db_path,
        embedding_provider,
        config=SemanticSearchConfig(min_similarity=0.0),
        vector_store=vector_store,
    )
    store = AnalysisStore(db_path)
    eligible_sources, _ = SourceScopeResolver(store).resolve()
    searcher = UnifiedAnchorSearcher(
        store,
        DeterministicCodeSearchEngine(extra_broad_providers=[semantic_provider]),
    )

    result = searcher.search("як створюється сайт", eligible_sources, KnowledgeQueryPolicy(max_search_documents=1, max_display_candidates=10))

    assert [document.node_id for document in searcher._hydrate_search_documents([("source-a", "create-site-service")], eligible_sources)] == [
        "create-site-service"
    ]
    assert any(node.nodeId == "create-site-service" for node in result.pools[CandidatePoolKind.SEMANTIC])
    semantic_candidate = next(node for node in result.all_candidates if node.nodeId == "create-site-service")
    assert "SEMANTIC_VECTOR_SIMILARITY" in semantic_candidate.matchReasons
    assert not any(diagnostic.code == "SEMANTIC_HIT_NOT_HYDRATED" for diagnostic in result.diagnostics)

    response = KnowledgeQueryService(
        SourceScopeResolver(store),
        searcher,
        GraphSliceQueryService(store),
        FlowPathExtractor(store),
        EvidenceBundleBuilder(),
        KnowledgeQueryPolicy(max_search_documents=1, max_display_candidates=10),
    ).query(query_request("як створюється сайт", intent="FLOW_EXPLANATION", answer_language="uk"))

    assert any(node.nodeId == "create-site-service" for node in response.matchedNodes)
    assert any(node.get("id") == "create-site-service" for node in response.nodes)


def test_claim_hydration_scopes_claims_to_requested_source_and_nodes(tmp_path, monkeypatch):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(
        db_path,
        source_id="source-a",
        nodes=[
            {"id": "requested-node", "nodeKind": "CALLABLE", "name": "Requested.run", "qualified": "Requested.run"},
            {"id": "other-node", "nodeKind": "CALLABLE", "name": "Other.run", "qualified": "Other.run"},
        ],
        claims=[
            {"id": "claim-requested", "node_id": "requested-node", "summary": "Requested responsibility."},
            {"id": "claim-other", "node_id": "other-node", "summary": "Other responsibility."},
        ],
    )
    seed_semantic_graph(
        db_path,
        source_id="source-b",
        nodes=[{"id": "foreign-node", "nodeKind": "CALLABLE", "name": "Foreign.run", "qualified": "Foreign.run"}],
        claims=[{"id": "claim-foreign", "node_id": "foreign-node", "summary": "Foreign responsibility."}],
    )
    statements = []
    original_connect = analysis_store_module.observed_connect

    def traced_connect(path, *, timeout):
        conn = original_connect(path, timeout=timeout)
        conn.set_trace_callback(statements.append)
        return conn

    monkeypatch.setattr(analysis_store_module, "observed_connect", traced_connect)

    documents = AnalysisStore(db_path).query_search_documents_by_node_ids([("source-a", "requested-node")], 1)

    assert documents[0]["summary"] == "Requested responsibility."
    claim_statements = [statement for statement in statements if "WITH claim AS" in statement]
    assert any("source_id IN ('source-a')" in statement for statement in claim_statements)
    assert any("node_id IN ('requested-node')" in statement for statement in claim_statements)
    assert any("claim_kind = 'RESPONSIBILITY'" in statement for statement in claim_statements)
    assert not any("other-node" in statement for statement in claim_statements)
    assert not any("source-b" in statement for statement in claim_statements)


def test_candidate_pool_dedup_merges_reasons_and_preserves_highest_score():
    store = FakeGraphStore(candidates=[candidate(id="site-controller", name="SiteController", label="SiteController")])
    eligible_sources, _ = SourceScopeResolver(store).resolve()
    result = UnifiedAnchorSearcher(store, StaticDuplicateSearchEngine()).search("SiteController", eligible_sources, KnowledgeQueryPolicy())

    assert len(result.all_candidates) == 1
    assert result.all_candidates[0].nodeId == "site-controller"
    assert result.all_candidates[0].score >= 0.98
    assert {"EXACT_NAME", "NAME_MATCH", "LEXICAL_TOKEN_OVERLAP"} <= set(result.all_candidates[0].matchReasons)
    assert result.pools[CandidatePoolKind.EXACT][0].nodeId == "site-controller"
    assert result.pools[CandidatePoolKind.LEXICAL][0].nodeId == "site-controller"


def test_candidate_pool_dedup_merges_exact_and_semantic_same_node():
    store = FakeGraphStore(candidates=[candidate(id="site-controller", name="SiteController", label="SiteController")])
    eligible_sources, _ = SourceScopeResolver(store).resolve()
    result = UnifiedAnchorSearcher(store, StaticExactSemanticDuplicateSearchEngine()).search(
        "SiteController",
        eligible_sources,
        KnowledgeQueryPolicy(),
    )

    assert len(result.all_candidates) == 1
    assert result.all_candidates[0].nodeId == "site-controller"
    assert result.all_candidates[0].score >= 0.98
    assert {"EXACT_NAME", "NAME_MATCH", "SEMANTIC_VECTOR_SIMILARITY"} <= set(result.all_candidates[0].matchReasons)
    assert result.pools[CandidatePoolKind.EXACT][0].nodeId == "site-controller"
    assert result.pools[CandidatePoolKind.SEMANTIC][0].nodeId == "site-controller"


def test_type_candidate_expands_declared_callables_as_flow_seeds():
    nodes = [
        graph_node("site-api-mapper", "SiteApiMapper", nodeKind="TYPE"),
        graph_node("as-create-command", "SiteApiMapper.asCreateSiteCommand", nodeKind="CALLABLE", parentNodeId="site-api-mapper"),
        graph_node("as-create-response", "SiteApiMapper.asCreateSiteResponseDTO", nodeKind="CALLABLE", parentNodeId="site-api-mapper"),
        graph_node("repository-save", "Repository.save", nodeKind="CALLABLE"),
    ]
    edges = [
        graph_edge("declares-command", "site-api-mapper", "as-create-command", edgeType="DECLARES"),
        graph_edge("declares-response", "site-api-mapper", "as-create-response", edgeType="DECLARES"),
        graph_edge("calls-save", "as-create-command", "repository-save"),
    ]
    store = FakeGraphStore(
        candidates=[candidate(id="site-api-mapper", nodeKind="TYPE", name="SiteApiMapper", label="SiteApiMapper")],
        nodes=nodes,
        edges=edges,
    )

    response = service(store, KnowledgeQueryPolicy(max_flow_paths=4)).query(query_request("SiteApiMapper"))

    requests, max_per_anchor, max_total = store.expansion_queries[-1]
    assert all(isinstance(item, AnchorExpansionRequest) for item in requests)
    assert requests == (
        AnchorExpansionRequest(
            source_id="source-a",
            graph_id="graph-a",
            graph_revision="graph-a",
            node_id="site-api-mapper",
        ),
    )
    assert max_per_anchor == 30
    assert max_total == 200
    assert [node.nodeId for node in response.matchedNodes] == ["site-api-mapper"]
    flow_seed_ids = store.adjacency_source_scopes[-1][0]["nodeIds"]
    assert flow_seed_ids == ["as-create-command", "as-create-response"]
    slice_anchor_ids = {node["nodeId"] for node in store.graph_slice_requests[-1]}
    assert {"site-api-mapper", "as-create-command", "as-create-response"} <= slice_anchor_ids
    assert "site-api-mapper" not in flow_seed_ids


def test_file_candidate_expands_contained_anchors_without_content_parsing():
    nodes = [
        graph_node("site-file", "SiteController.java", nodeKind="FILE"),
        graph_node("site-controller-type", "SiteController", nodeKind="TYPE", parentNodeId="site-file"),
        graph_node("create-site", "SiteController.create", nodeKind="CALLABLE", parentNodeId="site-controller-type"),
        graph_node("repository-save", "Repository.save", nodeKind="CALLABLE"),
    ]
    edges = [
        graph_edge("declares-type", "site-file", "site-controller-type", edgeType="DECLARES"),
        graph_edge("declares-create", "site-controller-type", "create-site", edgeType="DECLARES"),
        graph_edge("calls-save", "create-site", "repository-save"),
    ]
    store = FakeGraphStore(
        candidates=[candidate(id="site-file", nodeKind="FILE", name="SiteController.java", label="SiteController.java")],
        nodes=nodes,
        edges=edges,
    )

    response = service(store, KnowledgeQueryPolicy(max_flow_paths=4)).query(query_request("SiteController.java"))

    assert [node.nodeId for node in response.matchedNodes] == ["site-file"]
    assert store.expansion_queries
    assert store.adjacency_source_scopes[-1][0]["nodeIds"] == ["create-site"]
    slice_anchor_ids = {node["nodeId"] for node in store.graph_slice_requests[-1]}
    assert {"site-file", "site-controller-type", "create-site"} <= slice_anchor_ids


def test_field_candidate_expands_to_callables_that_use_field():
    nodes = [
        graph_node("foo-type", "FooType", nodeKind="TYPE"),
        graph_node("site-repository", "siteRepository", nodeKind="FIELD", parentNodeId="foo-type"),
        graph_node("save-site", "FooType.save", nodeKind="CALLABLE", parentNodeId="foo-type"),
    ]
    edges = [
        graph_edge("declares-field", "foo-type", "site-repository", edgeType="DECLARES"),
        graph_edge("uses-repository", "save-site", "site-repository", edgeType="USES_FIELD"),
    ]
    store = FakeGraphStore(
        candidates=[candidate(id="site-repository", nodeKind="FIELD", name="siteRepository", label="siteRepository")],
        nodes=nodes,
        edges=edges,
    )
    original_edges = [dict(edge) for edge in store.edges]

    service(store, KnowledgeQueryPolicy(max_flow_paths=4)).query(query_request("siteRepository"))

    assert store.adjacency_source_scopes[-1][0]["nodeIds"] == ["save-site"]
    slice_anchor_ids = {node["nodeId"] for node in store.graph_slice_requests[-1]}
    assert {"foo-type", "site-repository", "save-site"} <= slice_anchor_ids
    assert store.edges == original_edges


class StaticAnchorExpansionStore(FakeGraphStore):
    def __init__(self, bundle):
        super().__init__(nodes=[], edges=[])
        self.bundle = bundle

    def query_anchor_expansion(self, source_node_pairs, max_per_anchor=30, max_total=200):
        requests = tuple(source_node_pairs)
        self.expansion_queries.append((requests, max_per_anchor, max_total))
        assert all(isinstance(item, AnchorExpansionRequest) for item in requests)
        return self.bundle


def test_anchor_expansion_service_consumes_typed_bundle_contract():
    bundle = AnchorExpansionBundle(
        nodes=(
            AnchorExpansionNode(
                source_id="source-a",
                graph_id="graph-a",
                graph_revision="graph-a",
                node_id="foo-type",
                stable_key="foo-type",
                node_kind="TYPE",
                label="FooType",
            ),
            AnchorExpansionNode(
                source_id="source-a",
                graph_id="graph-a",
                graph_revision="graph-a",
                node_id="do-work",
                stable_key="do-work",
                node_kind="CALLABLE",
                label="FooType.doWork",
                parent_node_id="foo-type",
            ),
        ),
        edges=(
            AnchorExpansionEdge(
                source_id="source-a",
                graph_id="graph-a",
                graph_revision="graph-a",
                edge_id="declares-work",
                edge_type="DECLARES",
                from_node_id="foo-type",
                to_node_id="do-work",
            ),
        ),
    )
    store = StaticAnchorExpansionStore(bundle)
    eligible_sources, _ = SourceScopeResolver(store).resolve()

    result = AnchorExpansionService(store).expand(
        [matched_node(id="foo-type", nodeKind="TYPE", name="FooType", label="FooType")],
        eligible_sources,
        KnowledgeQueryPolicy(),
    )

    anchors_by_id = {anchor.node.nodeId: anchor for anchor in result.expanded_anchors}
    assert AnchorRole.FLOW_SEED in anchors_by_id["do-work"].roles
    assert AnchorExpansionReason.TYPE_DECLARED_CALLABLE in anchors_by_id["do-work"].reasons


def test_callable_candidate_stays_direct_seed_without_call_expansion():
    nodes = [
        graph_node("foo-type", "FooType", nodeKind="TYPE"),
        graph_node("caller", "Caller.call", nodeKind="CALLABLE"),
        graph_node("do-work", "FooType.doWork", nodeKind="CALLABLE", parentNodeId="foo-type"),
        graph_node("callee", "Callee.run", nodeKind="CALLABLE"),
    ]
    edges = [
        graph_edge("declares-do-work", "foo-type", "do-work", edgeType="DECLARES"),
        graph_edge("calls-in", "caller", "do-work"),
        graph_edge("calls-out", "do-work", "callee"),
    ]
    store = FakeGraphStore(
        candidates=[candidate(id="do-work", nodeKind="CALLABLE", name="FooType.doWork", label="FooType.doWork")],
        nodes=nodes,
        edges=edges,
    )

    service(store, KnowledgeQueryPolicy(max_flow_paths=4)).query(query_request("FooType.doWork"))

    assert store.adjacency_source_scopes[-1][0]["nodeIds"] == ["do-work"]
    slice_anchor_ids = {node["nodeId"] for node in store.graph_slice_requests[-1]}
    assert "do-work" in slice_anchor_ids
    assert "foo-type" in slice_anchor_ids
    assert "caller" not in slice_anchor_ids
    assert "callee" not in slice_anchor_ids


def test_entrypoint_candidate_role_requires_graph_fact():
    store = FakeGraphStore(
        nodes=[graph_node("site-controller", "SiteController", nodeKind="TYPE")],
        claims=[{"id": "entry-claim", "sourceId": "source-a", "nodeId": "site-controller", "claimKind": "ENTRYPOINT_HINT"}],
    )
    eligible_sources, _ = SourceScopeResolver(store).resolve()
    result = AnchorExpansionService(store).expand(
        [matched_node(id="site-controller", nodeKind="TYPE", name="SiteController", label="SiteController")],
        eligible_sources,
        KnowledgeQueryPolicy(),
    )

    anchor = next(anchor for anchor in result.expanded_anchors if anchor.node.nodeId == "site-controller")
    assert AnchorRole.ENTRYPOINT_CANDIDATE in anchor.roles
    assert AnchorExpansionReason.ENTRYPOINT_HINT in anchor.reasons

    no_claim_store = FakeGraphStore(nodes=[graph_node("site-controller", "SiteController", nodeKind="TYPE")])
    eligible_sources, _ = SourceScopeResolver(no_claim_store).resolve()
    no_claim_result = AnchorExpansionService(no_claim_store).expand(
        [matched_node(id="site-controller", nodeKind="TYPE", name="SiteController", label="SiteController")],
        eligible_sources,
        KnowledgeQueryPolicy(),
    )
    no_claim_anchor = next(anchor for anchor in no_claim_result.expanded_anchors if anchor.node.nodeId == "site-controller")
    assert AnchorRole.ENTRYPOINT_CANDIDATE not in no_claim_anchor.roles


def test_anchor_expansion_uses_generic_graph_facts_not_names():
    nodes = [
        graph_node("foo-type", "FooType", nodeKind="TYPE"),
        graph_node("bar-field", "BarField", nodeKind="FIELD", parentNodeId="foo-type"),
        graph_node("do-work", "doWork", nodeKind="CALLABLE", parentNodeId="foo-type"),
    ]
    edges = [
        graph_edge("declares-field", "foo-type", "bar-field", edgeType="DECLARES"),
        graph_edge("declares-work", "foo-type", "do-work", edgeType="DECLARES"),
    ]
    store = FakeGraphStore(nodes=nodes, edges=edges)
    eligible_sources, _ = SourceScopeResolver(store).resolve()

    result = AnchorExpansionService(store).expand(
        [matched_node(id="foo-type", nodeKind="TYPE", name="FooType", label="FooType")],
        eligible_sources,
        KnowledgeQueryPolicy(),
    )

    anchors_by_id = {anchor.node.nodeId: anchor for anchor in result.expanded_anchors}
    assert AnchorRole.FLOW_SEED in anchors_by_id["do-work"].roles
    assert AnchorExpansionReason.TYPE_DECLARED_CALLABLE in anchors_by_id["do-work"].reasons
    assert AnchorRole.CONTEXT in anchors_by_id["bar-field"].roles
    assert AnchorExpansionReason.TYPE_DECLARED_FIELD in anchors_by_id["bar-field"].reasons


def test_anchor_expansion_deduplicates_and_merges_roles_reasons_and_origins():
    nodes = [
        graph_node("foo-type", "FooType", nodeKind="TYPE"),
        graph_node("do-work", "FooType.doWork", nodeKind="CALLABLE", parentNodeId="foo-type"),
    ]
    edges = [graph_edge("declares-work", "foo-type", "do-work", edgeType="DECLARES")]
    store = FakeGraphStore(nodes=nodes, edges=edges)
    eligible_sources, _ = SourceScopeResolver(store).resolve()

    result = AnchorExpansionService(store).expand(
        [
            matched_node(id="foo-type", nodeKind="TYPE", name="FooType", label="FooType"),
            matched_node(id="do-work", nodeKind="CALLABLE", name="FooType.doWork", label="FooType.doWork"),
        ],
        eligible_sources,
        KnowledgeQueryPolicy(),
    )

    do_work_anchors = [anchor for anchor in result.expanded_anchors if anchor.node.nodeId == "do-work"]
    assert len(do_work_anchors) == 1
    anchor = do_work_anchors[0]
    assert {AnchorRole.ORIGINAL_CANDIDATE, AnchorRole.FLOW_SEED} <= set(anchor.roles)
    assert {AnchorExpansionReason.ORIGINAL_MATCH, AnchorExpansionReason.TYPE_DECLARED_CALLABLE} <= set(anchor.reasons)
    assert anchor.originNodeIds == ("do-work", "foo-type")


def test_anchor_expansion_safety_cap_preserves_original_and_trims_deterministically():
    nodes = [graph_node("foo-type", "FooType", nodeKind="TYPE")]
    edges = []
    for index in reversed(range(5)):
        node_id = f"method-{index}"
        nodes.append(graph_node(node_id, f"method{index}", nodeKind="CALLABLE", parentNodeId="foo-type"))
        edges.append(graph_edge(f"declares-{index}", "foo-type", node_id, edgeType="DECLARES"))
    store = FakeGraphStore(nodes=nodes, edges=edges)
    eligible_sources, _ = SourceScopeResolver(store).resolve()

    result = AnchorExpansionService(store).expand(
        [matched_node(id="foo-type", nodeKind="TYPE", name="FooType", label="FooType")],
        eligible_sources,
        KnowledgeQueryPolicy(max_expanded_anchors=2, max_anchor_expansion_per_candidate=10),
    )

    assert result.truncated is True
    assert any(diagnostic.code == "ANCHOR_EXPANSION_LIMIT_REACHED" for diagnostic in result.diagnostics)
    assert [anchor.node.nodeId for anchor in result.expanded_anchors] == ["foo-type", "method-0", "method-1"]
    assert [node.nodeId for node in result.flow_seed_nodes] == ["method-0", "method-1"]


def test_analysis_store_query_anchor_expansion_is_targeted_to_current_graph(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    graph_id = seed_semantic_graph(
        db_path,
        source_id="source-a",
        nodes=[
            {"id": "foo-file", "nodeKind": "FILE", "name": "Foo.java", "path": "src/Foo.java"},
            {"id": "foo-type", "nodeKind": "TYPE", "name": "FooType", "path": "src/Foo.java", "parent": "foo-file"},
            {"id": "do-work", "nodeKind": "CALLABLE", "name": "doWork", "path": "src/Foo.java", "parent": "foo-type"},
            {"id": "bar-field", "nodeKind": "FIELD", "name": "barField", "path": "src/Foo.java", "parent": "foo-type"},
            {"id": "save", "nodeKind": "CALLABLE", "name": "save", "path": "src/Foo.java", "parent": "foo-type"},
        ],
        edges=[
            {"id": "declares-type", "fromNodeId": "foo-file", "toNodeId": "foo-type", "edgeType": "DECLARES"},
            {"id": "declares-callable", "fromNodeId": "foo-type", "toNodeId": "do-work", "edgeType": "DECLARES"},
            {"id": "declares-field", "fromNodeId": "foo-type", "toNodeId": "bar-field", "edgeType": "DECLARES"},
            {"id": "uses-field", "fromNodeId": "save", "toNodeId": "bar-field", "edgeType": "USES_FIELD"},
        ],
        claims=[{"id": "entry-work", "node_id": "do-work", "claimKind": "ENTRYPOINT_HINT", "summary": "entry"}],
    )

    bundle = AnalysisStore(db_path).query_anchor_expansion(
        [AnchorExpansionRequest(source_id="source-a", graph_id=graph_id, graph_revision=graph_id, node_id="foo-type")],
        max_per_anchor=30,
        max_total=200,
    )

    assert isinstance(bundle, AnchorExpansionBundle)
    assert all(isinstance(node, AnchorExpansionNode) for node in bundle.nodes)
    assert all(isinstance(edge, AnchorExpansionEdge) for edge in bundle.edges)
    assert all(isinstance(hint, AnchorEntrypointHint) for hint in bundle.entrypoint_hints)
    node_ids = {node.node_id for node in bundle.nodes}
    edge_ids = {edge.edge_id for edge in bundle.edges}
    assert {"foo-type", "do-work", "bar-field"} <= node_ids
    assert {"declares-callable", "declares-field"} <= edge_ids
    assert "uses-field" not in edge_ids
    assert all(node.graph_id == graph_id for node in bundle.nodes)


def test_analysis_store_load_call_flow_graph_returns_typed_bundle(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    graph_id = seed_semantic_graph(
        db_path,
        source_id="source-a",
        nodes=[
            {"id": "a-start", "nodeKind": "CALLABLE", "name": "A.start", "path": "src/A.java"},
            {"id": "b-work", "nodeKind": "CALLABLE", "name": "B.work", "path": "src/B.java"},
            {"id": "c-finish", "nodeKind": "CALLABLE", "name": "C.finish", "path": "src/C.java"},
        ],
        edges=[
            {"id": "edge-a-b", "fromNodeId": "a-start", "toNodeId": "b-work", "edgeType": "CALLS"},
            {"id": "edge-b-c", "fromNodeId": "b-work", "toNodeId": "c-finish", "edgeType": "CALLS"},
        ],
    )

    bundle = AnalysisStore(db_path).load_call_flow_graph(
        [FlowGraphSourceScope(source_id="source-a", graph_id=graph_id, graph_revision=graph_id, node_ids=("b-work",))],
        max_edges=20,
        max_evidence=20,
    )

    assert isinstance(bundle, FlowGraphBundle)
    assert all(isinstance(node, FlowGraphNode) for node in bundle.nodes)
    assert all(isinstance(edge, FlowGraphEdge) for edge in bundle.edges)
    assert all(isinstance(item, FlowGraphEvidence) for item in bundle.evidence)
    assert {"a-start", "b-work", "c-finish"} <= {node.node_id for node in bundle.nodes}
    assert {"edge-a-b", "edge-b-c"} <= {edge.edge_id for edge in bundle.edges}
    assert all(node.graph_id == graph_id for node in bundle.nodes)
    node_by_id = {node.node_id: node for node in bundle.nodes}
    assert node_by_id["a-start"].relative_path == "src/A.java"
    assert node_by_id["a-start"].line_start == 1
    assert node_by_id["a-start"].line_end == 1


def test_call_flow_graph_edge_evidence_representatives_are_fair_by_edge(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    graph_id = seed_semantic_graph(
        db_path,
        source_id="source-a",
        nodes=[
            {"id": "a", "nodeKind": "CALLABLE", "name": "A.run", "path": "src/A.java"},
            {"id": "b", "nodeKind": "CALLABLE", "name": "B.run", "path": "src/B.java"},
            {"id": "c", "nodeKind": "CALLABLE", "name": "C.run", "path": "src/C.java"},
            {"id": "d", "nodeKind": "CALLABLE", "name": "D.run", "path": "src/D.java"},
        ],
        edges=[
            {"id": "edge-1", "fromNodeId": "a", "toNodeId": "b", "edgeType": "CALLS", "evidence_id": "e1"},
            {"id": "edge-2", "fromNodeId": "b", "toNodeId": "c", "edgeType": "CALLS", "evidence_id": "e3"},
            {"id": "edge-3", "fromNodeId": "c", "toNodeId": "d", "edgeType": "CALLS", "evidence_id": "e4"},
        ],
    )
    add_edge_evidence(db_path, source_id="source-a", edge_id="edge-1", evidence_id="e2", relative_path="src/A.java", line_start=2)
    add_edge_evidence(db_path, source_id="source-a", edge_id="edge-3", evidence_id="e5", relative_path="src/C.java", line_start=5)

    bundle = AnalysisStore(db_path).load_call_flow_graph(
        [FlowGraphSourceScope(source_id="source-a", graph_id=graph_id, graph_revision=graph_id, node_ids=("a",))],
        max_edges=10,
        max_evidence=3,
    )

    evidence_by_edge = {item.edge_id: item.evidence_id for item in bundle.evidence}
    assert len(bundle.evidence) == 3
    assert evidence_by_edge == {"edge-1": "e1", "edge-2": "e3", "edge-3": "e4"}


def test_call_flow_graph_evidence_budget_is_not_exceeded_and_truncates_honestly(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    graph_id = seed_semantic_graph(
        db_path,
        source_id="source-a",
        nodes=[
            {"id": "a", "nodeKind": "CALLABLE", "name": "A.run"},
            {"id": "b", "nodeKind": "CALLABLE", "name": "B.run"},
            {"id": "c", "nodeKind": "CALLABLE", "name": "C.run"},
            {"id": "d", "nodeKind": "CALLABLE", "name": "D.run"},
        ],
        edges=[
            {"id": "edge-1", "fromNodeId": "a", "toNodeId": "b", "edgeType": "CALLS", "evidence_id": "e1"},
            {"id": "edge-2", "fromNodeId": "b", "toNodeId": "c", "edgeType": "CALLS", "evidence_id": "e2"},
            {"id": "edge-3", "fromNodeId": "c", "toNodeId": "d", "edgeType": "CALLS", "evidence_id": "e3"},
        ],
    )

    bundle = AnalysisStore(db_path).load_call_flow_graph(
        [FlowGraphSourceScope(source_id="source-a", graph_id=graph_id, graph_revision=graph_id, node_ids=("a",))],
        max_edges=10,
        max_evidence=2,
    )

    assert len(bundle.evidence) == 2
    assert bundle.truncated is True


def test_returned_flow_unit_hydration_loads_only_returned_edge_evidence(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    graph_id = seed_semantic_graph(
        db_path,
        source_id="source-a",
        nodes=[
            {"id": "a", "nodeKind": "CALLABLE", "name": "A.start"},
            {"id": "b", "nodeKind": "CALLABLE", "name": "B.work"},
            {"id": "c", "nodeKind": "CALLABLE", "name": "C.finish"},
            {"id": "x", "nodeKind": "CALLABLE", "name": "X.start"},
            {"id": "y", "nodeKind": "CALLABLE", "name": "Y.work"},
        ],
        edges=[
            {"id": "edge-a-b", "fromNodeId": "a", "toNodeId": "b", "edgeType": "CALLS", "evidence_id": "ev-a-b"},
            {"id": "edge-b-c", "fromNodeId": "b", "toNodeId": "c", "edgeType": "CALLS", "evidence_id": "ev-b-c"},
            {"id": "edge-x-y", "fromNodeId": "x", "toNodeId": "y", "edgeType": "CALLS", "evidence_id": "ev-x-y"},
        ],
    )
    store = AnalysisStore(db_path)
    bundle = store.load_call_flow_graph(
        [
            FlowGraphSourceScope(source_id="source-a", graph_id=graph_id, graph_revision=graph_id, node_ids=("a",)),
            FlowGraphSourceScope(source_id="source-a", graph_id=graph_id, graph_revision=graph_id, node_ids=("x",)),
        ],
        max_edges=10,
        max_evidence=0,
    )
    result = FlowBuilder().build(
        bundle,
        [
            SimpleNamespace(sourceId="source-a", graphId=graph_id, graphRevision=graph_id, nodeId="a", nodeKind="CALLABLE", score=1.0, matchReasons=("EXACT_NAME",)),
            SimpleNamespace(sourceId="source-a", graphId=graph_id, graphRevision=graph_id, nodeId="x", nodeKind="CALLABLE", score=0.9, matchReasons=("EXACT_NAME",)),
        ],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    returned_unit = next(unit for unit in result.flow_units if "edge-a-b" in unit.edge_ids)

    hydration = store.hydrate_flow_unit_evidence((returned_unit,), max_evidence_refs=10)
    hydrated = hydration.flow_units[0]

    assert hydration.truncated is False
    assert set(hydrated.edge_ids) == {"edge-a-b", "edge-b-c"}
    assert {item.edge_id for item in hydrated.evidence} == {"edge-a-b", "edge-b-c"}
    assert "edge-x-y" not in {item.edge_id for item in hydrated.evidence}
    assert all(edge.evidence_ids for edge in hydrated.edges)


def test_returned_flow_unit_hydration_preserves_evidence_ownership(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    graph_id = seed_semantic_graph(
        db_path,
        source_id="source-a",
        evidence_ids=["node-a-evidence"],
        nodes=[
            {"id": "a", "nodeKind": "CALLABLE", "name": "A.start"},
            {"id": "b", "nodeKind": "CALLABLE", "name": "B.work"},
            {"id": "c", "nodeKind": "CALLABLE", "name": "C.finish"},
        ],
        edges=[
            {"id": "edge-a-b", "fromNodeId": "a", "toNodeId": "b", "edgeType": "CALLS", "evidence_id": "ev-a-b"},
            {"id": "edge-b-c", "fromNodeId": "b", "toNodeId": "c", "edgeType": "CALLS", "evidence_id": "ev-b-c"},
        ],
        claims=[{"id": "claim-a", "node_id": "a", "summary": "A starts the flow.", "evidence_ids": ["node-a-evidence"]}],
    )
    store = AnalysisStore(db_path)
    bundle = store.load_call_flow_graph(
        [FlowGraphSourceScope(source_id="source-a", graph_id=graph_id, graph_revision=graph_id, node_ids=("a",))],
        max_edges=10,
        max_evidence=0,
    )
    result = FlowBuilder().build(
        bundle,
        [SimpleNamespace(sourceId="source-a", graphId=graph_id, graphRevision=graph_id, nodeId="a", nodeKind="CALLABLE", score=1.0, matchReasons=("EXACT_NAME",))],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )

    hydrated = store.hydrate_flow_unit_evidence(result.flow_units[:1], max_evidence_refs=10).flow_units[0]

    evidence_ids_by_edge = {edge.edge_id: edge.evidence_ids for edge in hydrated.edges}
    assert evidence_ids_by_edge["edge-a-b"] == ("ev-a-b",)
    assert evidence_ids_by_edge["edge-b-c"] == ("ev-b-c",)
    assert "node-a-evidence" not in evidence_ids_by_edge["edge-a-b"]
    assert any(item.node_id == "a" and item.evidence_id == "node-a-evidence" for item in hydrated.evidence)


def test_hydrated_flow_bundle_deduplication_keeps_source_identity_for_same_evidence_id():
    node_a = flow_graph_node("a", "A.start")
    node_b = flow_graph_node("b", "B.work", source_id="source-b", graph_id="graph-b", graph_revision="graph-b")
    evidence_a = FlowGraphEvidence("source-a", "graph-a", "graph-a", "shared-evidence", "a", None, "src/A.java", 1, 1, "A")
    evidence_b = FlowGraphEvidence("source-b", "graph-b", "graph-b", "shared-evidence", "b", None, "src/B.java", 2, 2, "B")
    unit_a = FlowUnit(
        key=FlowUnitKey("source-a", "graph-a", ("a",), (), (), "TERMINAL_NODE"),
        origins=(FlowUnitOrigin("a", "source-a", "graph-a", 1.0, ("EXACT_NAME",)),),
        node_ids=("a",),
        edge_ids=(),
        boundary_edge_ids=(),
        nodes=(node_a,),
        edges=(),
        boundary_edges=(),
        evidence=(evidence_a,),
        complete=True,
        stop_reason=FlowStopReason.TERMINAL_NODE,
        root_stop_reason=FlowStopReason.TERMINAL_NODE,
        root_node_id="a",
        seed_node_ids=("a",),
        score=1.0,
    )
    unit_b = FlowUnit(
        key=FlowUnitKey("source-b", "graph-b", ("b",), (), (), "TERMINAL_NODE"),
        origins=(FlowUnitOrigin("b", "source-b", "graph-b", 1.0, ("EXACT_NAME",)),),
        node_ids=("b",),
        edge_ids=(),
        boundary_edge_ids=(),
        nodes=(node_b,),
        edges=(),
        boundary_edges=(),
        evidence=(evidence_b,),
        complete=True,
        stop_reason=FlowStopReason.TERMINAL_NODE,
        root_stop_reason=FlowStopReason.TERMINAL_NODE,
        root_node_id="b",
        seed_node_ids=("b",),
        score=1.0,
    )

    bundle = service(FakeGraphStore())._flow_bundle_from_units((unit_a, unit_b))

    assert len(bundle["evidence"]) == 2
    assert {(item["sourceId"], item["id"]) for item in bundle["evidence"]} == {
        ("source-a", "shared-evidence"),
        ("source-b", "shared-evidence"),
    }


def test_anchor_expansion_service_has_no_schema_alias_fallbacks():
    service_path = Path(__file__).parents[1] / "src" / "knowledge_service" / "knowledge_query_service.py"
    text = service_path.read_text()
    section = text[text.index("class AnchorExpansionService:") : text.index("class GraphSliceQueryService:")]

    forbidden_fragments = [
        'get("source' + 'Id") or',
        'get("source' + '_id")',
        'get("graph' + 'Id") or',
        'get("graph' + '_id")',
        'get("edge' + 'Type") or',
        'get("edge' + '_type")',
        'get("from' + 'NodeId") or',
        'get("from' + '_node_id")',
        'get("to' + 'NodeId") or',
        'get("to' + '_node_id")',
        'get("' + 'id") or',
        'get("edge' + 'Id")',
        'get("node' + 'Kind") or',
        'get("node' + '_kind")',
        "entrypoint" + "Hints",
        "bundle" + ".get(",
        "Dict" + "[str, Any]",
        "List" + "[Any]",
    ]
    for fragment in forbidden_fragments:
        assert fragment not in section


def test_flow_builder_seed_in_middle_uses_generic_typed_contract():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("a-start", "A.start"),
            flow_graph_node("b-work", "B.work"),
            flow_graph_node("c-finish", "C.finish"),
        ),
        edges=(
            flow_graph_edge("edge-a-b", "a-start", "b-work", evidence_ids=("ev-a-b",)),
            flow_graph_edge("edge-b-c", "b-work", "c-finish", evidence_ids=("ev-b-c",)),
        ),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-b", None, "edge-a-b", "src/A.java", 10, 10, "b.work();"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-b-c", None, "edge-b-c", "src/B.java", 20, 20, "c.finish();"),
        ),
    )

    result = FlowBuilder().build(
        bundle,
        [matched_node(id="b-work", name="B.work", label="B.work")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=4),
    )

    assert len(result.flow_paths) == 1
    flow = result.flow_paths[0]
    assert flow.nodeIds == ["a-start", "b-work", "c-finish"]
    assert flow.edgeIds == ["edge-a-b", "edge-b-c"]
    assert flow.evidenceIds == ["ev-a-b", "ev-b-c"]
    assert flow.complete is True
    assert flow.stopReason == "TERMINAL_NODE"


def test_flow_builder_seed_entrypoint_starts_at_seed():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("entry-start", "Entry.start", entrypoint=True),
            flow_graph_node("service-run", "Service.run"),
            flow_graph_node("repository-save", "Repository.save"),
        ),
        edges=(
            flow_graph_edge("edge-entry-service", "entry-start", "service-run"),
            flow_graph_edge("edge-service-save", "service-run", "repository-save"),
        ),
    )

    result = FlowBuilder().build(
        bundle,
        [matched_node(id="entry-start", name="Entry.start", label="Entry.start")],
        {("source-a", "graph-a", "entry-start")},
        KnowledgeQueryPolicy(max_flow_paths=4),
    )

    assert [flow.nodeIds for flow in result.flow_paths] == [["entry-start", "service-run", "repository-save"]]
    assert result.flow_paths[0].edgeIds == ["edge-entry-service", "edge-service-save"]


def test_flow_builder_entrypoint_is_graph_fact_driven_not_name_driven():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("site-controller", "SiteController"),
            flow_graph_node("foo-entry", "Foo", entrypoint=True),
            flow_graph_node("usecase-execute", "UseCase.execute"),
            flow_graph_node("repository-save", "Repository.save"),
        ),
        edges=(
            flow_graph_edge("edge-controller-usecase", "site-controller", "usecase-execute"),
            flow_graph_edge("edge-foo-usecase", "foo-entry", "usecase-execute"),
            flow_graph_edge("edge-usecase-save", "usecase-execute", "repository-save"),
        ),
    )

    result = FlowBuilder().build(
        bundle,
        [matched_node(id="usecase-execute", name="UseCase.execute", label="UseCase.execute")],
        {("source-a", "graph-a", "foo-entry")},
        KnowledgeQueryPolicy(max_flow_paths=4),
    )

    assert [flow.nodeIds for flow in result.flow_paths] == [
        ["foo-entry", "usecase-execute", "repository-save"],
        ["site-controller", "usecase-execute", "repository-save"],
    ]


def test_flow_builder_self_contained_units_preserve_shared_suffix_across_independent_flows():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("api-create", "ApiEntry.create"),
            flow_graph_node("kafka-consume", "KafkaEntry.consume"),
            flow_graph_node("job-run", "JobEntry.run"),
            flow_graph_node("usecase-execute", "UseCase.execute"),
            flow_graph_node("repository-save", "Repository.save"),
        ),
        edges=(
            flow_graph_edge("edge-api-usecase", "api-create", "usecase-execute"),
            flow_graph_edge("edge-kafka-usecase", "kafka-consume", "usecase-execute"),
            flow_graph_edge("edge-job-usecase", "job-run", "usecase-execute"),
            flow_graph_edge("edge-usecase-save", "usecase-execute", "repository-save"),
        ),
    )

    result = FlowBuilder().build(
        bundle,
        [matched_node(id="usecase-execute", name="UseCase.execute", label="UseCase.execute")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )

    assert len(result.flow_units) == 3
    assert sorted(unit.node_ids for unit in result.flow_units) == [
        ("api-create", "usecase-execute", "repository-save"),
        ("job-run", "usecase-execute", "repository-save"),
        ("kafka-consume", "usecase-execute", "repository-save"),
    ]
    assert sum(1 for unit in result.flow_units if "usecase-execute" in unit.node_ids) == 3
    assert sum(1 for unit in result.flow_units if "repository-save" in unit.node_ids) == 3
    assert all("same as previous" not in str(flow.dict()).lower() for flow in result.flow_paths)


def test_flow_explanation_llm_receives_one_flow_only_with_shared_suffix_repeated():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("a-start", "A.start"),
            flow_graph_node("d-start", "D.start"),
            flow_graph_node("b-work", "B.work"),
            flow_graph_node("c-finish", "C.finish"),
        ),
        edges=(
            flow_graph_edge("edge-a-b", "a-start", "b-work", evidence_ids=("ev-a-b",)),
            flow_graph_edge("edge-d-b", "d-start", "b-work", evidence_ids=("ev-d-b",)),
            flow_graph_edge("edge-b-c", "b-work", "c-finish", evidence_ids=("ev-b-c",)),
        ),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-b", None, "edge-a-b", "src/A.java", 10, 10, "b.work();"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-d-b", None, "edge-d-b", "src/D.java", 12, 12, "b.work();"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-b-c", None, "edge-b-c", "src/B.java", 20, 20, "c.finish();"),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="b-work", name="B.work", label="B.work")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    provider = RecordingFlowExplanationProvider()

    FlowExplanationService(provider).explain(query_request("B.work"), execution_from_flow_result(result))

    assert len(provider.calls) == 2
    first_symbols = [step["symbol"] for step in provider.calls[0]["llmInput"]["steps"]]
    second_symbols = [step["symbol"] for step in provider.calls[1]["llmInput"]["steps"]]
    assert first_symbols == ["A.start", "B.work", "C.finish"]
    assert second_symbols == ["D.start", "B.work", "C.finish"]
    assert "B.work" in first_symbols and "B.work" in second_symbols
    assert "C.finish" in first_symbols and "C.finish" in second_symbols
    assert "D.start" not in json.dumps(provider.calls[0]["llmInput"])
    assert "A.start" not in json.dumps(provider.calls[1]["llmInput"])
    assert "previous flow" not in json.dumps(provider.calls, ensure_ascii=False).lower()


def test_query_max_flows_truncation_updates_coverage_and_diagnostics():
    nodes = [
        graph_node("a-start", "A.start"),
        graph_node("d-start", "D.start"),
        graph_node("b-work", "B.work"),
    ]
    edges = [
        graph_edge("edge-a-b", "a-start", "b-work"),
        graph_edge("edge-d-b", "d-start", "b-work"),
    ]
    store = FakeGraphStore(
        candidates=[candidate(id="b-work", name="B.work", label="B.work")],
        nodes=nodes,
        edges=edges,
    )

    response = service(store, KnowledgeQueryPolicy(max_flow_paths=10)).query(query_request("B.work", max_flows=1))

    assert len(response.flowPaths) == 1
    assert response.coverage.truncated is True
    assert response.coverage.continuationAvailable is True
    diagnostic = next(item for item in response.diagnostics if item.code == "FLOW_QUERY_MAX_FLOWS_REACHED")
    assert diagnostic.metadata["returnedFlowCount"] == 1
    assert diagnostic.metadata["availableFlowCount"] == 2


def test_ui_flow_explanation_response_preserves_flow_paths_and_deep_narrative():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("a-start", "A.start"),
            flow_graph_node("b-work", "B.work"),
            flow_graph_node("c-finish", "C.finish"),
        ),
        edges=(
            flow_graph_edge("edge-a-b", "a-start", "b-work", evidence_ids=("ev-a-b",)),
            flow_graph_edge("edge-b-c", "b-work", "c-finish", evidence_ids=("ev-b-c",)),
        ),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-b", None, "edge-a-b", "src/A.java", 10, 10, "b.work();"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-b-c", None, "edge-b-c", "src/B.java", 20, 20, "c.finish();"),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    service_under_test = FlowExplanationService(RecordingFlowExplanationProvider())

    run = service_under_test.explain(query_request("A.start"), execution_from_flow_result(result))
    response = service_under_test.to_ui_response(run)

    assert response.flowPaths
    assert response.flowExplanations
    explanation = response.flowExplanations[0]
    assert explanation.flowIndex == 1
    assert len(explanation.narrative) == 2
    assert [step.order for step in explanation.steps] == [1, 2, 3]
    assert "answer" not in response.dict()


def test_codex_tool_response_excludes_internal_ids_and_includes_addresses_and_evidence():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("a-start", "A.start", relative_path="src/A.java"),
            flow_graph_node("b-work", "B.work", relative_path="src/B.java"),
            flow_graph_node("c-finish", "C.finish", relative_path="src/C.java"),
        ),
        edges=(
            flow_graph_edge("edge-a-b", "a-start", "b-work", evidence_ids=("ev-a-b",)),
            flow_graph_edge("edge-b-c", "b-work", "c-finish", evidence_ids=("ev-b-c",)),
        ),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-node", "a-start", None, "src/A.java", 10, 11, "class A { }"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-b", None, "edge-a-b", "src/A.java", 10, 11, "b.work();"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-b-c", None, "edge-b-c", "src/B.java", 20, 21, "c.finish();"),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    request = query_request("A.start", answer_language="uk")
    service_under_test = FlowExplanationService(RecordingFlowExplanationProvider())

    run = service_under_test.explain(request, execution_from_flow_result(result))
    response = service_under_test.to_tool_response(request, run)
    payload = response.dict()
    serialized = json.dumps(payload)

    assert "graphId" not in serialized
    assert "nodeId" not in serialized
    assert "edgeId" not in serialized
    assert "vector" not in serialized.lower()
    assert payload["queryText"] == "A.start"
    assert payload["answerLanguage"] == "uk"
    step = payload["flows"][0]["steps"][0]
    assert step["symbol"] == "A.start"
    assert step["address"]["service"] == "Source A"
    assert step["address"]["relativePath"] == "src/A.java"
    assert step["address"]["lineStart"] == 10
    assert step["evidence"][0]["excerpt"] == "class A { }"
    transition = payload["flows"][0]["transitions"][0]
    assert transition["fromOrder"] == 1
    assert transition["toOrder"] == 2
    assert transition["fromSymbol"] == "A.start"
    assert transition["toSymbol"] == "B.work"
    assert transition["explanation"]
    assert transition["evidence"][0]["relativePath"] == "src/A.java"
    assert transition["evidence"][0]["lineStart"] == 10
    assert payload["flows"][0]["narrative"]


def test_tool_step_address_uses_graph_node_lines_without_node_owned_evidence():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("a-start", "A.start", relative_path="src/A.java", line_start=7, line_end=9),
            flow_graph_node("b-work", "B.work", relative_path="src/B.java", line_start=20, line_end=22),
        ),
        edges=(flow_graph_edge("edge-a-b", "a-start", "b-work", evidence_ids=("ev-a-b",)),),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-b", None, "edge-a-b", "src/A.java", 40, 40, "b.work();"),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    request = query_request("A.start")
    service_under_test = FlowExplanationService(RecordingFlowExplanationProvider())

    payload = service_under_test.to_tool_response(request, service_under_test.explain(request, execution_from_flow_result(result))).dict()

    first_step = payload["flows"][0]["steps"][0]
    assert first_step["address"]["relativePath"] == "src/A.java"
    assert first_step["address"]["lineStart"] == 7
    assert first_step["address"]["lineEnd"] == 9
    assert payload["flows"][0]["transitions"][0]["evidence"][0]["lineStart"] == 40


def test_transition_evidence_does_not_replace_step_declaration_address():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("a-start", "A.start", relative_path="src/A.java", line_start=7, line_end=9),
            flow_graph_node("b-work", "B.work", relative_path="src/B.java", line_start=20, line_end=22),
        ),
        edges=(flow_graph_edge("edge-a-b", "a-start", "b-work", evidence_ids=("ev-a-b",)),),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-b", None, "edge-a-b", "src/A.java", 40, 40, "b.work();"),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    request = query_request("A.start")
    service_under_test = FlowExplanationService(RecordingFlowExplanationProvider())

    payload = service_under_test.to_tool_response(request, service_under_test.explain(request, execution_from_flow_result(result))).dict()

    assert payload["flows"][0]["steps"][0]["address"] == {
        "service": "Source A",
        "relativePath": "src/A.java",
        "lineStart": 7,
        "lineEnd": 9,
    }
    assert payload["flows"][0]["transitions"][0]["evidence"][0]["lineStart"] == 40


def test_tool_step_address_line_information_stays_null_without_node_or_declaration_range():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("a-start", "A.start", relative_path="src/A.java"),
            flow_graph_node("b-work", "B.work", relative_path="src/B.java"),
        ),
        edges=(flow_graph_edge("edge-a-b", "a-start", "b-work", evidence_ids=("ev-a-b",)),),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-b", None, "edge-a-b", "src/A.java", 40, 40, "b.work();"),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    request = query_request("A.start")
    service_under_test = FlowExplanationService(RecordingFlowExplanationProvider())

    payload = service_under_test.to_tool_response(request, service_under_test.explain(request, execution_from_flow_result(result))).dict()

    first_step = payload["flows"][0]["steps"][0]
    assert first_step["address"]["relativePath"] == "src/A.java"
    assert first_step["address"]["lineStart"] is None
    assert first_step["address"]["lineEnd"] is None
    assert payload["flows"][0]["transitions"][0]["evidence"][0]["lineStart"] == 40


def test_public_flow_bundle_round_trips_node_line_ranges_through_fallback_typed_node():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("a-start", "A.start", relative_path="src/A.java", line_start=7, line_end=9),
            flow_graph_node("b-work", "B.work", relative_path="src/B.java", line_start=20, line_end=22),
        ),
        edges=(flow_graph_edge("edge-a-b", "a-start", "b-work", evidence_ids=("ev-a-b",)),),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-b", None, "edge-a-b", "src/A.java", 40, 40, "b.work();"),
        ),
    )
    public_bundle = flow_graph_bundle_to_public_bundle(bundle)

    typed_bundle = FlowPathExtractor()._typed_bundle_from_public_graph(public_bundle)

    assert [(node.node_id, node.line_start, node.line_end) for node in typed_bundle.nodes] == [
        ("a-start", 7, 9),
        ("b-work", 20, 22),
    ]


def test_tool_transition_evidence_is_exact_callsite_and_internal_ids_are_not_serialized():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("a-start", "A.start", relative_path="src/A.java", line_start=7, line_end=9),
            flow_graph_node("b-work", "B.work", relative_path="src/B.java", line_start=20, line_end=22),
            flow_graph_node("c-finish", "C.finish", relative_path="src/C.java", line_start=30, line_end=31),
        ),
        edges=(
            flow_graph_edge("edge-a-b", "a-start", "b-work", evidence_ids=("ev-a-b",)),
            flow_graph_edge("edge-b-c", "b-work", "c-finish", evidence_ids=("ev-b-c",)),
        ),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-b", None, "edge-a-b", "src/A.java", 40, 40, "b.work();"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-b-c", None, "edge-b-c", "src/B.java", 50, 50, "c.finish();"),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    request = query_request("A.start")
    service_under_test = FlowExplanationService(RecordingFlowExplanationProvider())

    payload = service_under_test.to_tool_response(request, service_under_test.explain(request, execution_from_flow_result(result))).dict()
    serialized = json.dumps(payload)

    transitions = payload["flows"][0]["transitions"]
    assert [(item["fromOrder"], item["toOrder"]) for item in transitions] == [(1, 2), (2, 3)]
    assert transitions[0]["evidence"][0]["relativePath"] == "src/A.java"
    assert transitions[0]["evidence"][0]["lineStart"] == 40
    assert transitions[0]["evidence"][0]["excerpt"] == "b.work();"
    assert transitions[1]["evidence"][0]["relativePath"] == "src/B.java"
    assert transitions[1]["evidence"][0]["lineStart"] == 50
    assert transitions[1]["evidence"][0]["excerpt"] == "c.finish();"
    assert "edge-a-b" not in serialized
    assert "edge-b-c" not in serialized
    assert "nodeId" not in serialized
    assert "edgeId" not in serialized
    assert "graphId" not in serialized


def test_transition_validator_rejects_unrelated_edge_evidence_ref():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("a-start", "A.start"),
            flow_graph_node("b-work", "B.work"),
            flow_graph_node("c-finish", "C.finish"),
        ),
        edges=(
            flow_graph_edge("edge-a-b", "a-start", "b-work", evidence_ids=("ev-a-b",)),
            flow_graph_edge("edge-b-c", "b-work", "c-finish", evidence_ids=("ev-b-c",)),
        ),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-b", None, "edge-a-b", "src/A.java", 40, 40, "b.work();"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-b-c", None, "edge-b-c", "src/B.java", 50, 50, "c.finish();"),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    service_under_test = FlowExplanationService(RecordingFlowExplanationProvider())
    packed = service_under_test.packer.pack(
        request=query_request("A.start"),
        flow_unit=result.flow_units[0],
        flow_index=1,
        source_display_name="Source A",
    )
    response = valid_flow_explanation(packed.llm_input)
    wrong_ref = packed.llm_input["transitions"][1]["evidence"][0]["ref"]
    response["transitions"][0]["evidenceRefs"] = [wrong_ref]

    parsed, errors, code = FlowExplanationValidator().validate(json.dumps(response), packed)

    assert parsed is None
    assert code == FLOW_EXPLANATION_VALIDATION_FAILED
    assert f"evidence ref {wrong_ref} is not valid for transitionRef t1" in errors


def test_step_evidence_from_another_step_is_rejected_and_cannot_change_tool_address():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("a-start", "A.start", relative_path="src/A.java"),
            flow_graph_node("b-work", "B.work", relative_path="src/B.java"),
        ),
        edges=(flow_graph_edge("edge-a-b", "a-start", "b-work"),),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-node", "a-start", None, "src/A.java", 10, 12, "class A { }"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-b-node", "b-work", None, "src/B.java", 40, 44, "class B { }"),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    service_under_test = FlowExplanationService(RecordingFlowExplanationProvider())
    packed = service_under_test.packer.pack(
        request=query_request("A.start"),
        flow_unit=result.flow_units[0],
        flow_index=1,
        source_display_name="Source A",
    )
    step_1_ref = packed.llm_input["steps"][0]["evidence"][0]["ref"]
    step_2_ref = packed.llm_input["steps"][1]["evidence"][0]["ref"]
    assert step_1_ref != step_2_ref

    def wrong_step_evidence(llm_input, validation_errors=None):
        response = valid_flow_explanation(llm_input)
        response["steps"][0]["evidenceRefs"] = [step_2_ref]
        return response

    provider = RecordingFlowExplanationProvider([wrong_step_evidence, wrong_step_evidence])
    service_under_test = FlowExplanationService(provider)
    request = query_request("A.start")

    run = service_under_test.explain(request, execution_from_flow_result(result))
    response = service_under_test.to_tool_response(request, run).dict()

    assert len(provider.calls) == 2
    assert any(f"evidence ref {step_2_ref} is not valid for stepRef s1" in error for error in provider.calls[1]["validationErrors"])
    assert run.results[0].ok is False
    step = response["flows"][0]["steps"][0]
    assert step["address"]["relativePath"] == "src/A.java"
    assert step["address"]["lineStart"] == 10
    assert step["address"]["lineEnd"] == 12
    assert step["evidence"][0]["ref"] == step_1_ref


def test_flow_explanation_validator_rejects_invented_symbol_and_retry_can_recover():
    bundle = FlowGraphBundle(
        nodes=(flow_graph_node("a-start", "A.start"), flow_graph_node("b-work", "B.work")),
        edges=(flow_graph_edge("edge-a-b", "a-start", "b-work", evidence_ids=("ev-a-b",)),),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-node", "a-start", None, "src/A.java", 9, 9, "class A { }"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-b", None, "edge-a-b", "src/A.java", 10, 10, "b.work();"),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    def invented(llm_input, validation_errors=None):
        response = valid_flow_explanation(llm_input)
        response["title"] = "`A.start` calls `Z.missing`"
        response["narrative"][0]["text"] = "`A.start` calls `Z.missing` even though that target is not in the flow."
        response["steps"][0]["explanation"] = "`A.start` calls `Z.missing`."
        return response

    provider = RecordingFlowExplanationProvider([invented, valid_flow_explanation])

    run = FlowExplanationService(provider).explain(query_request("A.start"), execution_from_flow_result(result))

    assert len(provider.calls) == 2
    assert provider.calls[1]["validationErrors"]
    assert run.results[0].ok is True
    assert any(diagnostic.code == FLOW_EXPLANATION_VALIDATION_FAILED for diagnostic in run.diagnostics)


def test_flow_explanation_boundary_validation_requires_present_boundary():
    bundle = FlowGraphBundle(
        nodes=(flow_graph_node("a-start", "A.start"),),
        edges=(
            flow_graph_edge(
                "edge-external",
                "a-start",
                None,
                resolution_status="EXTERNAL_TARGET",
                external=True,
                unresolved_target={"name": "Remote.call"},
                evidence_ids=("ev-external",),
            ),
        ),
        evidence=(FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-external", None, "edge-external", "src/A.java", 30, 30, "remote.call();"),),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    def missing_boundary(llm_input, validation_errors=None):
        response = valid_flow_explanation(llm_input)
        response["boundaries"] = []
        return response

    provider = RecordingFlowExplanationProvider([missing_boundary, valid_flow_explanation])

    run = FlowExplanationService(provider).explain(query_request("A.start"), execution_from_flow_result(result))

    assert len(provider.calls) == 2
    assert "boundary refs must cover every input boundary; missing ['b1']" in provider.calls[1]["validationErrors"]
    assert run.results[0].ok is True
    assert run.results[0].explanation["boundaries"][0]["kind"] == "EXTERNAL_BOUNDARY"


def test_flow_explanation_validator_rejects_unknown_evidence_ref():
    bundle = FlowGraphBundle(
        nodes=(flow_graph_node("a-start", "A.start"), flow_graph_node("b-work", "B.work")),
        edges=(flow_graph_edge("edge-a-b", "a-start", "b-work", evidence_ids=("ev-a-b",)),),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-node", "a-start", None, "src/A.java", 9, 9, "class A { }"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-b", None, "edge-a-b", "src/A.java", 10, 10, "b.work();"),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    service_under_test = FlowExplanationService(RecordingFlowExplanationProvider())
    packed = service_under_test.packer.pack(
        request=query_request("A.start"),
        flow_unit=result.flow_units[0],
        flow_index=1,
        source_display_name="Source A",
    )
    raw = json.dumps(
        {
            "title": "`A.start` to `B.work`",
            "narrative": [
                {
                    "text": "`A.start` and `B.work` are explained from this packed flow context.",
                    "stepRefs": ["s1", "s2"],
                    "transitionRefs": ["t1"],
                    "boundaryRefs": [],
                },
                {
                    "text": "The ordered transition is grounded by the context and evidence refs.",
                    "stepRefs": ["s1", "s2"],
                    "transitionRefs": ["t1"],
                    "boundaryRefs": [],
                },
            ],
            "steps": [
                {"stepRef": "s1", "order": 1, "explanation": "`A.start` prepares the next transition.", "transitionRefs": ["t1"], "evidenceRefs": ["e999"]},
                {"stepRef": "s2", "order": 2, "explanation": "`B.work` runs.", "transitionRefs": [], "evidenceRefs": []},
            ],
            "transitions": [{"transitionRef": "t1", "explanation": "`A.start` leads to `B.work`.", "evidenceRefs": ["e999"]}],
            "boundaries": [],
        }
    )

    parsed, errors, code = FlowExplanationValidator().validate(raw, packed)

    assert parsed is None
    assert code == FLOW_EXPLANATION_VALIDATION_FAILED
    assert any("e999" in error for error in errors)


def test_flow_explanation_validator_rejects_ukrainian_invented_non_adjacent_call_by_transition_ref():
    bundle = FlowGraphBundle(
        nodes=(flow_graph_node("a-start", "A.start"), flow_graph_node("b-work", "B.work"), flow_graph_node("c-finish", "C.finish")),
        edges=(
            flow_graph_edge("edge-a-b", "a-start", "b-work"),
            flow_graph_edge("edge-b-c", "b-work", "c-finish"),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )

    def invented_transition(llm_input, validation_errors=None):
        response = valid_flow_explanation(llm_input)
        response["transitions"][0]["explanation"] = "`A.start` викликає `C.finish` напряму."
        return response

    provider = RecordingFlowExplanationProvider([invented_transition, valid_flow_explanation])

    run = FlowExplanationService(provider).explain(query_request("A.start", answer_language="uk"), execution_from_flow_result(result))

    assert len(provider.calls) == 2
    assert any("C.finish" in error and "not grounded by refs" in error for error in provider.calls[1]["validationErrors"])
    assert run.results[0].ok is True


def test_flow_explanation_validator_rejects_invented_non_dotted_symbol():
    bundle = FlowGraphBundle(
        nodes=(flow_graph_node("a-start", "A.start"), flow_graph_node("b-work", "B.work")),
        edges=(flow_graph_edge("edge-a-b", "a-start", "b-work"),),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )

    def invented_class(llm_input, validation_errors=None):
        response = valid_flow_explanation(llm_input)
        response["steps"][0]["explanation"] = "`MissingWorker` handles this step."
        return response

    provider = RecordingFlowExplanationProvider([invented_class, valid_flow_explanation])

    run = FlowExplanationService(provider).explain(query_request("A.start"), execution_from_flow_result(result))

    assert len(provider.calls) == 2
    assert any("MissingWorker" in error for error in provider.calls[1]["validationErrors"])
    assert run.results[0].ok is True


def test_flow_explanation_validator_rejects_backticked_lowercase_invented_method():
    bundle = FlowGraphBundle(
        nodes=(flow_graph_node("a-start", "A.start"), flow_graph_node("b-work", "B.work")),
        edges=(flow_graph_edge("edge-a-b", "a-start", "b-work"),),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )

    def invented_lowercase_method(llm_input, validation_errors=None):
        response = valid_flow_explanation(llm_input)
        response["steps"][0]["explanation"] = "`save` persists this step even though the method is not in context."
        return response

    provider = RecordingFlowExplanationProvider([invented_lowercase_method, valid_flow_explanation])

    run = FlowExplanationService(provider).explain(query_request("A.start"), execution_from_flow_result(result))

    assert len(provider.calls) == 2
    assert any("save" in error for error in provider.calls[1]["validationErrors"])
    assert run.results[0].ok is True


def test_flow_explanation_validator_does_not_reject_latin_language_prose():
    bundle = FlowGraphBundle(
        nodes=(flow_graph_node("a-start", "A.start"), flow_graph_node("b-work", "B.work")),
        edges=(flow_graph_edge("edge-a-b", "a-start", "b-work"),),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )

    def multilingual_prose(llm_input, validation_errors=None):
        response = valid_flow_explanation(llm_input)
        response["narrative"][0]["text"] = (
            "Le flux explique la premiere etape avec un contexte precis sans mentionner un symbole de code explicite."
        )
        response["narrative"][1]["text"] = (
            "Die zweite Beschreibung bleibt fachlich und Espanol tambien funciona porque no hay identificadores marcados."
        )
        return response

    provider = RecordingFlowExplanationProvider([multilingual_prose])

    run = FlowExplanationService(provider).explain(query_request("A.start", answer_language="fr"), execution_from_flow_result(result))

    assert len(provider.calls) == 1
    assert run.results[0].ok is True


def test_flow_explanation_validator_rejects_step_referencing_another_steps_transition():
    context = PackedFlowContext(
        flow_index=1,
        llm_input={
            "steps": [
                {"stepRef": "s1", "order": 1, "symbol": "A.start", "nodeLabel": "A.start", "qualifiedName": None},
                {"stepRef": "s2", "order": 2, "symbol": "B.work", "nodeLabel": "B.work", "qualifiedName": None},
                {"stepRef": "s3", "order": 3, "symbol": "C.finish", "nodeLabel": "C.finish", "qualifiedName": None},
            ],
            "transitions": [
                {"transitionRef": "t1", "fromStepRef": "s1", "toStepRef": "s2"},
                {"transitionRef": "t2", "fromStepRef": "s2", "toStepRef": "s3"},
            ],
            "boundaries": [],
        },
        evidence_by_ref={},
        evidence_id_by_ref={},
    )
    response = {
        "title": "`A.start` to `C.finish`",
        "narrative": [
            {
                "text": "`A.start` begins the flow and the ordered steps remain grounded in explicit references.",
                "stepRefs": ["s1", "s2", "s3"],
                "transitionRefs": ["t1", "t2"],
                "boundaryRefs": [],
            },
            {
                "text": "`B.work` continues to `C.finish` only through the recorded transitions from the context.",
                "stepRefs": ["s1", "s2", "s3"],
                "transitionRefs": ["t1", "t2"],
                "boundaryRefs": [],
            },
        ],
        "steps": [
            {"stepRef": "s1", "order": 1, "explanation": "`A.start` incorrectly references another transition.", "transitionRefs": ["t2"], "evidenceRefs": []},
            {"stepRef": "s2", "order": 2, "explanation": "`B.work` references its outgoing transition.", "transitionRefs": ["t2"], "evidenceRefs": []},
            {"stepRef": "s3", "order": 3, "explanation": "`C.finish` is terminal.", "transitionRefs": [], "evidenceRefs": []},
        ],
        "transitions": [
            {"transitionRef": "t1", "explanation": "`A.start` leads to `B.work`.", "evidenceRefs": []},
            {"transitionRef": "t2", "explanation": "`B.work` leads to `C.finish`.", "evidenceRefs": []},
        ],
        "boundaries": [],
    }

    parsed, errors, code = FlowExplanationValidator().validate(json.dumps(response), context)

    assert parsed is None
    assert code == FLOW_EXPLANATION_VALIDATION_FAILED
    assert any("stepRef s1 references another step's transition refs ['t2']" in error for error in errors)


@pytest.mark.parametrize(
    "mutator,expected",
    [
        (lambda response: response["transitions"].reverse(), "transition refs must preserve input order"),
        (lambda response: response["transitions"].append(dict(response["transitions"][0])), "transition refs must be unique"),
        (
            lambda response: response["transitions"].append(
                {"transitionRef": "t999", "explanation": "Extra transition.", "evidenceRefs": []}
            ),
            "transition refs are not present in the input flow",
        ),
        (lambda response: response["transitions"].pop(), "transition refs must cover every input transition"),
        (lambda response: response["steps"][0].update({"transitionRefs": ["t999"]}), "references unknown transitions"),
        (lambda response: response["steps"][0].update({"transitionRefs": []}), "must reference its exact outgoing transition refs"),
        (lambda response: response["steps"][2].update({"transitionRefs": ["t2"]}), "terminal stepRef s3 must not reference transitions"),
    ],
)
def test_flow_explanation_transition_refs_reject_reordered_duplicate_extra_missing_unknown_and_terminal(mutator, expected):
    context = PackedFlowContext(
        flow_index=1,
        llm_input={
            "steps": [
                {"stepRef": "s1", "order": 1, "symbol": "A.start", "nodeLabel": "A.start", "qualifiedName": None},
                {"stepRef": "s2", "order": 2, "symbol": "B.work", "nodeLabel": "B.work", "qualifiedName": None},
                {"stepRef": "s3", "order": 3, "symbol": "C.finish", "nodeLabel": "C.finish", "qualifiedName": None},
            ],
            "transitions": [
                {"transitionRef": "t1", "fromStepRef": "s1", "toStepRef": "s2", "evidence": []},
                {"transitionRef": "t2", "fromStepRef": "s2", "toStepRef": "s3", "evidence": []},
            ],
            "boundaries": [],
        },
        evidence_by_ref={},
        evidence_id_by_ref={},
    )
    response = {
        "title": "`A.start` to `C.finish`",
        "narrative": [
            {
                "text": "`A.start` begins the flow and the ordered steps remain grounded in explicit references.",
                "stepRefs": ["s1", "s2", "s3"],
                "transitionRefs": ["t1", "t2"],
                "boundaryRefs": [],
            },
            {
                "text": "`B.work` continues to `C.finish` only through the recorded transitions from the context.",
                "stepRefs": ["s1", "s2", "s3"],
                "transitionRefs": ["t1", "t2"],
                "boundaryRefs": [],
            },
        ],
        "steps": [
            {"stepRef": "s1", "order": 1, "explanation": "`A.start` references its outgoing transition.", "transitionRefs": ["t1"], "evidenceRefs": []},
            {"stepRef": "s2", "order": 2, "explanation": "`B.work` references its outgoing transition.", "transitionRefs": ["t2"], "evidenceRefs": []},
            {"stepRef": "s3", "order": 3, "explanation": "`C.finish` is terminal.", "transitionRefs": [], "evidenceRefs": []},
        ],
        "transitions": [
            {"transitionRef": "t1", "explanation": "`A.start` leads to `B.work`.", "evidenceRefs": []},
            {"transitionRef": "t2", "explanation": "`B.work` leads to `C.finish`.", "evidenceRefs": []},
        ],
        "boundaries": [],
    }
    mutator(response)

    parsed, errors, code = FlowExplanationValidator().validate(json.dumps(response), context)

    assert parsed is None
    assert code == FLOW_EXPLANATION_VALIDATION_FAILED
    assert any(expected in error for error in errors)


def test_flow_explanation_validator_accepts_valid_boundary_target_mention():
    bundle = FlowGraphBundle(
        nodes=(flow_graph_node("a-start", "A.start"),),
        edges=(
            flow_graph_edge(
                "edge-external",
                "a-start",
                None,
                resolution_status="EXTERNAL_TARGET",
                external=True,
                unresolved_target={"name": "Remote.call"},
            ),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )

    def mentions_target(llm_input, validation_errors=None):
        response = valid_flow_explanation(llm_input)
        response["boundaries"][0]["explanation"] = "The flow reaches the allowed `Remote.call` external target."
        response["narrative"][1]["text"] = "The ordered path ends at `Remote.call` because the boundary fact is present in this flow."
        response["narrative"][1]["boundaryRefs"] = ["b1"]
        return response

    provider = RecordingFlowExplanationProvider([mentions_target])

    run = FlowExplanationService(provider).explain(query_request("A.start"), execution_from_flow_result(result))

    assert len(provider.calls) == 1
    assert run.results[0].ok is True


@pytest.mark.parametrize(
    "mutator,expected",
    [
        (lambda response: response["boundaries"].reverse(), "boundary refs must preserve input order"),
        (lambda response: response["boundaries"].append(dict(response["boundaries"][0])), "boundary refs must be unique"),
        (
            lambda response: response["boundaries"].append(
                {"boundaryRef": "b999", "kind": "EXTERNAL_BOUNDARY", "explanation": "Extra boundary.", "evidenceRefs": []}
            ),
            "boundary refs are not present in the input flow",
        ),
        (lambda response: response["boundaries"].pop(), "boundary refs must cover every input boundary"),
    ],
)
def test_flow_explanation_boundary_refs_reject_reordered_duplicate_extra_and_missing(mutator, expected):
    context = PackedFlowContext(
        flow_index=1,
        llm_input={
            "steps": [{"stepRef": "s1", "order": 1, "symbol": "A.start", "nodeLabel": "A.start", "qualifiedName": None}],
            "transitions": [],
            "boundaries": [
                {"boundaryRef": "b1", "kind": "EXTERNAL_BOUNDARY", "target": "Remote.one", "evidence": []},
                {"boundaryRef": "b2", "kind": "UNRESOLVED_BOUNDARY", "target": "Remote.two", "evidence": []},
            ],
        },
        evidence_by_ref={},
        evidence_id_by_ref={},
    )
    response = {
        "title": "`A.start` boundaries",
        "narrative": [
            {
                "text": "`A.start` reaches the first boundary and then the second boundary from explicit facts.",
                "stepRefs": ["s1"],
                "transitionRefs": [],
                "boundaryRefs": ["b1", "b2"],
            },
            {
                "text": "`Remote.one` and `Remote.two` are allowed targets because both are present in the packed context.",
                "stepRefs": ["s1"],
                "transitionRefs": [],
                "boundaryRefs": ["b1", "b2"],
            },
        ],
        "steps": [{"stepRef": "s1", "order": 1, "explanation": "`A.start` reaches boundaries.", "transitionRefs": [], "evidenceRefs": []}],
        "transitions": [],
        "boundaries": [
            {"boundaryRef": "b1", "kind": "EXTERNAL_BOUNDARY", "explanation": "`Remote.one` is external.", "evidenceRefs": []},
            {"boundaryRef": "b2", "kind": "UNRESOLVED_BOUNDARY", "explanation": "`Remote.two` is unresolved.", "evidenceRefs": []},
        ],
    }
    mutator(response)

    parsed, errors, code = FlowExplanationValidator().validate(json.dumps(response), context)

    assert parsed is None
    assert code == FLOW_EXPLANATION_VALIDATION_FAILED
    assert any(expected in error for error in errors)


def test_ui_flow_explanation_evidence_refs_resolve_to_same_response_evidence():
    bundle = FlowGraphBundle(
        nodes=(flow_graph_node("a-start", "A.start"), flow_graph_node("b-work", "B.work")),
        edges=(flow_graph_edge("edge-a-b", "a-start", "b-work", evidence_ids=("ev-a-b",)),),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-node", "a-start", None, "src/A.java", 9, 9, "class A { }"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-b", None, "edge-a-b", "src/A.java", 10, 10, "b.work();"),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    service_under_test = FlowExplanationService(RecordingFlowExplanationProvider())

    response = service_under_test.to_ui_response(service_under_test.explain(query_request("A.start"), execution_from_flow_result(result)))

    evidence_ids = {item["id"] for item in response.dict()["evidence"]}
    refs = {ref for explanation in response.flowExplanations for step in explanation.steps for ref in step.evidenceRefs}
    assert refs
    assert refs <= evidence_ids


def test_tool_context_boundary_target_does_not_expose_unresolved_target_metadata():
    bundle = FlowGraphBundle(
        nodes=(flow_graph_node("a-start", "A.start"),),
        edges=(
            flow_graph_edge(
                "edge-external",
                "a-start",
                None,
                resolution_status="EXTERNAL_TARGET",
                external=True,
                unresolved_target={
                    "name": "Remote.call",
                    "graphId": "leaked-graph",
                    "nodeId": "leaked-node",
                    "databaseId": "leaked-db",
                    "vectorId": "leaked-vector",
                },
            ),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    request = query_request("A.start")
    service_under_test = FlowExplanationService(RecordingFlowExplanationProvider())

    payload = service_under_test.to_tool_response(request, service_under_test.explain(request, execution_from_flow_result(result))).dict()
    serialized = json.dumps(payload)

    assert payload["flows"][0]["boundaries"][0]["target"] == "Remote.call"
    assert "leaked-graph" not in serialized
    assert "leaked-node" not in serialized
    assert "leaked-db" not in serialized
    assert "leaked-vector" not in serialized


def test_total_explanation_deadline_stops_remaining_flow_calls():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("a-start", "A.start"),
            flow_graph_node("d-start", "D.start"),
            flow_graph_node("b-work", "B.work"),
        ),
        edges=(
            flow_graph_edge("edge-a-b", "a-start", "b-work"),
            flow_graph_edge("edge-d-b", "d-start", "b-work"),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="b-work", name="B.work", label="B.work")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    provider = RecordingFlowExplanationProvider(delay_seconds=0.02)

    run = FlowExplanationService(provider, request_deadline_seconds=0.03).explain(query_request("B.work"), execution_from_flow_result(result))

    assert len(provider.calls) == 1
    assert [item.ok for item in run.results] == [True, False]
    assert any(diagnostic.code == FLOW_EXPLANATION_LIMIT_REACHED and diagnostic.metadata["flowIndex"] == 2 for diagnostic in run.diagnostics)


def test_invalid_first_response_consumes_budget_and_retry_is_not_started():
    bundle = FlowGraphBundle(
        nodes=(flow_graph_node("a-start", "A.start"), flow_graph_node("b-work", "B.work")),
        edges=(flow_graph_edge("edge-a-b", "a-start", "b-work"),),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )

    def invalid_one_line(llm_input, validation_errors=None):
        response = valid_flow_explanation(llm_input)
        response["narrative"] = [{"text": "Too short.", "stepRefs": ["s1"], "transitionRefs": [], "boundaryRefs": []}]
        return response

    provider = RecordingFlowExplanationProvider([invalid_one_line], delay_seconds=0.02)

    run = FlowExplanationService(provider, request_deadline_seconds=0.028).explain(
        query_request("A.start"),
        execution_from_flow_result(result),
    )

    assert len(provider.calls) == 1
    assert provider.calls[0]["timeoutSeconds"] is not None
    assert run.results[0].ok is False
    assert any(diagnostic.code == FLOW_EXPLANATION_LIMIT_REACHED for diagnostic in run.results[0].diagnostics)


def test_in_progress_llm_call_is_bounded_by_remaining_timeout():
    bundle = FlowGraphBundle(
        nodes=(flow_graph_node("a-start", "A.start"), flow_graph_node("b-work", "B.work")),
        edges=(flow_graph_edge("edge-a-b", "a-start", "b-work"),),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )

    class TimeoutProvider:
        def __init__(self):
            self.calls = []

        def complete(self, llm_input, validation_errors=None, timeout_seconds=None):
            self.calls.append({"timeoutSeconds": timeout_seconds, "validationErrors": list(validation_errors or [])})
            raise TimeoutError("simulated provider timeout")

    provider = TimeoutProvider()

    started = time.monotonic()
    run = FlowExplanationService(provider, request_deadline_seconds=0.05).explain(
        query_request("A.start"),
        execution_from_flow_result(result),
    )
    elapsed = time.monotonic() - started

    assert len(provider.calls) == 1
    assert 0 < provider.calls[0]["timeoutSeconds"] <= 0.05
    assert elapsed < 0.05
    assert run.results[0].ok is False
    assert any(diagnostic.code == FLOW_EXPLANATION_LIMIT_REACHED for diagnostic in run.results[0].diagnostics)


def test_cancellation_event_stops_subsequent_flow_calls():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("a-start", "A.start"),
            flow_graph_node("d-start", "D.start"),
            flow_graph_node("b-work", "B.work"),
        ),
        edges=(
            flow_graph_edge("edge-a-b", "a-start", "b-work"),
            flow_graph_edge("edge-d-b", "d-start", "b-work"),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="b-work", name="B.work", label="B.work")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    cancel_event = threading.Event()

    def cancel_after_first_call(llm_input, validation_errors=None):
        cancel_event.set()
        return valid_flow_explanation(llm_input)

    provider = RecordingFlowExplanationProvider([cancel_after_first_call, valid_flow_explanation])

    run = FlowExplanationService(provider, cancel_event=cancel_event).explain(
        query_request("B.work"),
        execution_from_flow_result(result),
    )

    assert len(provider.calls) == 1
    assert [item.ok for item in run.results] == [False, False]
    assert all(
        any(diagnostic.code == FLOW_EXPLANATION_LIMIT_REACHED for diagnostic in result.diagnostics)
        for result in run.results
    )


def test_one_line_narrative_is_rejected_and_retried_once():
    bundle = FlowGraphBundle(
        nodes=(flow_graph_node("a-start", "A.start"), flow_graph_node("b-work", "B.work")),
        edges=(flow_graph_edge("edge-a-b", "a-start", "b-work"),),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )

    def one_line(llm_input, validation_errors=None):
        response = valid_flow_explanation(llm_input)
        response["narrative"] = [{"text": "A.start flows.", "stepRefs": ["s1"], "transitionRefs": [], "boundaryRefs": []}]
        return response

    provider = RecordingFlowExplanationProvider([one_line, valid_flow_explanation])

    run = FlowExplanationService(provider).explain(query_request("A.start"), execution_from_flow_result(result))

    assert len(provider.calls) == 2
    assert any("at least two grounded blocks" in error for error in provider.calls[1]["validationErrors"])
    assert run.results[0].ok is True


def test_one_long_narrative_block_is_rejected_and_retried_once():
    bundle = FlowGraphBundle(
        nodes=(flow_graph_node("a-start", "A.start"), flow_graph_node("b-work", "B.work")),
        edges=(flow_graph_edge("edge-a-b", "a-start", "b-work"),),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )

    def one_long_block(llm_input, validation_errors=None):
        response = valid_flow_explanation(llm_input)
        response["narrative"] = [
            {
                "text": (
                    "This detailed paragraph describes the ordered flow, explains the grounded transition, "
                    "connects the first step to the second step, and still remains a single narrative block."
                ),
                "stepRefs": ["s1", "s2"],
                "transitionRefs": ["t1"],
                "boundaryRefs": [],
            }
        ]
        return response

    provider = RecordingFlowExplanationProvider([one_long_block, valid_flow_explanation])

    run = FlowExplanationService(provider).explain(query_request("A.start"), execution_from_flow_result(result))

    assert len(provider.calls) == 2
    assert any("at least two grounded blocks" in error for error in provider.calls[1]["validationErrors"])
    assert run.results[0].ok is True


def test_one_failed_flow_does_not_kill_other_flow_tool_context():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("a-start", "A.start"),
            flow_graph_node("d-start", "D.start"),
            flow_graph_node("b-work", "B.work"),
            flow_graph_node("c-finish", "C.finish"),
        ),
        edges=(
            flow_graph_edge("edge-a-b", "a-start", "b-work", evidence_ids=("ev-a-b",)),
            flow_graph_edge("edge-d-b", "d-start", "b-work", evidence_ids=("ev-d-b",)),
            flow_graph_edge("edge-b-c", "b-work", "c-finish", evidence_ids=("ev-b-c",)),
        ),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-b", None, "edge-a-b", "src/A.java", 10, 10, "b.work();"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-d-b", None, "edge-d-b", "src/D.java", 12, 12, "b.work();"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-b-c", None, "edge-b-c", "src/B.java", 20, 20, "c.finish();"),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="b-work", name="B.work", label="B.work")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    def invalid(llm_input, validation_errors=None):
        response = valid_flow_explanation(llm_input)
        response["title"] = "`A.start` calls `Z.missing`"
        response["steps"][0]["explanation"] = "`A.start` calls `Z.missing`."
        return response

    provider = RecordingFlowExplanationProvider([valid_flow_explanation, invalid, invalid])
    request = query_request("B.work")
    service_under_test = FlowExplanationService(provider)

    run = service_under_test.explain(request, execution_from_flow_result(result))
    response = service_under_test.to_tool_response(request, run)

    assert [flow.ok for flow in run.results] == [True, False]
    assert response.status == KnowledgeQueryStatus.OK
    assert response.flows[0].narrative
    assert response.flows[0].status == "OK"
    assert response.flows[1].status == "FAILED"
    assert response.flows[1].narrative == []
    assert response.flows[1].steps
    assert response.flows[1].transitions
    assert all(transition.explanation is None for transition in response.flows[1].transitions)
    assert all(transition.evidence for transition in response.flows[1].transitions)
    assert any(diagnostic.code == FLOW_EXPLANATION_VALIDATION_FAILED for diagnostic in response.flows[1].diagnostics)


def test_flow_explanation_context_excludes_unrelated_flows_and_raw_debug_fields():
    bundle = FlowGraphBundle(
        nodes=(flow_graph_node("a-start", "A.start"), flow_graph_node("b-work", "B.work"), flow_graph_node("c-finish", "C.finish")),
        edges=(
            flow_graph_edge("edge-a-b", "a-start", "b-work", evidence_ids=("ev-a-b",)),
            flow_graph_edge("edge-b-c", "b-work", "c-finish", evidence_ids=("ev-b-c",)),
        ),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-b", None, "edge-a-b", "src/A.java", 10, 10, "b.work();"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-b-c", None, "edge-b-c", "src/B.java", 20, 20, "c.finish();"),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    provider = RecordingFlowExplanationProvider()

    FlowExplanationService(provider).explain(query_request("A.start"), execution_from_flow_result(result))
    packed_context = provider.calls[0]["llmInput"]
    serialized = json.dumps(packed_context)

    assert "graphId" not in serialized
    assert "nodeId" not in serialized
    assert "edgeId" not in serialized
    assert "vector" not in serialized.lower()
    assert "debug" not in serialized.lower()
    assert [step["symbol"] for step in packed_context["steps"]] == ["A.start", "B.work", "C.finish"]


def test_tool_transition_evidence_uses_edge_evidence_ids_when_evidence_row_has_no_edge_id():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("a-start", "A.start", relative_path="src/A.java", line_start=1, line_end=4),
            flow_graph_node("b-work", "B.work", relative_path="src/B.java", line_start=10, line_end=12),
        ),
        edges=(flow_graph_edge("edge-a-b", "a-start", "b-work", evidence_ids=("ev-a-b",)),),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-b", None, None, "src/A.java", 3, 3, "b.work();"),
        ),
    )
    result = FlowBuilder().build(
        bundle,
        [matched_node(id="a-start", name="A.start", label="A.start")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    service = FlowExplanationService(RecordingFlowExplanationProvider())

    run = service.explain(query_request("A.start"), execution_from_flow_result(result))
    response = service.to_tool_response(query_request("A.start"), run)

    assert result.flow_paths[0].evidenceIds == ["ev-a-b"]
    transition = response.flows[0].transitions[0]
    assert transition.fromSymbol == "A.start"
    assert transition.toSymbol == "B.work"
    assert [(item.relativePath, item.lineStart, item.lineEnd, item.excerpt) for item in transition.evidence] == [
        ("src/A.java", 3, 3, "b.work();")
    ]


def test_flow_builder_preserves_shared_prefix_side_effect_branches_in_evidence_order():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("usecase-execute", "UseCase.execute"),
            flow_graph_node("repository-save", "Repository.save"),
            flow_graph_node("event-publish", "EventPublisher.publish"),
            flow_graph_node("audit-write", "AuditWriter.write"),
        ),
        edges=(
            flow_graph_edge("edge-audit", "usecase-execute", "audit-write", evidence_ids=("ev-audit",)),
            flow_graph_edge("edge-publish", "usecase-execute", "event-publish", evidence_ids=("ev-publish",)),
            flow_graph_edge("edge-save", "usecase-execute", "repository-save", evidence_ids=("ev-save",)),
        ),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-save", None, "edge-save", "src/UseCase.java", 10, 10, "save();"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-publish", None, "edge-publish", "src/UseCase.java", 20, 20, "publish();"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-audit", None, "edge-audit", "src/UseCase.java", 30, 30, "audit();"),
        ),
    )

    result = FlowBuilder().build(
        bundle,
        [matched_node(id="usecase-execute", name="UseCase.execute", label="UseCase.execute")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )

    assert [flow.nodeIds for flow in result.flow_paths] == [
        ["usecase-execute", "repository-save"],
        ["usecase-execute", "event-publish"],
        ["usecase-execute", "audit-write"],
    ]
    assert [flow.evidenceIds for flow in result.flow_paths] == [["ev-save"], ["ev-publish"], ["ev-audit"]]


def test_flow_builder_exact_duplicate_path_merge_only_keeps_complete_unit():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("controller-create", "Controller.create"),
            flow_graph_node("usecase-execute", "UseCase.execute"),
            flow_graph_node("repository-save", "Repository.save"),
        ),
        edges=(
            flow_graph_edge("edge-controller-usecase", "controller-create", "usecase-execute"),
            flow_graph_edge("edge-usecase-save", "usecase-execute", "repository-save"),
        ),
    )

    result = FlowBuilder().build(
        bundle,
        [
            matched_node(id="usecase-execute", name="UseCase.execute", label="UseCase.execute", matchReasons=["EXACT_NAME"]),
            matched_node(id="repository-save", name="Repository.save", label="Repository.save", matchReasons=["SEMANTIC_VECTOR_SIMILARITY"]),
        ],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )

    assert len(result.flow_units) == 1
    assert result.exact_duplicate_merge_count == 1
    assert result.flow_units[0].node_ids == ("controller-create", "usecase-execute", "repository-save")
    assert set(result.flow_paths[0].matchedNodeIds) == {"usecase-execute", "repository-save"}
    assert result.flow_units[0].edges
    assert result.flow_units[0].nodes


def test_flow_builder_non_exact_suffix_overlap_is_not_merged():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("a-start", "A.start"),
            flow_graph_node("d-start", "D.start"),
            flow_graph_node("b-work", "B.work"),
            flow_graph_node("c-finish", "C.finish"),
        ),
        edges=(
            flow_graph_edge("edge-a-b", "a-start", "b-work"),
            flow_graph_edge("edge-d-b", "d-start", "b-work"),
            flow_graph_edge("edge-b-c", "b-work", "c-finish"),
        ),
    )

    result = FlowBuilder().build(
        bundle,
        [matched_node(id="b-work", name="B.work", label="B.work")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )

    assert len(result.flow_units) == 2
    assert result.exact_duplicate_merge_count == 0
    assert sorted(unit.node_ids for unit in result.flow_units) == [
        ("a-start", "b-work", "c-finish"),
        ("d-start", "b-work", "c-finish"),
    ]
    assert sum(1 for unit in result.flow_units if unit.node_ids[-2:] == ("b-work", "c-finish")) == 2


def test_flow_builder_boundary_unit_is_self_contained_with_evidence():
    bundle = FlowGraphBundle(
        nodes=(flow_graph_node("usecase-execute", "UseCase.execute"),),
        edges=(
            flow_graph_edge(
                "edge-external",
                "usecase-execute",
                None,
                resolution_status="EXTERNAL_TARGET",
                external=True,
                unresolved_target={"name": "External.call"},
                evidence_ids=("ev-external",),
            ),
        ),
        evidence=(FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-external", None, "edge-external", "src/UseCase.java", 40, 40, "call();"),),
    )

    result = FlowBuilder().build(
        bundle,
        [matched_node(id="usecase-execute", name="UseCase.execute", label="UseCase.execute")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )

    unit = result.flow_units[0]
    assert unit.node_ids == ("usecase-execute",)
    assert unit.boundary_edge_ids == ("edge-external",)
    assert unit.boundary_edges[0].edge_id == "edge-external"
    assert unit.stop_reason.value == "EXTERNAL_BOUNDARY"
    assert unit.complete is False
    assert unit.evidence[0].evidence_id == "ev-external"
    assert result.flow_paths[0].boundaryEdgeIds == ["edge-external"]
    assert result.flow_paths[0].evidenceIds == ["ev-external"]


def test_flow_builder_dynamic_ordering_uses_generic_graph_facts():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("a-start", "A.start"),
            flow_graph_node("d-start", "D.start"),
            flow_graph_node("b-work", "B.work"),
            flow_graph_node("c-finish", "C.finish"),
            flow_graph_node("e-side", "E.side"),
        ),
        edges=(
            flow_graph_edge("edge-d-b", "d-start", "b-work", evidence_ids=("ev-d-b",)),
            flow_graph_edge("edge-b-e", "b-work", "e-side", evidence_ids=("ev-b-e",)),
            flow_graph_edge("edge-a-b", "a-start", "b-work", evidence_ids=("ev-a-b",)),
            flow_graph_edge("edge-b-c", "b-work", "c-finish", evidence_ids=("ev-b-c",)),
        ),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-a-b", None, "edge-a-b", "src/A.java", 1, 1, "b.work();"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-d-b", None, "edge-d-b", "src/D.java", 2, 2, "b.work();"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-b-c", None, "edge-b-c", "src/B.java", 3, 3, "c.finish();"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-b-e", None, "edge-b-e", "src/B.java", 4, 4, "e.side();"),
        ),
    )

    result = FlowBuilder().build(
        bundle,
        [matched_node(id="b-work", name="B.work", label="B.work")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )

    assert [flow.nodeIds for flow in result.flow_paths] == [
        ["a-start", "b-work", "c-finish"],
        ["a-start", "b-work", "e-side"],
        ["d-start", "b-work", "c-finish"],
        ["d-start", "b-work", "e-side"],
    ]


def test_flow_builder_path_scoped_evidence_does_not_leak_between_flows():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("usecase-execute", "UseCase.execute"),
            flow_graph_node("repository-save", "Repository.save"),
            flow_graph_node("audit-write", "AuditWriter.write"),
        ),
        edges=(
            flow_graph_edge("edge-save", "usecase-execute", "repository-save", evidence_ids=("ev-save",)),
            flow_graph_edge("edge-audit", "usecase-execute", "audit-write", evidence_ids=("ev-audit",)),
        ),
        evidence=(
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-usecase", "usecase-execute", None, "src/UseCase.java", 1, 1, "execute"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-save", None, "edge-save", "src/UseCase.java", 10, 10, "save();"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-audit", None, "edge-audit", "src/UseCase.java", 20, 20, "audit();"),
            FlowGraphEvidence("source-a", "graph-a", "graph-a", "ev-other", None, "edge-other", "src/UseCase.java", 30, 30, "other();"),
        ),
    )

    result = FlowBuilder().build(
        bundle,
        [matched_node(id="usecase-execute", name="UseCase.execute", label="UseCase.execute")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )

    evidence_by_leaf = {flow.nodeIds[-1]: flow.evidenceIds for flow in result.flow_paths}
    assert evidence_by_leaf["repository-save"] == ["ev-usecase", "ev-save"]
    assert evidence_by_leaf["audit-write"] == ["ev-usecase", "ev-audit"]
    assert all("ev-other" not in evidence_ids for evidence_ids in evidence_by_leaf.values())


def test_flow_builder_never_crosses_source_or_graph_boundaries():
    bundle = FlowGraphBundle(
        nodes=(
            flow_graph_node("root", "SourceA.root", source_id="source-a", graph_id="graph-a"),
            flow_graph_node("shared", "SourceA.shared", source_id="source-a", graph_id="graph-a"),
            flow_graph_node("leaf", "SourceA.leaf", source_id="source-a", graph_id="graph-a"),
            flow_graph_node("root", "SourceB.root", source_id="source-b", graph_id="graph-b"),
            flow_graph_node("shared", "SourceB.shared", source_id="source-b", graph_id="graph-b"),
            flow_graph_node("leaf", "SourceB.leaf", source_id="source-b", graph_id="graph-b"),
        ),
        edges=(
            flow_graph_edge("edge-a-root-shared", "root", "shared", source_id="source-a", graph_id="graph-a"),
            flow_graph_edge("edge-a-shared-leaf", "shared", "leaf", source_id="source-a", graph_id="graph-a"),
            flow_graph_edge("edge-b-root-shared", "root", "shared", source_id="source-b", graph_id="graph-b"),
            flow_graph_edge("edge-b-shared-leaf", "shared", "leaf", source_id="source-b", graph_id="graph-b"),
        ),
    )

    result = FlowBuilder().build(
        bundle,
        [matched_node(id="shared", sourceId="source-a", graphId="graph-a", name="SourceA.shared", label="SourceA.shared")],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=4),
    )

    assert [flow.sourceId for flow in result.flow_paths] == ["source-a"]
    assert [flow.nodeIds for flow in result.flow_paths] == [["root", "shared", "leaf"]]
    assert result.flow_paths[0].edgeIds == ["edge-a-root-shared", "edge-a-shared-leaf"]


def test_no_callable_flow_seeds_returns_clear_diagnostic():
    store = FakeGraphStore(
        candidates=[candidate(id="field-only", nodeKind="FIELD", name="fieldOnly", label="fieldOnly")],
        nodes=[graph_node("field-only", "fieldOnly", nodeKind="FIELD")],
        edges=[],
    )

    response = service(store, KnowledgeQueryPolicy(max_flow_paths=4)).query(query_request("fieldOnly"))

    assert response.flowPaths == []
    assert any(diagnostic.code == "FLOW_BUILDER_NO_FLOW_SEEDS" for diagnostic in response.diagnostics)


def test_flow_builder_has_no_loose_graph_contract_fallbacks():
    builder_path = Path(__file__).parents[1] / "src" / "knowledge_service" / "flow_builder.py"
    text = builder_path.read_text()
    section = text[text.index("class FlowBuilder:") :]

    forbidden_fragments = [
        "Dict" + "[str, Any]",
        "List" + "[Any]",
        '.get("from' + 'NodeId"',
        '.get("from' + '_node_id"',
        '.get("to' + 'NodeId"',
        '.get("to' + '_node_id"',
        "bundle" + ".get(",
        "edge" + ".get(",
        "Controller",
        "Consumer",
        "Scheduler",
        "Kafka",
        "Job",
        "MapStruct",
        "Mapper",
        "Repository",
        "Service",
    ]
    for fragment in forbidden_fragments:
        assert fragment not in section


def test_flow_extraction_uses_candidate_after_old_top_five_cutoff():
    decoys = [
        candidate(id=f"a-decoy-{index}", name="SharedTerm", label="SharedTerm", qualifiedName=f"example.Decoy{index}", degree=0)
        for index in range(6)
    ]
    store = FakeGraphStore(
        candidates=[
            *decoys,
            candidate(id="z-flow-anchor", name="SharedTerm", label="SharedTerm", qualifiedName="example.FlowAnchor", degree=0),
        ],
        nodes=[
            *[graph_node(f"a-decoy-{index}", "SharedTerm") for index in range(6)],
            graph_node("controller-start", "Controller.start"),
            graph_node("z-flow-anchor", "SharedTerm"),
            graph_node("repository-save", "Repository.save"),
        ],
        edges=[
            graph_edge("calls-start", "controller-start", "z-flow-anchor"),
            graph_edge("calls-save", "z-flow-anchor", "repository-save"),
        ],
    )

    response = service(store, KnowledgeQueryPolicy(max_display_candidates=5, max_flow_paths=4)).query(query_request("SharedTerm"))

    displayed_ids = [node.nodeId for node in response.matchedNodes]
    assert "z-flow-anchor" not in displayed_ids
    assert response.coverage.matchedNodeCount == 7
    assert any(flow.nodeIds == ["controller-start", "z-flow-anchor", "repository-save"] for flow in response.flowPaths)
    assert any("z-flow-anchor" in flow.matchedNodeIds for flow in response.flowPaths)
    assert any(diagnostic.code == "MATCHED_NODE_PREVIEW_LIMITED" for diagnostic in response.diagnostics)
    forbidden_message = "truncated before " + "flow extraction"
    assert not any(forbidden_message in diagnostic.message for diagnostic in response.diagnostics)


def test_graph_flow_uses_raw_candidates_not_ranked_preview():
    decoys = [
        candidate(id=f"a-decoy-{index}", name="SharedTerm", label="SharedTerm", qualifiedName=f"example.Decoy{index}", degree=0)
        for index in range(6)
    ]
    store = FakeGraphStore(
        candidates=[
            *decoys,
            candidate(id="z-flow-anchor", name="SharedTerm", label="SharedTerm", qualifiedName="example.FlowAnchor", degree=0),
        ],
        nodes=[
            *[graph_node(f"a-decoy-{index}", "SharedTerm") for index in range(6)],
            graph_node("controller-start", "Controller.start"),
            graph_node("z-flow-anchor", "SharedTerm"),
            graph_node("repository-save", "Repository.save"),
        ],
        edges=[
            graph_edge("calls-start", "controller-start", "z-flow-anchor"),
            graph_edge("calls-save", "z-flow-anchor", "repository-save"),
        ],
    )
    query_service = KnowledgeQueryService(
        SourceScopeResolver(store),
        UnifiedAnchorSearcher(store, RankedPreviewOnlySearchEngine()),
        GraphSliceQueryService(store),
        FlowPathExtractor(store),
        EvidenceBundleBuilder(),
        KnowledgeQueryPolicy(max_display_candidates=1, max_flow_paths=4),
    )

    response = query_service.query(query_request("SharedTerm"))

    assert len(response.matchedNodes) == 1
    assert response.matchedNodes[0].nodeId == "a-decoy-0"
    assert response.coverage.matchedNodeCount == 7
    assert any(flow.nodeIds == ["controller-start", "z-flow-anchor", "repository-save"] for flow in response.flowPaths)
    assert any("z-flow-anchor" in flow.matchedNodeIds for flow in response.flowPaths)


def test_flow_path_extraction_uses_calls_edges_from_graph_slice():
    store = FakeGraphStore(candidates=[candidate(id="controller-create", name="Controller.create", label="Controller.create")])

    response = service(store).query(query_request("Controller create"))

    assert response.flowPaths
    flow = response.flowPaths[0]
    assert flow.nodeIds == ["controller-create", "usecase-execute", "repository-save"]
    assert flow.edgeIds == ["calls-1", "calls-2"]
    assert [node["id"] for node in flow.nodes] == ["controller-create", "usecase-execute", "repository-save"]
    assert [edge["id"] for edge in flow.edges] == ["calls-1", "calls-2"]
    assert flow.evidence[0]["sourceId"] == "source-a"
    assert flow.evidenceIds == ["ev-1", "ev-2"]
    assert response.coverage.flowPathCount == 1


def test_flow_path_extractor_extracts_upstream_and_downstream_for_middle_match():
    store = FakeGraphStore(candidates=[candidate(id="usecase-execute", name="CreateUseCase.execute", label="CreateUseCase.execute")])

    response = service(store).query(query_request("CreateUseCase.execute"))

    flow = response.flowPaths[0]
    assert flow.nodeIds == ["controller-create", "usecase-execute", "repository-save"]
    assert flow.edgeIds == ["calls-1", "calls-2"]
    assert flow.complete is True
    assert flow.stopReason == "TERMINAL_NODE"


def test_flow_path_extractor_handles_multiple_upstream_callers():
    nodes = [
        graph_node("controller-a", "ControllerA.create"),
        graph_node("controller-b", "ControllerB.create"),
        graph_node("usecase-execute", "UseCase.execute"),
        graph_node("repository-save", "Repository.save"),
    ]
    edges = [
        graph_edge("calls-a", "controller-a", "usecase-execute"),
        graph_edge("calls-b", "controller-b", "usecase-execute"),
        graph_edge("calls-save", "usecase-execute", "repository-save"),
    ]
    store = FakeGraphStore(nodes=nodes, edges=edges, candidates=[candidate(id="usecase-execute", name="UseCase.execute", label="UseCase.execute")])

    response = service(store).query(query_request("UseCase.execute"))

    assert sorted(flow.nodeIds for flow in response.flowPaths) == [
        ["controller-a", "usecase-execute", "repository-save"],
        ["controller-b", "usecase-execute", "repository-save"],
    ]


def test_flow_path_extractor_handles_downstream_branching_without_labels():
    nodes = [
        graph_node("controller-create", "Controller.create"),
        graph_node("usecase-execute", "UseCase.execute"),
        graph_node("repository-save", "Repository.save"),
        graph_node("event-publish", "EventPublisher.publish"),
    ]
    edges = [
        graph_edge("calls-controller", "controller-create", "usecase-execute"),
        graph_edge("calls-save", "usecase-execute", "repository-save"),
        graph_edge("calls-publish", "usecase-execute", "event-publish"),
    ]
    store = FakeGraphStore(nodes=nodes, edges=edges, candidates=[candidate(id="usecase-execute", name="UseCase.execute", label="UseCase.execute")])

    response = service(store).query(query_request("UseCase.execute"))

    assert sorted(flow.nodeIds for flow in response.flowPaths) == [
        ["controller-create", "usecase-execute", "event-publish"],
        ["controller-create", "usecase-execute", "repository-save"],
    ]
    assert all("branch" not in flow.dict() for flow in response.flowPaths)


def test_flow_path_extractor_deduplicates_identical_paths_and_merges_matches():
    store = FakeGraphStore(
        candidates=[
            candidate(id="usecase-execute", name="CreateUseCase.execute", label="CreateUseCase.execute"),
            candidate(id="repository-save", name="Repository.save", label="Repository.save"),
        ]
    )

    response = service(store).query(query_request("execute save"))

    assert len(response.flowPaths) == 1
    assert response.flowPaths[0].nodeIds == ["controller-create", "usecase-execute", "repository-save"]
    assert set(response.flowPaths[0].matchedNodeIds) == {"usecase-execute", "repository-save"}


def test_flow_path_extractor_detects_cycles():
    nodes = [graph_node("a", "Alpha"), graph_node("b", "Beta"), graph_node("c", "Gamma")]
    edges = [graph_edge("ab", "a", "b"), graph_edge("bc", "b", "c"), graph_edge("ca", "c", "a")]
    store = FakeGraphStore(nodes=nodes, edges=edges, candidates=[candidate(id="a", name="Alpha", label="Alpha")])

    response = service(store, KnowledgeQueryPolicy(max_flow_paths=4)).query(query_request("Alpha"))

    assert any(flow.stopReason == "CYCLE_DETECTED" and flow.complete is False for flow in response.flowPaths)
    assert any(diagnostic.code == "CYCLE_DETECTED" for diagnostic in response.diagnostics)


def test_flow_path_extractor_stops_on_external_target_edge():
    nodes = [
        graph_node("controller-create", "Controller.create"),
    ]
    edges = [
        graph_edge(
            "calls-external",
            "controller-create",
            None,
            resolutionStatus="EXTERNAL_TARGET",
            unresolvedTarget={"name": "HttpClient.post", "kindHint": "CALLABLE"},
            external=True,
        )
    ]
    store = FakeGraphStore(nodes=nodes, edges=edges, candidates=[candidate(id="controller-create", name="Controller.create", label="Controller.create")])

    response = service(store).query(query_request("Controller create"))

    flow = response.flowPaths[0]
    assert flow.nodeIds == ["controller-create"]
    assert flow.edgeIds == []
    assert flow.boundaryEdgeIds == ["calls-external"]
    assert flow.stopReason == "EXTERNAL_BOUNDARY"
    assert flow.complete is False
    assert response.unresolved[0]["unresolvedTarget"]["name"] == "HttpClient.post"


def test_flow_path_extractor_stops_on_unresolved_edge():
    nodes = [graph_node("controller-create", "Controller.create")]
    edges = [graph_edge("calls-missing", "controller-create", None, resolutionStatus="UNRESOLVED", unresolvedTarget={"name": "missing"})]
    store = FakeGraphStore(nodes=nodes, edges=edges, candidates=[candidate(id="controller-create", name="Controller.create", label="Controller.create")])

    response = service(store).query(query_request("Controller create"))

    flow = response.flowPaths[0]
    assert flow.nodeIds == ["controller-create"]
    assert flow.edgeIds == []
    assert flow.boundaryEdgeIds == ["calls-missing"]
    assert flow.stopReason == "UNRESOLVED_BOUNDARY"
    assert flow.complete is False


def test_flow_path_extractor_does_not_mutate_graph_facts():
    store = FakeGraphStore(candidates=[candidate(id="usecase-execute", name="CreateUseCase.execute", label="CreateUseCase.execute")])
    original_nodes = [dict(node) for node in store.nodes]
    original_edges = [dict(edge) for edge in store.edges]

    service(store).query(query_request("CreateUseCase.execute"))

    assert store.nodes == original_nodes
    assert store.edges == original_edges


def test_flow_path_extractor_batch_loads_adjacency_once():
    store = FakeGraphStore(candidates=[candidate(id="usecase-execute", name="CreateUseCase.execute", label="CreateUseCase.execute")])

    service(store).query(query_request("CreateUseCase.execute"))

    assert store.adjacency_loads == 1


def test_no_candidates_returns_controlled_response():
    response = service(FakeGraphStore(candidates=[])).query(query_request("missing"))

    assert response.status == "NO_CANDIDATES"
    assert response.matchedNodes == []
    assert response.flowPaths == []
    assert any(diagnostic.code == "NO_GRAPH_CANDIDATES" for diagnostic in response.diagnostics)


def test_graph_slice_failure_becomes_diagnostic_not_exception():
    response = service(FakeGraphStore(candidates=[candidate()], slice_error=True)).query(query_request("JarvisGateway"))

    assert response.status == "OK"
    assert any(diagnostic.code == "GRAPH_SLICE_FAILED" for diagnostic in response.diagnostics)


def test_guardrail_reports_truncated_flow_result():
    nodes = [
        graph_node("controller-create", "Controller.create"),
        graph_node("usecase-execute", "UseCase.execute"),
        graph_node("repository-save", "Repository.save"),
        graph_node("event-publish", "EventPublisher.publish"),
    ]
    edges = [
        graph_edge("calls-controller", "controller-create", "usecase-execute"),
        graph_edge("calls-publish", "usecase-execute", "event-publish"),
        graph_edge("calls-save", "usecase-execute", "repository-save"),
    ]
    store = FakeGraphStore(
        nodes=nodes,
        edges=edges,
        candidates=[candidate(id="usecase-execute", name="UseCase.execute", label="UseCase.execute")],
    )

    response = service(store, KnowledgeQueryPolicy(max_flow_paths=1)).query(query_request("UseCase.execute"))

    assert response.coverage.truncated is True
    assert response.coverage.continuationAvailable is True
    assert any(diagnostic.code == "RESULT_LIMIT_REACHED" for diagnostic in response.diagnostics)


def test_document_safety_cap_reports_diagnostic_without_ranked_limit_metadata():
    store = FakeGraphStore(
        candidates=[
            candidate(id="controller-create", name="Controller.create", label="Controller.create"),
            candidate(id="node-extra", name="Controller.createExtra", label="Controller.createExtra"),
            candidate(id="node-more", name="Controller.createMore", label="Controller.createMore"),
        ]
    )

    response = service(store, KnowledgeQueryPolicy(max_search_documents=1, max_flow_paths=2)).query(query_request("Controller.create"))

    diagnostic = next(diagnostic for diagnostic in response.diagnostics if diagnostic.code == "SEARCH_CANDIDATE_LIMIT_REACHED")
    assert diagnostic.metadata == {"maxSearchDocuments": 1, "maxCandidatesPerProvider": 100}


def test_provider_safety_cap_reports_diagnostic_without_ranked_limit_metadata():
    store = FakeGraphStore(
        candidates=[
            candidate(id="shared-a", name="SharedTerm", label="SharedTerm"),
            candidate(id="shared-b", name="SharedTerm", label="SharedTerm"),
            candidate(id="shared-c", name="SharedTerm", label="SharedTerm"),
        ]
    )

    response = service(store, KnowledgeQueryPolicy(max_candidates_per_provider=1, max_flow_paths=2)).query(query_request("SharedTerm"))

    diagnostic = next(diagnostic for diagnostic in response.diagnostics if diagnostic.code == "SEARCH_CANDIDATE_LIMIT_REACHED")
    assert diagnostic.metadata == {"maxSearchDocuments": 5000, "maxCandidatesPerProvider": 1}


def test_query_uses_deterministic_search_when_semantic_index_failed(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path, source_id="source-a")
    state = SemanticIndexStore(db_path).status_for_source("source-a")
    SemanticIndexStore(db_path).mark_source_failed(
        "source-a",
        state.graph_revision,
        state.total_node_count,
        error="Embedding model is not available in local Ollama: embeddinggemma. Pull or configure an installed embedding model.",
        diagnostics=[
            {
                "code": "SEMANTIC_EMBEDDING_MODEL_UNAVAILABLE",
                "message": "Embedding model is not available in local Ollama: embeddinggemma. Pull or configure an installed embedding model.",
                "severity": "WARN",
            }
        ],
    )
    store = FakeGraphStore(candidates=[candidate(id="controller-create", sourceId="source-a", graphId="graph-a")])
    store.db_path = db_path

    response = build_knowledge_query_service(
        store,
        embedding_provider=FakeDeterministicEmbeddingProvider(model="embeddinggemma"),
    ).query(query_request("Controller create flow"))

    assert SemanticIndexStore(db_path).status_for_source("source-a").status == SemanticIndexStatus.FAILED
    assert response.status == "OK"
    assert response.matchedNodes
    assert any(diagnostic.code == "SEMANTIC_INDEX_FAILED" for diagnostic in response.diagnostics)


def test_query_uses_deterministic_search_when_semantic_provider_unavailable(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _seed_site_graph(db_path)
    _build_site_semantic_index(db_path, model="fake-semantic")

    response = build_knowledge_query_service(
        AnalysisStore(db_path),
        embedding_provider=FailingEmbeddingProvider(),
    ).query(query_request("SiteController"))

    assert response.status == "OK"
    assert response.matchedNodes[0].nodeId == "site-controller"
    assert any(diagnostic.code == "SEMANTIC_PROVIDER_UNAVAILABLE" for diagnostic in response.diagnostics)


@pytest.mark.parametrize(
    "state,expected_code",
    [
        ("MISSING", "SEMANTIC_INDEX_NOT_READY"),
        ("STALE", "SEMANTIC_INDEX_STALE"),
        ("MODEL_MISMATCH", "SEMANTIC_INDEX_NOT_READY"),
    ],
)
def test_query_falls_back_when_semantic_index_not_ready_stale_or_model_mismatch(tmp_path, state, expected_code):
    db_path = tmp_path / "knowledge.sqlite"
    _seed_site_graph(db_path)
    if state == "STALE":
        _build_site_semantic_index(db_path, model="fake-semantic")
        _seed_site_graph(db_path, graph_suffix="new", extra_node=True)
    elif state == "MODEL_MISMATCH":
        _build_site_semantic_index(db_path, model="other-semantic")

    response = build_knowledge_query_service(
        AnalysisStore(db_path),
        embedding_provider=FakeDeterministicEmbeddingProvider(model="fake-semantic"),
    ).query(query_request("SiteController"))

    assert response.status == "OK"
    assert response.matchedNodes[0].nodeId == "site-controller"
    diagnostic = next(diagnostic for diagnostic in response.diagnostics if diagnostic.code == expected_code)
    assert diagnostic.sourceId == "source-a"
    if state == "MODEL_MISMATCH":
        assert diagnostic.metadata["embeddingModel"] == "other-semantic"
        assert diagnostic.metadata["expectedEmbeddingModel"] == "fake-semantic"


def test_semantic_hit_not_hydrated_reports_diagnostic_and_keeps_deterministic_results(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _seed_site_graph(db_path)
    embedding_provider = _build_site_semantic_index(db_path, model="fake-semantic")
    semantic_provider = SemanticCandidateProvider(
        db_path,
        embedding_provider,
        config=SemanticSearchConfig(min_similarity=0.0),
        vector_store=StaticVectorStore(
            [
                SemanticVectorMatch(
                    source_id="source-a",
                    node_id="missing-semantic-node",
                    document_id="semantic-doc-missing",
                    similarity=0.91,
                )
            ]
        ),
    )
    store = AnalysisStore(db_path)
    eligible_sources, _ = SourceScopeResolver(store).resolve()

    result = UnifiedAnchorSearcher(
        store,
        DeterministicCodeSearchEngine(extra_broad_providers=[semantic_provider]),
    ).search("SiteController", eligible_sources, KnowledgeQueryPolicy())

    assert result.all_candidates[0].nodeId == "site-controller"
    assert all(node.nodeId != "missing-semantic-node" for node in result.all_candidates)
    diagnostic = next(diagnostic for diagnostic in result.diagnostics if diagnostic.code == "SEMANTIC_HIT_NOT_HYDRATED")
    assert diagnostic.metadata["unhydratedCount"] == 1


def test_semantic_no_candidates_reports_diagnostic_and_keeps_deterministic_results(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _seed_site_graph(db_path)
    embedding_provider = _build_site_semantic_index(db_path, model="fake-semantic")
    semantic_provider = SemanticCandidateProvider(
        db_path,
        embedding_provider,
        config=SemanticSearchConfig(min_similarity=0.99),
        vector_store=StaticVectorStore([], scanned_count=2),
    )
    store = AnalysisStore(db_path)
    eligible_sources, _ = SourceScopeResolver(store).resolve()

    result = UnifiedAnchorSearcher(
        store,
        DeterministicCodeSearchEngine(extra_broad_providers=[semantic_provider]),
    ).search("SiteController", eligible_sources, KnowledgeQueryPolicy())

    assert result.all_candidates[0].nodeId == "site-controller"
    diagnostic = next(diagnostic for diagnostic in result.diagnostics if diagnostic.code == "SEMANTIC_NO_CANDIDATES")
    assert diagnostic.metadata["scannedCount"] == 2


def _seed_site_graph(db_path, *, graph_suffix="one", extra_node=False):
    nodes = [
        {
            "id": "site-controller",
            "nodeKind": "TYPE",
            "name": "SiteController",
            "qualified": "app.SiteController",
            "path": "src/SiteController.java",
        },
        {
            "id": "create-site-service",
            "nodeKind": "CALLABLE",
            "name": "CreateSiteService.create",
            "qualified": "app.CreateSiteService.create",
            "path": "src/CreateSiteService.java",
        },
    ]
    if extra_node:
        nodes.append(
            {
                "id": "create-site-flow",
                "nodeKind": "CALLABLE",
                "name": "CreateSiteFlow.run",
                "qualified": "app.CreateSiteFlow.run",
                "path": "src/CreateSiteFlow.java",
            }
        )
    return seed_semantic_graph(db_path, source_id="source-a", graph_suffix=graph_suffix, nodes=nodes)


def _build_site_semantic_index(db_path, *, model):
    provider = FakeDeterministicEmbeddingProvider(model=model)
    SemanticIndexBuilder(db_path, provider, config=SemanticBuildConfig(embedding_model=provider.model)).build(["source-a"], force=True)
    return provider
