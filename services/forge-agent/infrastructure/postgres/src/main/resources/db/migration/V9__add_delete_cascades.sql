ALTER TABLE node_runs
    DROP CONSTRAINT fk_node_runs_workflow_run,
    ADD CONSTRAINT fk_node_runs_workflow_run
        FOREIGN KEY (workflow_run_id) REFERENCES workflow_runs(id) ON DELETE CASCADE;

ALTER TABLE workflow_runs
    DROP CONSTRAINT fk_workflow_runs_task,
    ADD CONSTRAINT fk_workflow_runs_task
        FOREIGN KEY (task_id) REFERENCES project_tasks(id) ON DELETE CASCADE;

ALTER TABLE workflow_runs
    DROP CONSTRAINT fk_workflow_runs_project,
    ADD CONSTRAINT fk_workflow_runs_project
        FOREIGN KEY (project_id) REFERENCES agent_projects(id) ON DELETE CASCADE;

ALTER TABLE project_tasks
    DROP CONSTRAINT fk_project_tasks_project,
    ADD CONSTRAINT fk_project_tasks_project
        FOREIGN KEY (project_id) REFERENCES agent_projects(id) ON DELETE CASCADE;

ALTER TABLE workflow_nodes
    DROP CONSTRAINT fk_workflow_nodes_workflow,
    ADD CONSTRAINT fk_workflow_nodes_workflow
        FOREIGN KEY (workflow_id) REFERENCES agent_workflows(id) ON DELETE CASCADE;

ALTER TABLE agent_workflows
    DROP CONSTRAINT fk_agent_workflows_project,
    ADD CONSTRAINT fk_agent_workflows_project
        FOREIGN KEY (project_id) REFERENCES agent_projects(id) ON DELETE CASCADE;

ALTER TABLE agent_definitions
    DROP CONSTRAINT fk_agent_definitions_project,
    ADD CONSTRAINT fk_agent_definitions_project
        FOREIGN KEY (project_id) REFERENCES agent_projects(id) ON DELETE CASCADE;
