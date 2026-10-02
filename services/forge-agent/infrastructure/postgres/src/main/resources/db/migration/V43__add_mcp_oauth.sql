ALTER TABLE mcp_connections DROP CONSTRAINT mcp_connections_auth_type_check;
ALTER TABLE mcp_connections ADD CONSTRAINT mcp_connections_auth_type_check
    CHECK (auth_type IN ('NONE','BEARER','SECRET_HEADERS','OAUTH'));
ALTER TABLE mcp_connections ADD COLUMN oauth_configuration JSONB;
ALTER TABLE mcp_connections ADD COLUMN oauth_authorization_id UUID;
ALTER TABLE mcp_connections ADD CONSTRAINT mcp_connections_oauth_configuration_check
    CHECK ((auth_type = 'OAUTH') = (oauth_configuration IS NOT NULL));
ALTER TABLE mcp_connections ADD CONSTRAINT mcp_connections_oauth_identity_check
    CHECK ((auth_type = 'OAUTH' OR oauth_authorization_id IS NULL)
        AND (auth_type <> 'OAUTH' OR NOT credential_configured OR oauth_authorization_id IS NOT NULL));
ALTER TABLE mcp_connections ADD CONSTRAINT mcp_connections_owner_identity_unique UNIQUE (installation_id,id);

CREATE TABLE mcp_oauth_transactions (
    id UUID PRIMARY KEY,
    installation_id UUID NOT NULL,
    connection_id UUID NOT NULL UNIQUE,
    state_hash VARCHAR(64) NOT NULL CHECK (state_hash ~ '^[0-9a-f]{64}$'),
    browser_hash VARCHAR(64) NOT NULL CHECK (browser_hash ~ '^[0-9a-f]{64}$'),
    connection_snapshot JSONB NOT NULL,
    verifier_key_id TEXT NOT NULL,
    verifier_ciphertext BYTEA NOT NULL CHECK (octet_length(verifier_ciphertext) > 0),
    expires_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ,
    FOREIGN KEY (installation_id,connection_id) REFERENCES mcp_connections(installation_id,id) ON DELETE CASCADE
);
