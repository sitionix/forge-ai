package com.sitionix.forgeai.domain.port;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import java.util.List;
import java.util.UUID;

public interface ForgeAgentClient {

    List<AgentProject> listProjects();

    AgentProject createProject(CreateAgentProjectCommand command);

    List<AgentDefinitionListItem> listProjectAgents(UUID projectId);

    AgentDefinitionDetails createAgent(UUID projectId, SaveAgentDefinitionCommand command);

    AgentDefinitionDetails getAgent(UUID agentId);

    AgentDefinitionDetails updateAgent(UUID agentId, SaveAgentDefinitionCommand command);
}
