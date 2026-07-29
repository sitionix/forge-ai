from __future__ import annotations

import json
import sqlite3
from pathlib import Path
from typing import Any

from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.boundary_contract import LocalBoundaryDescriptor, LocalBoundaryFact
from knowledge_service.boundary_resolution import BoundaryCandidateLoadLimits, boundary_identity, descriptor_fingerprint
from knowledge_service.flow_graph_contract import FlowGraphEvidence
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.local_flow_unit_store import LocalFlowUnitGraphRepository


def test_cross_source_provided_candidates_load_and_same_source_is_excluded(tmp_path):
    repo = _repo(tmp_path, ("source-a", "source-b"))
    required = _required("source-a", "contract.identity", "alpha")
    _insert_boundary(tmp_path, "source-a", "same-source", "PROVIDED", "contract.identity", "alpha")
    _insert_boundary(tmp_path, "source-b", "cross-source", "PROVIDED", "contract.identity", "alpha")

    result = repo.find_provided_boundary_candidates([required], eligible_source_ids=["source-a", "source-b"], include_tests=False)

    assert _candidate_ids(result) == ["cross-source"]
    assert result.provided_candidates_by_source == {"source-b": 1}


def test_partial_source_facts_remain_eligible(tmp_path):
    repo = _repo(tmp_path, ("source-a", "source-b"), analysis_status_by_source={"source-b": "PARTIAL"})
    required = _required("source-a", "contract.identity", "alpha")
    _insert_boundary(tmp_path, "source-b", "partial-provided", "PROVIDED", "contract.identity", "alpha")

    result = repo.find_provided_boundary_candidates([required], eligible_source_ids=["source-b"], include_tests=False)

    assert _candidate_ids(result) == ["partial-provided"]


def test_rejected_stale_boundary_and_stale_descriptor_rows_are_excluded(tmp_path):
    repo = _repo(tmp_path, ("source-a", "source-b"))
    required = _required("source-a", "contract.identity", "alpha")
    _insert_boundary(tmp_path, "source-b", "rejected", "PROVIDED", "contract.identity", "alpha", rejection_reason="bad")
    _insert_boundary(tmp_path, "source-b", "stale-boundary", "PROVIDED", "contract.identity", "alpha", boundary_status="STALE")
    _insert_boundary(tmp_path, "source-b", "stale-descriptor", "PROVIDED", "contract.identity", "alpha", descriptor_status="STALE")
    _insert_boundary(tmp_path, "source-b", "current", "PROVIDED", "contract.identity", "alpha")

    result = repo.find_provided_boundary_candidates([required], eligible_source_ids=["source-b"], include_tests=False)

    assert _candidate_ids(result) == ["current"]


def test_stale_descriptor_evidence_does_not_authorise_proof(tmp_path):
    repo = _repo(tmp_path, ("source-a", "source-b"))
    required = _required("source-a", "contract.identity", "alpha")
    _insert_boundary(tmp_path, "source-b", "candidate", "PROVIDED", "contract.identity", "alpha", descriptor_evidence_current=False)

    result = repo.find_provided_boundary_candidates([required], eligible_source_ids=["source-b"], include_tests=False)

    candidate = _single_candidate(result)
    assert candidate.boundary_id == "candidate"
    assert candidate.descriptors[0].evidence == ()


def test_test_facts_are_filtered_by_include_tests(tmp_path):
    repo = _repo(tmp_path, ("source-a", "source-test"), flow_domain_by_source={"source-test": "TEST"})
    required = _required("source-a", "contract.identity", "alpha")
    _insert_boundary(tmp_path, "source-test", "test-candidate", "PROVIDED", "contract.identity", "alpha", flow_domain="TEST")

    excluded = repo.find_provided_boundary_candidates([required], eligible_source_ids=["source-test"], include_tests=False)
    included = repo.find_provided_boundary_candidates([required], eligible_source_ids=["source-test"], include_tests=True)

    assert _candidate_ids(excluded) == []
    assert _candidate_ids(included) == ["test-candidate"]


def test_exact_typed_fingerprints_and_value_type_are_preserved(tmp_path):
    repo = _repo(tmp_path, ("source-a", "source-b"))
    required = _required("source-a", "contract.identity", "1", value_type="STRING")
    _insert_boundary(tmp_path, "source-b", "string", "PROVIDED", "contract.identity", "1", value_type="STRING")
    _insert_boundary(tmp_path, "source-b", "integer", "PROVIDED", "contract.identity", 1, value_type="INTEGER")

    result = repo.find_provided_boundary_candidates([required], eligible_source_ids=["source-b"], include_tests=False)

    assert _candidate_ids(result) == ["string"]
    queried = next(iter(result.provided_boundaries_by_fingerprint))
    assert queried == descriptor_fingerprint(required.descriptors[0])
    assert queried.value_type == "STRING"


