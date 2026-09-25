ALTER TABLE mcp_connections
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0);

CREATE TABLE mcp_discovered_tools (
    connection_id UUID NOT NULL REFERENCES mcp_connections(id) ON DELETE CASCADE,
    tool_name VARCHAR(255) NOT NULL CHECK (btrim(tool_name) <> ''),
    description VARCHAR(1024) NOT NULL,
    schema_fingerprint VARCHAR(71) NOT NULL CHECK (schema_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    PRIMARY KEY (connection_id, tool_name)
);
