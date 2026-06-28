import pytest

from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.knowledge_query_service import (
    EvidenceBundleBuilder,
    GraphSliceQueryService,
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

    def query_graph_slice(self, anchors, depth):
        if self.slice_error:
            raise RuntimeError("slice failed")
        return {
            "nodes": [{"id": anchor["nodeId"], "sourceId": anchor["sourceId"], "label": anchor["label"]} for anchor in anchors],
            "edges": [],
            "evidence": [],
            "unresolved": [],
            "external": [],
            "verifiedPaths": [],
        }


def service(store):
    return KnowledgeQueryService(
        SourceScopeResolver(store),
        UnifiedAnchorSearcher(store),
        GraphSliceQueryService(store),
        EvidenceBundleBuilder(),
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


@pytest.mark.parametrize("field,value", [("maxAnchors", 0), ("depth", 0), ("maxAnchors", 999), ("depth", 999)])
def test_invalid_depth_and_max_anchors_rejected(field, value):
    payload = {"query": "JarvisGateway", field: value}
    with pytest.raises(ValueError):
        KnowledgeQueryRequest(**payload)


def test_auto_source_scope_resolves_all_current_graph_sources():
    store = FakeGraphStore(candidates=[candidate()])

    response = service(store).query(KnowledgeQueryRequest(query="JarvisGateway"))

    assert response.status == "OK"
    assert store.source_searches[0][1] == ["source-a", "source-b"]
    assert response.coverage.searchedSourceCount == 2
    assert response.anchors[0].sourceId == "source-a"


def test_baseline_search_finds_by_node_name_stable_key_and_qualified_name():
    store = FakeGraphStore(
        candidates=[
            candidate(name="Different", label="Different", qualifiedName="example.JarvisGateway"),
            candidate(id="node-stable", name="Other", label="Other", qualifiedName="example.Other"),
        ]
    )

    response = service(store).query(KnowledgeQueryRequest(query="JarvisGateway"))

    reasons = {reason for anchor in response.anchors for reason in anchor.matchReasons}
    assert "QUALIFIED_NAME_MATCH" in reasons
    assert "STABLE_KEY_MATCH" in reasons


def test_no_candidates_returns_controlled_response():
    response = service(FakeGraphStore(candidates=[])).query(KnowledgeQueryRequest(query="missing"))

    assert response.status == "NO_CANDIDATES"
    assert response.anchors == []
    assert any(diagnostic.code == "NO_GRAPH_CANDIDATES" for diagnostic in response.diagnostics)


def test_graph_slice_failure_becomes_diagnostic_not_exception():
    response = service(FakeGraphStore(candidates=[candidate()], slice_error=True)).query(KnowledgeQueryRequest(query="JarvisGateway"))

    assert response.status == "OK"
    assert response.nodes == []
    assert any(diagnostic.code == "GRAPH_SLICE_FAILED" for diagnostic in response.diagnostics)
