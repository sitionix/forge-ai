# MCP tool schema compatibility

Forge uses the same Java MCP SDK version for remote discovery, invocation and the native agent gateway. The version is managed once in `services/forge-agent/pom.xml`.

SDK 2.0.1 represents tool input schemas as JSON objects instead of the older, restricted `JsonSchema` record. Forge preserves all schema keywords and provider extensions, including object-valued `additionalProperties`, `$ref`, `$defs`, composition keywords and `unevaluatedProperties`. It does not replace constraints with permissive booleans, resolve external references, or discard unknown schema keywords. Input schemas must still have the MCP-required root `type: object`.

Approval fingerprints hash the complete schema with recursively ordered object keys. A changed constraint or extension invalidates approval before dispatch; different object key ordering does not. Existing approvals created from the old restricted representation may require Test connection and explicit tool reapproval after this update. Forge does not silently migrate these approvals or grant new permissions.

The shared protocol mapper decodes decimal numbers with arbitrary precision. Distinct decimal constraints cannot collapse into one rounded double or approval fingerprint; large integers and explicit null values are retained. Gateway argument conversion uses the same mapper.

Forge relays provider schemas without evaluating them locally. Gateway input validation and remote automatic output-schema caching are explicitly disabled; an opaque gateway schema adapter also avoids build-time conformance assumptions and local output-schema interpretation. The upstream MCP server owns argument/result schema validation. This preserves provider dialects and external `$ref` values without local reference resolution; endpoint, approval and admission checks still run before dispatch.

Malformed protocol JSON or incompatible schema shapes from an HTTP-successful server produce `MCP_INVALID_RESPONSE`, rather than the misleading `MCP_UNAVAILABLE`. Authentication, permissions, endpoint policy, pagination and response size limits remain enforced.

Regression coverage exercises JSON and SSE discovery, complete schema serialization for native agents, tool invocation, schema-change refusal and malformed responses. Notion's object-valued `additionalProperties: {}` is covered without server-specific logic.
