package com.sitionix.forgeai.application.agentproxy;

import com.sitionix.forgeai.domain.model.mcp.*;
import com.sitionix.forgeai.domain.port.ForgeAgentMcpClient;
import com.sitionix.forgeai.domain.usecase.ManageAgentMcpConnections;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AgentMcpConnectionsUseCase implements ManageAgentMcpConnections {
    private static final Set<String> FORBIDDEN=Set.of("host","cookie","authorization","connection","keep-alive","proxy-connection","proxy-authenticate","proxy-authorization","te","trailer","transfer-encoding","upgrade","forwarded","via","content-length");
    private final ForgeAgentMcpClient client;
    public AgentMcpConnectionsUseCase(ForgeAgentMcpClient client){this.client=client;}
    public List<McpConnection> list(){return client.list();}
    public McpConnection get(UUID id){requireId(id);return client.get(id);}
    public McpProbeReport test(UUID id){requireId(id);return client.test(id);}
    public List<McpProbeReport.Tool> inventory(UUID id){requireId(id);return client.inventory(id);}
    public McpConnection approve(UUID id, Set<McpConnection.AllowedTool> tools){
        requireId(id);
        if (tools == null || tools.stream().anyMatch(tool -> tool == null || tool.name() == null
                || tool.name().isBlank() || tool.schemaFingerprint() == null || tool.schemaFingerprint().isBlank()))
            throw new IllegalArgumentException("Invalid MCP tool approval");
        return client.approve(id, tools);
    }
    public McpConnection create(McpConnectionCommand command){validate(command,false);return client.create(command);}
    public McpConnection update(UUID id,McpConnectionCommand command){requireId(id);validate(command,true);return client.update(id,command);}
    public McpConnection setEnabled(UUID id,boolean enabled){requireId(id);return client.setEnabled(id,enabled);}
    public void delete(UUID id){requireId(id);client.delete(id);}
    public void reencrypt(UUID id){requireId(id);client.reencrypt(id);}
    private static void requireId(UUID id){if(id==null)throw new IllegalArgumentException("Invalid MCP request");}
    private static void validate(McpConnectionCommand c,boolean update){
        if(c==null || c.displayName()==null || c.displayName().isBlank() || c.displayName().length()>255
                || c.transport()!=McpConnection.Transport.STREAMABLE_HTTP || c.authType()==null || c.projectAccess()==null
                || c.allowedTools()==null || !c.allowedTools().isEmpty() || (update && c.credentialChange()==null)) invalid();
        URI uri=c.endpoint();
        if(uri==null || uri.getScheme()==null || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))
                || uri.getHost()==null || uri.getHost().isBlank() || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null || uri.getRawQuery()!=null) invalid();
        boolean hasBearer=c.bearer()!=null,hasHeaders=c.headers()!=null;
        if(hasBearer && hasHeaders || (c.credentialChange()==McpConnection.CredentialChange.REPLACE)!=(hasBearer||hasHeaders)
                || (!update && c.credentialChange()!=null && c.credentialChange()!=McpConnection.CredentialChange.REPLACE)) invalid();
        if(hasBearer){if(c.authType()!=McpConnection.AuthType.BEARER || c.bearer().isBlank() || masked(c.bearer()) || crlf(c.bearer()))invalid();}
        if(hasHeaders){if(c.authType()!=McpConnection.AuthType.SECRET_HEADERS || c.headers().isEmpty())invalid();
            for(var e:c.headers().entrySet()){String n=e.getKey(),v=e.getValue();
                if(n==null || !n.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || FORBIDDEN.contains(n.toLowerCase(Locale.ROOT))
                        || n.toLowerCase(Locale.ROOT).startsWith("x-forwarded-") || v==null || v.isBlank() || masked(v) || crlf(v)) invalid();
            }
        }
        if(c.authType()==McpConnection.AuthType.NONE && (hasBearer||hasHeaders))invalid();
    }
    private static boolean masked(String s){return s.strip().matches("(?:\\*{4,}|•{4,})");}
    private static boolean crlf(String s){return s.indexOf('\r')>=0 || s.indexOf('\n')>=0;}
    private static void invalid(){throw new IllegalArgumentException("Invalid MCP request");}
}
