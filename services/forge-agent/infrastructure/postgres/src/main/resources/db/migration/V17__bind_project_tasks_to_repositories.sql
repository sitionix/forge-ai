CREATE TABLE project_task_repositories (
    task_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    repository_ordinal INTEGER NOT NULL,
    CONSTRAINT pk_project_task_repositories PRIMARY KEY (task_id, repository_ordinal),
    CONSTRAINT uq_project_task_repositories_task_repository UNIQUE (task_id, repository_id),
    CONSTRAINT fk_project_task_repositories_task
        FOREIGN KEY (task_id) REFERENCES project_tasks(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_task_repositories_repository
        FOREIGN KEY (repository_id) REFERENCES project_repositories(id) ON DELETE CASCADE
);
