package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.application.definition.*;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;
import com.sitionix.forgeagent.domain.exception.*;
import com.sitionix.forgeagent.domain.model.AgentModelSelection;
import com.sitionix.forgeagent.domain.model.execution.EffectiveAgentDefinition;
import com.sitionix.forgeagent.domain.model.library.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class CodexNativeAgentDefinitionAdapter implements NativeAgentDefinitionPort {
    private final Path globalHome;
    public CodexNativeAgentDefinitionAdapter(@Value("${forge.codex.library.global-home:}") String home) { globalHome=home.isBlank()?null:Path.of(home); }
    @Override public EffectiveAgentDefinition resolve(UUID project,String reference,ExecutionWorkspace workspace,AgentModelSelection fallback) {
        if(reference==null||!reference.matches("[a-f0-9]{64}"))throw unavailable();
        var roots=new ArrayList<Path>();roots.add(workspace.cwd());workspace.workspaceRoots().stream().filter(p->!roots.contains(p)).forEach(roots::add);
        try {
            var context=new LibraryContext(project,new LibraryProviderContext(0,"codex","resolution",null,null,null),workspace.cwd(),roots);
            var result=new CodexProjectAgentInventory(globalHome).resolve(context,reference).path("resolved");
            if(!result.isArray()||result.size()!=1)throw unavailable();
            var value=result.get(0);String model=value.path("model").asText(null);
            if(model==null)model=fallback==null||!"codex".equals(fallback.providerId())?null:fallback.modelId();
            if(model==null||model.isBlank())throw new ConflictException("AGENT_MODEL_UNAVAILABLE","Native agent has no effective model.");
            String effort=value.path("effort").asText(fallback==null?null:fallback.effortId());
            return new EffectiveAgentDefinition("NATIVE_CODEX",reference,CodexLibraryMetadataMapper.safeText(value.path("name").asText()),value.path("instructions").asText(),
                    new AgentModelSelection("codex",model,effort),value.path("fingerprint").asText(),Map.of(value.path("identity").asText(),value.path("fingerprint").asText()),false);
        }catch(ConflictException e){throw e;}catch(Exception failure){throw unavailable();}
    }
    @Override public void revalidate(EffectiveAgentDefinition snapshot,ExecutionWorkspace workspace) {
        if(!snapshot.resources().equals(resources(workspace)))throw new ConflictException("AGENT_RESOURCE_CHANGED","Agent resources changed; this session requires compatibility review.");
    }
    @Override public Map<String,String> resources(ExecutionWorkspace workspace) {
        var roots=new ArrayList<Path>();roots.add(workspace.cwd());workspace.workspaceRoots().stream().filter(p->!roots.contains(p)).forEach(roots::add);
        try {
            var result=new CodexProjectAgentInventory(globalHome).manifest(new LibraryContext(null,null,workspace.cwd(),roots));
            if(!result.path("diagnostics").isEmpty()||!result.path("manifest").isObject())throw unavailable();
            var manifest=new TreeMap<String,String>();result.path("manifest").fields().forEachRemaining(e->manifest.put(e.getKey(),e.getValue().asText()));return Map.copyOf(manifest);
        }catch(Exception incompatible){throw new ConflictException("AGENT_RESOURCE_UNAVAILABLE","Agent resources cannot be preserved safely in this workspace.");}
    }
    private static ConflictException unavailable() { return new ConflictException("AGENT_DEFINITION_UNAVAILABLE","Native definition is unavailable or its runtime configuration is unsupported."); }
}
