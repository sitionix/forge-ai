import pytest

from knowledge_service.embedding_provider import FakeDeterministicEmbeddingProvider
from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.knowledge_query_service import (
    EvidenceBundleBuilder,
    FlowPathExtractor,
    GraphSliceQueryService,
    KnowledgeQueryPolicy,
    KnowledgeQueryService,
    SourceScopeResolver,
    UnifiedAnchorSearcher,
    build_knowledge_query_service,
)
from knowledge_service.semantic_index import SemanticIndexStatus, SemanticIndexStore
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
    def __init__(self, *, candidates=None, nodes=None, edges=None, evidence=None, slice_error=False, adjacency_truncated=False):
        self.candidates = candidates if candidates is not None else []
        self.nodes = nodes if nodes is not None else default_nodes()
        self.edges = edges if edges is not None else default_edges()
        self.evidence = evidence if evidence is not None else default_evidence()
        self.slice_error = slice_error
        self.adjacency_truncated = adjacency_truncated
        self.source_searches = []
        self.adjacency_loads = 0

    def query_current_graph_sources(self):
        return [
            {"sourceId": "source-a", "displayName": "Source A", "graphId": "graph-a", "nodeCount": 3, "edgeCount": 2},
            {"sourceId": "source-b", "displayName": "Source B", "graphId": "graph-b", "nodeCount": 1, "edgeCount": 0},
        ]

    def query_anchor_candidates(self, tokens, source_ids, limit):
        self.source_searches.append((tokens, source_ids, limit))
        return self.candidates

    def query_graph_slice(self, matched_nodes, depth):
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
        policy or KnowledgeQueryPolicy(max_matched_nodes=2, max_flow_paths=2),
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


def graph_node(node_id, label, **overrides):
    value = {
        "id": node_id,
        "sourceId": "source-a",
        "graphId": "graph-a",
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

    response = service(store, KnowledgeQueryPolicy(max_matched_nodes=2, max_flow_paths=4)).query(query_request("Alpha"))

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
    assert flow.stopReason == "EXTERNAL_TARGET"
    assert flow.complete is True
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
    assert flow.stopReason == "UNRESOLVED_EDGE"
    assert flow.complete is True


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
    store = FakeGraphStore(
        candidates=[
            candidate(id="controller-create", name="Controller.create", label="Controller.create"),
            candidate(id="node-extra", name="Controller.createExtra", label="Controller.createExtra"),
            candidate(id="node-more", name="Controller.createMore", label="Controller.createMore"),
        ]
    )

    response = service(store).query(query_request("Controller create"))

    assert response.coverage.truncated is True
    assert response.coverage.continuationAvailable is True
    assert any(diagnostic.code == "RESULT_LIMIT_REACHED" for diagnostic in response.diagnostics)


def test_search_candidate_limit_reports_diagnostic():
    store = FakeGraphStore(
        candidates=[
            candidate(id="controller-create", name="Controller.create", label="Controller.create"),
            candidate(id="node-extra", name="Controller.createExtra", label="Controller.createExtra"),
            candidate(id="node-more", name="Controller.createMore", label="Controller.createMore"),
        ]
    )

    response = service(store, KnowledgeQueryPolicy(max_search_documents=1, max_matched_nodes=2, max_flow_paths=2)).query(
        query_request("Controller.create")
    )

    assert any(diagnostic.code == "SEARCH_CANDIDATE_LIMIT_REACHED" for diagnostic in response.diagnostics)


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
