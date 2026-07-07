# 1. Task
Enrich one document file for a local structural knowledge graph.

Return one valid JSON object only.
No markdown, code fences, comments, or explanations outside JSON.

# 2. Inputs
Use only the provided file metadata, contentLines, staticAnchors, and closed graph contract.
The input JSON appears after this contract under "File metadata and content JSON".

# 3. Static anchors
Static anchors are the only valid existing graph nodes.
Every static anchor key is an opaque identifier.
Copy anchor keys exactly as shown in staticAnchors.nodes.
Do not derive, append, shorten, split, normalize, or invent anchor keys from headings, filenames, comments, line numbers, or paths.
If an anchor key is not listed in staticAnchors.nodes, it does not exist.

{{GRAPH_RESPONSE_SHAPE}}

{{ANALYSIS_GRAPH_CONTRACT}}
