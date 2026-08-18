package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.GitHeadState;
import com.sitionix.forgeagent.domain.model.GitHeadType;
import com.sitionix.forgeagent.domain.model.GitLocalRepositoryState;
import com.sitionix.forgeagent.domain.model.GitConflictState;
import com.sitionix.forgeagent.domain.model.GitOperationState;
import com.sitionix.forgeagent.domain.model.GitRemoteInspection;
import com.sitionix.forgeagent.domain.model.GitUpstreamRelation;
import com.sitionix.forgeagent.domain.model.GitUpstreamState;
import com.sitionix.forgeagent.domain.model.GitWorkingTreeState;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryCloneAttempt;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryLink;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryView;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceReference;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceState;
import com.sitionix.forgeagent.domain.port.GitExecutionException;
import com.sitionix.forgeagent.domain.port.GitRemoteRejectedException;
import com.sitionix.forgeagent.domain.port.GitRepositoryPort;
import com.sitionix.forgeagent.domain.port.LocalProjectWorkspaceException;
import com.sitionix.forgeagent.domain.port.LocalProjectWorkspacePort;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.ProjectRepositoryLinkRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectRepositoryUseCasesTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID REPOSITORY_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant NOW = Instant.parse("2026-08-17T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectRepositoryLinkRepository repositoryLinkRepository;
    @Mock
    private GitRepositoryPort gitRepositoryPort;
    @Mock
    private LocalProjectWorkspacePort localProjectWorkspacePort;

    private ProjectRepositoryUseCases useCases;

    @BeforeEach
    void setUp() {
        this.useCases = new ProjectRepositoryUseCases(
                this.projectRepository,
                this.repositoryLinkRepository,
                this.gitRepositoryPort,
                this.localProjectWorkspacePort,
                CLOCK
        );
    }

    @Test
    void importsRepositoryAfterInspectingRemoteBeforePersistence() {
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.gitRepositoryPort.inspectRemote("git@gitlab.com:company/service-a.git")).thenReturn(new GitRemoteInspection("service-a"));
        when(this.repositoryLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());
        when(this.repositoryLinkRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        final ArgumentCaptor<ProjectRepositoryLink> captor = ArgumentCaptor.forClass(ProjectRepositoryLink.class);

        final ProjectRepositoryView result = this.useCases.importRepository(
                PROJECT_ID,
                new ImportProjectRepositoryCommand("  git@gitlab.com:company/service-a.git  ")
        );

        final InOrder ordered = inOrder(this.gitRepositoryPort, this.repositoryLinkRepository);
        ordered.verify(this.gitRepositoryPort).inspectRemote("git@gitlab.com:company/service-a.git");
        ordered.verify(this.repositoryLinkRepository).save(captor.capture());
        assertThat(captor.getValue().remoteUrl()).isEqualTo("git@gitlab.com:company/service-a.git");
        assertThat(result.name()).isEqualTo("service-a");
        assertThat(result.cloned()).isFalse();
    }

    @Test
    void invalidRemoteIsNotPersisted() {
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.gitRepositoryPort.inspectRemote("git@example.com:missing.git")).thenThrow(new GitRemoteRejectedException("missing"));

        assertThatThrownBy(() -> this.useCases.importRepository(PROJECT_ID, new ImportProjectRepositoryCommand("git@example.com:missing.git")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Repository remote is not reachable.");

        verify(this.repositoryLinkRepository, never()).save(any());
    }

    @Test
    void remoteInspectionInfrastructureFailureIsNotValidationError() {
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.gitRepositoryPort.inspectRemote("git@example.com:service-a.git")).thenThrow(new GitExecutionException("timeout"));

        assertThatThrownBy(() -> this.useCases.importRepository(PROJECT_ID, new ImportProjectRepositoryCommand("git@example.com:service-a.git")))
                .isInstanceOf(InfrastructureExecutionException.class)
                .hasMessage("Project repository remote inspection failed.");

        verify(this.repositoryLinkRepository, never()).save(any());
    }

    @Test
    void duplicateResolvedRepositoryNameInProjectIsRejected() {
        final ProjectRepositoryLink existing = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.gitRepositoryPort.inspectRemote("https://github.com/other/service-a.git")).thenReturn(new GitRemoteInspection("service-a"));
        when(this.repositoryLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(existing));
        when(this.gitRepositoryPort.resolveRepositoryName(existing.remoteUrl())).thenReturn("service-a");

        assertThatThrownBy(() -> this.useCases.importRepository(PROJECT_ID, new ImportProjectRepositoryCommand("https://github.com/other/service-a.git")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Project repository name already exists.");

        verify(this.repositoryLinkRepository, never()).save(any());
    }

    @Test
    void listsWithOneBatchLocalStateLookupAndProjectsClonedState() {
        final ProjectRepositoryLink first = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        final UUID secondId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        final ProjectRepositoryLink second = this.repositoryLink(secondId, "https://github.com/company/service-b.git");
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(first, second));
        when(this.gitRepositoryPort.resolveRepositoryName(first.remoteUrl())).thenReturn("service-a");
        when(this.gitRepositoryPort.resolveRepositoryName(second.remoteUrl())).thenReturn("service-b");
        final Path firstPath = Path.of("/tmp/forge-projects/project/service-a");
        when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceStates(PROJECT_ID, List.of(
                new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a"),
                new ProjectRepositoryWorkspaceReference(secondId, "service-b")
        ))).thenReturn(Map.of(
                REPOSITORY_ID, new ProjectRepositoryWorkspaceState(REPOSITORY_ID, firstPath, true),
                secondId, new ProjectRepositoryWorkspaceState(secondId, Path.of("/tmp/forge-projects/project/service-b"), false)
        ));
        when(this.gitRepositoryPort.inspectLocalRepository(firstPath)).thenReturn(this.branchState(GitWorkingTreeState.CLEAN));

        final List<ProjectRepositoryView> repositories = this.useCases.listProjectRepositories(PROJECT_ID);

        assertThat(repositories).extracting(ProjectRepositoryView::name).containsExactly("service-a", "service-b");
        assertThat(repositories).extracting(ProjectRepositoryView::cloned).containsExactly(true, false);
        assertThat(repositories.get(0).gitState()).isEqualTo(this.branchState(GitWorkingTreeState.CLEAN));
        assertThat(repositories.get(1).gitState()).isNull();
        verify(this.localProjectWorkspacePort).resolveRepositoryWorkspaceStates(PROJECT_ID, List.of(
                new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a"),
                new ProjectRepositoryWorkspaceReference(secondId, "service-b")
        ));
        verify(this.gitRepositoryPort).inspectLocalRepository(firstPath);
    }

    @Test
    void listDoesNotInspectGitStateForUnclonedRepositories() {
        final ProjectRepositoryLink repository = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(repository));
        when(this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl())).thenReturn("service-a");
        when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceStates(PROJECT_ID, List.of(
                new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a")
        ))).thenReturn(Map.of(REPOSITORY_ID, new ProjectRepositoryWorkspaceState(REPOSITORY_ID, Path.of("/tmp/service-a"), false)));

        final List<ProjectRepositoryView> repositories = this.useCases.listProjectRepositories(PROJECT_ID);

        assertThat(repositories.getFirst().cloned()).isFalse();
        assertThat(repositories.getFirst().gitState()).isNull();
        verify(this.gitRepositoryPort, never()).inspectLocalRepository(any());
    }

    @Test
    void listMapsDirtyGitStateForClonedRepository() {
        final ProjectRepositoryLink repository = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        final Path repositoryPath = Path.of("/tmp/forge-projects/project/service-a");
        final GitLocalRepositoryState dirtyState = this.branchState(GitWorkingTreeState.DIRTY);
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(repository));
        when(this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl())).thenReturn("service-a");
        when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceStates(PROJECT_ID, List.of(
                new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a")
        ))).thenReturn(Map.of(REPOSITORY_ID, new ProjectRepositoryWorkspaceState(REPOSITORY_ID, repositoryPath, true)));
        when(this.gitRepositoryPort.inspectLocalRepository(repositoryPath)).thenReturn(dirtyState);

        final List<ProjectRepositoryView> repositories = this.useCases.listProjectRepositories(PROJECT_ID);

        assertThat(repositories.getFirst().gitState()).isEqualTo(dirtyState);
        verify(this.repositoryLinkRepository, never()).save(any());
    }

    @Test
    void listMapsDetachedGitStateForClonedRepository() {
        final ProjectRepositoryLink repository = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        final Path repositoryPath = Path.of("/tmp/forge-projects/project/service-a");
        final GitLocalRepositoryState detachedState = GitLocalRepositoryState.valid(
                new GitHeadState(GitHeadType.DETACHED, null, "a1b2c3"),
                GitWorkingTreeState.CLEAN,
                GitConflictState.NONE,
                GitOperationState.NORMAL,
                null
        );
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(repository));
        when(this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl())).thenReturn("service-a");
        when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceStates(PROJECT_ID, List.of(
                new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a")
        ))).thenReturn(Map.of(REPOSITORY_ID, new ProjectRepositoryWorkspaceState(REPOSITORY_ID, repositoryPath, true)));
        when(this.gitRepositoryPort.inspectLocalRepository(repositoryPath)).thenReturn(detachedState);

        final List<ProjectRepositoryView> repositories = this.useCases.listProjectRepositories(PROJECT_ID);

        assertThat(repositories.getFirst().gitState()).isEqualTo(detachedState);
    }

    @Test
    void listMapsInvalidClonedCheckoutWithoutChangingClonedState() {
        final ProjectRepositoryLink repository = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        final Path repositoryPath = Path.of("/tmp/forge-projects/project/service-a");
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(repository));
        when(this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl())).thenReturn("service-a");
        when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceStates(PROJECT_ID, List.of(
                new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a")
        ))).thenReturn(Map.of(REPOSITORY_ID, new ProjectRepositoryWorkspaceState(REPOSITORY_ID, repositoryPath, true)));
        when(this.gitRepositoryPort.inspectLocalRepository(repositoryPath)).thenReturn(GitLocalRepositoryState.invalid());

        final List<ProjectRepositoryView> repositories = this.useCases.listProjectRepositories(PROJECT_ID);

        assertThat(repositories.getFirst().cloned()).isTrue();
        assertThat(repositories.getFirst().gitState()).isEqualTo(GitLocalRepositoryState.invalid());
    }

    @Test
    void localGitInspectionFailureMapsToInfrastructureFailure() {
        final ProjectRepositoryLink repository = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        final Path repositoryPath = Path.of("/tmp/forge-projects/project/service-a");
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(repository));
        when(this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl())).thenReturn("service-a");
        when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceStates(PROJECT_ID, List.of(
                new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a")
        ))).thenReturn(Map.of(REPOSITORY_ID, new ProjectRepositoryWorkspaceState(REPOSITORY_ID, repositoryPath, true)));
        when(this.gitRepositoryPort.inspectLocalRepository(repositoryPath)).thenThrow(new GitExecutionException("timeout"));

        assertThatThrownBy(() -> this.useCases.listProjectRepositories(PROJECT_ID))
                .isInstanceOf(InfrastructureExecutionException.class)
                .hasMessage("Project repository state could not be resolved.");
    }

    @Test
    void cloneRejectsAlreadyClonedRepository() {
        final ProjectRepositoryLink repository = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findById(REPOSITORY_ID)).thenReturn(Optional.of(repository));
        when(this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl())).thenReturn("service-a");
        when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceStates(PROJECT_ID, List.of(new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a"))))
                .thenReturn(Map.of(REPOSITORY_ID, new ProjectRepositoryWorkspaceState(REPOSITORY_ID, Path.of("/tmp/service-a"), true)));

        assertThatThrownBy(() -> this.useCases.cloneRepository(PROJECT_ID, REPOSITORY_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Project repository is already cloned.");

        verify(this.gitRepositoryPort, never()).clone(any(), any());
        verify(this.localProjectWorkspacePort, never()).cleanupCloneAttempt(any());
    }

    @Test
    void clonePreparesStagingTargetDelegatesToGitAndFinalizes() {
        final ProjectRepositoryLink repository = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        final Path stagingTarget = Path.of("/tmp/forge-projects/project/.forge-clone-attempts/service-a-attempt");
        final Path finalTarget = Path.of("/tmp/forge-projects/project/service-a");
        final ProjectRepositoryCloneAttempt attempt = new ProjectRepositoryCloneAttempt(stagingTarget, finalTarget);
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findById(REPOSITORY_ID)).thenReturn(Optional.of(repository));
        when(this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl())).thenReturn("service-a");
        when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceStates(PROJECT_ID, List.of(new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a"))))
                .thenReturn(Map.of(REPOSITORY_ID, new ProjectRepositoryWorkspaceState(REPOSITORY_ID, finalTarget, false)));
        when(this.localProjectWorkspacePort.prepareCloneAttempt(PROJECT_ID, new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a")))
                .thenReturn(attempt);
        when(this.gitRepositoryPort.inspectLocalRepository(stagingTarget)).thenReturn(this.branchState(GitWorkingTreeState.CLEAN));

        final ProjectRepositoryView result = this.useCases.cloneRepository(PROJECT_ID, REPOSITORY_ID);

        final InOrder ordered = inOrder(this.localProjectWorkspacePort, this.gitRepositoryPort);
        ordered.verify(this.localProjectWorkspacePort).prepareCloneAttempt(PROJECT_ID, new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a"));
        ordered.verify(this.gitRepositoryPort).clone(repository.remoteUrl(), stagingTarget);
        ordered.verify(this.gitRepositoryPort).inspectLocalRepository(stagingTarget);
        ordered.verify(this.localProjectWorkspacePort).finalizeCloneAttempt(attempt);
        verify(this.gitRepositoryPort, never()).inspectLocalRepository(finalTarget);
        verify(this.repositoryLinkRepository, never()).save(any());
        assertThat(result.name()).isEqualTo("service-a");
        assertThat(result.cloned()).isTrue();
        assertThat(result.gitState()).isEqualTo(this.branchState(GitWorkingTreeState.CLEAN));
    }

    @Test
    void cloneInspectionFailureDoesNotPublishFinalRepository() {
        final ProjectRepositoryLink repository = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        final Path stagingTarget = Path.of("/tmp/forge-projects/project/.forge-clone-attempts/service-a-attempt");
        final Path finalTarget = Path.of("/tmp/forge-projects/project/service-a");
        final ProjectRepositoryCloneAttempt attempt = new ProjectRepositoryCloneAttempt(stagingTarget, finalTarget);
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findById(REPOSITORY_ID)).thenReturn(Optional.of(repository));
        when(this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl())).thenReturn("service-a");
        when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceStates(PROJECT_ID, List.of(new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a"))))
                .thenReturn(Map.of(REPOSITORY_ID, new ProjectRepositoryWorkspaceState(REPOSITORY_ID, finalTarget, false)));
        when(this.localProjectWorkspacePort.prepareCloneAttempt(PROJECT_ID, new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a")))
                .thenReturn(attempt);
        when(this.gitRepositoryPort.inspectLocalRepository(stagingTarget)).thenThrow(new GitExecutionException("inspection failed"));

        assertThatThrownBy(() -> this.useCases.cloneRepository(PROJECT_ID, REPOSITORY_ID))
                .isInstanceOf(InfrastructureExecutionException.class)
                .hasMessage("Project repository clone failed.");

        final InOrder ordered = inOrder(this.gitRepositoryPort, this.localProjectWorkspacePort);
        ordered.verify(this.localProjectWorkspacePort).prepareCloneAttempt(PROJECT_ID, new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a"));
        ordered.verify(this.gitRepositoryPort).clone(repository.remoteUrl(), stagingTarget);
        ordered.verify(this.gitRepositoryPort).inspectLocalRepository(stagingTarget);
        ordered.verify(this.localProjectWorkspacePort).cleanupCloneAttempt(attempt);
        verify(this.localProjectWorkspacePort, never()).finalizeCloneAttempt(attempt);
        verify(this.gitRepositoryPort, never()).inspectLocalRepository(finalTarget);
        verify(this.repositoryLinkRepository, never()).save(any());
    }

    @Test
    void cloneFailureDoesNotCreatePersistedCloneState() {
        final ProjectRepositoryLink repository = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        final Path stagingTarget = Path.of("/tmp/forge-projects/project/.forge-clone-attempts/service-a-attempt");
        final Path finalTarget = Path.of("/tmp/forge-projects/project/service-a");
        final ProjectRepositoryCloneAttempt attempt = new ProjectRepositoryCloneAttempt(stagingTarget, finalTarget);
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findById(REPOSITORY_ID)).thenReturn(Optional.of(repository));
        when(this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl())).thenReturn("service-a");
        when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceStates(PROJECT_ID, List.of(new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a"))))
                .thenReturn(Map.of(REPOSITORY_ID, new ProjectRepositoryWorkspaceState(REPOSITORY_ID, finalTarget, false)));
        when(this.localProjectWorkspacePort.prepareCloneAttempt(PROJECT_ID, new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a")))
                .thenReturn(attempt);
        org.mockito.Mockito.doThrow(new GitExecutionException("clone failed"))
                .when(this.gitRepositoryPort).clone(repository.remoteUrl(), stagingTarget);

        assertThatThrownBy(() -> this.useCases.cloneRepository(PROJECT_ID, REPOSITORY_ID))
                .isInstanceOf(InfrastructureExecutionException.class)
                .hasMessage("Project repository clone failed.");

        verify(this.localProjectWorkspacePort).cleanupCloneAttempt(attempt);
        verify(this.localProjectWorkspacePort, never()).finalizeCloneAttempt(attempt);
        verify(this.repositoryLinkRepository, never()).save(any());
    }

    @Test
    void cloneCleanupFailureIsInfrastructureFailure() {
        final ProjectRepositoryLink repository = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        final Path stagingTarget = Path.of("/tmp/forge-projects/project/.forge-clone-attempts/service-a-attempt");
        final Path finalTarget = Path.of("/tmp/forge-projects/project/service-a");
        final ProjectRepositoryCloneAttempt attempt = new ProjectRepositoryCloneAttempt(stagingTarget, finalTarget);
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findById(REPOSITORY_ID)).thenReturn(Optional.of(repository));
        when(this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl())).thenReturn("service-a");
        when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceStates(PROJECT_ID, List.of(new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a"))))
                .thenReturn(Map.of(REPOSITORY_ID, new ProjectRepositoryWorkspaceState(REPOSITORY_ID, finalTarget, false)));
        when(this.localProjectWorkspacePort.prepareCloneAttempt(PROJECT_ID, new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a")))
                .thenReturn(attempt);
        org.mockito.Mockito.doThrow(new GitExecutionException("clone failed"))
                .when(this.gitRepositoryPort).clone(repository.remoteUrl(), stagingTarget);
        org.mockito.Mockito.doThrow(new LocalProjectWorkspaceException("cleanup failed"))
                .when(this.localProjectWorkspacePort).cleanupCloneAttempt(attempt);

        assertThatThrownBy(() -> this.useCases.cloneRepository(PROJECT_ID, REPOSITORY_ID))
                .isInstanceOf(InfrastructureExecutionException.class)
                .hasMessage("Project repository clone cleanup failed.");

        verify(this.repositoryLinkRepository, never()).save(any());
    }

    @Test
    void localWorkspaceFailureIsInfrastructureFailure() {
        final ProjectRepositoryLink repository = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findById(REPOSITORY_ID)).thenReturn(Optional.of(repository));
        when(this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl())).thenReturn("service-a");
        when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceStates(PROJECT_ID, List.of(new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a"))))
                .thenThrow(new LocalProjectWorkspaceException("root missing"));

        assertThatThrownBy(() -> this.useCases.cloneRepository(PROJECT_ID, REPOSITORY_ID))
                .isInstanceOf(InfrastructureExecutionException.class)
                .hasMessage("Project repository workspace operation failed.");

        verify(this.gitRepositoryPort, never()).clone(any(), any());
        verify(this.repositoryLinkRepository, never()).save(any());
    }

    @Test
    void pullRejectsUnclonedRepositoryBeforeGitMutation() {
        final ProjectRepositoryLink repository = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        final Path repositoryPath = Path.of("/tmp/forge-projects/project/service-a");
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findById(REPOSITORY_ID)).thenReturn(Optional.of(repository));
        when(this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl())).thenReturn("service-a");
        when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceState(PROJECT_ID, new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a")))
                .thenReturn(new ProjectRepositoryWorkspaceState(REPOSITORY_ID, repositoryPath, false));

        assertThatThrownBy(() -> this.useCases.pullRepository(PROJECT_ID, REPOSITORY_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Project repository is not cloned.");

        verify(this.gitRepositoryPort, never()).inspectLocalRepository(any());
        verify(this.gitRepositoryPort, never()).pullFastForward(any());
        verify(this.repositoryLinkRepository, never()).save(any());
    }

    @Test
    void pullRejectsDirtyRepositoryBeforeGitMutation() {
        final ProjectRepositoryLink repository = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        final Path repositoryPath = Path.of("/tmp/forge-projects/project/service-a");
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findById(REPOSITORY_ID)).thenReturn(Optional.of(repository));
        when(this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl())).thenReturn("service-a");
        when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceState(PROJECT_ID, new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a")))
                .thenReturn(new ProjectRepositoryWorkspaceState(REPOSITORY_ID, repositoryPath, true));
        when(this.gitRepositoryPort.inspectLocalRepository(repositoryPath)).thenReturn(this.branchState(GitWorkingTreeState.DIRTY));

        assertThatThrownBy(() -> this.useCases.pullRepository(PROJECT_ID, REPOSITORY_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Project repository is not safe to pull.");

        verify(this.gitRepositoryPort, never()).pullFastForward(any());
        verify(this.repositoryLinkRepository, never()).save(any());
    }

    @Test
    void pullUsesManagedWorkspaceAndReturnsRefreshedState() {
        final ProjectRepositoryLink repository = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        final Path repositoryPath = Path.of("/tmp/forge-projects/project/service-a");
        final GitLocalRepositoryState finalState = GitLocalRepositoryState.valid(
                new GitHeadState(GitHeadType.BRANCH, "main", "fedcba"),
                GitWorkingTreeState.CLEAN,
                GitConflictState.NONE,
                GitOperationState.NORMAL,
                new GitUpstreamState("origin/main", GitUpstreamRelation.UP_TO_DATE)
        );
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findById(REPOSITORY_ID)).thenReturn(Optional.of(repository));
        when(this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl())).thenReturn("service-a");
        when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceState(PROJECT_ID, new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a")))
                .thenReturn(new ProjectRepositoryWorkspaceState(REPOSITORY_ID, repositoryPath, true));
        when(this.gitRepositoryPort.inspectLocalRepository(repositoryPath)).thenReturn(this.branchState(GitWorkingTreeState.CLEAN, GitUpstreamRelation.BEHIND));
        when(this.gitRepositoryPort.pullFastForward(repositoryPath)).thenReturn(finalState);

        final ProjectRepositoryView result = this.useCases.pullRepository(PROJECT_ID, REPOSITORY_ID);

        final InOrder ordered = inOrder(this.localProjectWorkspacePort, this.gitRepositoryPort);
        ordered.verify(this.localProjectWorkspacePort).resolveRepositoryWorkspaceState(PROJECT_ID, new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a"));
        ordered.verify(this.gitRepositoryPort).inspectLocalRepository(repositoryPath);
        ordered.verify(this.gitRepositoryPort).pullFastForward(repositoryPath);
        assertThat(result.gitState()).isEqualTo(finalState);
        verify(this.repositoryLinkRepository, never()).save(any());
    }

    @Test
    void pullGitFailureMapsToInfrastructureFailure() {
        final ProjectRepositoryLink repository = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        final Path repositoryPath = Path.of("/tmp/forge-projects/project/service-a");
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findById(REPOSITORY_ID)).thenReturn(Optional.of(repository));
        when(this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl())).thenReturn("service-a");
        when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceState(PROJECT_ID, new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a")))
                .thenReturn(new ProjectRepositoryWorkspaceState(REPOSITORY_ID, repositoryPath, true));
        when(this.gitRepositoryPort.inspectLocalRepository(repositoryPath)).thenReturn(this.branchState(GitWorkingTreeState.CLEAN, GitUpstreamRelation.BEHIND));
        when(this.gitRepositoryPort.pullFastForward(repositoryPath)).thenThrow(new GitExecutionException("fetch failed"));

        assertThatThrownBy(() -> this.useCases.pullRepository(PROJECT_ID, REPOSITORY_ID))
                .isInstanceOf(InfrastructureExecutionException.class)
                .hasMessage("Project repository pull failed.");

        verify(this.repositoryLinkRepository, never()).save(any());
    }

    @Test
    void checkUpdatesUsesManagedWorkspaceAndReturnsRefreshedState() {
        final ProjectRepositoryLink repository = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        final Path repositoryPath = Path.of("/tmp/forge-projects/project/service-a");
        final GitLocalRepositoryState finalState = this.branchState(GitWorkingTreeState.CLEAN, GitUpstreamRelation.BEHIND);
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findById(REPOSITORY_ID)).thenReturn(Optional.of(repository));
        when(this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl())).thenReturn("service-a");
        when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceState(PROJECT_ID, new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a")))
                .thenReturn(new ProjectRepositoryWorkspaceState(REPOSITORY_ID, repositoryPath, true));
        when(this.gitRepositoryPort.inspectLocalRepository(repositoryPath)).thenReturn(this.branchState(GitWorkingTreeState.CLEAN));
        when(this.gitRepositoryPort.checkUpdates(repositoryPath)).thenReturn(finalState);

        final ProjectRepositoryView result = this.useCases.checkRepositoryUpdates(PROJECT_ID, REPOSITORY_ID);

        final InOrder ordered = inOrder(this.localProjectWorkspacePort, this.gitRepositoryPort);
        ordered.verify(this.localProjectWorkspacePort).resolveRepositoryWorkspaceState(PROJECT_ID, new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a"));
        ordered.verify(this.gitRepositoryPort).inspectLocalRepository(repositoryPath);
        ordered.verify(this.gitRepositoryPort).checkUpdates(repositoryPath);
        assertThat(result.gitState()).isEqualTo(finalState);
        verify(this.gitRepositoryPort, never()).pullFastForward(any());
        verify(this.repositoryLinkRepository, never()).save(any());
    }

    @Test
    void checkUpdatesRejectsUnsafeRepositoryBeforeGitFetch() {
        final ProjectRepositoryLink repository = this.repositoryLink(REPOSITORY_ID, "git@gitlab.com:company/service-a.git");
        final Path repositoryPath = Path.of("/tmp/forge-projects/project/service-a");
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));
        when(this.repositoryLinkRepository.findById(REPOSITORY_ID)).thenReturn(Optional.of(repository));
        when(this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl())).thenReturn("service-a");
        when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceState(PROJECT_ID, new ProjectRepositoryWorkspaceReference(REPOSITORY_ID, "service-a")))
                .thenReturn(new ProjectRepositoryWorkspaceState(REPOSITORY_ID, repositoryPath, true));
        when(this.gitRepositoryPort.inspectLocalRepository(repositoryPath)).thenReturn(this.branchState(GitWorkingTreeState.DIRTY));

        assertThatThrownBy(() -> this.useCases.checkRepositoryUpdates(PROJECT_ID, REPOSITORY_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Project repository is not safe to check for updates.");

        verify(this.gitRepositoryPort, never()).checkUpdates(any());
        verify(this.gitRepositoryPort, never()).pullFastForward(any());
        verify(this.repositoryLinkRepository, never()).save(any());
    }

    @Test
    void rejectsBlankRemoteUrl() {
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(this.project()));

        assertThatThrownBy(() -> this.useCases.importRepository(PROJECT_ID, new ImportProjectRepositoryCommand("  ")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Repository URL is required.");

        verify(this.gitRepositoryPort, never()).inspectRemote(any());
        verify(this.repositoryLinkRepository, never()).save(any());
    }

    @Test
    void rejectsMissingProject() {
        when(this.projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.useCases.importRepository(PROJECT_ID, new ImportProjectRepositoryCommand("git@example.com:repo.git")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Project was not found.");

        verify(this.gitRepositoryPort, never()).inspectRemote(any());
        verify(this.repositoryLinkRepository, never()).save(any());
    }

    private Project project() {
        return new Project(PROJECT_ID, "Sitionix", "sitionix", NOW, NOW);
    }

    private ProjectRepositoryLink repositoryLink(final UUID repositoryId, final String remoteUrl) {
        return new ProjectRepositoryLink(repositoryId, PROJECT_ID, remoteUrl, NOW);
    }

    private GitLocalRepositoryState branchState(final GitWorkingTreeState workingTreeState) {
        return this.branchState(workingTreeState, GitUpstreamRelation.UP_TO_DATE);
    }

    private GitLocalRepositoryState branchState(final GitWorkingTreeState workingTreeState, final GitUpstreamRelation relation) {
        return GitLocalRepositoryState.valid(
                new GitHeadState(GitHeadType.BRANCH, "main", "abcdef"),
                workingTreeState,
                GitConflictState.NONE,
                GitOperationState.NORMAL,
                new GitUpstreamState("origin/main", relation)
        );
    }
}
