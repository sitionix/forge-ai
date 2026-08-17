CREATE TABLE project_repositories (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    remote_url TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_project_repositories_project
        FOREIGN KEY (project_id) REFERENCES agent_projects(id) ON DELETE CASCADE,
    CONSTRAINT chk_project_repositories_remote_url_not_blank CHECK (btrim(remote_url) <> '')
);

CREATE INDEX idx_project_repositories_project_created_id
    ON project_repositories(project_id, created_at, id);
