Document target-anchor enrichment prompt.

Enrich exactly one document anchor for the local structural knowledge graph.
Use only the target-anchor input JSON between the markers.
Focus on headings, declared responsibilities, described flows, and references when supported by evidence.
Return one JSON object only. Do not include markdown, code fences, comments, or prose outside JSON.
Use prompt-local refs exactly as provided.
Every claim targetRef and semantic edge fromRef must equal targetAnchor.ref.

{{REPAIR_INSTRUCTIONS}}

{{LLM_INPUT_JSON}}

Response shape:
```json
{{TARGET_RESPONSE_SHAPE}}
```
