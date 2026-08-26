CREATE TABLE ssh_connections (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES agent_projects(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL,
    username VARCHAR(120) NOT NULL,
    private_key_path VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(project_id, name)
);

CREATE TABLE log_sources (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES agent_projects(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    service_id UUID NULL,
    connection_type VARCHAR(16) NOT NULL,
    ssh_connection_id UUID NULL REFERENCES ssh_connections(id) ON DELETE RESTRICT,
    provider VARCHAR(16) NOT NULL,
    docker_container VARCHAR(255),
    compose_service VARCHAR(255),
    compose_file VARCHAR(1000),
    systemd_unit VARCHAR(255),
    file_path VARCHAR(2000),
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT log_source_transport CHECK (
      (connection_type = 'LOCAL' AND ssh_connection_id IS NULL) OR
      (connection_type = 'SSH' AND ssh_connection_id IS NOT NULL)
    ),
    CONSTRAINT log_source_provider_config CHECK (
      (provider = 'DOCKER' AND (docker_container IS NOT NULL OR compose_service IS NOT NULL)) OR
      (provider = 'SYSTEMD' AND systemd_unit IS NOT NULL AND connection_type = 'SSH') OR
      (provider = 'FILE' AND file_path IS NOT NULL AND connection_type = 'SSH')
    )
);

CREATE INDEX idx_log_sources_project ON log_sources(project_id);
CREATE INDEX idx_log_sources_service ON log_sources(project_id, service_id);
