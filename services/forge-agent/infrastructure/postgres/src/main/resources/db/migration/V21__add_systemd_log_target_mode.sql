ALTER TABLE log_sources ADD COLUMN systemd_mode VARCHAR(24);

UPDATE log_sources SET systemd_mode = 'UNIT' WHERE provider = 'SYSTEMD';

ALTER TABLE log_sources DROP CONSTRAINT log_source_provider_config;
ALTER TABLE log_sources ADD CONSTRAINT log_source_provider_config CHECK (
  (provider = 'DOCKER' AND (docker_container IS NOT NULL OR compose_service IS NOT NULL)) OR
  (provider = 'SYSTEMD' AND connection_type = 'SSH' AND (
    (systemd_mode = 'UNIT' AND systemd_unit IS NOT NULL) OR
    (systemd_mode = 'FULL_JOURNAL' AND systemd_unit IS NULL)
  )) OR
  (provider = 'FILE' AND file_path IS NOT NULL AND connection_type = 'SSH')
);
