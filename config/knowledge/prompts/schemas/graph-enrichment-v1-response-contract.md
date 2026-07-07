# 4. Closed graph contract

Allowed values are closed.
Use only values listed in allowedValues.
Do not create synonyms, variants, or more specific enum values.
If no listed value fits, omit the item and add a diagnostic.

Allowed values and endpoint rules from analysis-policy.yaml:
```json
{{ALLOWED_VALUES}}
```

Edge creation checklist:
Create a semantic edge only when all of these are true:
1. fromStableKey is exactly one listed static anchor key.
2. edgeType is exactly one listed allowedValues.edgeType key.
3. The edge is supported by specific source evidence.
4. If toStableKey references an existing node, it is exactly one key listed in staticAnchors.nodes.
5. If allowedEdgeEndpoints has an entry for edgeType, the source nodeKind must be in fromKinds and the resolved target nodeKind must be in toKinds.
6. For unresolved or external targets, toStableKey is null and unresolvedTarget carries the target details.

If any condition is not true, omit the edge and add a diagnostic explaining why it was omitted.
The edgeType is only the relationship kind; do not encode resolution or uncertainty in edgeType.

# 5. Output rules

Use exactly the field names shown in the final response shape.
Return only fields shown in the final response shape.
Do not rename fields.
Do not add extra fields.
claims, semanticEdges, and diagnostics may be empty arrays.
Omit uncertain facts instead of guessing.
Use only exact static anchor keys from staticAnchors.nodes for targetStableKey, fromStableKey, and resolved toStableKey.

Claim checklist:
Create a claim only when claimKind is exactly one listed allowedValues.claimKind key, targetStableKey is exactly one listed static anchor key, and evidence materially supports the summary.
If evidence is broad, generic, header-only, or unrelated to the claim, omit the claim or add a diagnostic.

Unresolved target rule:
When a target cannot be linked to a listed static anchor, represent that only with the unresolved target fields shown in the final response shape.
For unresolved or external targets, use toStableKey null, resolutionStatus, and unresolvedTarget.
Do not represent unresolved, unknown, or external target state by inventing enum values or node references.

Diagnostics explain omitted or skipped facts.
For diagnostics returned by this enrichment response, stage is the fixed value LLM_ENRICHMENT.
Use diagnostics for missing valid anchors, no fitting allowed value, endpoint rule mismatch, weak evidence, low confidence, or skipped unsupported items.

# 6. Final JSON response shape

Return this object shape only:
```json
{{FINAL_RESPONSE_SHAPE}}
```

The code fences above are prompt formatting only; do not include code fences in the response.
