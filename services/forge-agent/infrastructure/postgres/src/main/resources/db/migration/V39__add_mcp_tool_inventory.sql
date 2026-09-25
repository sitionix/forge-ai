CREATE TABLE mcp_tool_inventory (
    connection_id UUID NOT NULL REFERENCES mcp_connections(id) ON DELETE CASCADE,
    tool_name TEXT NOT NULL CHECK (btrim(tool_name) <> ''),
    description TEXT,
    schema_fingerprint TEXT NOT NULL CHECK (btrim(schema_fingerprint) <> ''),
    PRIMARY KEY (connection_id, tool_name)
);
