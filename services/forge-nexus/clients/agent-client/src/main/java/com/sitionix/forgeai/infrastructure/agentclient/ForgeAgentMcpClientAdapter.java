package com.sitionix.forgeai.infrastructure.agentclient;

import com.sitionix.forgeai.domain.model.mcp.*;
import com.sitionix.forgeai.domain.port.ForgeAgentMcpClient;
import com.sitionix.forgeai.infrastructure.agentclient.dto.McpConnectionOutboundRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ForgeAgentMcpClientAdapter implements ForgeAgentMcpClient {
    private final ForgeAgentHttpClient http;
    private final ForgeAgentClientCallExecutor executor;
    private final McpClientMapper mapper;
    public ForgeAgentMcpClientAdapter(ForgeAgentHttpClient http,ForgeAgentClientCallExecutor executor,McpClientMapper mapper){this.http=http;this.executor=executor;this.mapper=mapper;}
    public List<McpConnection> list(){return executor.execute(http::listMcpConnections).stream().map(mapper::toDomain).toList();}
    public McpConnection get(UUID id){return mapper.toDomain(executor.execute(()->http.getMcpConnection(id)));}
    public McpProbeReport test(UUID id){return mapper.toDomain(executor.execute(()->http.testMcpConnection(id)));}
    public List<McpProbeReport.Tool> inventory(UUID id){return mapper.toTools(executor.execute(()->http.listMcpTools(id)));}
    public McpConnection approve(UUID id,java.util.Set<McpConnection.AllowedTool> tools){
        return mapper.toDomain(executor.execute(()->http.approveMcpTools(id,new Approvals(tools))));
    }
    public McpConnection create(McpConnectionCommand command){var request=McpConnectionOutboundRequest.from(command);return mapper.toDomain(executor.execute(()->http.createMcpConnection(request)));}
    public McpConnection update(UUID id,McpConnectionCommand command){var request=McpConnectionOutboundRequest.from(command);return mapper.toDomain(executor.execute(()->http.updateMcpConnection(id,request)));}
    public McpConnection setEnabled(UUID id,boolean enabled){var request=new Enabled(enabled);return mapper.toDomain(executor.execute(()->http.setMcpConnectionEnabled(id,request)));}
    public void delete(UUID id){executor.execute(()->{http.deleteMcpConnection(id);return null;});}
    public void reencrypt(UUID id){executor.execute(()->{http.reencryptMcpConnection(id);return null;});}
    public record Enabled(boolean enabled){}
    public record Approvals(java.util.Set<McpConnection.AllowedTool> tools){}
}
