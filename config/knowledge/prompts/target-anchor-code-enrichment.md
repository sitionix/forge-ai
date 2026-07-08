Code target-anchor enrichment prompt.

Enrich exactly one code anchor for the local structural knowledge graph.
Use only the target-anchor input JSON between the markers.
Focus on classes, methods, fields, method responsibility, calls, side effects, and data access when supported by evidence.
Return one JSON object only. Do not include markdown, code fences, comments, or prose outside JSON.
You are enriching only the current targetAnchor.
Backend attaches every claim and edge to targetAnchor and fills ids, confidence, evidence text, and resolved status.
Do not return schemaVersion, localId, targetRef, fromRef, resolutionStatus, confidence, evidence.text, or diagnostics.
Use only claimKind values from allowedValues.claimKind.
Use edgeOptions as the only source of valid semantic edge choices.
edgeType must be one of edgeOptions[].edgeType.
For resolved edges, toRef must be one of toRefs for the same edgeType; do not choose arbitrary anchorRegistry refs.
If edgeOptions for an edgeType has empty toRefs, do not create a resolved edge for that edgeType.
For unresolved/external edges, use one unresolvedStatus from that edgeOption and unresolvedTarget when supported by evidence.
Do not add semanticEdges[].summary.
If no valid edge can be formed, omit the edge or return semanticEdges: [].
Omit invalid or weakly supported facts instead of guessing.

{{REPAIR_INSTRUCTIONS}}

{{LLM_INPUT_JSON}}

Response shape:
```json
{{TARGET_RESPONSE_SHAPE}}
```
