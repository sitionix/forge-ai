package com.sitionix.forgeai.domain.port;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeCatalog;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunSummary;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowRunCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentWorkflowCommand;
import java.util.List;
import java.util.UUID;

public interface ForgeAgentClient {

    List<AgentProject> listProjects();

    AgentProject createProject(CreateAgentProjectCommand command);

    AgentRuntimeCatalog getRuntime();

    List<AgentDefinitionListItem> listProjectAgents(UUID projectId);

    AgentDefinitionDetails createAgent(UUID projectId, SaveAgentDefinitionCommand command);

    AgentDefinitionDetails getAgent(UUID agentId);

    AgentDefinitionDetails updateAgent(UUID agentId, SaveAgentDefinitionCommand command);

    List<AgentWorkflow> listProjectWorkflows(UUID projectId);

    AgentWorkflow createWorkflow(UUID projectId, CreateAgentWorkflowCommand command);

    AgentWorkflow getWorkflow(UUID workflowId);

    AgentWorkflow updateWorkflow(UUID workflowId, SaveAgentWorkflowCommand command);

    AgentWorkflowRun createWorkflowRun(UUID workflowId, CreateAgentWorkflowRunCommand command);

    List<AgentWorkflowRunSummary> listWorkflowRuns(UUID workflowId);

    AgentWorkflowRun getWorkflowRun(UUID runId);
}
