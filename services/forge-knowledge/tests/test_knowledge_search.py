from knowledge_service.knowledge_search import (
    CandidateMerger,
    DeterministicCodeSearchEngine,
    MergedCandidate,
    QueryNormalizer,
    SearchCandidate,
    SearchConfig,
    SearchDocument,
    SearchQueryProfile,
)
from knowledge_service.entrypoint_flow_engine import (
    EntrypointFlow,
    EntrypointFlowCoverage,
    EntrypointFlowKey,
    EntrypointFlowOrigin,
)
from knowledge_service.flow_graph_contract import FlowGraphNode
from knowledge_service.knowledge_query_service import KnowledgeQueryPolicy, KnowledgeQueryService, UnifiedAnchorSearcher
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


def test_retrieval_plan_filter_keeps_grounded_candidates_within_policy_score_window():
    searcher = UnifiedAnchorSearcher(graph_store=object())
    top = doc("top", "ReadController.read", summary="reads a record")
    nearby = doc("nearby", "ReadService.fetch", summary="fetches the same record")
    distant = doc("distant", "UnrelatedWorker.run", summary="background maintenance")
    plan = QueryRetrievalPlan(
        original_query="how is a record read",
        normalized_query="how is a record read",
        search_queries=("record read flow",),
        code_identifiers=(),
        concepts=("record read",),
        effective_intent="FLOW_EXPLANATION",
        detected_language="en",
        response_language="en",
    )
    candidates = [
        MergedCandidate(top, 0.70, "MEDIUM", 42, ["LEXICAL_TOKEN_OVERLAP", "QUERY_ORIGINAL"], ["LexicalCandidateProvider"]),
        MergedCandidate(nearby, 0.56, "MEDIUM", 42, ["LEXICAL_TOKEN_OVERLAP", "QUERY_EXPANSION"], ["LexicalCandidateProvider"]),
        MergedCandidate(distant, 0.40, "LOW", 42, ["LEXICAL_TOKEN_OVERLAP", "QUERY_EXPANSION"], ["LexicalCandidateProvider"]),
    ]

    filtered = searcher._filter_plan_candidates(candidates, plan, KnowledgeQueryPolicy(plan_candidate_min_score=0.42, plan_candidate_top_delta=0.18))

    assert [candidate.document.node_id for candidate in filtered] == ["top", "nearby"]


def test_retrieval_plan_filter_prefers_exact_code_identifier_matches():
    searcher = UnifiedAnchorSearcher(graph_store=object())
    exact = doc("site", "SiteController.createSite", qualified_name="example.SiteController.createSite")
    controller_type = doc("site-controller", "SiteController", node_kind="TYPE", qualified_name="example.SiteController")
    broad = doc("site-overview", "SiteController.getSiteOverview", qualified_name="example.SiteController.getSiteOverview")
    plan = QueryRetrievalPlan(
        original_query="як працює SiteController.createSite",
        normalized_query="як працює SiteController.createSite",
        search_queries=("SiteController execution flow",),
        code_identifiers=("SiteController.createSite",),
        concepts=("site controller",),
        effective_intent="FLOW_EXPLANATION",
        detected_language="uk",
        response_language="uk",
    )
    candidates = [
        MergedCandidate(exact, 0.99, "HIGH", 8, ["QUALIFIED_NAME_SUFFIX", "QUERY_EXACT_IDENTIFIER"], ["QualifiedNameCandidateProvider"]),
        MergedCandidate(controller_type, 0.98, "HIGH", 10, ["EXACT_NAME", "QUERY_EXACT_IDENTIFIER"], ["ExactCandidateProvider"]),
        MergedCandidate(broad, 0.91, "HIGH", 42, ["LEXICAL_TOKEN_OVERLAP", "QUERY_EXPANSION"], ["LexicalCandidateProvider"]),
    ]

    filtered = searcher._filter_plan_candidates(candidates, plan, KnowledgeQueryPolicy())

    assert [candidate.document.node_id for candidate in filtered] == ["site"]


def test_flow_retrieval_plan_filter_uses_scores_without_subject_vocabulary():
    searcher = UnifiedAnchorSearcher(graph_store=object())
    callable_match = doc("callable", "PaymentListener.handle", qualified_name="example.PaymentListener.handle", summary="handles payment")
    type_context = doc("type", "PaymentListener", node_kind="TYPE", qualified_name="example.PaymentListener", summary="payment listener")
    plan = QueryRetrievalPlan(
        original_query="how is payment handled",
        normalized_query="how is payment handled",
        search_queries=("payment handling flow",),
        code_identifiers=(),
        concepts=("payment handling",),
        effective_intent="FLOW_EXPLANATION",
        detected_language="en",
        response_language="en",
    )
    candidates = [
        MergedCandidate(type_context, 0.64, "MEDIUM", 42, ["LEXICAL_TOKEN_OVERLAP", "QUERY_ORIGINAL"], ["LexicalCandidateProvider"]),
        MergedCandidate(callable_match, 0.62, "MEDIUM", 42, ["LEXICAL_TOKEN_OVERLAP", "QUERY_ORIGINAL"], ["LexicalCandidateProvider"]),
    ]

    filtered = searcher._filter_plan_candidates(candidates, plan, KnowledgeQueryPolicy())

    assert [candidate.document.node_id for candidate in filtered] == ["type", "callable"]


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
