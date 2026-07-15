from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.overview_projection import refresh_overview_for_sources
from knowledge_service.semantic_index import SemanticIndexStore


def seed_semantic_graph(
    db_path: Path,
    *,
    source_id: str = "semantic-source",
    graph_suffix: str = "one",
    nodes: Optional[List[Dict[str, Any]]] = None,
    edges: Optional[List[Dict[str, Any]]] = None,
    claims: Optional[List[Dict[str, Any]]] = None,
    evidence_ids: Optional[List[str]] = None,
    refresh_overview: bool = False,
) -> str:
    InventoryStore(db_path).init()
    AnalysisStore(db_path).init()
    now = datetime.now(timezone.utc).isoformat()
    job_id = f"semantic-job:{graph_suffix}:{source_id}"
    default_node_id = "node-query" if source_id == "semantic-source" else f"{source_id}:node-query"
    node_rows = nodes or [
        {
            "id": default_node_id,
            "nodeKind": "CALLABLE",
            "name": "JarvisQueryService.query",
            "qualified": "jarvis.JarvisQueryService.query",
            "path": "src/jarvis/query_service.py",
            "line_start": 10,
            "line_end": 42,
        }
    ]
    evidence_rows = evidence_ids or ["ev-node-query"]
    source_paths = sorted({str(node.get("path") or "src/semantic_fixture.py") for node in node_rows})
    path_to_file: dict[str, tuple[int, str]] = {}
    for index, relative_path in enumerate(source_paths, start=1):
        identity = f"{source_id}:{graph_suffix}:{relative_path}"
        file_id = 70000 + index + sum((pos + 1) * ord(char) for pos, char in enumerate(identity))
        path_to_file[relative_path] = (file_id, f"hash-{source_id}-{graph_suffix}-{index}")

    with sqlite3.connect(db_path) as conn:
        conn.execute("PRAGMA foreign_keys = ON")
        conn.row_factory = sqlite3.Row
        conn.execute(
            """
            INSERT OR REPLACE INTO sources(source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at)
            VALUES (?, ?, 'semantic-test', '.', 1, '[]', '{}', ?)
            """,
            (source_id, source_id.replace("-", " ").title(), now),
        )
        for relative_path, (file_id, content_hash) in path_to_file.items():
            conn.execute(
                """
                INSERT OR REPLACE INTO files(
                    id, source_id, source_path, absolute_path, relative_path, extension, language, flow_domain,
                    size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at
                )
                VALUES (?, ?, '.', '.', ?, '.py', 'python', 'CODE', 100, ?, ?, 100, 'utf-8:replace', ?)
                """,
                (file_id, source_id, relative_path, content_hash, now, now),
            )
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_files(
                    file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status,
                    analyzed_at, diagnostics_json, flow_domain
                )
                VALUES (?, ?, ?, ?, 'semantic-fixture', '1', 'ANALYZED', ?, '[]', 'CODE')
                """,
                (file_id, source_id, relative_path, content_hash, now),
            )

        conn.execute(
            "DELETE FROM analysis_graph_claim_evidence WHERE claim_id IN (SELECT id FROM analysis_graph_claims WHERE source_id = ?)",
            (source_id,),
        )
        conn.execute(
            "DELETE FROM analysis_graph_edge_evidence WHERE edge_id IN (SELECT id FROM analysis_graph_edges WHERE source_id = ?)",
            (source_id,),
        )
        for table in (
            "semantic_documents",
            "analysis_graph_claims",
            "analysis_graph_edges",
            "analysis_graph_evidence",
            "analysis_graph_diagnostics",
            "analysis_graph_nodes",
            "analysis_graph_state",
        ):
            conn.execute(f"DELETE FROM {table} WHERE source_id = ?", (source_id,))

        for index, node in enumerate(node_rows, start=1):
            relative_path = str(node.get("path") or "src/semantic_fixture.py")
            file_id, content_hash = path_to_file[relative_path]
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_nodes(
                    id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                    stable_key, node_kind, language, name, qualified_name, display_name, parent_node_id,
                    line_start, line_end, confidence, status, created_at, updated_at, fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'fixture', ?, ?, ?, ?, ?, ?, 0.96, ?, ?, ?, 'STATIC', 'CODE')
                """,
                (
                    node["id"],
                    job_id,
                    source_id,
                    file_id,
                    file_id,
                    file_id,
                    relative_path,
                    content_hash,
                    f"{source_id}|{relative_path}|{node['nodeKind']}|{node['name']}",
                    node["nodeKind"],
                    node["name"],
                    node.get("qualified") or node["name"],
                    node.get("display") or node["name"],
                    node.get("parent"),
                    node.get("line_start", index),
                    node.get("line_end", index),
                    node.get("status", "TRUSTED"),
                    now,
                    now,
                ),
            )

        first_path = source_paths[0]
        first_file_id, first_hash = path_to_file[first_path]
        for index, evidence_id in enumerate(evidence_rows, start=1):
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_evidence(
                    id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                    line_start, line_end, excerpt, excerpt_hash, evidence_kind, created_at, updated_at, fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CLAIM', ?, ?, 'STATIC', 'CODE')
                """,
                (
                    evidence_id,
                    job_id,
                    source_id,
                    first_file_id,
                    first_file_id,
                    first_file_id,
                    first_path,
                    first_hash,
                    index,
                    index,
                    f"excerpt-{evidence_id}",
                    f"excerpt-{evidence_id}",
                    now,
                    now,
                ),
            )

        for claim in claims or []:
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_claims(
                    id, job_id, source_id, node_id, claim_kind, summary, confidence, status,
                    rejection_reason, created_at, updated_at, entrypoint_kind,
                    entrypoint_http_method, entrypoint_route, entrypoint_topic, entrypoint_schedule,
                    entrypoint_interface_method, entrypoint_execution_kind, fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, ?, 0.9, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STATIC', 'CODE')
                """,
                (
                    claim["id"],
                    job_id,
                    source_id,
                    claim["node_id"],
                    claim.get("claimKind", "RESPONSIBILITY"),
                    claim.get("summary", ""),
                    claim.get("status", "TRUSTED"),
                    claim.get("rejection_reason"),
                    now,
                    now,
                    claim.get("entrypointKind") or claim.get("entrypoint_kind"),
                    claim.get("httpMethod") or claim.get("http_method"),
                    claim.get("route"),
                    claim.get("topic"),
                    claim.get("schedule"),
                    claim.get("interfaceMethod") or claim.get("interface_method"),
                    claim.get("entrypointExecutionKind") or claim.get("entrypoint_execution_kind") or "EXECUTABLE",
                ),
            )
            for evidence_id in claim.get("evidence_ids", []):
                conn.execute(
                    """
                    INSERT OR IGNORE INTO analysis_graph_claim_evidence(claim_id, evidence_id)
                    VALUES (?, ?)
                    """,
                    (claim["id"], evidence_id),
                )

        known_evidence = set(evidence_rows)
        for index, edge in enumerate(edges or [], start=1):
            evidence_id = edge.get("evidence_id") or f"ev-{edge['id']}"
            if evidence_id not in known_evidence:
                conn.execute(
                    """
                    INSERT OR REPLACE INTO analysis_graph_evidence(
                        id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                        line_start, line_end, excerpt, excerpt_hash, evidence_kind, created_at, updated_at, fact_origin, flow_domain
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'EDGE', ?, ?, 'STATIC', 'CODE')
                    """,
                    (
                        evidence_id,
                        job_id,
                        source_id,
                        first_file_id,
                        first_file_id,
                        first_file_id,
                        first_path,
                        first_hash,
                        index,
                        index,
                        f"excerpt-{evidence_id}",
                        f"excerpt-{evidence_id}",
                        now,
                        now,
                    ),
                )
                known_evidence.add(evidence_id)
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_edges(
                    id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                    from_node_id, to_node_id, edge_type, resolution_status, confidence,
                    unresolved_target_json, metadata_json, status, created_at, updated_at, fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0.91, ?, '{}', ?, ?, ?, 'STATIC', 'CODE')
                """,
                (
                    edge["id"],
                    job_id,
                    source_id,
                    first_file_id,
                    first_file_id,
                    first_file_id,
                    first_path,
                    first_hash,
                    edge["fromNodeId"],
                    edge.get("toNodeId"),
                    edge.get("edgeType", "CALLS"),
                    edge.get("resolutionStatus", "RESOLVED" if edge.get("toNodeId") else "UNRESOLVED"),
                    json.dumps(edge.get("unresolved")) if edge.get("unresolved") else None,
                    edge.get("status", "TRUSTED"),
                    now,
                    now,
                ),
            )
            conn.execute(
                """
                INSERT OR IGNORE INTO analysis_graph_edge_evidence(edge_id, evidence_id)
                VALUES (?, ?)
                """,
                (edge["id"], evidence_id),
            )

        graph_id = SemanticIndexStore.compute_graph_revision_conn(conn, source_id)
        counts = conn.execute(
            """
            SELECT
              (SELECT COUNT(*) FROM analysis_graph_nodes WHERE source_id = ?) AS node_count,
              (SELECT COUNT(*) FROM analysis_graph_edges WHERE source_id = ?) AS edge_count,
              (SELECT COUNT(*) FROM analysis_graph_claims WHERE source_id = ?) AS claim_count,
              (SELECT COUNT(*) FROM analysis_graph_evidence WHERE source_id = ?) AS evidence_count
            """,
            (source_id, source_id, source_id, source_id),
        ).fetchone()
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_graph_state(
                source_id, graph_id, content_identity, node_count, edge_count, claim_count, evidence_count, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                source_id,
                graph_id,
                graph_id,
                int(counts["node_count"] or 0),
                int(counts["edge_count"] or 0),
                int(counts["claim_count"] or 0),
                int(counts["evidence_count"] or 0),
                now,
            ),
        )
        if refresh_overview:
            refresh_overview_for_sources(conn, [source_id])
    return graph_id
