You are analyzing one source file for a local structural knowledge index.

Return one valid JSON object only.
No markdown.
No explanations outside JSON.
No code fences.
No comments.
Invalid JSON causes this file analysis to fail.

Use only the provided file content and metadata.
Do not infer files that are not present.
Do not invent paths.
Do not execute anything.
If uncertain, use UNKNOWN with low confidence.
Evidence must be based on file content.
Naming conventions are weak evidence only.
Prefer behavior and structure over class/file names.
Do not use business-specific assumptions.

Allowed symbol kinds:
FILE, CLASS, INTERFACE, METHOD, FUNCTION, FIELD, CONFIG_ENTRY, CONTRACT_OPERATION, DTO, RECORD, UNKNOWN

Allowed roles:
ENTRYPOINT, HTTP_HANDLER, EVENT_HANDLER, COMMAND_HANDLER, QUERY_HANDLER, USE_CASE, APPLICATION_SERVICE, DOMAIN_MODEL, REPOSITORY, CLIENT, MAPPER, DTO, CONFIGURATION, CONTRACT, TEST, UTILITY, UNKNOWN

Allowed relations:
DECLARES, CONTAINS, CALLS, IMPLEMENTS, EXTENDS, INJECTS, MAPS_TO, USES, READS_FROM, WRITES_TO, PUBLISHES, CONSUMES, CONFIGURES, REFERENCES_CONTRACT, REFERENCES_DTO, RELATED_TO, UNKNOWN

Required compact schema:
{
  "fileSummary": "Short neutral summary",
  "symbols": [
    {
      "localId": "symbol-1",
      "name": "Name",
      "kind": "CLASS",
      "roles": [
        {
          "role": "UNKNOWN",
          "confidence": 0.2,
          "evidence": ["Exact or specific evidence from this file"]
        }
      ],
      "lineStart": 1,
      "lineEnd": 10,
      "metadata": {}
    }
  ],
  "relations": [
    {
      "fromLocalId": "symbol-1",
      "toLocalId": "symbol-2",
      "relation": "RELATED_TO",
      "confidence": 0.5,
      "evidence": ["Exact or specific evidence from this file"],
      "lineStart": 5,
      "lineEnd": 5,
      "metadata": {}
    }
  ],
  "diagnostics": []
}
