package com.sitionix.forgeagent.application.standalone;

import com.sitionix.forgeagent.application.definition.AgentDefinitionResolver;
import com.sitionix.forgeagent.application.runtime.*;
import com.sitionix.forgeagent.domain.exception.*;
import com.sitionix.forgeagent.domain.model.AgentModelSelection;
import com.sitionix.forgeagent.domain.model.execution.EffectiveAgentDefinition;
import com.sitionix.forgeagent.domain.model.standalone.*;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.standalone.StandaloneSessionRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class StandaloneSessionCommands {
    public record Create(UUID requestId,String agentSource,String agentId,List<UUID> repositoryIds,String text,String definitionFingerprint) { }
    public record Send(UUID requestId,long expectedRevision,String text) { }
    public record Cancel(UUID requestId,long expectedRevision) { }
    private final StandaloneSessionRepository sessions;private final ProjectRepository projects;
    private final ExecutionWorkspaceResolver workspaces;private final AgentDefinitionResolver definitions;
    private final com.sitionix.forgeagent.domain.port.AgentExecutionProviderCapabilities capabilities;
    public StandaloneSessionCommands(StandaloneSessionRepository sessions,ProjectRepository projects,ExecutionWorkspaceResolver workspaces,AgentDefinitionResolver definitions) {
        this(sessions,projects,workspaces,definitions,(provider,capability)->capability==com.sitionix.forgeagent.domain.model.AgentExecutionProviderCapability.DURABLE_CONTEXT);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public StandaloneSessionCommands(StandaloneSessionRepository sessions,ProjectRepository projects,ExecutionWorkspaceResolver workspaces,AgentDefinitionResolver definitions,com.sitionix.forgeagent.domain.port.AgentExecutionProviderCapabilities capabilities) {
        this.sessions=sessions;this.projects=projects;this.workspaces=workspaces;this.definitions=definitions;this.capabilities=capabilities;
    }
    private EffectiveAgentDefinition policy(EffectiveAgentDefinition d) {
        if(!capabilities.supports(d.model().providerId(),com.sitionix.forgeagent.domain.model.AgentExecutionProviderCapability.DURABLE_CONTEXT))throw new ConflictException("AGENT_SESSION_PROVIDER_UNSUPPORTED","Agent provider does not support durable sessions.");
        boolean children=capabilities.supports(d.model().providerId(),com.sitionix.forgeagent.domain.model.AgentExecutionProviderCapability.NATIVE_SUBAGENTS);
        return new EffectiveAgentDefinition(d.source(),d.reference(),d.name(),d.instructions(),d.model(),d.fingerprint(),d.resources(),children,d.sourceOutputSchemaJson());
    }
    public EffectiveAgentDefinition preview(UUID project,String source,String id,List<UUID> repositoryIds,AgentModelSelection fallback) {
        project(project);var ids=repositories(repositoryIds);return policy(definitions.resolve(project,source,id,workspaces.resolve(project,null,ids),fallback));
    }
    public StandaloneCommandReceipt create(UUID project,Create c,AgentModelSelection fallback) {
        project(project);request(c.requestId());StandaloneSessionPolicy.message(c.text());var ids=repositories(c.repositoryIds());
        String hash=AgentDefinitionResolver.fingerprint(c.agentSource()+"\u0000"+c.agentId()+"\u0000"+ids+"\u0000"+c.text());
        var old=sessions.creationReceipt(project,c.requestId(),hash);if(old.isPresent())return old.get();
        var workspace=workspaces.resolve(project,null,ids);var d=policy(definitions.resolve(project,c.agentSource(),c.agentId(),workspace,fallback));
        if(c.definitionFingerprint()!=null&&!c.definitionFingerprint().equals(d.fingerprint()))throw new ConflictException("AGENT_DEFINITION_CHANGED","Agent definition changed; review the effective model before starting.");
        Instant now=Instant.now();var s=new StandaloneAgentSession(UUID.randomUUID(),project,d,ids,workspaceFingerprint(workspace),null,null,"CREATED",0,null,null,now,now,now);
        return sessions.create(s,c.requestId(),hash,c.text());
    }
    public StandaloneCommandReceipt send(UUID project,UUID id,Send c) { project(project);request(c.requestId());StandaloneSessionPolicy.message(c.text());return sessions.send(project,id,c.requestId(),c.expectedRevision(),c.text()); }
    public StandaloneCommandReceipt cancel(UUID project,UUID id,Cancel c) { project(project);request(c.requestId());return sessions.cancel(project,id,c.requestId(),c.expectedRevision()); }
    private void project(UUID id) { if(id==null||projects.findById(id).isEmpty())throw new NotFoundException("PROJECT_NOT_FOUND","Project was not found."); }
    private void request(UUID id) { if(id==null)throw new ValidationException("REQUEST_ID_REQUIRED","A request identity is required."); }
    private List<UUID> repositories(List<UUID> value) {
        var ids=value==null?List.<UUID>of():value;
        if(ids.size()>31||ids.stream().anyMatch(Objects::isNull)||new HashSet<>(ids).size()!=ids.size())throw new ValidationException("INVALID_SESSION_REPOSITORIES","Select up to 31 distinct project repositories.");
        return ids.stream().sorted().toList();
    }
    public static String workspaceFingerprint(ExecutionWorkspace space) { return AgentDefinitionResolver.fingerprint(space.cwd()+"\u0000"+space.workspaceRoots()); }
}
