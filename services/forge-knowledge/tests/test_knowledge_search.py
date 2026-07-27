from knowledge_service.entrypoint_flow_engine import (
    EntrypointFlow,
    EntrypointFlowCoverage,
    EntrypointFlowKey,
    EntrypointFlowOrigin,
)
from knowledge_service.flow_graph_contract import FlowGraphNode
from knowledge_service.knowledge_query_service import (
    KnowledgeQueryPolicy,
    KnowledgeQueryService,
    QuerySource,
    RetrievalScope,
    SourceDiverseAnchorSelector,
    UnifiedAnchorSearcher,
)
from knowledge_service.knowledge_search import (
    CandidateMerger,
    CandidateProvider,
    DeterministicCodeSearchEngine,
    MergedCandidate,
    QueryNormalizer,
    SearchCandidate,
    SearchConfig,
    SearchDocument,
    SearchQueryProfile,
)
from knowledge_service.query_interpretation import QueryRetrievalPlan


def doc(node_id, name, *, source_id="source-a", node_kind="CALLABLE", qualified_name="", relative_path="", stable_key=None, summary=""):
    return SearchDocument.from_graph_node(
        {
            "id": node_id,
            "sourceId": source_id,
            "nodeKind": node_kind,
            "name": name,
            "label": name,
            "stableKey": stable_key or f"{source_id}|{relative_path or name}|{node_kind}",
            "qualifiedName": qualified_name,
            "relativePath": relative_path,
            "summary": summary,
            "confidence": 0.95,
            "degree": 1,
        }
    )


def raw_doc(
    node_id,
    name,
    *,
    source_id="source-a",
    node_kind="CALLABLE",
    qualified_name="",
    relative_path="",
    summary="",
    flow_domain="CODE",
):
    return {
        "id": node_id,
        "sourceId": source_id,
        "nodeKind": node_kind,
        "name": name,
        "label": name,
        "stableKey": f"{source_id}|{relative_path or name}|{node_kind}",
        "qualifiedName": qualified_name or name,
        "relativePath": relative_path or f"src/{name}.java",
        "summary": summary,
        "flowDomain": flow_domain,
        "confidence": 0.95,
        "degree": 1,
        "graphId": f"{source_id}:graph",
        "graphRevision": f"{source_id}:rev",
    }


def query_source(source_id: str, node_count: int = 1) -> QuerySource:
    return QuerySource(
        source_id=source_id,
        display_name=source_id,
        graph_id=f"{source_id}:graph",
        graph_revision=f"{source_id}:rev",
        node_count=node_count,
        edge_count=0,
    )


class SourceAwareSearchStore:
    def __init__(self, documents_by_source):
        self.documents_by_source = {
            source_id: sorted(documents, key=lambda item: (str(item.get("label") or ""), str(item.get("id") or "")))
            for source_id, documents in documents_by_source.items()
        }
        self.count_queries = []
        self.document_queries = []

    def query_search_document_counts(self, source_ids, include_tests=True):
        self.count_queries.append((tuple(source_ids), include_tests))
        return {
            source_id: len(
                [
                    item
                    for item in self.documents_by_source.get(source_id, [])
                    if include_tests or str(item.get("flowDomain") or "").upper() != "TEST"
                ]
            )
            for source_id in source_ids
        }

    def query_search_documents(self, source_ids, limit, include_tests=True):
        self.document_queries.append((tuple(source_ids), limit, include_tests))
        rows = []
        for source_id in source_ids:
            source_rows = [
                item
                for item in self.documents_by_source.get(source_id, [])
                if include_tests or str(item.get("flowDomain") or "").upper() != "TEST"
            ]
            rows.extend(source_rows[:limit])
        return rows


class FakeSemanticProvider(CandidateProvider):
    name = "SEMANTIC"

    def __init__(self, node_ids):
        self.node_ids = set(node_ids)

    def search(self, query, documents, config):
        return [
            self._candidate(document, "SEMANTIC_VECTOR_SIMILARITY", 0.82, "HIGH", 52)
            for document in documents
            if document.node_id in self.node_ids
        ]


