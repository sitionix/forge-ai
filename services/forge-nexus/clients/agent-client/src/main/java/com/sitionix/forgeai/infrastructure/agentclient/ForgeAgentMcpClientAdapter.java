package com.sitionix.forgeai.infrastructure.agentclient;

import com.sitionix.forgeai.domain.model.mcp.*;
import com.sitionix.forgeai.domain.exception.AgentClientException;
import com.sitionix.forgeai.domain.exception.McpAgentClientException;
import com.sitionix.forgeai.domain.port.ForgeAgentMcpClient;
import com.sitionix.forgeai.infrastructure.agentclient.dto.McpConnectionOutboundRequest;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

@Component
public class ForgeAgentMcpClientAdapter implements ForgeAgentMcpClient {
    private final ForgeAgentHttpClient http;
    private final ForgeAgentClientCallExecutor executor;
    private final McpClientMapper mapper;
    public ForgeAgentMcpClientAdapter(ForgeAgentHttpClient http,ForgeAgentClientCallExecutor executor,McpClientMapper mapper){this.http=http;this.executor=executor;this.mapper=mapper;}
    public List<McpConnection> list(){return safe(()->executor.execute(http::listMcpConnections).stream().map(mapper::toDomain).toList());}
    public McpConnection get(UUID id){return safe(()->mapper.toDomain(executor.execute(()->http.getMcpConnection(id))));}
    public McpConnection create(McpConnectionCommand command){return safe(()->mapper.toDomain(executor.execute(()->http.createMcpConnection(McpConnectionOutboundRequest.from(command)))));}
    public McpConnection update(UUID id,McpConnectionCommand command){return safe(()->mapper.toDomain(executor.execute(()->http.updateMcpConnection(id,McpConnectionOutboundRequest.from(command)))));}
    public McpConnection setEnabled(UUID id,boolean enabled){return safe(()->mapper.toDomain(executor.execute(()->http.setMcpConnectionEnabled(id,new Enabled(enabled)))));}
    public void delete(UUID id){safe(()->{executor.execute(()->{http.deleteMcpConnection(id);return null;});return null;});}
    public void reencrypt(UUID id){safe(()->{executor.execute(()->{http.reencryptMcpConnection(id);return null;});return null;});}
    private <T> T safe(Supplier<T> call){
        try { return call.get(); }
        catch (AgentClientException error) {
            throw new McpAgentClientException(switch(error.statusCode()) {
                case 400 -> McpAgentClientException.Category.INVALID_REQUEST;
                case 404 -> McpAgentClientException.Category.NOT_FOUND;
                default -> McpAgentClientException.Category.UPSTREAM_ERROR;
            });
        }
        catch (ResourceAccessException error) {
            throw new McpAgentClientException(McpAgentClientException.Category.UPSTREAM_UNAVAILABLE);
        }
        catch (RuntimeException error) {
            throw new McpAgentClientException(McpAgentClientException.Category.UPSTREAM_ERROR);
        }
    }
    public record Enabled(boolean enabled){}
}