def test_descriptor_pagination_order_fairness_and_limits_are_deterministic(tmp_path):
    repo = _repo(tmp_path, ("source-a", "source-b", "source-c"))
    required = _required("source-a", "contract.identity", "alpha")
    for index in range(4):
        _insert_boundary(tmp_path, "source-b", f"b-{index}", "PROVIDED", "contract.identity", "alpha")
        _insert_boundary(tmp_path, "source-c", f"c-{index}", "PROVIDED", "contract.identity", "alpha")

    limits = BoundaryCandidateLoadLimits(
        max_candidate_descriptor_page_size=1,
        max_candidate_descriptor_rows_scanned=20,
        max_candidates_per_required=2,
        max_candidate_boundaries_total=2,
    )
    first = repo.find_provided_boundary_candidates([required], eligible_source_ids=["source-b", "source-c"], include_tests=False, internal_limits=limits)
    second = repo.find_provided_boundary_candidates([required], eligible_source_ids=["source-c", "source-b"], include_tests=False, internal_limits=limits)

    assert _candidate_ids(first) == _candidate_ids(second)
    assert set(first.provided_candidates_by_source) == {"source-b", "source-c"}
    assert sum(first.provided_candidates_by_source.values()) == 2
    assert first.candidate_pages_loaded >= 2
    assert first.sql_statements <= 32


def test_global_descriptor_row_budget_reports_exact_truncated_required_identities(tmp_path):
    repo = _repo(tmp_path, ("source-a", "source-b"))
    required_a = _required("source-a", "contract.a", "a", boundary_id="required-a")
    required_b = _required("source-a", "contract.b", "b", boundary_id="required-b")
    _insert_boundary(tmp_path, "source-b", "candidate-a", "PROVIDED", "contract.a", "a")
    _insert_boundary(tmp_path, "source-b", "candidate-b", "PROVIDED", "contract.b", "b")

    result = repo.find_provided_boundary_candidates(
        [required_a, required_b],
        eligible_source_ids=["source-b"],
        include_tests=False,
        internal_limits=BoundaryCandidateLoadLimits(max_candidate_descriptor_rows_scanned=1, max_candidate_descriptor_page_size=1),
    )

    assert result.candidate_descriptor_scan_truncated is True
    assert result.truncated_required_identities
    assert result.truncated_required_identities <= {boundary_identity(required_a), boundary_identity(required_b)}


def test_current_graph_revision_filtering_excludes_old_revisions(tmp_path):
    repo = _repo(tmp_path, ("source-a", "source-b"))
    required = _required("source-a", "contract.identity", "alpha")
    _insert_boundary(tmp_path, "source-b", "old-revision", "PROVIDED", "contract.identity", "alpha", content_hash="old-hash")
    _insert_boundary(tmp_path, "source-b", "current-revision", "PROVIDED", "contract.identity", "alpha")

    result = repo.find_provided_boundary_candidates([required], eligible_source_ids=["source-b"], include_tests=False)

    assert _candidate_ids(result) == ["current-revision"]


def test_reversing_required_input_order_preserves_canonical_result(tmp_path):
    repo = _repo(tmp_path, ("source-a", "source-b"))
    required_a = _required("source-a", "contract.a", "a", boundary_id="required-a")
    required_b = _required("source-a", "contract.b", "b", boundary_id="required-b")
    _insert_boundary(tmp_path, "source-b", "candidate-a", "PROVIDED", "contract.a", "a")
    _insert_boundary(tmp_path, "source-b", "candidate-b", "PROVIDED", "contract.b", "b")

    first = repo.find_provided_boundary_candidates([required_a, required_b], eligible_source_ids=["source-b"], include_tests=False)
    second = repo.find_provided_boundary_candidates([required_b, required_a], eligible_source_ids=["source-b"], include_tests=False)

    assert _result_signature(first) == _result_signature(second)