def flow(label: str, *, qualified_name: str = "", route: str = "", summary: str = "") -> EntrypointFlow:
    node = FlowGraphNode(
        source_id="source-a",
        graph_id="graph-a",
        graph_revision="rev-a",
        node_id=label,
        stable_key=f"source-a|{qualified_name or label}",
        node_kind="CALLABLE",
        label=label,
        qualified_name=qualified_name or label,
        summary=summary,
        entrypoint=True,
        entrypoint_kind="HTTP_ENDPOINT",
        entrypoint_http_method="POST",
        entrypoint_route=route,
    )
    return EntrypointFlow(
        key=EntrypointFlowKey("source-a", "rev-a", node.node_id),
        entrypoint=node,
        origin=EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT,
        anchors=(),
        nodes=(node,),
        transitions=(),
        boundary_transitions=(),
        evidence=(),
        complete=True,
        coverage=EntrypointFlowCoverage(1, 0, 0, 0, 0),
        diagnostics=(),
        relevance_score=1.0,
    )


def test_query_normalization_tokenizes_code_shapes():
    normalizer = QueryNormalizer()

    assert {"agent", "chat", "page", "tsx", "agentchatpage", "agentchatpagetsx"} <= set(normalizer.normalize("AgentChatPage.tsx").tokens)
    assert {"submit", "jarvis", "query", "submitjarvisquery"} <= set(normalizer.normalize("submitJarvisQuery").tokens)
    assert {"forge", "ai", "infrastructure", "jarvis", "controller", "forgeaiinfrastructurejarviscontroller"} <= set(
        normalizer.normalize("ForgeAiInfrastructureJarvisController").tokens
    )
    assert {"snake", "case", "name", "snakecasename"} <= set(normalizer.normalize("snake_case_name").tokens)
    assert {"kebab", "case", "name", "kebabcasename"} <= set(normalizer.normalize("kebab-case-name").tokens)
    assert {"api", "v1", "knowledge", "query", "apiv1knowledgequery"} <= set(normalizer.normalize("/api/v1/knowledge/query").tokens)
    assert {"com", "sitionix", "forgeai", "api", "forge", "infrastructure", "jarvis", "controller"} <= set(
        normalizer.normalize("com.sitionix.forgeai.api.ForgeAiInfrastructureJarvisController").tokens
    )


def test_query_profile_classifies_common_query_shapes():
    normalizer = QueryNormalizer()

    assert normalizer.normalize("submitJarvisQuery").profile == SearchQueryProfile.IDENTIFIER_LIKE
    assert normalizer.normalize("static/operator/jarvis.html").profile == SearchQueryProfile.PATH_LIKE
    assert normalizer.normalize("com.sitionix.forgeai.api.ForgeAiInfrastructureJarvisController").profile == SearchQueryProfile.QUALIFIED_NAME_LIKE
    assert normalizer.normalize("/api/v1/knowledge/query").profile == SearchQueryProfile.ENDPOINT_LIKE
    assert normalizer.normalize("jarvis query knowledge").profile == SearchQueryProfile.HUMAN_TEXT_LIKE
    assert normalizer.normalize("where does submitJarvisQuery call /api/v1/knowledge/query").profile == SearchQueryProfile.MIXED


def test_ranker_exact_beats_fuzzy_and_ties_are_deterministic():
    exact = doc("exact", "AgentChatPage", source_id="source-a", node_kind="TYPE", qualified_name="example.AgentChatPage")
    typo = doc("typo", "AgetnChatPage", source_id="source-b", node_kind="TYPE", qualified_name="example.AgetnChatPage")
    candidates = [
        SearchCandidate(typo, "FuzzyCandidateProvider", "FUZZY_NAME_EDIT_DISTANCE_1", 0.7, "MEDIUM", 64),
        SearchCandidate(exact, "ExactCandidateProvider", "EXACT_NAME", 0.98, "HIGH", 10),
    ]

    results = CandidateMerger().merge(candidates)

    assert results[0].document.node_id == "exact"
    assert results[0].score > results[1].score
    assert results[0].document.source_id == "source-a"


