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
    public List<McpConnection> list(){return executor.executeMcp(http::listMcpConnections).stream().map(mapper::toDomain).toList();}
    public McpConnection get(UUID id){return mapper.toDomain(executor.executeMcp(()->http.getMcpConnection(id)));}
    public McpConnection create(McpConnectionCommand command){var request=McpConnectionOutboundRequest.from(command);return mapper.toDomain(executor.executeMcp(()->http.createMcpConnection(request)));}
    public McpConnection update(UUID id,McpConnectionCommand command){var request=McpConnectionOutboundRequest.from(command);return mapper.toDomain(executor.executeMcp(()->http.updateMcpConnection(id,request)));}
    public McpConnection setEnabled(UUID id,boolean enabled){var request=new Enabled(enabled);return mapper.toDomain(executor.executeMcp(()->http.setMcpConnectionEnabled(id,request)));}
    public void delete(UUID id){executor.executeMcp(()->{http.deleteMcpConnection(id);return null;});}
    public void reencrypt(UUID id){executor.executeMcp(()->{http.reencryptMcpConnection(id);return null;});}
    public record Enabled(boolean enabled){}
}
