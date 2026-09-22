CREATE TABLE forge_instance_identity (
    singleton BOOLEAN PRIMARY KEY CHECK (singleton),
    instance_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE remote_access_sessions (
    id UUID PRIMARY KEY,
    invitation_id UUID NOT NULL UNIQUE,
    local_role VARCHAR(16) NOT NULL CHECK (local_role IN ('GRANTOR', 'ACCESSOR')),
    grantor_instance_id UUID NOT NULL,
    accessor_instance_id UUID NOT NULL,
    peer_display_name VARCHAR(255) NOT NULL CHECK (btrim(peer_display_name) <> ''),
    ssh_host VARCHAR(255) NOT NULL CHECK (btrim(ssh_host) <> ''),
    ssh_port INTEGER NOT NULL CHECK (ssh_port BETWEEN 1 AND 65535),
    ssh_username VARCHAR(120) NOT NULL CHECK (btrim(ssh_username) <> ''),
    pinned_host_public_key TEXT NOT NULL CHECK (btrim(pinned_host_public_key) <> ''),
    session_public_key TEXT NOT NULL CHECK (btrim(session_public_key) <> ''),
    session_fingerprint VARCHAR(255) NOT NULL CHECK (btrim(session_fingerprint) <> ''),
    local_private_key_reference UUID,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PROVISIONING','ACTIVE','REVOKING','REVOKED')),
    created_at TIMESTAMPTZ NOT NULL,
    provisioning_expires_at TIMESTAMPTZ NOT NULL CHECK (provisioning_expires_at > created_at),
    activated_at TIMESTAMPTZ,
    revoke_requested_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    connectivity VARCHAR(16) NOT NULL CHECK (connectivity IN ('UNKNOWN','REACHABLE','UNREACHABLE')),
    last_seen_at TIMESTAMPTZ,
    last_checked_at TIMESTAMPTZ,
    failure_code VARCHAR(120),
    failure_message VARCHAR(500),
    version BIGINT NOT NULL CHECK (version >= 0),
    CHECK (grantor_instance_id <> accessor_instance_id),
    CHECK ((local_role='GRANTOR' AND local_private_key_reference IS NULL)
        OR (local_role='ACCESSOR' AND (local_private_key_reference IS NOT NULL OR status='REVOKED'))),
    CHECK (activated_at IS NULL OR (activated_at >= created_at AND activated_at < provisioning_expires_at)),
    CHECK (revoke_requested_at IS NULL OR (revoke_requested_at >= created_at AND (activated_at IS NULL OR revoke_requested_at >= activated_at))),
    CHECK (revoked_at IS NULL OR (revoke_requested_at IS NOT NULL AND revoked_at >= revoke_requested_at)),
    CHECK ((status='PROVISIONING' AND activated_at IS NULL AND revoke_requested_at IS NULL AND revoked_at IS NULL)
        OR (status='ACTIVE' AND activated_at IS NOT NULL AND revoke_requested_at IS NULL AND revoked_at IS NULL)
        OR (status='REVOKING' AND revoke_requested_at IS NOT NULL AND revoked_at IS NULL)
        OR (status='REVOKED' AND revoke_requested_at IS NOT NULL AND revoked_at IS NOT NULL))
);

-- ACCESSOR stores a remote invitation id, so sessions deliberately have no FK
-- to local invitation rows. GRANTOR's redemption link is deferred until commit.
CREATE TABLE remote_access_invitations (
    id UUID PRIMARY KEY,
    grantor_instance_id UUID NOT NULL,
    ssh_host VARCHAR(255) NOT NULL CHECK (btrim(ssh_host) <> ''),
    ssh_port INTEGER NOT NULL CHECK (ssh_port BETWEEN 1 AND 65535),
    ssh_username VARCHAR(120) NOT NULL CHECK (btrim(ssh_username) <> ''),
    pairing_public_key TEXT NOT NULL CHECK (btrim(pairing_public_key) <> ''),
    pairing_fingerprint VARCHAR(255) NOT NULL CHECK (btrim(pairing_fingerprint) <> ''),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    consumed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    redeemed_session_id UUID UNIQUE REFERENCES remote_access_sessions(id) DEFERRABLE INITIALLY DEFERRED,
    CHECK ((consumed_at IS NULL) = (redeemed_session_id IS NULL)),
    CHECK (consumed_at IS NULL OR (consumed_at >= created_at AND consumed_at < expires_at)),
    CHECK (cancelled_at IS NULL OR (cancelled_at >= created_at AND consumed_at IS NULL))
);
