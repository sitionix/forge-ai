Document target-anchor enrichment prompt.

Enrich exactly one document anchor for the local structural knowledge graph.
Use only the target-anchor input JSON between the markers.
Focus on headings, declared responsibilities, described flows, and references when supported by evidence.
Return one JSON object only. Do not include markdown, code fences, comments, or prose outside JSON.
You are enriching only the current targetAnchor.
Backend attaches every claim and edge to targetAnchor and fills ids, confidence, evidence text, and resolved status.
Do not return schemaVersion, localId, targetRef, fromRef, resolutionStatus, confidence, evidence.text, or diagnostics.
Use only claimKind values from allowedValues.claimKind and edgeType values from allowedValues.edgeType.
If allowedValues.edgeType is empty or no valid edge is grounded, return semanticEdges: [].
For a resolved edge, return toRef. For an unresolved/external edge, omit toRef and return unresolvedStatus plus unresolvedTarget when required.
Omit invalid or weakly supported facts instead of guessing.

{{REPAIR_INSTRUCTIONS}}

{{LLM_INPUT_JSON}}

Response shape:
```json
{{TARGET_RESPONSE_SHAPE}}
```
