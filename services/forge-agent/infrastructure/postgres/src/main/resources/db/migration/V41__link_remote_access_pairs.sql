CREATE TABLE remote_access_pairs (
    id UUID PRIMARY KEY,
    local_forward_role VARCHAR(8) NOT NULL CHECK (local_forward_role IN ('ACCESSOR', 'GRANTOR')),
    forward_session_id UUID UNIQUE REFERENCES remote_access_sessions(id),
    reverse_session_id UUID UNIQUE REFERENCES remote_access_sessions(id),
    reverse_invitation_id UUID UNIQUE REFERENCES remote_access_invitations(id),
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT remote_access_pair_role_invitation CHECK (
        (local_forward_role = 'ACCESSOR' AND reverse_invitation_id IS NOT NULL)
        OR (local_forward_role = 'GRANTOR' AND reverse_invitation_id IS NULL)
    ),
    CONSTRAINT remote_access_pair_distinct_sessions CHECK (
        forward_session_id IS NULL OR reverse_session_id IS NULL OR forward_session_id <> reverse_session_id
    )
);
