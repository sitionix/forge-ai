Target-anchor enrichment prompt template.

Enrich exactly one target anchor for the local structural knowledge graph.
Use only the target-anchor input JSON between the markers.
Return one JSON object only. Do not include markdown, code fences, comments, or prose outside JSON.
Use prompt-local refs exactly as provided.
For this request, every claim targetRef and every semantic edge fromRef must equal targetAnchor.ref.

{{REPAIR_INSTRUCTIONS}}

{{LLM_INPUT_JSON}}

Response shape:
```json
{{TARGET_RESPONSE_SHAPE}}
```
