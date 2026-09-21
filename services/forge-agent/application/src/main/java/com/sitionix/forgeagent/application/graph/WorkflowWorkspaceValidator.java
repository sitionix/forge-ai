package com.sitionix.forgeagent.application.graph;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryLink;
import com.sitionix.forgeagent.domain.port.ProjectRepositoryLinkRepository;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorkflowWorkspaceValidator {
    private final ProjectRepositoryLinkRepository repositories;

    public void validate(final UUID projectId, final List<Node> nodes) {
        final var selected = nodes.stream().flatMap(node -> node.workspaceRepositoryIds().stream()).toList();
        if (selected.isEmpty()) return;
        final var available = this.repositories.findByProjectId(projectId).stream()
                .map(ProjectRepositoryLink::id).collect(Collectors.toSet());
        if (!available.containsAll(selected)) {
            throw new ValidationException("INVALID_WORKSPACE_REPOSITORY", "Working repositories must belong to the workflow project.");
        }
    }
}
