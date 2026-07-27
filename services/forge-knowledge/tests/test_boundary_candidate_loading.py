from __future__ import annotations

import json
import sqlite3
from pathlib import Path
from typing import Any, Sequence

from semantic_test_support import seed_semantic_graph
from test_boundary_resolution import Unit

from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.boundary_resolution import (
    BoundaryCandidateLoadLimits,
    BoundaryResolutionStatus,
    GenericBoundaryResolver,
    boundary_identity,
)
from knowledge_service.entrypoint_flow_engine import EntrypointFlowEngine
from knowledge_service.entrypoint_flow_store import EntrypointFlowGraphRepository
from knowledge_service.flow_family import FlowFamilyAssembler
from knowledge_service.knowledge_query_schema import KnowledgeQueryMatchedNode, KnowledgeQueryRequest
from knowledge_service.knowledge_query_service import KnowledgeQueryService, QuerySource, SourceScopeResolver, UnifiedAnchorSearcher


def owner_id(source_id: str) -> str:
    return f"{source_id}-owner"


def seed_source(db_path: Path, source_id: str, node_id: str | None = None, *, graph_suffix: str | None = None) -> str:
    node_id = node_id or owner_id(source_id)
    return seed_semantic_graph(
        db_path,
        source_id=source_id,
        graph_suffix=graph_suffix or source_id,
        nodes=[{"id": node_id, "nodeKind": "CALLABLE", "name": f"{source_id}.{node_id}", "qualified": f"{source_id}.{node_id}", "path": f"src/{node_id}.txt"}],
        evidence_ids=[f"ev-node-{source_id}-{node_id}"],
    )


