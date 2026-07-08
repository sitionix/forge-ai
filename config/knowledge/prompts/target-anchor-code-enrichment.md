Code target-anchor enrichment prompt.

Enrich exactly one code anchor for the local structural knowledge graph.
Use only the target-anchor input JSON between the markers.
Focus on classes, methods, fields, method responsibility, calls, side effects, and data access when supported by evidence.
Return one JSON object only. Do not include markdown, code fences, comments, or prose outside JSON.
Use prompt-local refs exactly as provided.
Every claim targetRef and semantic edge fromRef must equal targetAnchor.ref.

{{REPAIR_INSTRUCTIONS}}

{{LLM_INPUT_JSON}}

Response shape:
```json
{{TARGET_RESPONSE_SHAPE}}
```
