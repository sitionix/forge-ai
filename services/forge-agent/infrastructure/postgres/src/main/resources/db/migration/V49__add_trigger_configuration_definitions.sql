ALTER TABLE mcp_trigger_configurations ADD COLUMN definition JSONB;
ALTER TABLE mcp_trigger_configurations ADD CONSTRAINT chk_trigger_configuration_definition
    CHECK (definition IS NULL OR jsonb_typeof(definition) = 'object');
