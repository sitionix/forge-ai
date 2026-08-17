package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryLink;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.ProjectRepositoryLinkRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectRepositoryUseCases {

    private final ProjectRepository projectRepository;
    private final ProjectRepositoryLinkRepository repositoryLinkRepository;
    private final Clock clock;

    @Transactional
    public ProjectRepositoryLink importRepository(final UUID projectId, final ImportProjectRepositoryCommand command) {
        this.requireProject(projectId);
        final String remoteUrl = this.requireRemoteUrl(command.remoteUrl());
        final Instant now = Instant.now(this.clock);
        return this.repositoryLinkRepository.save(new ProjectRepositoryLink(UUID.randomUUID(), projectId, remoteUrl, now));
    }

    @Transactional(readOnly = true)
    public List<ProjectRepositoryLink> listProjectRepositories(final UUID projectId) {
        this.requireProject(projectId);
        return this.repositoryLinkRepository.findByProjectId(projectId);
    }

    private void requireProject(final UUID projectId) {
        this.projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("PROJECT_NOT_FOUND", "Project was not found."));
    }

    private String requireRemoteUrl(final String candidate) {
        if (candidate == null || candidate.trim().isBlank()) {
            throw new ValidationException("INVALID_REPOSITORY_URL", "Repository URL is required.");
        }
        return candidate.trim();
    }
}
