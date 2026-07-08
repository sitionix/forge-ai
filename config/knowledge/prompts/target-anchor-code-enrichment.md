Code target-anchor enrichment prompt.

Enrich exactly one code anchor for the local structural knowledge graph.
Use only the target-anchor input JSON between the markers.
Focus on classes, methods, fields, method responsibility, calls, side effects, and data access when supported by evidence.
Return one JSON object only. Do not include markdown, code fences, comments, or prose outside JSON.
You are enriching only the current targetAnchor.
Follow llmInput.claimScope for the target kind.
FILE target: describe file-level purpose only; do not summarize individual methods as FILE claims.
TYPE target: describe class/type-level responsibility only; do not duplicate every method scenario.
CALLABLE target: describe only the current callable; every evidence range must be inside that callable.
Return claims only. Do not return graph topology, refs, semanticEdges, or edge facts.
Backend/static analysis creates declarations, imports, calls, field usage, and graph edges.
Backend attaches every claim to targetAnchor and fills ids, confidence, and evidence text.
Do not return schemaVersion, localId, targetRef, fromRef, resolutionStatus, confidence, evidence.text, or diagnostics.
Use only claimKind values from allowedValues.claimKind.
Evidence must be line ranges only.
Use method body evidence for CALLABLE responsibility, side-effect, and data-access claims; do not use another method's lines.
You may mention fields, collaborators, dependencies, or external boundaries in claim summaries when supported by evidence.
If nothing is grounded, return {"claims": []}.
Omit invalid or weakly supported facts instead of guessing.

{{REPAIR_INSTRUCTIONS}}

{{LLM_INPUT_JSON}}

Response shape:
```json
{{TARGET_RESPONSE_SHAPE}}
```
