Final JSON response shape (return this object shape only):
```json
{{FINAL_RESPONSE_SHAPE}}
```

Field rules:
- schemaVersion: fixed value knowledge.graph.enrichment.v1.
- claims: array of grounded statements attached to existing static anchors.
- semanticEdges: array of grounded relationships between existing static anchors or unresolved/external targets.
- diagnostics: optional array of non-fatal notes. Empty array is valid.
- localId: required unique id inside the response.
- targetStableKey: exact key from staticAnchors.nodes.
- fromStableKey: exact key from staticAnchors nodes or callsites.
- toStableKey: exact key from staticAnchors when resolved; null when not resolved.
- claimKind: one of the allowed claimKind values for this file.
- edgeType: one of the allowed edgeType values for this file.
- resolutionStatus: one of the allowed resolutionStatus values for this file.
- summary: short factual text supported by evidence.
- confidence: number from 0.0 to 1.0.
- evidence: array of evidence objects.
- lineStart: source line number, integer >= 1.
- lineEnd: source line number, integer >= lineStart.
- text: short exact excerpt from source lines.
- unresolvedTarget: target details only when the edge has no resolved toStableKey.
- code: short diagnostic code.
- severity: diagnostic severity for non-fatal notes.
- message: short diagnostic explanation.

Allowed values for this file from analysis-policy.yaml:
```json
{{ALLOWED_VALUES}}
```

Return rules:
- Use exactly the field names shown in the final response shape.
- Return only fields shown in the final response shape.
- Do not rename fields.
- Do not add extra fields.
- claims, semanticEdges, and diagnostics may be empty arrays when no grounded facts are found.
- Omit uncertain facts instead of guessing.
- Use only exact keys from staticAnchors.
- Put resolutionStatus as a first-class semanticEdges field.
- For unresolved or external targets, use toStableKey null, resolutionStatus, and unresolvedTarget.
- The code fences above are prompt formatting only; do not include code fences in the response.
