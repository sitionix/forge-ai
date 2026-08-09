CREATE TABLE agent_projects (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    normalized_name VARCHAR(120) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_agent_projects_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT chk_agent_projects_normalized_name_not_blank CHECK (btrim(normalized_name) <> '')
);

CREATE TABLE agent_definitions (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    normalized_name VARCHAR(120) NOT NULL,
    instructions TEXT NOT NULL,
    output_schema JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_agent_definitions_project
        FOREIGN KEY (project_id) REFERENCES agent_projects(id),
    CONSTRAINT uk_agent_definitions_project_normalized_name
        UNIQUE (project_id, normalized_name),
    CONSTRAINT chk_agent_definitions_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT chk_agent_definitions_normalized_name_not_blank CHECK (btrim(normalized_name) <> ''),
    CONSTRAINT chk_agent_definitions_instructions_not_blank CHECK (btrim(instructions) <> ''),
    CONSTRAINT chk_agent_definitions_output_schema_object CHECK (jsonb_typeof(output_schema) = 'object')
);

CREATE INDEX idx_agent_projects_normalized_name_id
    ON agent_projects(normalized_name, id);
CREATE INDEX idx_agent_definitions_project_normalized_name_id
    ON agent_definitions(project_id, normalized_name, id);
