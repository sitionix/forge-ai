from __future__ import annotations

import sqlite3
import time
from dataclasses import replace

from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.entrypoint_flow_engine import (
    EntrypointFlow,
    EntrypointFlowAnchor,
    EntrypointFlowCoverage,
    EntrypointFlowKey,
    EntrypointFlowOrigin,
)
from knowledge_service.entrypoint_flow_store import EntrypointFlowGraphRepository
from knowledge_service.flow_family import FlowFamilyAssembler
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode
from knowledge_service.flow_narrative import FlowNarrativePartKind, FlowNarrativePlanner
from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.knowledge_query_service import KnowledgeQueryService, SourceScopeResolver, UnifiedAnchorSearcher
from semantic_test_support import seed_semantic_graph


REVISION = "graph-current"


def _entrypoint_claim(node_id: str, *, method: str = "POST", route: str = "/items", role: str = "EXECUTABLE"):
    return {
        "id": f"claim-{node_id}",
        "node_id": node_id,
        "claimKind": "ENTRYPOINT_HINT",
        "summary": f"{method} {route}",
        "entrypointKind": "HTTP",
        "httpMethod": method,
        "route": route,
        "entrypointExecutionKind": role,
        "evidence_ids": [f"ev-{node_id}"],
    }


def _service(db_path) -> KnowledgeQueryService:
    store = AnalysisStore(db_path)
    return KnowledgeQueryService(
        SourceScopeResolver(store),
        UnifiedAnchorSearcher(store),
        EntrypointFlowGraphRepository(store),
    )