def test_ranker_path_beats_lexical_noise():
    engine = DeterministicCodeSearchEngine()
    documents = [
        doc("file", "jarvis.html", node_kind="FILE", relative_path="boot/src/main/resources/static/operator/jarvis.html"),
        doc("noise", "JarvisQueryNoise", summary="jarvis operator query text but not a path"),
    ]

    results = engine.search("operator/jarvis", documents, SearchConfig()).candidates

    assert results[0].document.node_id == "file"
    assert any(reason.startswith("PATH_") or reason == "PATH_MATCH" for reason in results[0].reasons)


def test_ranker_qualified_full_match_beats_suffix_match():
    engine = DeterministicCodeSearchEngine()
    full = "com.sitionix.forgeai.api.ForgeAiInfrastructureJarvisController"
    documents = [
        doc("suffix", "ForgeAiInfrastructureJarvisController", node_kind="TYPE", qualified_name="other.ForgeAiInfrastructureJarvisController"),
        doc("full", "ForgeAiInfrastructureJarvisController", node_kind="TYPE", qualified_name=full),
    ]

    results = engine.search(full, documents, SearchConfig()).candidates

    assert results[0].document.node_id == "full"
    assert "QUALIFIED_NAME_EXACT" in results[0].reasons or "EXACT_QUALIFIED_NAME" in results[0].reasons


def test_ranker_merges_duplicate_candidates_and_preserves_reasons():
    document = doc("shared", "SharedJarvisQuery")
    candidates = [
        SearchCandidate(document, "ExactCandidateProvider", "EXACT_NAME", 0.98, "HIGH", 10),
        SearchCandidate(document, "LexicalCandidateProvider", "LEXICAL_FULL_COVERAGE", 0.76, "MEDIUM", 42),
    ]

    merged = CandidateMerger().merge(candidates)

    assert len(merged) == 1
    assert merged[0].document.node_id == "shared"
    assert {"EXACT_NAME", "LEXICAL_FULL_COVERAGE", "NAME_MATCH"} <= set(merged[0].reasons)
    assert {"ExactCandidateProvider", "LexicalCandidateProvider"} <= set(merged[0].providers)


def test_source_diverse_selector_rejects_expansion_only_candidates():
    selector = SourceDiverseAnchorSelector()
    top = doc("top", "UserRegistrationController.create", summary="creates a user account")
    additive = doc("additive", "UserRegistrationService.create", summary="creates a user record")
    agent_project = doc("agent-project", "AgentProjectController.addAgentToProject", summary="adds an agent to a project")
    distant = doc("distant", "UnrelatedWorker.run", summary="background maintenance")
    candidates = [
        MergedCandidate(top, 0.70, "MEDIUM", 42, ["LEXICAL_TOKEN_OVERLAP", "QUERY_ORIGINAL"], ["LexicalCandidateProvider"], ["PRIMARY"], ["створити юзера"]),
        MergedCandidate(
            additive,
            0.68,
            "MEDIUM",
            42,
            ["LEXICAL_TOKEN_OVERLAP", "QUERY_NORMALIZED", "QUERY_EXPANSION"],
            ["LexicalCandidateProvider"],
            ["PRIMARY"],
            ["create user", "AgentProjectController.addAgentToProject"],
        ),
        MergedCandidate(
            agent_project,
            0.99,
            "HIGH",
            8,
            ["QUALIFIED_NAME_EXACT", "QUERY_EXPANSION"],
            ["QualifiedNameCandidateProvider"],
            ["PRIMARY"],
            ["AgentProjectController.addAgentToProject"],
        ),
        MergedCandidate(distant, 0.40, "LOW", 42, ["LEXICAL_TOKEN_OVERLAP", "QUERY_EXPANSION"], ["LexicalCandidateProvider"], ["PRIMARY"], ["noise"]),
    ]

    selected = selector.select(candidates, KnowledgeQueryPolicy(plan_candidate_min_score=0.42))

    assert [candidate.document.node_id for candidate in selected] == ["top", "additive"]


