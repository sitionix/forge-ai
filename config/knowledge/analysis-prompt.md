You enrich one source file for a local structural knowledge graph.

Return one valid JSON object only.
No markdown.
No explanations outside JSON.
No code fences.
No comments.

Use only the provided file content, metadata, static anchors, and analysis graph contract.
The generated contract block is authoritative for node kinds, edge kinds, claim kinds, statuses, origins, evidence kinds, resolution statuses, semantic eligibility, and unsupported behavior.

Required response shape:
{
  "schemaVersion": "knowledge.graph.enrichment.v1",
  "claims": [],
  "semanticEdges": [],
  "diagnostics": []
}

Rules:
- Use targetStableKey, fromStableKey, and toStableKey values from staticAnchors exactly.
- Cite exact line ranges and short excerpts for every claim or semantic edge.
- Omit unsupported or weakly supported facts.
- Do not create new structural anchors.
- Keep summaries short, factual, and evidence-bound.

{{ANALYSIS_GRAPH_CONTRACT}}
