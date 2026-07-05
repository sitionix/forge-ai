from knowledge_service.knowledge_search import (
    CandidateMerger,
    DeterministicCodeSearchEngine,
    QueryNormalizer,
    SearchCandidate,
    SearchConfig,
    SearchDocument,
    SearchQueryProfile,
)


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
