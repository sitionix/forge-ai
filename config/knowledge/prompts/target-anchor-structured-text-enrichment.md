Structured-text target-anchor enrichment prompt.

Enrich exactly one structured-text anchor for the local structural knowledge graph.
Use only the target-anchor input JSON between the markers.
Focus on config keys, workflow/build/deploy steps, declared dependencies, and resources when supported by evidence.
Return one JSON object only. Do not include markdown, code fences, comments, or prose outside JSON.
You are enriching only the current targetAnchor.
Return claims only. Do not return graph topology, refs, semanticEdges, or edge facts.
Backend/static analysis creates declarations, imports, calls, field usage, and graph edges.
Backend attaches every claim to targetAnchor and fills ids, confidence, and evidence text.
Do not return schemaVersion, localId, targetRef, fromRef, resolutionStatus, confidence, evidence.text, or diagnostics.
Use only claimKind values from allowedValues.claimKind.
Evidence must be line ranges only.
You may mention keys, resources, collaborators, dependencies, or external boundaries in claim summaries when supported by evidence.
If nothing is grounded, return {"claims": []}.
Omit invalid or weakly supported facts instead of guessing.

{{REPAIR_INSTRUCTIONS}}

{{LLM_INPUT_JSON}}

Response shape:
```json
{{TARGET_RESPONSE_SHAPE}}
```
