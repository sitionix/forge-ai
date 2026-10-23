package com.sitionix.forgeagent.application.definition;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;
import com.sitionix.forgeagent.domain.exception.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.model.execution.EffectiveAgentDefinition;
import com.sitionix.forgeagent.domain.port.AgentDefinitionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AgentDefinitionResolver {
    private final AgentDefinitionRepository agents;private final NativeAgentDefinitionPort nativeDefinitions;
    private final com.sitionix.forgeagent.domain.port.AgentExecutionProviderCapabilities capabilities;
    public AgentDefinitionResolver(AgentDefinitionRepository agents,NativeAgentDefinitionPort nativeDefinitions) { this(agents,nativeDefinitions,null); }
    @org.springframework.beans.factory.annotation.Autowired
    public AgentDefinitionResolver(AgentDefinitionRepository agents,NativeAgentDefinitionPort nativeDefinitions,com.sitionix.forgeagent.domain.port.AgentExecutionProviderCapabilities capabilities) { this.agents=agents;this.nativeDefinitions=nativeDefinitions;this.capabilities=capabilities; }
    public EffectiveAgentDefinition resolve(UUID project,String source,String reference,ExecutionWorkspace workspace,AgentModelSelection fallback) {
        if("NATIVE_CODEX".equals(source))return withResources(nativeDefinitions.resolve(project,reference,workspace,fallback),workspace);
        if(!"FORGE".equals(source))throw new ValidationException("INVALID_AGENT_SOURCE","Unknown agent source.");
        final UUID id;try{id=UUID.fromString(reference);}catch(RuntimeException bad){throw new ValidationException("INVALID_AGENT_REFERENCE","Invalid agent reference.");}
        var a=agents.findById(id).filter(agent->project.equals(agent.projectId())).orElseThrow(()->new NotFoundException("AGENT_NOT_FOUND","Agent was not found."));
        return withResources(forgeSnapshot(project,a),workspace);
    }
    public static EffectiveAgentDefinition forgeSnapshot(UUID project,AgentDefinition a) {
        if(!project.equals(a.projectId()))throw new NotFoundException("AGENT_NOT_FOUND","Agent was not found.");
        if(a.model()==null||a.model().providerId()==null||a.model().modelId()==null)throw new ConflictException("AGENT_MODEL_NOT_CONFIGURED","Agent model is not configured.");
        return new EffectiveAgentDefinition("FORGE",a.id().toString(),a.name(),a.instructions(),a.model(),fingerprint(a.id()+"\u0000"+a.instructions()+"\u0000"+a.model()+"\u0000"+a.outputSchema()),Map.of(),false,a.outputSchema()==null?null:a.outputSchema().jsonObject());
    }
    private EffectiveAgentDefinition withResources(EffectiveAgentDefinition d,ExecutionWorkspace workspace) {
        if(workspace==null)return d;
        var resources=nativeDefinitions.resources(workspace);
        if(!resources.entrySet().containsAll(d.resources().entrySet()))throw new ConflictException("AGENT_RESOURCE_CHANGED","Agent resources changed during resolution.");
        return new EffectiveAgentDefinition(d.source(),d.reference(),d.name(),d.instructions(),d.model(),fingerprint(d.fingerprint()+"\u0000"+d.model()+"\u0000"+new java.util.TreeMap<>(resources)),resources,d.nativeSubagents(),d.sourceOutputSchemaJson());
    }
    public void revalidate(EffectiveAgentDefinition snapshot,ExecutionWorkspace workspace) {
        if(snapshot.nativeSubagents() && capabilities!=null && !capabilities.supports(snapshot.model().providerId(),com.sitionix.forgeagent.domain.model.AgentExecutionProviderCapability.NATIVE_SUBAGENTS))throw new ConflictException("SESSION_CAPABILITY_REVOKED","Native subagent permission is no longer available.");
        nativeDefinitions.revalidate(snapshot,workspace);
    }
    public static String fingerprint(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