def test_exact_identifier_candidate_is_not_exclusive_to_its_source():
    selector = SourceDiverseAnchorSelector()
    exact = doc("site", "SiteController.createSite", qualified_name="example.SiteController.createSite")
    semantic = doc(
        "site-worker",
        "SiteProvisioningWorker.run",
        source_id="source-b",
        qualified_name="example.SiteProvisioningWorker.run",
        summary="provisions site creation resources",
    )
    candidates = [
        MergedCandidate(
            exact,
            0.99,
            "HIGH",
            8,
            ["QUALIFIED_NAME_SUFFIX", "QUERY_EXACT_IDENTIFIER"],
            ["QualifiedNameCandidateProvider"],
            ["PRIMARY"],
            ["SiteController.createSite"],
        ),
        MergedCandidate(
            semantic,
            0.82,
            "HIGH",
            52,
            ["SEMANTIC_VECTOR_SIMILARITY", "QUERY_ORIGINAL"],
            ["SEMANTIC"],
            ["PRIMARY"],
            ["як працює SiteController.createSite"],
        ),
    ]

    selected = selector.select(candidates, KnowledgeQueryPolicy())

    assert [candidate.document.node_id for candidate in selected] == ["site", "site-worker"]


def test_source_diverse_selector_uses_scores_without_callable_preference():
    selector = SourceDiverseAnchorSelector()
    callable_match = doc("callable", "PaymentListener.handle", qualified_name="example.PaymentListener.handle", summary="handles payment")
    type_context = doc("type", "PaymentListener", node_kind="TYPE", qualified_name="example.PaymentListener", summary="payment listener")
    candidates = [
        MergedCandidate(type_context, 0.64, "MEDIUM", 42, ["LEXICAL_TOKEN_OVERLAP", "QUERY_ORIGINAL"], ["LexicalCandidateProvider"], ["PRIMARY"], ["how is payment handled"]),
        MergedCandidate(callable_match, 0.62, "MEDIUM", 42, ["LEXICAL_TOKEN_OVERLAP", "QUERY_ORIGINAL"], ["LexicalCandidateProvider"], ["PRIMARY"], ["how is payment handled"]),
    ]

    selected = selector.select(candidates, KnowledgeQueryPolicy())

    assert [candidate.document.node_id for candidate in selected] == ["type", "callable"]


def test_source_aware_loading_prevents_large_source_starvation_and_is_order_independent():
    large_source = [
        raw_doc(f"noise-{index:04d}", f"Noise{index:04d}", source_id="large-source", summary="maintenance background")
        for index in range(5005)
    ]
    documents_by_source = {
        "large-source": large_source,
        "registration-source": [
            raw_doc(
                "register",
                "RegisterUser.start",
                source_id="registration-source",
                summary="register user account creation",
            )
        ],
        "login-source": [
            raw_doc("login", "LoginUser.start", source_id="login-source", summary="login user session")
        ],
    }

    def run(source_order):
        store = SourceAwareSearchStore(documents_by_source)
        sources = [query_source(source_id, len(documents_by_source[source_id])) for source_id in source_order]
        result = UnifiedAnchorSearcher(store).search(
            "register user login",
            sources,
            KnowledgeQueryPolicy(max_search_documents=30, max_selected_anchors=5),
        )
        return result, store

    forward, forward_store = run(["large-source", "registration-source", "login-source"])
    reversed_result, _reversed_store = run(["login-source", "registration-source", "large-source"])

    assert [node.nodeId for node in forward.all_candidates] == [node.nodeId for node in reversed_result.all_candidates]
    assert {"register", "login"} <= {node.nodeId for node in forward.all_candidates}
    inspected_sources = {query[0][0] for query in forward_store.document_queries}
    assert inspected_sources == {"large-source", "registration-source", "login-source"}
    large_limit = next(limit for source_ids, limit, _include_tests in forward_store.document_queries if source_ids == ("large-source",))
    assert large_limit < 30
    assert len(forward_store.document_queries) == 3
    diagnostic = next(item for item in forward.diagnostics if item.code == "SOURCE_DIVERSE_RETRIEVAL_DIAGNOSTICS")
    assert diagnostic.metadata["documentsInspectedBySource"]["registration-source"] == 1
    assert diagnostic.metadata["documentsInspectedBySource"]["login-source"] == 1
    assert diagnostic.metadata["sourcesStarved"] == []


