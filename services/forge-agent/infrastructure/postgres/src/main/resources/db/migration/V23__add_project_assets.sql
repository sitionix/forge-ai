CREATE TABLE project_assets (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES agent_projects(id) ON DELETE CASCADE,
  name VARCHAR(120) NOT NULL,
  ssh_connection_id UUID NOT NULL REFERENCES ssh_connections(id) ON DELETE RESTRICT,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  UNIQUE(project_id, name)
);
CREATE INDEX idx_project_assets_project ON project_assets(project_id);

ALTER TABLE log_sources ADD COLUMN asset_id UUID NULL;
ALTER TABLE log_sources DROP CONSTRAINT log_source_transport;
ALTER TABLE log_sources ADD CONSTRAINT log_source_transport CHECK (
  (connection_type = 'LOCAL' AND ssh_connection_id IS NULL AND asset_id IS NULL) OR
  (connection_type = 'SSH' AND ((asset_id IS NULL AND ssh_connection_id IS NOT NULL) OR
    (asset_id IS NOT NULL AND ssh_connection_id IS NULL)))
);
ALTER TABLE log_sources ADD CONSTRAINT fk_log_sources_asset
  FOREIGN KEY(asset_id) REFERENCES project_assets(id) ON DELETE CASCADE;
ALTER TABLE log_sources ADD CONSTRAINT log_source_single_owner
  CHECK (NOT (service_id IS NOT NULL AND asset_id IS NOT NULL));
CREATE INDEX idx_log_sources_asset ON log_sources(project_id, asset_id);