def insert_boundary(
    db_path: Path,
    *,
    source_id: str,
    node_id: str,
    boundary_id: str,
    role: str,
    descriptors: Sequence[tuple[str, str, Any]],
    status: str = "TRUSTED",
    descriptor_status: str = "TRUSTED",
    rejection_reason: str | None = None,
    flow_domain: str = "CODE",
    stale_boundary: bool = False,
    stale_descriptor: bool = False,
    stale_evidence: bool = False,
    with_evidence: bool = True,
) -> None:
    now = "2026-07-27T00:00:00+00:00"
    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        file_row = conn.execute(
            "SELECT id, relative_path, content_hash FROM files WHERE source_id = ? ORDER BY id LIMIT 1",
            (source_id,),
        ).fetchone()
        file_id = int(file_row["id"])
        relative_path = str(file_row["relative_path"])
        content_hash = str(file_row["content_hash"])
        boundary_hash = f"stale-{content_hash}" if stale_boundary else content_hash
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_graph_boundaries(
                id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                stable_key, node_id, role, confidence, status, rejection_reason, descriptor_json, metadata_json,
                created_at, updated_at, fact_origin, flow_domain
            )
            VALUES (?, 'boundary-test', ?, ?, ?, ?, ?, ?, ?, ?, ?, 0.92, ?, ?, '[]', '{}', ?, ?, 'STATIC', ?)
            """,
            (
                boundary_id,
                source_id,
                file_id,
                file_id,
                file_id,
                relative_path,
                boundary_hash,
                f"{source_id}:boundary:{boundary_id}",
                node_id,
                role,
                status,
                rejection_reason,
                now,
                now,
                flow_domain,
            ),
        )
        evidence_ids: list[str] = []
        if with_evidence:
            evidence_hash = f"stale-{content_hash}" if stale_evidence else content_hash
            evidence_id = f"ev-boundary-{boundary_id}"
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_evidence(
                    id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                    line_start, line_end, excerpt, excerpt_hash, evidence_kind, created_at, updated_at, fact_origin, flow_domain
                )
                VALUES (?, 'boundary-test', ?, ?, ?, ?, ?, ?, 1, 1, 'boundary evidence', ?, 'BOUNDARY', ?, ?, 'STATIC', ?)
                """,
                (evidence_id, source_id, file_id, file_id, file_id, relative_path, evidence_hash, evidence_id, now, now, flow_domain),
            )
            conn.execute("INSERT OR IGNORE INTO analysis_graph_boundary_evidence(boundary_id, evidence_id) VALUES (?, ?)", (boundary_id, evidence_id))
            evidence_ids.append(evidence_id)
        for index, (path, value_type, value) in enumerate(descriptors, start=1):
            descriptor_id = f"{boundary_id}:descriptor:{index}"
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_boundary_descriptors(
                    id, boundary_id, descriptor_path, value_type, value_json, origin, confidence, status, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, 'STATIC', 0.9, ?, ?, ?)
                """,
                (
                    descriptor_id,
                    boundary_id,
                    path,
                    value_type,
                    json.dumps(value, ensure_ascii=False, sort_keys=True),
                    "STALE" if stale_descriptor else descriptor_status,
                    now,
                    now,
                ),
            )
            for evidence_id in evidence_ids:
                conn.execute(
                    "INSERT OR IGNORE INTO analysis_graph_boundary_descriptor_evidence(descriptor_id, evidence_id) VALUES (?, ?)",
                    (descriptor_id, evidence_id),
                )


def load_required(db_path: Path, source_id: str, node_id: str | None = None):
    node_id = node_id or owner_id(source_id)
    repo = EntrypointFlowGraphRepository(AnalysisStore(db_path))
    graph_id = AnalysisStore(db_path).query_current_graph_sources()
    revision = next(item["graphRevision"] for item in graph_id if item["sourceId"] == source_id)
    boundaries = repo.load_boundaries({(source_id, revision, node_id)}, include_tests=False)[(source_id, revision, node_id)]
    return next(item for item in boundaries if item.role == "REQUIRED")


def query_sources(db_path: Path, source_ids: Sequence[str]) -> tuple[QuerySource, ...]:
    current = {item["sourceId"]: item for item in AnalysisStore(db_path).query_current_graph_sources()}
    return tuple(
        QuerySource(
            source_id=source_id,
            display_name=source_id,
            graph_id=current[source_id]["graphId"],
            graph_revision=current[source_id]["graphRevision"],
            node_count=1,
            edge_count=0,
        )
        for source_id in source_ids
    )


def sqlite_family_inputs(repo: EntrypointFlowGraphRepository, db_path: Path, source_id: str):
    current = {item["sourceId"]: item for item in AnalysisStore(db_path).query_current_graph_sources()}
    revision = current[source_id]["graphRevision"]
    node_id = owner_id(source_id)
    anchor = KnowledgeQueryMatchedNode(
        sourceId=source_id,
        nodeId=node_id,
        stableKey=node_id,
        nodeKind="CALLABLE",
        label=node_id,
        score=1.0,
        matchReasons=["SQLITE_ACCEPTANCE"],
        graphId=revision,
        graphRevision=revision,
    )
    result = EntrypointFlowEngine(repo).build([anchor], max_flows=10, include_tests=False)
    assembly = FlowFamilyAssembler().assemble(result.flows)
    return FlowFamilyAssembler().rank(assembly.families), result.local_units


class LimitedBoundaryCandidateRepository(EntrypointFlowGraphRepository):
    def __init__(self, graph_store: AnalysisStore, limits: BoundaryCandidateLoadLimits) -> None:
        super().__init__(graph_store)
        self.limits = limits

    def find_provided_boundary_candidates(self, required_boundaries, *, eligible_source_ids, include_tests, internal_limits=None):
        return super().find_provided_boundary_candidates(
            required_boundaries,
            eligible_source_ids=eligible_source_ids,
            include_tests=include_tests,
            internal_limits=self.limits,
        )


def query_service(db_path: Path, repo: EntrypointFlowGraphRepository | None = None) -> KnowledgeQueryService:
    store = AnalysisStore(db_path)
    return KnowledgeQueryService(
        SourceScopeResolver(store),
        UnifiedAnchorSearcher(store),
        repo or EntrypointFlowGraphRepository(store),
    )


def evidence_refs(boundary):
    return [item.evidence_id for item in boundary.evidence] + [item.evidence_id for descriptor in boundary.descriptors for item in descriptor.evidence]


def test_candidates_load_across_sources_exclude_same_source_and_keep_partial_current_facts(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    for source in ("source-a", "source-b", "source-c"):
        seed_source(db_path, source)
    with sqlite3.connect(db_path) as conn:
        conn.execute("UPDATE analysis_graph_state SET status = 'PARTIAL' WHERE source_id = 'source-c'")
    insert_boundary(db_path, source_id="source-a", node_id=owner_id("source-a"), boundary_id="required", role="REQUIRED", descriptors=(("neutral.identity", "STRING", "alpha"),))
    insert_boundary(db_path, source_id="source-a", node_id=owner_id("source-a"), boundary_id="same-source", role="PROVIDED", descriptors=(("neutral.identity", "STRING", "alpha"),))
    insert_boundary(db_path, source_id="source-b", node_id=owner_id("source-b"), boundary_id="provided-b", role="PROVIDED", descriptors=(("neutral.identity", "STRING", "alpha"),))
    insert_boundary(db_path, source_id="source-c", node_id=owner_id("source-c"), boundary_id="provided-c", role="PROVIDED", descriptors=(("neutral.identity", "STRING", "alpha"),))
    repo = EntrypointFlowGraphRepository(AnalysisStore(db_path))
    required = load_required(db_path, "source-a")

    result = repo.find_provided_boundary_candidates(
        [required],
        eligible_source_ids=["source-a", "source-b", "source-c"],
        include_tests=False,
    )

    candidates = result.candidates_by_required_identity[boundary_identity(required)]
    assert [(item.source_id, item.boundary_id) for item in candidates] == [("source-b", "provided-b"), ("source-c", "provided-c")]
    assert result.eligible_provided_boundary_count == 3


def test_rejected_stale_boundaries_descriptors_evidence_and_tests_are_excluded(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    for source in ("source-a", "source-b", "source-c", "source-d", "source-e", "source-test"):
        seed_source(db_path, source)
    insert_boundary(db_path, source_id="source-a", node_id=owner_id("source-a"), boundary_id="required", role="REQUIRED", descriptors=(("neutral.identity", "STRING", "alpha"),))
    insert_boundary(db_path, source_id="source-b", node_id=owner_id("source-b"), boundary_id="rejected", role="PROVIDED", descriptors=(("neutral.identity", "STRING", "alpha"),), rejection_reason="no")
    insert_boundary(db_path, source_id="source-c", node_id=owner_id("source-c"), boundary_id="stale-boundary", role="PROVIDED", descriptors=(("neutral.identity", "STRING", "alpha"),), stale_boundary=True)
    insert_boundary(db_path, source_id="source-d", node_id=owner_id("source-d"), boundary_id="stale-descriptor", role="PROVIDED", descriptors=(("neutral.identity", "STRING", "alpha"),), stale_descriptor=True)
    insert_boundary(db_path, source_id="source-e", node_id=owner_id("source-e"), boundary_id="stale-evidence", role="PROVIDED", descriptors=(("neutral.identity", "STRING", "alpha"),), stale_evidence=True)
    insert_boundary(db_path, source_id="source-test", node_id=owner_id("source-test"), boundary_id="test-boundary", role="PROVIDED", descriptors=(("neutral.identity", "STRING", "alpha"),), flow_domain="TEST")
    with sqlite3.connect(db_path) as conn:
        conn.execute("UPDATE files SET flow_domain = 'TEST' WHERE source_id = 'source-test'")
        conn.execute("UPDATE analysis_graph_nodes SET flow_domain = 'TEST' WHERE source_id = 'source-test'")
    repo = EntrypointFlowGraphRepository(AnalysisStore(db_path))
    required = load_required(db_path, "source-a")

    result = repo.find_provided_boundary_candidates(
        [required],
        eligible_source_ids=["source-a", "source-b", "source-c", "source-d", "source-e", "source-test"],
        include_tests=False,
    )

    candidates = result.candidates_by_required_identity[boundary_identity(required)]
    assert [(item.source_id, item.boundary_id) for item in candidates] == [("source-e", "stale-evidence")]
    assert evidence_refs(candidates[0]) == []

    included = repo.find_provided_boundary_candidates(
        [required],
        eligible_source_ids=["source-a", "source-test"],
        include_tests=True,
    )
    assert [(item.source_id, item.boundary_id) for item in included.candidates_by_required_identity[boundary_identity(required)]] == [
        ("source-test", "test-boundary")
    ]


def test_candidate_loading_is_batched_chunked_and_truncation_blocks_proof(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_source(db_path, "source-a")
    for index in range(12):
        seed_source(db_path, f"source-{index:02d}")
    insert_boundary(
        db_path,
        source_id="source-a",
        node_id=owner_id("source-a"),
        boundary_id="required",
        role="REQUIRED",
        descriptors=tuple((f"neutral.{index}", "STRING", f"value-{index}") for index in range(12)),
    )
    for index in range(12):
        insert_boundary(
            db_path,
            source_id=f"source-{index:02d}",
            node_id=owner_id(f"source-{index:02d}"),
            boundary_id=f"provided-{index:02d}",
            role="PROVIDED",
            descriptors=((f"neutral.{index}", "STRING", f"value-{index}"),),
        )
    for index in range(5):
        insert_boundary(
            db_path,
            source_id="source-00",
            node_id=owner_id("source-00"),
            boundary_id=f"provided-00-extra-{index}",
            role="PROVIDED",
            descriptors=(("neutral.0", "STRING", "value-0"),),
        )
    repo = EntrypointFlowGraphRepository(AnalysisStore(db_path))
    required = load_required(db_path, "source-a")

    result = repo.find_provided_boundary_candidates(
        [required],
        eligible_source_ids=["source-a", *(f"source-{index:02d}" for index in range(12))],
        include_tests=False,
        internal_limits=BoundaryCandidateLoadLimits(max_source_chunk_size=3, max_path_type_chunk_size=4, max_candidates_per_required=20),
    )
    assert len(result.candidates_by_required_identity[boundary_identity(required)]) == 17
    assert result.sql_statements < 80
    assert result.candidate_pages_loaded == 39

    truncated = repo.find_provided_boundary_candidates(
        [required],
        eligible_source_ids=["source-a", *(f"source-{index:02d}" for index in range(12))],
        include_tests=False,
        internal_limits=BoundaryCandidateLoadLimits(max_candidates_per_required=1),
    )
    resolution = GenericBoundaryResolver().resolve((Unit("unit-a", (required,)),), truncated)
    assert resolution.resolutions[0].status is BoundaryResolutionStatus.UNRESOLVED
    assert boundary_identity(required) in truncated.truncated_required_identities

    source_fair = repo.find_provided_boundary_candidates(
        [required],
        eligible_source_ids=["source-a", *(f"source-{index:02d}" for index in range(12))],
        include_tests=False,
        internal_limits=BoundaryCandidateLoadLimits(max_candidate_boundaries_total=3),
    )
    retained_sources = {item.source_id for item in source_fair.candidates_by_required_identity[boundary_identity(required)]}
    assert len(retained_sources) == 3


def test_descriptor_scan_row_budget_truncates_before_candidate_materialization(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_source(db_path, "source-a")
    seed_source(db_path, "source-b")
    insert_boundary(
        db_path,
        source_id="source-a",
        node_id=owner_id("source-a"),
        boundary_id="required",
        role="REQUIRED",
        descriptors=(("neutral.common", "STRING", "match"),),
    )
    for index in range(1200):
        insert_boundary(
            db_path,
            source_id="source-b",
            node_id=owner_id("source-b"),
            boundary_id=f"provided-{index:04d}",
            role="PROVIDED",
            descriptors=(("neutral.common", "STRING", "match" if index == 1199 else f"other-{index}"),),
        )
    repo = EntrypointFlowGraphRepository(AnalysisStore(db_path))
    required = load_required(db_path, "source-a")

    result = repo.find_provided_boundary_candidates(
        [required],
        eligible_source_ids=["source-a", "source-b"],
        include_tests=False,
        internal_limits=BoundaryCandidateLoadLimits(max_candidate_descriptor_rows_scanned=25, max_candidate_descriptor_page_size=7),
    )
    resolution = GenericBoundaryResolver().resolve((Unit("unit-a", (required,)),), result)

    assert result.candidate_descriptor_rows_scanned == 25
    assert result.candidate_descriptor_row_budget == 25
    assert result.candidate_descriptor_scan_truncated is True
    assert result.required_candidate_sets_incomplete == 1
    assert boundary_identity(required) in result.truncated_required_identities
    assert resolution.resolutions[0].status is BoundaryResolutionStatus.UNRESOLVED
    assert resolution.metrics.required_candidate_sets_incomplete == 1


def test_source_fair_descriptor_scan_and_per_required_limit_keep_minor_source_visible(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    for source in ("source-a", "source-b", "source-dominant"):
        seed_source(db_path, source)
    insert_boundary(
        db_path,
        source_id="source-a",
        node_id=owner_id("source-a"),
        boundary_id="required",
        role="REQUIRED",
        descriptors=(("neutral.identity", "STRING", "match"),),
    )
    for index in range(8):
        insert_boundary(
            db_path,
            source_id="source-dominant",
            node_id=owner_id("source-dominant"),
            boundary_id=f"provided-dominant-{index}",
            role="PROVIDED",
            descriptors=(("neutral.identity", "STRING", "match"),),
        )
    insert_boundary(
        db_path,
        source_id="source-b",
        node_id=owner_id("source-b"),
        boundary_id="provided-b",
        role="PROVIDED",
        descriptors=(("neutral.identity", "STRING", "match"),),
    )
    repo = EntrypointFlowGraphRepository(AnalysisStore(db_path))
    required = load_required(db_path, "source-a")

    scanned = repo.find_provided_boundary_candidates(
        [required],
        eligible_source_ids=["source-a", "source-dominant", "source-b"],
        include_tests=False,
        internal_limits=BoundaryCandidateLoadLimits(max_candidate_descriptor_rows_scanned=4, max_candidate_descriptor_page_size=100),
    )
    assert scanned.candidate_sources_inspected >= 2
    assert scanned.candidate_descriptor_scan_truncated is True
    assert any(item.source_id == "source-b" for item in scanned.candidates_by_required_identity[boundary_identity(required)])

    limited = repo.find_provided_boundary_candidates(
        [required],
        eligible_source_ids=["source-a", "source-dominant", "source-b"],
        include_tests=False,
        internal_limits=BoundaryCandidateLoadLimits(max_candidates_per_required=2),
    )
    retained = limited.candidates_by_required_identity[boundary_identity(required)]
    assert {item.source_id for item in retained} == {"source-b", "source-dominant"}
    assert boundary_identity(required) in limited.truncated_required_identities
    assert GenericBoundaryResolver().resolve((Unit("unit-a", (required,)),), limited).resolutions[0].status is BoundaryResolutionStatus.UNRESOLVED


def test_source_fair_scan_order_spans_sources_before_repeating_descriptor_chunks(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    for source in ("source-a", "source-b", "source-dominant"):
        seed_source(db_path, source)
    insert_boundary(
        db_path,
        source_id="source-a",
        node_id=owner_id("source-a"),
        boundary_id="required",
        role="REQUIRED",
        descriptors=tuple((f"neutral.chunk.{index}", "STRING", f"value-{index}") for index in range(6)),
    )
    for index in range(6):
        insert_boundary(
            db_path,
            source_id="source-dominant",
            node_id=owner_id("source-dominant"),
            boundary_id=f"provided-dominant-{index}",
            role="PROVIDED",
            descriptors=((f"neutral.chunk.{index}", "STRING", f"value-{index}"),),
        )
    insert_boundary(
        db_path,
        source_id="source-b",
        node_id=owner_id("source-b"),
        boundary_id="provided-b",
        role="PROVIDED",
        descriptors=(("neutral.chunk.0", "STRING", "value-0"),),
    )
    repo = EntrypointFlowGraphRepository(AnalysisStore(db_path))
    required = load_required(db_path, "source-a")

    result = repo.find_provided_boundary_candidates(
        [required],
        eligible_source_ids=["source-a", "source-dominant", "source-b"],
        include_tests=False,
        internal_limits=BoundaryCandidateLoadLimits(
            max_path_type_chunk_size=1,
            max_candidate_descriptor_rows_scanned=2,
            max_candidate_descriptor_page_size=100,
        ),
    )

    assert result.candidate_descriptor_rows_scanned == 2
    assert result.candidate_sources_inspected >= 2
    assert result.candidate_descriptor_scan_truncated is True
    assert any(item.source_id == "source-b" for item in result.candidates_by_required_identity[boundary_identity(required)])
    assert boundary_identity(required) in result.truncated_required_identities


def test_keyset_pages_do_not_skip_duplicate_or_depend_on_insertion_order(tmp_path):
    def build_db(db_path: Path, order: Sequence[int]):
        seed_source(db_path, "source-a")
        seed_source(db_path, "source-b")
        insert_boundary(
            db_path,
            source_id="source-a",
            node_id=owner_id("source-a"),
            boundary_id="required",
            role="REQUIRED",
            descriptors=(("neutral.identity", "STRING", "match"),),
        )
        for index in order:
            insert_boundary(
                db_path,
                source_id="source-b",
                node_id=owner_id("source-b"),
                boundary_id=f"provided-{index}",
                role="PROVIDED",
                descriptors=(("neutral.identity", "STRING", "match"),),
            )

    forward_db = tmp_path / "forward.sqlite"
    reverse_db = tmp_path / "reverse.sqlite"
    build_db(forward_db, range(7))
    build_db(reverse_db, reversed(range(7)))

    observed: list[tuple[str, ...]] = []
    for db_path in (forward_db, reverse_db):
        repo = EntrypointFlowGraphRepository(AnalysisStore(db_path))
        required = load_required(db_path, "source-a")
        result = repo.find_provided_boundary_candidates(
            [required],
            eligible_source_ids=["source-a", "source-b"],
            include_tests=False,
            internal_limits=BoundaryCandidateLoadLimits(max_candidate_descriptor_page_size=2),
        )
        observed.append(tuple(item.boundary_id for item in result.candidates_by_required_identity[boundary_identity(required)]))
        assert result.candidate_descriptor_rows_scanned == 7
        assert result.candidate_pages_loaded >= 4

    assert observed[0] == observed[1] == tuple(f"provided-{index}" for index in range(7))


def test_loader_exact_value_filtering_and_complete_vs_incomplete_uniqueness(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    for source in ("source-a", "source-b", "source-c"):
        seed_source(db_path, source)
    insert_boundary(
        db_path,
        source_id="source-a",
        node_id=owner_id("source-a"),
        boundary_id="required",
        role="REQUIRED",
        descriptors=(("neutral.identity", "STRING", "1"),),
    )
    insert_boundary(
        db_path,
        source_id="source-b",
        node_id=owner_id("source-b"),
        boundary_id="provided-string",
        role="PROVIDED",
        descriptors=(("neutral.identity", "STRING", "1"),),
    )
    insert_boundary(
        db_path,
        source_id="source-c",
        node_id=owner_id("source-c"),
        boundary_id="provided-number",
        role="PROVIDED",
        descriptors=(("neutral.identity", "INT", 1),),
    )
    repo = EntrypointFlowGraphRepository(AnalysisStore(db_path))
    required = load_required(db_path, "source-a")

    complete = repo.find_provided_boundary_candidates(
        [required],
        eligible_source_ids=["source-a", "source-b", "source-c"],
        include_tests=False,
    )
    assert [(item.source_id, item.boundary_id) for item in complete.candidates_by_required_identity[boundary_identity(required)]] == [
        ("source-b", "provided-string")
    ]
    assert GenericBoundaryResolver().resolve((Unit("unit-a", (required,)),), complete).resolutions[0].status is BoundaryResolutionStatus.PROVEN

    incomplete = repo.find_provided_boundary_candidates(
        [required],
        eligible_source_ids=["source-a", "source-b", "source-c"],
        include_tests=False,
        internal_limits=BoundaryCandidateLoadLimits(max_candidate_descriptor_rows_scanned=1, max_candidate_descriptor_page_size=1),
    )
    assert incomplete.candidate_descriptor_scan_truncated is True
    assert GenericBoundaryResolver().resolve((Unit("unit-a", (required,)),), incomplete).resolutions[0].status is BoundaryResolutionStatus.UNRESOLVED


def test_resolver_truncation_sets_public_query_coverage_flags(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_source(db_path, "source-a")
    seed_source(db_path, "source-b")
    insert_boundary(
        db_path,
        source_id="source-a",
        node_id=owner_id("source-a"),
        boundary_id="required",
        role="REQUIRED",
        descriptors=(("neutral.identity", "STRING", "match"),),
    )
    for index in range(20):
        insert_boundary(
            db_path,
            source_id="source-b",
            node_id=owner_id("source-b"),
            boundary_id=f"provided-{index}",
            role="PROVIDED",
            descriptors=(("neutral.identity", "STRING", "match"),),
        )
    store = AnalysisStore(db_path)
    repo = LimitedBoundaryCandidateRepository(
        store,
        BoundaryCandidateLoadLimits(max_candidate_descriptor_rows_scanned=1, max_candidate_descriptor_page_size=1),
    )

    result = query_service(db_path, repo).query_with_flows(KnowledgeQueryRequest(queryText=owner_id("source-a")))

    assert result.response.coverage.truncated is True
    assert result.response.coverage.continuationAvailable is True
    assert result.boundary_resolution is not None
    assert result.boundary_resolution.truncation.candidate_sets_truncated == 1
    assert any(item.code == "BOUNDARY_CANDIDATE_SET_INCOMPLETE" for item in result.response.diagnostics)


def test_complete_ambiguous_and_unresolved_do_not_set_public_query_truncation(tmp_path):
    ambiguous_db = tmp_path / "ambiguous.sqlite"
    for source in ("source-a", "source-b", "source-c"):
        seed_source(ambiguous_db, source)
    insert_boundary(
        ambiguous_db,
        source_id="source-a",
        node_id=owner_id("source-a"),
        boundary_id="required-ambiguous",
        role="REQUIRED",
        descriptors=(("neutral.identity", "STRING", "same"), ("neutral.variant", "STRING", "same")),
    )
    for source in ("source-b", "source-c"):
        insert_boundary(
            ambiguous_db,
            source_id=source,
            node_id=owner_id(source),
            boundary_id=f"provided-{source}",
            role="PROVIDED",
            descriptors=(("neutral.identity", "STRING", "same"), ("neutral.variant", "STRING", "same")),
        )
    ambiguous = query_service(ambiguous_db).query_with_flows(KnowledgeQueryRequest(queryText=owner_id("source-a")))

    unresolved_db = tmp_path / "unresolved.sqlite"
    seed_source(unresolved_db, "source-a")
    seed_source(unresolved_db, "source-b")
    insert_boundary(
        unresolved_db,
        source_id="source-a",
        node_id=owner_id("source-a"),
        boundary_id="required-unresolved",
        role="REQUIRED",
        descriptors=(("neutral.identity", "STRING", "missing"),),
    )
    insert_boundary(
        unresolved_db,
        source_id="source-b",
        node_id=owner_id("source-b"),
        boundary_id="provided-other",
        role="PROVIDED",
        descriptors=(("neutral.identity", "STRING", "other"),),
    )
    unresolved = query_service(unresolved_db).query_with_flows(KnowledgeQueryRequest(queryText=owner_id("source-a")))

    assert ambiguous.boundary_resolution is not None
    assert ambiguous.boundary_resolution.resolutions[0].status is BoundaryResolutionStatus.AMBIGUOUS
    assert ambiguous.response.coverage.truncated is False
    assert ambiguous.response.coverage.continuationAvailable is False
    assert unresolved.boundary_resolution is not None
    assert unresolved.boundary_resolution.resolutions[0].status is BoundaryResolutionStatus.UNRESOLVED
    assert unresolved.response.coverage.truncated is False
    assert unresolved.response.coverage.continuationAvailable is False


def test_temporary_sqlite_seeded_schema_acceptance_proven_ambiguous_unresolved(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    for source in ("source-a", "source-proven", "source-ambiguous-a", "source-ambiguous-b", "source-unresolved", "source-unresolved-extra"):
        seed_source(db_path, source)
    insert_boundary(
        db_path,
        source_id="source-a",
        node_id=owner_id("source-a"),
        boundary_id="required-proven",
        role="REQUIRED",
        descriptors=(("neutral.a", "STRING", "one"), ("neutral.b", "STRING", "two")),
    )
    insert_boundary(
        db_path,
        source_id="source-proven",
        node_id=owner_id("source-proven"),
        boundary_id="provided-proven",
        role="PROVIDED",
        descriptors=(("neutral.a", "STRING", "one"), ("neutral.b", "STRING", "two")),
    )
    insert_boundary(
        db_path,
        source_id="source-a",
        node_id=owner_id("source-a"),
        boundary_id="required-ambiguous",
        role="REQUIRED",
        descriptors=(("neutral.c", "STRING", "three"), ("neutral.d", "STRING", "four")),
    )
    for source in ("source-ambiguous-a", "source-ambiguous-b"):
        insert_boundary(
            db_path,
            source_id=source,
            node_id=owner_id(source),
            boundary_id=f"provided-{source}",
            role="PROVIDED",
            descriptors=(("neutral.c", "STRING", "three"), ("neutral.d", "STRING", "four")),
        )
    insert_boundary(
        db_path,
        source_id="source-a",
        node_id=owner_id("source-a"),
        boundary_id="required-unresolved",
        role="REQUIRED",
        descriptors=(("neutral.common", "STRING", "same"),),
    )
    insert_boundary(
        db_path,
        source_id="source-unresolved",
        node_id=owner_id("source-unresolved"),
        boundary_id="provided-unresolved",
        role="PROVIDED",
        descriptors=(("neutral.common", "STRING", "same"),),
    )
    insert_boundary(
        db_path,
        source_id="source-unresolved-extra",
        node_id=owner_id("source-unresolved-extra"),
        boundary_id="provided-unresolved-extra",
        role="PROVIDED",
        descriptors=(("neutral.common", "STRING", "same"),),
    )
    repo = EntrypointFlowGraphRepository(AnalysisStore(db_path))
    graph_revision = AnalysisStore(db_path).query_current_graph_sources()[0]["graphRevision"]
    required_key = ("source-a", graph_revision, owner_id("source-a"))
    required_boundaries = repo.load_boundaries({required_key}, include_tests=False)[required_key]
    load = repo.find_provided_boundary_candidates(
        required_boundaries,
        eligible_source_ids=["source-a", "source-proven", "source-ambiguous-a", "source-ambiguous-b", "source-unresolved", "source-unresolved-extra"],
        include_tests=False,
    )
    resolution = GenericBoundaryResolver().resolve((Unit("unit-a", required_boundaries),), load)
    by_boundary = {item.required_boundary.boundary_id: item.status for item in resolution.resolutions}

    assert by_boundary == {
        "required-proven": BoundaryResolutionStatus.PROVEN,
        "required-ambiguous": BoundaryResolutionStatus.AMBIGUOUS,
        "required-unresolved": BoundaryResolutionStatus.UNRESOLVED,
    }

    families, units = sqlite_family_inputs(repo, db_path, "source-a")
    continuation = KnowledgeQueryService(
        None,
        None,
        repo,
        flow_engine=EntrypointFlowEngine(repo),
    )._assemble_generic_boundary_continuations(
        families,
        units,
        query_sources(
            db_path,
            ("source-a", "source-proven", "source-ambiguous-a", "source-ambiguous-b", "source-unresolved", "source-unresolved-extra"),
        ),
        include_tests=False,
    )

    assert continuation.boundary_resolution is not None
    assert continuation.boundary_resolution.proven_links[0].target_owner.source_id == "source-proven"
    assert {unit.source_id for unit in continuation.boundary_resolution.discovered_local_units} == {"source-proven"}
