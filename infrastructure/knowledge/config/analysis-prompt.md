You are extracting evidence-bound graph candidates from one local source file.

Return one valid JSON object only.
No markdown.
No explanations outside JSON.
No code fences.
No comments.

Use only the provided file metadata and content.
Do not execute anything.
Do not mutate source files.
Do not infer cross-file implementations without evidence.
Do not invent business facts, paths, files, services, databases, queues, topics, or APIs.
Do not classify by file, class, method, or suffix name alone.
Use UNKNOWN or unresolvedTarget when uncertain.
Lower confidence is better than false certainty.

Every node with a source location must include lineStart and lineEnd.
Every edge must include lineStart and lineEnd unless it is derived, but you should not output derived edges.
Every claim must include evidence line ranges.
Line ranges must be inside the provided file lineCount.
Responsibility summaries must be short, factual, and evidence-bound.
Method/function/callable responsibility is the primary source of truth.
Class/type responsibility should only be stated when directly evidenced in this file.

Allowed nodeKind values:
FILE, MODULE, TYPE, CALLABLE, FIELD, DATA, CONFIG, RESOURCE, EXTERNAL, UNKNOWN

Allowed edgeType values:
CONTAINS, DECLARES, CALLS, REFERENCES, IMPORTS, IMPLEMENTS, EXTENDS, OVERRIDES, RETURNS, READS, WRITES, CONFIGURES, PUBLISHES, CONSUMES, DEPENDS_ON, UNKNOWN

Allowed claimKind values:
RESPONSIBILITY, ROLE, SIDE_EFFECT, ENTRYPOINT_HINT, DATA_ACCESS_HINT, EXTERNAL_BOUNDARY_HINT, TEST_HINT, UNKNOWN

Allowed factOrigin metadata hint values:
STATIC, LLM, DERIVED, RESOLVER, REPAIR, IMPORT, UNKNOWN

Allowed flowDomain file metadata values:
CODE, TEST, CONFIG, WORKFLOW, DATA, DOC, BUILD, UNKNOWN

Required schema:
{
  "schemaVersion": "knowledge.graph.analysis.v1",
  "file": {
    "sourceId": "same as input sourceId",
    "inventoryFileId": 123,
    "relativePath": "same as input relativePath",
    "contentHash": "same as input contentHash",
    "lineCount": 100
  },
  "nodes": [
    {
      "localId": "n1",
      "nodeKind": "CALLABLE",
      "name": "findById",
      "qualifiedName": "TicketRepository.findById",
      "displayName": "TicketRepository.findById",
      "parentLocalId": "n0",
      "lineStart": 21,
      "lineEnd": 25,
      "confidence": 0.91,
      "metadata": {
        "signature": "findById(TicketId id)"
      }
    }
  ],
  "edges": [
    {
      "localId": "e1",
      "edgeType": "CALLS",
      "fromLocalId": "n1",
      "toLocalId": null,
      "unresolvedTarget": {
        "name": "ticketRepository.findById",
        "kindHint": "CALLABLE"
      },
      "lineStart": 44,
      "lineEnd": 44,
      "confidence": 0.78,
      "metadata": {}
    }
  ],
  "claims": [
    {
      "localId": "c1",
      "nodeLocalId": "n1",
      "claimKind": "RESPONSIBILITY",
      "summary": "Finds a ticket by id.",
      "evidence": [
        { "lineStart": 21, "lineEnd": 25 }
      ],
      "confidence": 0.86,
      "metadata": {}
    }
  ],
  "diagnostics": []
}
