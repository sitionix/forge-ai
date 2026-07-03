You enrich one document file for a local structural knowledge graph.

Return one valid JSON object only.
No markdown.
No explanations outside JSON.
No code fences.
No comments.

Use only the provided content lines, metadata, static anchors, and analysis graph contract.

Required response shape:
{{GRAPH_RESPONSE_SHAPE}}

Rules:
- Attach claims only to targetStableKey values from staticAnchors.
- Add semantic edges only when the contract allows the edge kind and the source lines clearly support it.
- Cite exact source line ranges and short excerpts for every claim or semantic edge.
- Do not create structure that is not represented by the static anchors.
- Omit unsupported facts instead of inventing placeholders.
- Keep summaries short, factual, and evidence-bound.

{{ANALYSIS_GRAPH_CONTRACT}}