def _repo(
    tmp_path: Path,
    source_ids: tuple[str, ...],
    *,
    flow_domain_by_source: dict[str, str] | None = None,
    analysis_status_by_source: dict[str, str] | None = None,
) -> LocalFlowUnitGraphRepository:
    db_path = tmp_path / "knowledge.sqlite"
    InventoryStore(db_path).init()
    AnalysisStore(db_path).init()
    with sqlite3.connect(db_path) as conn:
        for index, source_id in enumerate(source_ids, start=1):
            flow_domain = (flow_domain_by_source or {}).get(source_id, "CODE")
            status = (analysis_status_by_source or {}).get(source_id, "ANALYZED")
            _insert_source_file_node(conn, source_id, index, flow_domain=flow_domain, analysis_status=status)
    return LocalFlowUnitGraphRepository(AnalysisStore(db_path))


def _insert_source_file_node(conn: sqlite3.Connection, source_id: str, file_id: int, *, flow_domain: str, analysis_status: str) -> None:
    now = "2026-07-28T00:00:00Z"
    relative_path = f"src/{source_id}.py"
    content_hash = _current_hash(source_id)
    conn.execute(
        "INSERT INTO sources(source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at) VALUES (?, ?, 'test', '.', 1, '[]', '{}', ?)",
        (source_id, source_id, now),
    )
    conn.execute(
        """
        INSERT INTO files(id, source_id, source_path, absolute_path, relative_path, extension, language, flow_domain,
                          size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at)
        VALUES (?, ?, '.', '.', ?, '.py', 'python', ?, 100, ?, ?, 10, 'utf-8:replace', ?)
        """,
        (file_id, source_id, relative_path, flow_domain, content_hash, now, now),
    )
    conn.execute(
        """
        INSERT INTO analysis_files(file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version,
                                   status, analyzed_at, attempt_count, diagnostics_json, flow_domain)
        VALUES (?, ?, ?, ?, 'test', '1', ?, ?, 1, '[]', ?)
        """,
        (file_id, source_id, relative_path, content_hash, analysis_status, now, flow_domain),
    )
    conn.execute(
        """
        INSERT INTO analysis_graph_nodes(
            id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
            stable_key, node_kind, language, name, qualified_name, display_name, line_start, line_end,
            confidence, status, created_at, updated_at, fact_origin, flow_domain
        )
        VALUES (?, 'job', ?, ?, ?, ?, ?, ?, ?, 'CALLABLE', 'python', ?, ?, ?, 1, 1, 1.0, 'TRUSTED', ?, ?, 'STATIC', ?)
        """,
        (
            _owner_node_id(source_id),
            source_id,
            file_id,
            file_id,
            file_id,
            relative_path,
            content_hash,
            f"{source_id}:owner",
            f"{source_id}.owner",
            f"{source_id}.owner",
            f"{source_id}.owner",
            now,
            now,
            flow_domain,
        ),
    )
    conn.execute(
        """
        INSERT INTO analysis_graph_evidence(
            id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
            line_start, line_end, excerpt, excerpt_hash, evidence_kind, created_at, updated_at, fact_origin, flow_domain
        )
        VALUES (?, 'job', ?, ?, ?, ?, ?, ?, 1, 1, ?, ?, 'NODE', ?, ?, 'STATIC', ?)
        """,
        (_evidence_id(source_id), source_id, file_id, file_id, file_id, relative_path, content_hash, f"evidence {source_id}", f"evidence {source_id}", now, now, flow_domain),
    )
    conn.execute(
        """
        INSERT INTO analysis_graph_state(source_id, graph_id, content_identity, node_count, edge_count, claim_count, evidence_count, status, diagnostics_json, updated_at)
        VALUES (?, ?, ?, 1, 0, 0, 1, 'READY', '[]', ?)
        """,
        (source_id, f"{source_id}:graph", _current_revision(source_id), now),
    )


