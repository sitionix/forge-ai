You enrich one source file for a local structural knowledge graph.

Return one valid JSON object only.
No markdown.
No explanations outside JSON.
No code fences.
No comments.
Invalid JSON causes this file enrichment to fail.

Use only the provided file content, metadata, and staticAnchors.
The staticAnchors were produced by a deterministic parser and are the source of truth for FILE, TYPE, CALLABLE, FIELD, IMPORT, and CALLSITE structure.
Do not discover classes, methods, fields, or callsites.
Do not change anchor line ranges.
Do not create trusted structure.
Use targetStableKey/fromStableKey/toStableKey values from staticAnchors exactly.
If a target anchor is missing, omit the claim or edge.

Allowed claimKind values:
RESPONSIBILITY, ROLE, CONTRACT, DIAGNOSTIC, ENTRYPOINT_HINT, SIDE_EFFECT, DATA_ACCESS_HINT, EXTERNAL_BOUNDARY_HINT, CONFIG_REFERENCE, UNKNOWN

Allowed semantic edgeType values:
READS, WRITES, PUBLISHES, CONSUMES, CONFIGURES, REFERENCES, USES, DEPENDS_ON, RELATED_TO, UNKNOWN

Required response schema:
{
  "schemaVersion": "knowledge.graph.enrichment.v1",
  "file": {
    "sourceId": "...",
    "inventoryFileId": 123,
    "relativePath": "...",
    "contentHash": "...",
    "lineCount": 120
  },
  "claims": [
    {
      "localId": "claim1",
      "targetStableKey": "copy-a-static-anchor-targetStableKey-here",
      "claimKind": "RESPONSIBILITY",
      "summary": "Handles validation exceptions and returns the related HTTP response.",
      "evidence": [
        {"lineStart": 56, "lineEnd": 64, "text": "short evidence excerpt", "metadata": {}}
      ],
      "confidence": 0.86,
      "metadata": {}
    }
  ],
  "semanticEdges": [
    {
      "localId": "semantic1",
      "fromStableKey": "copy-a-static-anchor-targetStableKey-here",
      "toStableKey": null,
      "edgeType": "REFERENCES",
      "unresolvedTarget": {"name": "externalName", "kindHint": "EXTERNAL"},
      "evidence": [
        {"lineStart": 42, "lineEnd": 42, "text": "short evidence excerpt", "metadata": {}}
      ],
      "confidence": 0.72,
      "metadata": {}
    }
  ],
  "diagnostics": []
}

Responsibility rules:
- Create RESPONSIBILITY claims for meaningful FILE, TYPE, and CALLABLE anchors only when evidence supports them.
- A FILE responsibility describes what the file contains and belongs only to the FILE anchor.
- A TYPE responsibility describes the class/interface/enum/record itself and belongs only to that TYPE anchor.
- A CALLABLE responsibility describes the method/function itself and belongs only to that CALLABLE anchor.
- Do not copy FILE or TYPE summary text into CALLABLE responsibility claims.
- Method/callable evidence must overlap the callable anchor line range.
- Type evidence must overlap the type anchor line range or its declaration annotations.
- If evidence does not exist inside the method range, omit the method claim.
- If unsure, omit rather than invent.
- Summaries must be short, factual, and evidence-bound.

Semantic hint rules:
- Add ROLE, SIDE_EFFECT, DATA_ACCESS_HINT, EXTERNAL_BOUNDARY_HINT, or semanticEdges only when the code evidence is clear.
- Prefer REFERENCES/USES for uncertain external or data interactions.
- Do not infer business/domain behavior from names alone.
- Do not classify controller/service/repository roles by suffix alone.
- Annotation evidence is allowed.
- Use UNKNOWN only where allowed and with low confidence.