def test_exact_anchor_does_not_suppress_semantic_anchor_from_other_source():
    store = SourceAwareSearchStore(
        {
            "source-a": [
                raw_doc(
                    "create-site",
                    "CreateSite",
                    source_id="source-a",
                    qualified_name="example.SiteController.createSite",
                )
            ],
            "source-b": [
                raw_doc(
                    "semantic-site",
                    "SiteProvisioningWorker.run",
                    source_id="source-b",
                    summary="semantic site provisioning workflow",
                )
            ],
        }
    )
    engine = DeterministicCodeSearchEngine(extra_broad_providers=[FakeSemanticProvider({"semantic-site"})])
    result = UnifiedAnchorSearcher(store, search_engine=engine).search(
        "CreateSite",
        [query_source("source-a"), query_source("source-b")],
        KnowledgeQueryPolicy(max_selected_anchors=5),
    )

    assert [node.nodeId for node in result.all_candidates] == ["create-site", "semantic-site"]
    assert result.all_candidates[0].sourceId == "source-a"
    assert result.all_candidates[1].sourceId == "source-b"


def test_two_independent_anchors_from_same_source_survive_within_budget():
    store = SourceAwareSearchStore(
        {
            "source-a": [
                raw_doc("authorize", "PaymentAuthorize.start", summary="payment authorize"),
                raw_doc("capture", "PaymentCapture.start", summary="payment capture"),
            ]
        }
    )

    result = UnifiedAnchorSearcher(store).search(
        "payment authorize capture",
        [query_source("source-a", 2)],
        KnowledgeQueryPolicy(max_selected_anchors=5),
    )

    assert {node.nodeId for node in result.all_candidates} == {"authorize", "capture"}


def test_weak_unrelated_sources_are_not_forced_into_selection():
    selector = SourceDiverseAnchorSelector()
    strong = MergedCandidate(
        doc("strong", "Registration.start", source_id="source-a"),
        0.82,
        "HIGH",
        42,
        ["LEXICAL_TOKEN_OVERLAP", "QUERY_ORIGINAL"],
        ["LexicalCandidateProvider"],
        ["PRIMARY"],
        ["registration"],
    )
    weak = MergedCandidate(
        doc("weak", "Unrelated.run", source_id="source-b"),
        0.24,
        "LOW",
        42,
        ["LEXICAL_TOKEN_OVERLAP", "QUERY_ORIGINAL"],
        ["LexicalCandidateProvider"],
        ["PRIMARY"],
        ["registration"],
    )

    selected = selector.select([strong, weak], KnowledgeQueryPolicy())

    assert [candidate.document.node_id for candidate in selected] == ["strong"]


def test_identically_named_nodes_in_different_sources_remain_distinct():
    left = doc("left", "SharedHandler.handle", source_id="source-a")
    right = doc("right", "SharedHandler.handle", source_id="source-b")

    merged = CandidateMerger().merge(
        [
            SearchCandidate(left, "ExactCandidateProvider", "EXACT_NAME", 0.98, "HIGH", 10),
            SearchCandidate(right, "ExactCandidateProvider", "EXACT_NAME", 0.98, "HIGH", 10),
        ]
    )

    assert {(candidate.document.source_id, candidate.document.node_id) for candidate in merged} == {
        ("source-a", "left"),
        ("source-b", "right"),
    }


def test_multiple_query_plan_inputs_can_contribute_anchors():
    store = SourceAwareSearchStore(
        {
            "source-a": [
                raw_doc("create", "CreateUserFlow", summary="create user"),
                raw_doc("register", "RegisterAccountFlow", summary="register account"),
            ]
        }
    )
    plan = QueryRetrievalPlan(
        original_query="CreateUserFlow",
        normalized_query="RegisterAccountFlow",
        search_queries=(),
        code_identifiers=(),
        concepts=("user",),
        effective_intent="FLOW_EXPLANATION",
        detected_language="en",
        response_language="en",
    )

    result = UnifiedAnchorSearcher(store).search_plan(
        plan,
        [query_source("source-a", 2)],
        KnowledgeQueryPolicy(max_selected_anchors=5),
    )

    assert {node.nodeId for node in result.all_candidates} == {"create", "register"}


