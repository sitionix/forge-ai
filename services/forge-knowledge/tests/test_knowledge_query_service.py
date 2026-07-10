import pytest

from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.embedding_provider import EmbeddingProviderError
from knowledge_service.embedding_provider import FakeDeterministicEmbeddingProvider
from knowledge_service.knowledge_query_schema import KnowledgeQueryMatchedNode, KnowledgeQueryRequest
from knowledge_service.knowledge_query_service import (
    AnchorExpansionReason,
    AnchorExpansionService,
    AnchorRole,
    CandidatePoolKind,
    EvidenceBundleBuilder,
    FlowPathExtractor,
    GraphSliceQueryService,
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
            source_id = item.get("sourceId") or item.get("source_id")
            node_id = item.get("id") or item.get("nodeId")
            if (source_id, node_id) not in requested:
                continue
            projected = dict(item)
            projected.setdefault("graphRevision", self.graph_revision)
            hydrated.append(projected)
        return hydrated[:limit]

    def query_anchor_expansion(self, source_node_pairs, max_per_anchor=30, max_total=200):
        self.expansion_queries.append((list(source_node_pairs), max_per_anchor, max_total))
        requested = set()
        for item in source_node_pairs:
            if isinstance(item, dict):
                source_id = item.get("sourceId") or item.get("source_id")
                graph_id = item.get("graphId") or item.get("graph_id") or "graph-a"
                node_id = item.get("nodeId") or item.get("node_id") or item.get("id")
            else:
                source_id = item[0]
                graph_id = item[1] if len(item) > 2 else "graph-a"
                node_id = item[2] if len(item) > 2 else item[1]
            if source_id and node_id:
                requested.add((str(source_id), str(graph_id or "graph-a"), str(node_id)))

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
        return {"nodes": nodes, "edges": structural_edges, "entrypointHints": entrypoint_hints, "truncated": False}

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
        [{"sourceId": "source-a", "graphId": graph_id, "nodeId": "foo-type"}],
        max_per_anchor=30,
        max_total=200,
    )

    node_ids = {node["id"] for node in bundle["nodes"]}
    edge_ids = {edge["id"] for edge in bundle["edges"]}
    assert {"foo-type", "do-work", "bar-field"} <= node_ids
    assert {"declares-callable", "declares-field"} <= edge_ids
    assert "uses-field" not in edge_ids
    assert all(node["graphId"] == graph_id for node in bundle["nodes"])


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
