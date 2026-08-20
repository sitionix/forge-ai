package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.NodeScopeMode;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ScopeProjectionPolicy {
    public List<UUID> project(final NodeScopeMode sourceMode, final NodeScopeMode targetMode,
                              final UUID sourceRepositoryId, final List<UUID> repositoryIds) {
        this.assertValidSourceInvocation(sourceMode, sourceRepositoryId, repositoryIds);
        if (targetMode == NodeScopeMode.GLOBAL) {
            return Collections.singletonList(null);
        }
        return sourceMode == NodeScopeMode.PER_SCOPE ? List.of(sourceRepositoryId) : List.copyOf(repositoryIds);
    }

    void assertValidSourceInvocation(final NodeScopeMode sourceMode, final UUID sourceRepositoryId,
                                     final List<UUID> repositoryIds) {
        if (sourceMode == NodeScopeMode.GLOBAL && sourceRepositoryId != null) {
            throw new ValidationException("INVALID_GLOBAL_NODE_RUN_SCOPE", "GLOBAL node run cannot have repositoryId.");
        }
        if (sourceMode == NodeScopeMode.PER_SCOPE && sourceRepositoryId == null) {
            throw new ValidationException("MISSING_NODE_RUN_REPOSITORY", "PER_SCOPE node run requires repositoryId.");
        }
        if (sourceMode == NodeScopeMode.PER_SCOPE && !repositoryIds.contains(sourceRepositoryId)) {
            throw new ValidationException("NODE_RUN_REPOSITORY_OUTSIDE_SNAPSHOT",
                    "PER_SCOPE source repositoryId must belong to the workflow run repository snapshot.");
        }
    }
}