def test_search_query_is_additive_and_cannot_invent_anchor():
    store = SourceAwareSearchStore(
        {
            "source-a": [
                raw_doc(
                    "agent-project",
                    "AgentProjectController.addAgentToProject",
                    summary="adds an agent to a project",
                )
            ]
        }
    )
    plan = QueryRetrievalPlan(
        original_query="create user",
        normalized_query="create user",
        search_queries=("AgentProjectController.addAgentToProject",),
        code_identifiers=(),
        concepts=("user",),
        effective_intent="FLOW_EXPLANATION",
        detected_language="en",
        response_language="en",
    )

    result = UnifiedAnchorSearcher(store).search_plan(
        plan,
        [query_source("source-a")],
        KnowledgeQueryPolicy(max_selected_anchors=5),
    )

    assert result.all_candidates == []


def test_code_identifier_input_must_be_exact_substring_of_user_query():
    store = SourceAwareSearchStore(
        {
            "source-a": [
                raw_doc("site", "SiteController.createSite", qualified_name="example.SiteController.createSite")
            ]
        }
    )
    invalid_plan = QueryRetrievalPlan(
        original_query="unrelated words",
        normalized_query="unrelated words",
        search_queries=(),
        code_identifiers=("SiteController.createSite",),
        concepts=(),
        effective_intent="FLOW_EXPLANATION",
        detected_language="en",
        response_language="en",
    )
    valid_plan = QueryRetrievalPlan(
        original_query="explain SiteController.createSite",
        normalized_query="explain SiteController.createSite",
        search_queries=(),
        code_identifiers=("SiteController.createSite",),
        concepts=(),
        effective_intent="FLOW_EXPLANATION",
        detected_language="en",
        response_language="en",
    )
    searcher = UnifiedAnchorSearcher(store)

    invalid = searcher.search_plan(invalid_plan, [query_source("source-a")], KnowledgeQueryPolicy())
    valid = searcher.search_plan(valid_plan, [query_source("source-a")], KnowledgeQueryPolicy())

    assert invalid.all_candidates == []
    assert [node.nodeId for node in valid.all_candidates] == ["site"]


def test_generic_names_are_not_rejected_by_suffix_or_domain_patterns():
    selector = SourceDiverseAnchorSelector()
    names = [
        "PaymentDTO",
        "PaymentDelegate",
        "PaymentController",
        "PaymentClient",
        "PaymentKafkaHandler",
        "PaymentSchedule",
    ]
    candidates = [
        MergedCandidate(
            doc(name, name),
            0.86,
            "HIGH",
            42,
            ["LEXICAL_TOKEN_OVERLAP", "QUERY_ORIGINAL"],
            ["LexicalCandidateProvider"],
            ["PRIMARY"],
            ["payment"],
        )
        for name in names
    ]

    selected = selector.select(candidates, KnowledgeQueryPolicy(max_selected_anchors=10))

    assert {candidate.document.node_id for candidate in selected} == set(names)


def test_supported_graph_node_kinds_can_all_be_selected_when_relevant():
    selector = SourceDiverseAnchorSelector()
    kinds = ["FILE", "TYPE", "CALLABLE", "FIELD", "CONTRACT_DECLARATION", "CLAIM"]
    candidates = [
        MergedCandidate(
            doc(kind.lower(), f"Relevant{kind}", node_kind=kind),
            0.84,
            "HIGH",
            42,
            ["LEXICAL_TOKEN_OVERLAP", "QUERY_ORIGINAL"],
            ["LexicalCandidateProvider"],
            ["PRIMARY"],
            ["relevant"],
        )
        for kind in kinds
    ]

    selected = selector.select(candidates, KnowledgeQueryPolicy(max_selected_anchors=10))

    assert {candidate.document.node_kind for candidate in selected} == set(kinds)