def _insert_boundary(
    tmp_path: Path,
    source_id: str,
    boundary_id: str,
    role: str,
    descriptor_path: str,
    value: Any,
    *,
    value_type: str = "STRING",
    boundary_status: str = "TRUSTED",
    descriptor_status: str = "TRUSTED",
    rejection_reason: str | None = None,
    descriptor_evidence_current: bool = True,
    content_hash: str | None = None,
    flow_domain: str = "CODE",
) -> None:
    db_path = tmp_path / "knowledge.sqlite"
    now = "2026-07-28T00:00:00Z"
    descriptor_id = f"{boundary_id}:descriptor"
    evidence_id = _evidence_id(source_id) if descriptor_evidence_current else f"{boundary_id}:stale-evidence"
    with sqlite3.connect(db_path) as conn:
        row = conn.execute("SELECT id, relative_path FROM files WHERE source_id = ?", (source_id,)).fetchone()
        assert row is not None
        file_id = int(row[0])
        relative_path = str(row[1])
        row_hash = content_hash or _current_hash(source_id)
        if not descriptor_evidence_current:
            conn.execute(
                """
                INSERT INTO analysis_graph_evidence(
                    id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                    line_start, line_end, excerpt, excerpt_hash, evidence_kind, created_at, updated_at, fact_origin, flow_domain
                )
                VALUES (?, 'job', ?, ?, ?, ?, ?, 'stale-hash', 1, 1, 'stale', 'stale', 'NODE', ?, ?, 'STATIC', ?)
                """,
                (evidence_id, source_id, file_id, file_id, file_id, relative_path, now, now, flow_domain),
            )
        conn.execute(
            """
            INSERT INTO analysis_graph_boundaries(
                id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                stable_key, node_id, role, confidence, status, rejection_reason, descriptor_json, metadata_json,
                created_at, updated_at, fact_origin, flow_domain
            )
            VALUES (?, 'job', ?, ?, ?, ?, ?, ?, ?, ?, ?, 1.0, ?, ?, ?, '{}', ?, ?, 'STATIC', ?)
            """,
            (
                boundary_id,
                source_id,
                file_id,
                file_id,
                file_id,
                relative_path,
                row_hash,
                boundary_id,
                _owner_node_id(source_id),
                role,
                boundary_status,
                rejection_reason,
                json.dumps([{"path": descriptor_path, "valueType": value_type, "value": value}], sort_keys=True),
                now,
                now,
                flow_domain,
            ),
        )
        conn.execute(
            """
            INSERT INTO analysis_graph_boundary_descriptors(
                id, boundary_id, descriptor_path, value_type, value_json, origin, confidence, status, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, 'STATIC', 1.0, ?, ?, ?)
            """,
            (descriptor_id, boundary_id, descriptor_path, value_type, json.dumps(value, sort_keys=True), descriptor_status, now, now),
        )
        conn.execute(
            """
            INSERT INTO analysis_graph_boundary_descriptor_evidence(descriptor_id, evidence_id)
            VALUES (?, ?)
            """,
            (descriptor_id, evidence_id),
        )


def _required(
    source_id: str,
    descriptor_path: str,
    value: Any,
    *,
    value_type: str = "STRING",
    boundary_id: str = "required",
) -> LocalBoundaryFact:
    evidence = FlowGraphEvidence(
        source_id=source_id,
        graph_id=f"{source_id}:graph",
        graph_revision=_current_revision(source_id),
        evidence_id=_evidence_id(source_id),
        node_id=_owner_node_id(source_id),
        edge_id=None,
        relative_path=f"src/{source_id}.py",
        line_start=1,
        line_end=1,
        text=f"evidence {source_id}",
    )
    return LocalBoundaryFact(
        boundary_id=boundary_id,
        stable_key=boundary_id,
        source_id=source_id,
        graph_id=f"{source_id}:graph",
        graph_revision=_current_revision(source_id),
        owner_node_id=_owner_node_id(source_id),
        role="REQUIRED",
        status="TRUSTED",
        provenance="STATIC",
        confidence=1.0,
        flow_domain="CODE",
        descriptors=(
            LocalBoundaryDescriptor(
                descriptor_id=f"{boundary_id}:descriptor",
                path=descriptor_path,
                value_type=value_type,
                value=value,
                origin="STATIC",
                confidence=1.0,
                evidence=(evidence,),
            ),
        ),
        evidence=(evidence,),
    )


def _candidate_ids(result) -> list[str]:
    return sorted(fact.boundary_id for values in result.candidates_by_required_identity.values() for fact in values)


def _single_candidate(result) -> LocalBoundaryFact:
    candidates = [fact for values in result.candidates_by_required_identity.values() for fact in values]
    assert len(candidates) == 1
    return candidates[0]


def _result_signature(result) -> tuple[tuple[str, tuple[str, ...]], ...]:
    return tuple(
        sorted(
            (
                f"{identity.source_id}:{identity.boundary_key}",
                tuple(fact.boundary_id for fact in facts),
            )
            for identity, facts in result.candidates_by_required_identity.items()
        )
    )


def _owner_node_id(source_id: str) -> str:
    return f"{source_id}:owner"


def _current_hash(source_id: str) -> str:
    return f"{source_id}:hash:current"


def _current_revision(source_id: str) -> str:
    return f"{source_id}:revision:current"


def _evidence_id(source_id: str) -> str:
    return f"{source_id}:evidence"
