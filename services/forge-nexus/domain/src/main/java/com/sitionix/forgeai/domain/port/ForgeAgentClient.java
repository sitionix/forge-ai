package com.sitionix.forgeai.domain.port;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepository;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTask;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskPage;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskSummary;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeCatalog;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunSummary;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectTaskCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowRunCommand;
import com.sitionix.forgeai.domain.model.agentproxy.ImportAgentProjectRepositoryCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentWorkflowCommand;
import java.util.List;
import java.util.UUID;

public interface ForgeAgentClient {

    List<AgentProject> listProjects();

    AgentProject createProject(CreateAgentProjectCommand command);

    void deleteProject(UUID projectId);

    AgentProjectRepository importProjectRepository(UUID projectId, ImportAgentProjectRepositoryCommand command);

    List<AgentProjectRepository> listProjectRepositories(UUID projectId);

    AgentProjectRepository cloneProjectRepository(UUID projectId, UUID repositoryId);

    AgentProjectTask createProjectTask(UUID projectId, CreateAgentProjectTaskCommand command);

    AgentProjectTaskPage listProjectTasks(UUID projectId, int page, int size);

    AgentProjectTask getProjectTask(UUID taskId);

    void deleteProjectTask(UUID taskId);

    AgentRuntimeCatalog getRuntime();

    List<AgentDefinitionListItem> listProjectAgents(UUID projectId);

    AgentDefinitionDetails createAgent(UUID projectId, SaveAgentDefinitionCommand command);

    AgentDefinitionDetails getAgent(UUID agentId);

    AgentDefinitionDetails updateAgent(UUID agentId, SaveAgentDefinitionCommand command);

    void deleteAgent(UUID agentId);

    List<AgentWorkflow> listProjectWorkflows(UUID projectId);

    AgentWorkflow createWorkflow(UUID projectId, CreateAgentWorkflowCommand command);

    AgentWorkflow getWorkflow(UUID workflowId);

    AgentWorkflow updateWorkflow(UUID workflowId, SaveAgentWorkflowCommand command);

    void deleteWorkflow(UUID workflowId);

    AgentWorkflowRun createWorkflowRun(UUID workflowId, CreateAgentWorkflowRunCommand command);

    List<AgentWorkflowRunSummary> listWorkflowRuns(UUID workflowId);

    AgentWorkflowRun getWorkflowRun(UUID runId);
}
