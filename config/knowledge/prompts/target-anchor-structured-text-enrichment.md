Structured-text target-anchor enrichment prompt.

Enrich exactly one structured-text anchor for the local structural knowledge graph.
Use only the target-anchor input JSON between the markers.
Focus on config keys, workflow/build/deploy steps, declared dependencies, and resources when supported by evidence.
Return one JSON object only. Do not include markdown, code fences, comments, or prose outside JSON.
You are enriching only the current targetAnchor.
Follow llmInput.claimScope for the target kind.
FILE target: describe file-level purpose only; do not summarize individual sections or steps as FILE claims.
TYPE target: describe type/section-level responsibility only; do not duplicate every child item.
CALLABLE target: describe only the current callable-like target; every evidence range must be inside that target.
Return claims and optional generic boundaries only. Do not return graph topology, refs, semanticEdges, or edge facts.
Backend/static analysis creates declarations, imports, calls, field usage, and graph edges.
Backend attaches every claim to targetAnchor and fills ids, confidence, and evidence text.
Backend attaches every boundary to targetAnchor and fills ids and evidence text.
Boundary role is PROVIDED when the target can be entered, triggered, or consumed from outside its local flow unit.
Boundary role is REQUIRED when the target invokes, emits, requests, or otherwise depends on something outside its local flow unit.
Boundary descriptors are arbitrary grounded path/value pairs. Include only descriptors supported by evidence or omit the boundary.
Do not return schemaVersion, localId, targetRef, fromRef, resolutionStatus, claim confidence, evidence.text, or diagnostics.
Use only claimKind values from allowedValues.claimKind.
Evidence must be line ranges only.
For callable-like targets, do not use evidence lines from another target.
You may mention keys, resources, collaborators, dependencies, or external boundaries in claim summaries when supported by evidence.
If nothing is grounded, return {"claims": [], "boundaries": []}.
Omit invalid or weakly supported facts instead of guessing.

{{REPAIR_INSTRUCTIONS}}

{{LLM_INPUT_JSON}}

Response shape:
```json
{{TARGET_RESPONSE_SHAPE}}
```
