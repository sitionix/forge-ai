import pytest

from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.knowledge_query_service import (
    EvidenceBundleBuilder,
    FlowPathExtractor,
    GraphSliceQueryService,
    KnowledgeQueryPolicy,
    KnowledgeQueryService,
    SourceScopeResolver,
    UnifiedAnchorSearcher,
)


class FakeGraphStore:
    def __init__(self, *, candidates=None, slice_error=False):
        self.candidates = candidates if candidates is not None else []
        self.slice_error = slice_error
        self.source_searches = []

    def query_current_graph_sources(self):
        return [
            {"sourceId": "source-a", "displayName": "Source A", "snapshotId": "snap-a", "nodeCount": 3, "edgeCount": 2},
            {"sourceId": "source-b", "displayName": "Source B", "snapshotId": "snap-b", "nodeCount": 1, "edgeCount": 0},
        ]

    def query_anchor_candidates(self, tokens, source_ids, limit):
        self.source_searches.append((tokens, source_ids, limit))
        return self.candidates

    def query_graph_slice(self, matched_nodes, depth):
        if self.slice_error:
            raise RuntimeError("slice failed")
        nodes = [
            {"id": "controller-create", "sourceId": "source-a", "kind": "CALLABLE", "label": "Controller.create"},
            {"id": "usecase-execute", "sourceId": "source-a", "kind": "CALLABLE", "label": "CreateUseCase.execute"},
            {"id": "repository-save", "sourceId": "source-a", "kind": "CALLABLE", "label": "Repository.save"},
        ]
        if matched_nodes and matched_nodes[0]["nodeId"] not in {node["id"] for node in nodes}:
            nodes = [{"id": node["nodeId"], "sourceId": node["sourceId"], "label": node["label"]} for node in matched_nodes]
        return {
            "nodes": nodes,
            "edges": [
                {"id": "calls-1", "sourceId": "source-a", "fromNodeId": "controller-create", "toNodeId": "usecase-execute", "edgeType": "CALLS"},
                {"id": "calls-2", "sourceId": "source-a", "fromNodeId": "usecase-execute", "toNodeId": "repository-save", "edgeType": "CALLS"},
            ],
            "evidence": [{"id": "ev-1", "sourceId": "source-a", "nodeId": "controller-create", "summary": "Controller delegates creation."}],
            "unresolved": [],
            "external": [],
            "verifiedPaths": [],
        }


def service(store):
    return KnowledgeQueryService(
        SourceScopeResolver(store),
        UnifiedAnchorSearcher(store),
        GraphSliceQueryService(store),
        FlowPathExtractor(),
        EvidenceBundleBuilder(),
        KnowledgeQueryPolicy(max_matched_nodes=2, max_flow_paths=2),
    )


def candidate(**overrides):
    value = {
        "id": "node-gateway",
        "sourceId": "source-a",
        "snapshotId": "snap-a",
        "stableKey": "src/JarvisGateway.java|CALLABLE|JarvisGateway",
        "kind": "CALLABLE",
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


def test_empty_query_validation():
    with pytest.raises(ValueError):
        KnowledgeQueryRequest(query="   ")


@pytest.mark.parametrize("field,value", [("maxAnchors", 5), ("depth", 2)])
def test_public_retrieval_knobs_are_not_accepted(field, value):
    payload = {"query": "JarvisGateway", field: value}
    with pytest.raises(ValueError):
        KnowledgeQueryRequest(**payload)


def test_auto_source_scope_resolves_all_current_graph_sources():
    store = FakeGraphStore(candidates=[candidate()])

    response = service(store).query(KnowledgeQueryRequest(query="JarvisGateway"))

    assert response.status == "OK"
    assert store.source_searches[0][1] == ["source-a", "source-b"]
    assert response.coverage.searchedSourceCount == 2
    assert response.matchedNodes[0].sourceId == "source-a"


def test_baseline_search_finds_by_node_name_stable_key_and_qualified_name():
    store = FakeGraphStore(
        candidates=[
            candidate(name="Different", label="Different", qualifiedName="example.JarvisGateway"),
            candidate(id="node-stable", name="Other", label="Other", qualifiedName="example.Other"),
        ]
    )

    response = service(store).query(KnowledgeQueryRequest(query="JarvisGateway"))

    reasons = {reason for matched_node in response.matchedNodes for reason in matched_node.matchReasons}
    assert "QUALIFIED_NAME_MATCH" in reasons
    assert "STABLE_KEY_MATCH" in reasons


def test_flow_path_extraction_uses_calls_edges_from_graph_slice():
    store = FakeGraphStore(candidates=[candidate(id="controller-create", name="Controller.create", label="Controller.create")])

    response = service(store).query(KnowledgeQueryRequest(query="Controller.create"))

    assert response.flowPaths
    flow = response.flowPaths[0]
    assert [node["id"] for node in flow.nodes] == ["controller-create", "usecase-execute", "repository-save"]
    assert [edge["id"] for edge in flow.edges] == ["calls-1", "calls-2"]
    assert flow.evidence[0]["sourceId"] == "source-a"
    assert response.coverage.flowPathCount == 1


def test_no_candidates_returns_controlled_response():
    response = service(FakeGraphStore(candidates=[])).query(KnowledgeQueryRequest(query="missing"))

    assert response.status == "NO_CANDIDATES"
    assert response.matchedNodes == []
    assert response.flowPaths == []
    assert any(diagnostic.code == "NO_GRAPH_CANDIDATES" for diagnostic in response.diagnostics)


def test_graph_slice_failure_becomes_diagnostic_not_exception():
    response = service(FakeGraphStore(candidates=[candidate()], slice_error=True)).query(KnowledgeQueryRequest(query="JarvisGateway"))

    assert response.status == "OK"
    assert response.nodes == []
    assert any(diagnostic.code == "GRAPH_SLICE_FAILED" for diagnostic in response.diagnostics)


def test_guardrail_reports_truncated_flow_result():
    store = FakeGraphStore(
        candidates=[
            candidate(id="controller-create", name="Controller.create", label="Controller.create"),
            candidate(id="node-extra", name="Controller.createExtra", label="Controller.createExtra"),
            candidate(id="node-more", name="Controller.createMore", label="Controller.createMore"),
        ]
    )

    response = service(store).query(KnowledgeQueryRequest(query="Controller.create"))

    assert response.coverage.truncated is True
    assert response.coverage.continuationAvailable is True
    assert any(diagnostic.code == "FLOW_RESULT_LIMIT_REACHED" for diagnostic in response.diagnostics)
