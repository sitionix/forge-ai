ALTER TABLE log_sources ADD COLUMN owner_type VARCHAR(24);

UPDATE log_sources SET owner_type = 'LEGACY_SERVICE' WHERE service_id IS NOT NULL;
UPDATE log_sources SET owner_type = 'ASSET' WHERE owner_type IS NULL AND asset_id IS NOT NULL;
UPDATE log_sources SET owner_type = 'CUSTOM' WHERE owner_type IS NULL;

ALTER TABLE log_sources ALTER COLUMN owner_type SET NOT NULL;

ALTER TABLE log_sources ADD CONSTRAINT log_source_owner_type_valid
  CHECK (owner_type IN ('CUSTOM', 'ASSET', 'LEGACY_SERVICE'));

ALTER TABLE log_sources ADD CONSTRAINT log_source_owner_consistency CHECK (
  (owner_type = 'CUSTOM' AND service_id IS NULL AND asset_id IS NULL) OR
  (owner_type = 'ASSET' AND service_id IS NULL AND asset_id IS NOT NULL) OR
  (owner_type = 'LEGACY_SERVICE' AND asset_id IS NULL)
);
