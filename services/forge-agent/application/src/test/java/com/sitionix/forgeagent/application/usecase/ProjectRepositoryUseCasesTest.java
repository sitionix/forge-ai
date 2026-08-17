package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryLink;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.ProjectRepositoryLinkRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectRepositoryUseCasesTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-08-17T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectRepositoryLinkRepository repositoryLinkRepository;

    private ProjectRepositoryUseCases useCases;

    @BeforeEach
    void setUp() {
        this.useCases = new ProjectRepositoryUseCases(this.projectRepository, this.repositoryLinkRepository, CLOCK);
    }

    @Test
    void importsRepositoryByStoringTrimmedRemoteUrlOnly() {
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        final ArgumentCaptor<ProjectRepositoryLink> captor = ArgumentCaptor.forClass(ProjectRepositoryLink.class);

        final ProjectRepositoryLink result = this.useCases.importRepository(
                PROJECT_ID,
                new ImportProjectRepositoryCommand("  git@gitlab.com:company/service-a.git  ")
        );

        verify(this.repositoryLinkRepository).save(captor.capture());
        assertThat(captor.getValue().id()).isNotNull();
        assertThat(captor.getValue().projectId()).isEqualTo(PROJECT_ID);
        assertThat(captor.getValue().remoteUrl()).isEqualTo("git@gitlab.com:company/service-a.git");
        assertThat(captor.getValue().createdAt()).isEqualTo(NOW);
        assertThat(result.remoteUrl()).isEqualTo("git@gitlab.com:company/service-a.git");
    }

    @Test
    void listsProjectRepositoriesAfterCheckingProjectExists() {
        final ProjectRepositoryLink repository = new ProjectRepositoryLink(UUID.randomUUID(), PROJECT_ID, "git@gitlab.com:company/service-a.git", NOW);
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(repository));

        assertThat(this.useCases.listProjectRepositories(PROJECT_ID)).containsExactly(repository);

        verify(this.projectRepository).findById(PROJECT_ID);
        verify(this.repositoryLinkRepository).findByProjectId(PROJECT_ID);
    }

    @Test
    void rejectsBlankRemoteUrl() {
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));

        assertThatThrownBy(() -> this.useCases.importRepository(PROJECT_ID, new ImportProjectRepositoryCommand("  ")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Repository URL is required.");

        verify(this.repositoryLinkRepository, never()).save(any());
    }

    @Test
    void rejectsMissingProject() {
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.useCases.importRepository(PROJECT_ID, new ImportProjectRepositoryCommand("git@example.com:repo.git")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Project was not found.");

        verify(this.repositoryLinkRepository, never()).save(any());
    }

    private Project project() {
        return new Project(PROJECT_ID, "Sitionix", "sitionix", NOW, NOW);
    }
}