def test_internal_fallback_scope_runs_only_after_primary_is_insufficient_and_preserves_primary():
    store = SourceAwareSearchStore(
        {
            "primary-source": [raw_doc("primary", "PrimaryFlow.start", source_id="primary-source", summary="primary flow")],
            "fallback-source": [raw_doc("fallback", "FallbackFlow.start", source_id="fallback-source", summary="fallback flow")],
        }
    )
    scope = RetrievalScope(
        primary_sources=(query_source("primary-source"),),
        fallback_sources=(query_source("fallback-source"),),
    )

    result = UnifiedAnchorSearcher(store).search_scope(
        (("ORIGINAL_QUERY", "flow"),),
        scope,
        KnowledgeQueryPolicy(fallback_anchor_trigger_count=2, max_selected_anchors=5),
    )

    assert [node.nodeId for node in result.all_candidates] == ["primary", "fallback"]
    diagnostic = next(item for item in result.diagnostics if item.code == "SOURCE_DIVERSE_RETRIEVAL_DIAGNOSTICS")
    assert diagnostic.metadata["eligiblePrimarySourceCount"] == 1
    assert diagnostic.metadata["eligibleFallbackSourceCount"] == 1
    assert diagnostic.metadata["fallbackCandidateCount"] > 0


def test_include_tests_filters_documents_before_candidate_selection():
    store = SourceAwareSearchStore(
        {
            "source-a": [
                raw_doc("code", "PaymentFlow.start", summary="payment flow", flow_domain="CODE"),
                raw_doc("test", "PaymentFlowTest.start", summary="payment flow", flow_domain="TEST"),
            ]
        }
    )

    result = UnifiedAnchorSearcher(store).search(
        "payment flow",
        [query_source("source-a", 2)],
        KnowledgeQueryPolicy(max_selected_anchors=5),
        include_tests=False,
    )

    assert [node.nodeId for node in result.all_candidates] == ["code"]
    assert store.count_queries == [(("source-a",), False)]
    assert store.document_queries == [(("source-a",), 1, False)]


def test_preview_limit_does_not_reduce_internal_anchor_set():
    store = SourceAwareSearchStore(
        {
            "source-a": [
                raw_doc("first", "FirstPaymentFlow.start", summary="payment flow"),
                raw_doc("second", "SecondPaymentFlow.start", summary="payment flow"),
            ]
        }
    )

    result = UnifiedAnchorSearcher(store).search(
        "payment flow",
        [query_source("source-a", 2)],
        KnowledgeQueryPolicy(max_selected_anchors=5, max_display_candidates=1),
    )

    assert len(result.all_candidates) == 2
    assert len(result.display_candidates) == 1
    assert any(item.code == "MATCHED_NODE_PREVIEW_LIMITED" for item in result.diagnostics)


def test_exact_downstream_identifier_selects_containing_flow():
    service = KnowledgeQueryService(
        source_scope_resolver=object(),
        anchor_searcher=UnifiedAnchorSearcher(graph_store=object()),
        flow_repository=object(),
        anchor_expander=object(),
    )
    root = flow("PaymentController.readPayment", qualified_name="example.PaymentController.readPayment")
    downstream = FlowGraphNode(
        source_id="source-a",
        graph_id="graph-a",
        graph_revision="rev-a",
        node_id="repository-node",
        stable_key="source-a|repository-node",
        node_kind="CALLABLE",
        label="PaymentRepository.findById",
        qualified_name="example.PaymentRepository.findById",
    )
    containing = root.__class__(
        key=root.key,
        entrypoint=root.entrypoint,
        origin=root.origin,
        anchors=root.anchors,
        nodes=(root.entrypoint, downstream),
        transitions=root.transitions,
        boundary_transitions=root.boundary_transitions,
        evidence=root.evidence,
        complete=root.complete,
        coverage=root.coverage,
        diagnostics=root.diagnostics,
        relevance_score=root.relevance_score,
    )
    unrelated = flow("InvoiceController.readInvoice", qualified_name="example.InvoiceController.readInvoice")

    filtered = service._filter_flows_by_code_identifiers((containing, unrelated), ("PaymentRepository.findById",))

    assert [item.entrypoint.label for item in filtered] == ["PaymentController.readPayment"]


