package com.sitionix.forgeai.domain.port;

import com.sitionix.forgeai.domain.model.mcp.*;
import java.util.List;
import java.util.UUID;

public interface ForgeAgentMcpClient {
    List<McpConnection> list();
    McpConnection get(UUID id);
    McpProbeReport test(UUID id);
    List<McpProbeReport.Tool> inventory(UUID id);
    McpConnection approve(UUID id, java.util.Set<McpConnection.AllowedTool> tools);
    McpConnection create(McpConnectionCommand command);
    McpConnection update(UUID id,McpConnectionCommand command);
    McpConnection setEnabled(UUID id,boolean enabled);
    void delete(UUID id);
    void reencrypt(UUID id);
}
