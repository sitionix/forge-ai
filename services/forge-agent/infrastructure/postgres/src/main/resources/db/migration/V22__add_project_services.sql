CREATE TABLE project_services (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES agent_projects(id) ON DELETE CASCADE,
  name VARCHAR(120) NOT NULL,
  repository_id UUID NULL REFERENCES project_repositories(id) ON DELETE RESTRICT,
  connection_type VARCHAR(16) NOT NULL,
  ssh_connection_id UUID NULL REFERENCES ssh_connections(id) ON DELETE RESTRICT,
  provider VARCHAR(16) NOT NULL,
  docker_container VARCHAR(255),
  systemd_unit VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  UNIQUE(project_id, name),
  CONSTRAINT service_transport CHECK ((connection_type='LOCAL' AND ssh_connection_id IS NULL) OR (connection_type='SSH' AND ssh_connection_id IS NOT NULL)),
  CONSTRAINT service_target CHECK ((provider='DOCKER' AND docker_container IS NOT NULL AND systemd_unit IS NULL) OR (provider='SYSTEMD' AND systemd_unit IS NOT NULL AND docker_container IS NULL))
);
CREATE INDEX idx_project_services_project ON project_services(project_id);
ALTER TABLE log_sources ADD CONSTRAINT fk_log_sources_service FOREIGN KEY(service_id) REFERENCES project_services(id) ON DELETE SET NULL;
