package com.sitionix.forgeai.infrastructure.agentclient;

import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionListResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;
import org.springframework.web.bind.annotation.RequestBody;

public interface ForgeAgentHttpClient {

    @GetExchange("/api/v1/projects")
    List<AgentProjectResponse> listProjects();

    @PostExchange(value = "/api/v1/projects", contentType = MediaType.APPLICATION_JSON_VALUE)
    AgentProjectResponse createProject(@RequestBody AgentProjectRequest request);

    @GetExchange("/api/v1/projects/{projectId}/agents")
    List<AgentDefinitionListResponse> listProjectAgents(@PathVariable UUID projectId);

    @PostExchange(value = "/api/v1/projects/{projectId}/agents", contentType = MediaType.APPLICATION_JSON_VALUE)
    AgentDefinitionResponse createAgent(@PathVariable UUID projectId, @RequestBody AgentDefinitionRequest request);

    @GetExchange("/api/v1/agents/{agentId}")
    AgentDefinitionResponse getAgent(@PathVariable UUID agentId);

    @PutExchange(value = "/api/v1/agents/{agentId}", contentType = MediaType.APPLICATION_JSON_VALUE)
    AgentDefinitionResponse updateAgent(@PathVariable UUID agentId, @RequestBody AgentDefinitionRequest request);
}
