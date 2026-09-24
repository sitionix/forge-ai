CREATE TABLE mcp_connections (
    id UUID PRIMARY KEY,
    installation_id UUID NOT NULL REFERENCES forge_instance_identity(instance_id),
    display_name VARCHAR(255) NOT NULL CHECK (btrim(display_name) <> ''),
    endpoint TEXT NOT NULL,
    auth_type VARCHAR(20) NOT NULL CHECK (auth_type IN ('NONE','BEARER','SECRET_HEADERS')),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    project_scope VARCHAR(10) NOT NULL CHECK (project_scope IN ('ALL','SELECTED')),
    credential_configured BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    checked_at TIMESTAMPTZ,
    safe_diagnostic VARCHAR(500),
    CHECK (auth_type <> 'NONE' OR credential_configured = FALSE)
);
CREATE INDEX idx_mcp_connections_installation ON mcp_connections(installation_id,display_name,id);

CREATE TABLE mcp_connection_projects (
    connection_id UUID NOT NULL REFERENCES mcp_connections(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES agent_projects(id) ON DELETE CASCADE,
    PRIMARY KEY (connection_id,project_id)
);

CREATE TABLE mcp_allowed_tools (
    connection_id UUID NOT NULL REFERENCES mcp_connections(id) ON DELETE CASCADE,
    tool_name TEXT NOT NULL CHECK (btrim(tool_name) <> ''),
    schema_fingerprint TEXT NOT NULL CHECK (btrim(schema_fingerprint) <> ''),
    PRIMARY KEY (connection_id,tool_name,schema_fingerprint)
);

CREATE TABLE mcp_connection_credentials (
    connection_id UUID PRIMARY KEY REFERENCES mcp_connections(id) ON DELETE CASCADE,
    key_id TEXT NOT NULL CHECK (btrim(key_id) <> ''),
    ciphertext BYTEA NOT NULL CHECK (octet_length(ciphertext) > 0)
);