def test_exact_identifier_filter_does_not_use_substring_matches():
    service = KnowledgeQueryService(
        source_scope_resolver=object(),
        anchor_searcher=UnifiedAnchorSearcher(graph_store=object()),
        flow_repository=object(),
        anchor_expander=object(),
    )
    settings = flow("UserController.readSettings", qualified_name="example.UserController.readSettings")
    settings_node = FlowGraphNode(
        source_id="source-a",
        graph_id="graph-a",
        graph_revision="rev-a",
        node_id="settings-node",
        stable_key="source-a|settings-node",
        node_kind="CALLABLE",
        label="User.getSettings",
        qualified_name="example.User.getSettings",
    )
    containing_settings = settings.__class__(
        key=settings.key,
        entrypoint=settings.entrypoint,
        origin=settings.origin,
        anchors=settings.anchors,
        nodes=(settings.entrypoint, settings_node),
        transitions=settings.transitions,
        boundary_transitions=settings.boundary_transitions,
        evidence=settings.evidence,
        complete=settings.complete,
        coverage=settings.coverage,
        diagnostics=settings.diagnostics,
        relevance_score=settings.relevance_score,
    )
    save_flow = flow("AccountController.updateAccount", qualified_name="example.AccountController.updateAccount")
    save_node = FlowGraphNode(
        source_id="source-a",
        graph_id="graph-a",
        graph_revision="rev-a",
        node_id="save-node",
        stable_key="source-a|save-node",
        node_kind="CALLABLE",
        label="AccountRepository.saveAccount",
        qualified_name="example.AccountRepository.saveAccount",
    )
    containing_save = save_flow.__class__(
        key=save_flow.key,
        entrypoint=save_flow.entrypoint,
        origin=save_flow.origin,
        anchors=save_flow.anchors,
        nodes=(save_flow.entrypoint, save_node),
        transitions=save_flow.transitions,
        boundary_transitions=save_flow.boundary_transitions,
        evidence=save_flow.evidence,
        complete=save_flow.complete,
        coverage=save_flow.coverage,
        diagnostics=save_flow.diagnostics,
        relevance_score=save_flow.relevance_score,
    )

    assert service._filter_flows_by_code_identifiers((containing_settings,), ("User.get",)) == ()
    assert service._filter_flows_by_code_identifiers((containing_save,), ("save",)) == ()


def test_fuzzy_matcher_finds_transposed_and_missing_character_identifiers():
    engine = DeterministicCodeSearchEngine()
    documents = [
        doc("agent", "AgentChatPage", node_kind="TYPE", qualified_name="example.AgentChatPage"),
        doc("searcher", "UnifiedAnchorSearcher", node_kind="TYPE", qualified_name="knowledge.UnifiedAnchorSearcher"),
        doc("jarvis", "JarvisQuery", node_kind="TYPE", qualified_name="example.JarvisQuery"),
        doc("knowledge", "KnowledgeQuery", node_kind="TYPE", qualified_name="example.KnowledgeQuery"),
    ]

    assert engine.search("AgetnChatPage", documents, SearchConfig()).candidates[0].document.node_id == "agent"
    assert engine.search("UnifedAnchorSearcher", documents, SearchConfig()).candidates[0].document.node_id == "searcher"
    assert engine.search("JarvsiQuery", documents, SearchConfig()).candidates[0].document.node_id == "jarvis"
    assert engine.search("KnowlegeQuery", documents, SearchConfig()).candidates[0].document.node_id == "knowledge"


def test_exact_match_scores_higher_than_typo_match_for_same_node():
    engine = DeterministicCodeSearchEngine()
    documents = [doc("agent", "AgentChatPage", node_kind="TYPE", qualified_name="example.AgentChatPage")]

    exact = engine.search("AgentChatPage", documents, SearchConfig()).candidates[0]
    typo = engine.search("AgetnChatPage", documents, SearchConfig()).candidates[0]

    assert exact.score > typo.score
    assert any(reason.startswith("FUZZY") for reason in typo.reasons)