def test_partial_source_current_successful_facts_are_queryable(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(
        db_path,
        source_id="partial-source",
        nodes=[
            {"id": "entry", "nodeKind": "CALLABLE", "name": "Entry.start", "qualified": "Entry.start", "path": "src/Entry.java"},
            {"id": "worker", "nodeKind": "CALLABLE", "name": "Worker.run", "qualified": "Worker.run", "path": "src/Worker.java"},
            {"id": "repo", "nodeKind": "CALLABLE", "name": "Repo.save", "qualified": "Repo.save", "path": "src/Repo.java"},
        ],
        edges=[
            {"id": "entry-worker", "fromNodeId": "entry", "toNodeId": "worker", "edgeType": "CALLS"},
            {"id": "worker-repo", "fromNodeId": "worker", "toNodeId": "repo", "edgeType": "CALLS"},
        ],
        claims=[_entrypoint_claim("entry", route="/partial")],
        evidence_ids=["ev-entry", "ev-worker", "ev-repo"],
    )
    with sqlite3.connect(db_path) as conn:
        conn.execute("UPDATE analysis_graph_state SET status = 'PARTIAL' WHERE source_id = 'partial-source'")
        for index in range(4, 101):
            conn.execute(
                """
                INSERT OR REPLACE INTO files(
                    id, source_id, source_path, absolute_path, relative_path, extension, language, flow_domain,
                    size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at
                )
                VALUES (?, 'partial-source', '.', '.', ?, '.java', 'java', 'CODE', 1, ?, 'now', 1, 'utf-8:replace', 'now')
                """,
                (90000 + index, f"src/Unprocessed{index}.java", f"pending-hash-{index}"),
            )
        for index in range(101, 103):
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_files(
                    file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status,
                    analyzed_at, diagnostics_json, flow_domain
                )
                VALUES (?, 'partial-source', ?, ?, 'fixture', '1', 'FAILED', 'now', '[]', 'CODE')
                """,
                (90000 + index, f"src/Failed{index}.java", f"failed-hash-{index}"),
            )

    result = _service(db_path).query_with_flows(KnowledgeQueryRequest(queryText="Entry.start"))

    assert result.response.status in {"OK", "AMBIGUOUS"}
    assert len(result.narrative_plans) == 1
    assert [node.qualified_name for node in result.flows[0].nodes] == ["Entry.start", "Repo.save", "Worker.run"]
    assert any(item.code == "PARTIAL_SOURCE_GRAPH_STATE" for item in result.response.diagnostics)


def test_stale_fact_is_excluded_while_current_fact_from_same_source_remains(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(
        db_path,
        source_id="mixed-source",
        nodes=[
            {"id": "current", "nodeKind": "CALLABLE", "name": "Current.start", "qualified": "Current.start", "path": "src/Current.java"},
            {"id": "stale", "nodeKind": "CALLABLE", "name": "Stale.start", "qualified": "Stale.start", "path": "src/Stale.java"},
        ],
        claims=[_entrypoint_claim("current", route="/current"), _entrypoint_claim("stale", route="/stale")],
        evidence_ids=["ev-current", "ev-stale"],
    )
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            "UPDATE files SET content_hash = 'new-current-hash' WHERE source_id = 'mixed-source' AND relative_path = 'src/Stale.java'"
        )

    stale = _service(db_path).query_with_flows(KnowledgeQueryRequest(queryText="Stale.start"))
    current = _service(db_path).query_with_flows(KnowledgeQueryRequest(queryText="Current.start"))

    assert all(
        "Stale.start" != node.qualified_name
        for flow in stale.flows
        for node in flow.nodes
    )
    assert current.response.status in {"OK", "AMBIGUOUS"}
    assert len(current.narrative_plans) == 1


def _node(node_id: str, *, source: str, role: str, method: str = "POST", route: str = "/items") -> FlowGraphNode:
    return FlowGraphNode(
        source_id=source,
        graph_id=f"{source}:{REVISION}",
        graph_revision=f"{source}:{REVISION}",
        node_id=node_id,
        stable_key=f"{source}:{node_id}",
        node_kind="CALLABLE",
        label=node_id,
        qualified_name=f"{source}.{node_id}",
        relative_path=f"src/{source}/{node_id}.java",
        line_start=1,
        line_end=1,
        entrypoint=True,
        entrypoint_kind="HTTP",
        entrypoint_http_method=method,
        entrypoint_route=route,
        execution_role=role,
    )


def _flow(
    root: FlowGraphNode,
    nodes: tuple[FlowGraphNode, ...] | None = None,
    edges: tuple[FlowGraphEdge, ...] = (),
    evidence: tuple[FlowGraphEvidence, ...] = (),
) -> EntrypointFlow:
    selected_nodes = nodes or (root,)
    return EntrypointFlow(
        key=EntrypointFlowKey(root.source_id, root.graph_revision or root.graph_id, root.node_id),
        entrypoint=root,
        origin=EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT,
        anchors=(EntrypointFlowAnchor(root.node_id, root.label, 1.0, ("TEST",), 0),),
        nodes=selected_nodes,
        transitions=edges,
        boundary_transitions=(),
        evidence=evidence,
        complete=True,
        coverage=EntrypointFlowCoverage(len(selected_nodes), len(edges), 0, 1, len(selected_nodes)),
        diagnostics=(),
        relevance_score=1.0,
    )


def _families(*flows: EntrypointFlow):
    return FlowFamilyAssembler().rank(FlowFamilyAssembler().assemble(flows).families)


def test_disconnected_exact_http_fragments_become_one_plan_with_unverified_gap():
    outbound = _node("Client.create", source="client", role="CLIENT_OPERATION")
    inbound = _node("Controller.create", source="service", role="EXECUTABLE")

    plans, diagnostics = FlowNarrativePlanner().assemble(_families(_flow(outbound), _flow(inbound)), max_plans=10)

    assert diagnostics == ()
    assert len(plans) == 1
    assert [part.kind for part in plans[0].parts] == [
        FlowNarrativePartKind.VERIFIED_FRAGMENT,
        FlowNarrativePartKind.UNVERIFIED_GAP,
        FlowNarrativePartKind.VERIFIED_FRAGMENT,
    ]
    assert plans[0].parts[1].gap.verification_status == "UNVERIFIED"


def test_ambiguous_http_target_does_not_merge_plans():
    outbound = _node("Client.create", source="client", role="CLIENT_OPERATION")
    target_a = _node("ControllerA.create", source="service-a", role="EXECUTABLE")
    target_b = _node("ControllerB.create", source="service-b", role="EXECUTABLE")

    plans, diagnostics = FlowNarrativePlanner().assemble(_families(_flow(outbound), _flow(target_a), _flow(target_b)), max_plans=10)

    assert len(plans) == 3
    assert any(part.kind is FlowNarrativePartKind.AMBIGUOUS_GAP for plan in plans for part in plan.parts)
    assert any(item.code == "FLOW_CORRELATION_AMBIGUOUS" for item in diagnostics)


def test_independent_roots_sharing_downstream_remain_independent_plans():
    entry_a = _node("EntryA.start", source="source-a", role="EXECUTABLE", method="GET", route="/a")
    entry_b = _node("EntryB.start", source="source-a", role="EXECUTABLE", method="GET", route="/b")
    shared = replace(entry_a, node_id="Shared.work", label="Shared.work", qualified_name="source-a.Shared.work", entrypoint=False)
    edge_a = FlowGraphEdge("source-a", entry_a.graph_id, entry_a.graph_revision, "a-shared", "CALLS", entry_a.node_id, shared.node_id, "RESOLVED")
    edge_b = FlowGraphEdge("source-a", entry_b.graph_id, entry_b.graph_revision, "b-shared", "CALLS", entry_b.node_id, shared.node_id, "RESOLVED")

    plans, _diagnostics = FlowNarrativePlanner().assemble(
        _families(
            _flow(entry_a, (entry_a, shared), (edge_a,)),
            _flow(entry_b, (entry_b, shared), (edge_b,)),
        ),
        max_plans=10,
    )

    assert len(plans) == 2
    assert all(len(plan.fragments) == 1 for plan in plans)


def test_large_partial_fragment_with_exact_http_gap_is_linear_enough():
    count = 2000
    root = _node("Client.bulkCreate", source="client", role="CLIENT_OPERATION", method="POST", route="/bulk-items")
    nodes = [root]
    edges = []
    evidence = []
    previous = root
    for index in range(count):
        node = replace(
            root,
            node_id=f"GeneratedStep{index:04d}.call",
            label=f"GeneratedStep{index:04d}.call",
            qualified_name=f"client.GeneratedStep{index:04d}.call",
            entrypoint=False,
            execution_role=None,
        )
        edge = FlowGraphEdge(
            "client",
            root.graph_id,
            root.graph_revision,
            f"edge-{index:04d}",
            "CALLS",
            previous.node_id,
            node.node_id,
            "RESOLVED",
            evidence_ids=(f"evidence-{index:04d}",),
        )
        nodes.append(node)
        edges.append(edge)
        evidence.append(
            FlowGraphEvidence(
                "client",
                root.graph_id,
                root.graph_revision,
                f"evidence-{index:04d}",
                None,
                edge.edge_id,
                "src/client/BulkClient.java",
                10,
                10,
                "repeated-looking call evidence",
            )
        )
        previous = node
    inbound = _node("BulkController.create", source="service", role="EXECUTABLE", method="POST", route="/bulk-items")

    started = time.perf_counter()
    families = _families(_flow(root, tuple(nodes), tuple(edges), tuple(evidence)), _flow(inbound))
    plans, diagnostics = FlowNarrativePlanner().assemble(families, max_plans=10)
    elapsed = time.perf_counter() - started

    assert diagnostics == ()
    assert len(plans) == 1
    assert elapsed < 5.0
    assert [part.kind for part in plans[0].parts] == [
        FlowNarrativePartKind.VERIFIED_FRAGMENT,
        FlowNarrativePartKind.UNVERIFIED_GAP,
        FlowNarrativePartKind.VERIFIED_FRAGMENT,
    ]
    first_fragment = plans[0].parts[0].fragment
    assert len(first_fragment.family.transitions) == count
    assert len(first_fragment.family.evidence) == count
