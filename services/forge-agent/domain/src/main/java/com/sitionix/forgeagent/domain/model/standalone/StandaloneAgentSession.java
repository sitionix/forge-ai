package com.sitionix.forgeagent.domain.model.standalone;
import com.sitionix.forgeagent.domain.model.execution.EffectiveAgentDefinition;
import java.time.Instant;
import java.util.*;
public record StandaloneAgentSession(UUID id,UUID projectId,EffectiveAgentDefinition definition,List<UUID> repositoryIds,
        String workspaceFingerprint,String providerConversationId,String providerVersion,String status,long revision,
        UUID activeTurnId,String failureCode,Instant createdAt,Instant updatedAt,Instant lastActivityAt) {
    public StandaloneAgentSession { repositoryIds=List.copyOf(repositoryIds); }
}
