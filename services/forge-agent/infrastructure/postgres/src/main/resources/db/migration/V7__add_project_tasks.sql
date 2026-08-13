CREATE TABLE project_tasks (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    title VARCHAR(120) NOT NULL,
    input TEXT NOT NULL,
    workflow_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_project_tasks_project
        FOREIGN KEY (project_id) REFERENCES agent_projects(id),
    CONSTRAINT fk_project_tasks_workflow
        FOREIGN KEY (workflow_id) REFERENCES agent_workflows(id),
    CONSTRAINT chk_project_tasks_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT chk_project_tasks_input_not_blank CHECK (btrim(input) <> '')
);

ALTER TABLE workflow_runs
    ADD COLUMN task_id UUID NULL,
    ADD CONSTRAINT fk_workflow_runs_task
        FOREIGN KEY (task_id) REFERENCES project_tasks(id);

CREATE INDEX idx_project_tasks_project_history
    ON project_tasks(project_id, created_at DESC, id DESC);
CREATE INDEX idx_workflow_runs_task_history
    ON workflow_runs(task_id, created_at DESC, id DESC);
