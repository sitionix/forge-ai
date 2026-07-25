import sqlite3

from knowledge_service.semantic_builder import SemanticBuildConfig, SemanticDocumentBuilder
from knowledge_service.semantic_index import SemanticIndexStore
from semantic_test_support import seed_semantic_graph


def _build_documents(db_path, *, config=None, source_id="semantic-source"):
    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        graph = SemanticIndexStore.current_graph_info_conn(conn, source_id)
        return SemanticDocumentBuilder(config).build_source_documents(conn, source_id, graph_info=graph)


def test_semantic_document_builder_uses_only_trusted_responsibility_claims(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(
        db_path,
        nodes=[
            {"id": "node-query", "nodeKind": "CALLABLE", "name": "JarvisQueryService.query", "qualified": "jarvis.JarvisQueryService.query"},
            {"id": "node-other", "nodeKind": "CALLABLE", "name": "Other.call", "qualified": "other.Other.call"},
        ],
        claims=[
            {
                "id": "claim-trusted",
                "node_id": "node-query",
                "summary": "Passes query text to KnowledgeClient.query.",
                "evidence_ids": ["ev-node-query"],
            },
            {
                "id": "claim-candidate",
                "node_id": "node-query",
                "summary": "Candidate text must not appear.",
                "status": "CANDIDATE",
                "evidence_ids": ["ev-node-query"],
            },
            {
                "id": "claim-rejected",
                "node_id": "node-query",
                "summary": "Rejected text must not appear.",
                "status": "REJECTED",
                "evidence_ids": ["ev-node-query"],
            },
            {
                "id": "claim-missing-evidence",
                "node_id": "node-query",
                "summary": "Missing evidence text must not appear.",
                "evidence_ids": [],
            },
            {
                "id": "claim-other",
                "node_id": "node-other",
                "summary": "Other node trusted summary.",
                "evidence_ids": ["ev-node-query"],
            },
        ],
    )

    document = next(doc for doc in _build_documents(db_path) if doc.node_id == "node-query")

    assert "Passes query text to KnowledgeClient.query." in document.text
    assert "Candidate text" not in document.text
    assert "Rejected text" not in document.text
    assert "Missing evidence text" not in document.text
    assert "Other node trusted summary" not in document.text
    assert document.claim_ids == ("claim-trusted",)
    assert document.evidence_ids == ("ev-node-query",)


def test_semantic_document_builder_creates_structural_only_document_without_trusted_summary(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path, claims=[])

    document = _build_documents(db_path)[0]

    assert "Node kind: CALLABLE" in document.text
    assert "Name: JarvisQueryService.query" in document.text
    assert "Responsibility:" not in document.text
    assert "orchestrates" not in document.text
    assert document.claim_ids == ()
    assert document.evidence_ids == ()


def test_semantic_document_builder_orders_text_and_hash_deterministically(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(
        db_path,
        nodes=[
            {"id": "b", "nodeKind": "CALLABLE", "name": "Beta.call", "qualified": "example.Beta.call"},
            {"id": "a", "nodeKind": "CALLABLE", "name": "Alpha.call", "qualified": "example.Alpha.call"},
        ],
        edges=[
            {"id": "edge-z", "fromNodeId": "a", "toNodeId": "b", "edgeType": "CALLS"},
            {"id": "edge-a", "fromNodeId": "b", "toNodeId": "a", "edgeType": "CALLS"},
        ],
        claims=[
            {"id": "claim-a", "node_id": "a", "summary": "Alpha trusted summary.", "evidence_ids": ["ev-node-query"]},
            {"id": "claim-b", "node_id": "b", "summary": "Beta trusted summary.", "evidence_ids": ["ev-node-query"]},
        ],
    )

    first = _build_documents(db_path)
    second = _build_documents(db_path)

    assert [document.node_id for document in first] == ["a", "b"]
    assert [document.text for document in first] == [document.text for document in second]
    assert [document.text_hash for document in first] == [document.text_hash for document in second]


def test_semantic_document_builder_bounds_edge_facts_and_text_length(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    nodes = [{"id": "root", "nodeKind": "CALLABLE", "name": "Root.call", "qualified": "example.Root.call"}]
    edges = []
    for index in range(8):
        node_id = f"target-{index}"
        nodes.append({"id": node_id, "nodeKind": "CALLABLE", "name": f"Target{index}.call", "qualified": f"example.Target{index}.call"})
        edges.append({"id": f"edge-{index}", "fromNodeId": "root", "toNodeId": node_id, "edgeType": "CALLS"})
    seed_semantic_graph(
        db_path,
        nodes=nodes,
        edges=edges,
        claims=[
            {
                "id": "claim-long",
                "node_id": "root",
                "summary": "Trusted summary " + ("word " * 60),
                "evidence_ids": ["ev-node-query"],
            }
        ],
    )

    document = next(
        doc
        for doc in _build_documents(db_path, config=SemanticBuildConfig(max_edges_per_document=3, max_document_chars=260))
        if doc.node_id == "root"
    )

    assert len(document.text) <= 260
    assert document.text.count("- CALLS:") <= 3
