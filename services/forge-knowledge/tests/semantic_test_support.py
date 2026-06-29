from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.overview_projection import refresh_overview_for_sources


def seed_semantic_graph(
    db_path: Path,
    *,
    source_id: str = "semantic-source",
    snapshot_suffix: str = "one",
    nodes: Optional[List[Dict[str, Any]]] = None,
    edges: Optional[List[Dict[str, Any]]] = None,
    claims: Optional[List[Dict[str, Any]]] = None,
    evidence_ids: Optional[List[str]] = None,
    refresh_overview: bool = False,
) -> str:
    InventoryStore(db_path).init()
    AnalysisStore(db_path).init()
    now = datetime.now(timezone.utc).isoformat()
    snapshot_id = f"semantic:{snapshot_suffix}:{source_id}"
    job_id = f"semantic-job:{snapshot_suffix}:{source_id}"
    file_id = 70000 + sum((index + 1) * ord(char) for index, char in enumerate(f"{source_id}:{snapshot_suffix}"))
    node_rows = nodes or [
        {
            "id": "node-query",
            "kind": "CALLABLE",
            "name": "JarvisQueryService.query",
            "qualified": "jarvis.JarvisQueryService.query",
            "path": "src/jarvis/query_service.py",
            "line_start": 10,
            "line_end": 42,
        }
    ]
    evidence_rows = evidence_ids or ["ev-node-query"]
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
        conn.execute(
            """
            INSERT OR REPLACE INTO files(id, source_id, source_path, absolute_path, relative_path, extension, language, flow_domain, size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at)
            VALUES (?, ?, '.', '.', ?, '.py', 'python', 'CODE', 100, ?, ?, 100, 'utf-8:replace', ?)
            """,
            (file_id, source_id, "src/semantic_fixture.py", f"hash-{source_id}-{snapshot_suffix}", now, now),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_files(file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status, analyzed_at, symbol_count, relation_count, diagnostics_json, engine_version, flow_domain)
            VALUES (?, ?, ?, ?, 'semantic-fixture', '1', 'ANALYZED', ?, ?, ?, '[]', 'GRAPH_V1', 'CODE')
            """,
            (file_id, source_id, "src/semantic_fixture.py", f"hash-{source_id}-{snapshot_suffix}", now, len(node_rows), len(edges or [])),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO graph_snapshots(snapshot_id, source_id, job_id, state, created_at, published_at, manifest_json, content_identity)
            VALUES (?, ?, ?, 'PUBLISHED', ?, ?, '{}', ?)
            """,
            (snapshot_id, source_id, job_id, now, now, f"{source_id}:semantic-test:{snapshot_suffix}"),
        )
        for index, node in enumerate(node_rows, start=1):
            relative_path = node.get("path") or "src/semantic_fixture.py"
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_nodes(
                    id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, stable_key, node_kind, language, name,
                    qualified_name, display_name, parent_node_id, line_start, line_end, confidence, status, metadata_json,
                    created_at, fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'fixture', ?, ?, ?, ?, ?, ?, 0.96, ?, '{}', ?, 'STATIC', 'CODE')
                """,
                (
                    node["id"],
                    snapshot_id,
                    job_id,
                    source_id,
                    file_id,
                    file_id,
                    f"{source_id}|{relative_path}|{node['kind']}|{node['name']}",
                    node["kind"],
                    node["name"],
                    node.get("qualified") or node["name"],
                    node.get("display") or node["name"],
                    node.get("parent"),
                    node.get("line_start", index),
                    node.get("line_end", index),
                    node.get("status", "TRUSTED"),
                    now,
                ),
            )
        for index, evidence_id in enumerate(evidence_rows, start=1):
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_evidence(
                    id, snapshot_id, job_id, source_id, analysis_file_id, content_hash, evidence_kind, excerpt_hash,
                    line_start, line_end, metadata_json, created_at, fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, ?, 'CLAIM', ?, ?, ?, '{}', ?, 'STATIC', 'CODE')
                """,
                (evidence_id, snapshot_id, job_id, source_id, file_id, f"hash-{evidence_id}", f"excerpt-{evidence_id}", index, index, now),
            )
        for claim in claims or []:
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_claims(
                    id, snapshot_id, job_id, source_id, node_id, claim_kind, summary, confidence,
                    status, evidence_ids_json, metadata_json, rejection_reason, created_at, fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 0.9, ?, ?, '{}', ?, ?, 'STATIC', 'CODE')
                """,
                (
                    claim["id"],
                    snapshot_id,
                    job_id,
                    source_id,
                    claim["node_id"],
                    claim.get("kind", "RESPONSIBILITY"),
                    claim.get("summary", ""),
                    claim.get("status", "TRUSTED"),
                    json.dumps(claim.get("evidence_ids", [])),
                    claim.get("rejection_reason"),
                    now,
                ),
            )
        for index, edge in enumerate(edges or [], start=1):
            evidence_id = edge.get("evidence_id") or f"ev-{edge['id']}"
            if evidence_id not in evidence_rows:
                conn.execute(
                    """
                    INSERT OR REPLACE INTO analysis_graph_evidence(
                        id, snapshot_id, job_id, source_id, analysis_file_id, content_hash, evidence_kind, excerpt_hash,
                        line_start, line_end, metadata_json, created_at, fact_origin, flow_domain
                    )
                    VALUES (?, ?, ?, ?, ?, ?, 'EDGE', ?, ?, ?, '{}', ?, 'STATIC', 'CODE')
                    """,
                    (evidence_id, snapshot_id, job_id, source_id, file_id, f"hash-{evidence_id}", f"excerpt-{evidence_id}", index, index, now),
                )
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_edges(
                    id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, from_node_id, to_node_id, edge_type,
                    resolution_status, confidence, evidence_id, unresolved_target_json, metadata_json, status,
                    created_at, fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0.91, ?, ?, '{}', ?, ?, 'STATIC', 'CODE')
                """,
                (
                    edge["id"],
                    snapshot_id,
                    job_id,
                    source_id,
                    file_id,
                    file_id,
                    edge["from"],
                    edge.get("to"),
                    edge.get("type", "CALLS"),
                    edge.get("resolution", "RESOLVED" if edge.get("to") else "UNRESOLVED"),
                    evidence_id,
                    json.dumps(edge.get("unresolved")) if edge.get("unresolved") else None,
                    edge.get("status", "TRUSTED"),
                    now,
                ),
            )
        conn.execute(
            """
            INSERT OR REPLACE INTO graph_current_snapshots(source_id, snapshot_id, published_at)
            VALUES (?, ?, ?)
            """,
            (source_id, snapshot_id, now),
        )
        if refresh_overview:
            refresh_overview_for_sources(conn, [source_id])
    return snapshot_id
