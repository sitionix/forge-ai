package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.NodeScopeMode;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ScopeProjectionPolicy {
    public List<UUID> invocationRepositories(final NodeScopeMode scopeMode, final List<UUID> repositoryIds) {
        return switch (scopeMode) {
            case GLOBAL -> Collections.singletonList(null);
            case PER_SCOPE -> List.copyOf(repositoryIds);
        };
    }

    public List<UUID> project(final NodeScopeMode sourceMode, final NodeScopeMode targetMode,
                              final UUID sourceRepositoryId, final List<UUID> repositoryIds) {
        this.assertValidSourceInvocation(sourceMode, sourceRepositoryId, repositoryIds);
        return switch (sourceMode) {
            case GLOBAL -> switch (targetMode) {
                case GLOBAL -> Collections.singletonList(null);
                case PER_SCOPE -> List.copyOf(repositoryIds);
            };
            case PER_SCOPE -> switch (targetMode) {
                case GLOBAL -> Collections.singletonList(null);
                case PER_SCOPE -> List.of(sourceRepositoryId);
            };
        };
    }

    void assertValidSourceInvocation(final NodeScopeMode sourceMode, final UUID sourceRepositoryId,
                                     final List<UUID> repositoryIds) {
        switch (sourceMode) {
            case GLOBAL -> {
                if (sourceRepositoryId != null) {
                    throw new ValidationException("INVALID_GLOBAL_NODE_RUN_SCOPE", "GLOBAL node run cannot have repositoryId.");
                }
            }
            case PER_SCOPE -> {
                if (sourceRepositoryId == null) {
                    throw new ValidationException("MISSING_NODE_RUN_REPOSITORY", "PER_SCOPE node run requires repositoryId.");
                }
                if (!repositoryIds.contains(sourceRepositoryId)) {
                    throw new ValidationException("NODE_RUN_REPOSITORY_OUTSIDE_SNAPSHOT",
                            "PER_SCOPE source repositoryId must belong to the workflow run repository snapshot.");
                }
            }
        }
    }
}
