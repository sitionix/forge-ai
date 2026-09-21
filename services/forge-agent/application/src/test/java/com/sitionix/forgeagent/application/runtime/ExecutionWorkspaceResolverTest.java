package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.sitionix.forgeagent.domain.model.ProjectRepositoryLink;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceState;
import com.sitionix.forgeagent.domain.port.GitRepositoryPort;
import com.sitionix.forgeagent.domain.port.LocalProjectWorkspacePort;
import com.sitionix.forgeagent.domain.port.ProjectRepositoryLinkRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionWorkspaceResolverTest {

    private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID REPOSITORY_A_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID REPOSITORY_B_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID FOREIGN_REPOSITORY_ID = UUID.fromString("20000000-0000-4000-8000-000000000003");
    private static final Path PROJECT_WORKSPACE = Path.of("/forge/forge-projects").resolve(PROJECT_ID.toString());
    private static final Path REPOSITORY_A = PROJECT_WORKSPACE.resolve("backend");
    private static final Path REPOSITORY_B = PROJECT_WORKSPACE.resolve("frontend");

    @Mock
    private ProjectRepositoryLinkRepository repositoryLinkRepository;
    @Mock
    private LocalProjectWorkspacePort localProjectWorkspacePort;
    @Mock
    private GitRepositoryPort gitRepositoryPort;

    @Mock
    private com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository graphRepository;
    private ExecutionWorkspaceResolver resolver;
    private final Map<UUID, ProjectRepositoryWorkspaceState> states = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        this.resolver = new ExecutionWorkspaceResolver(
                this.repositoryLinkRepository,
                this.localProjectWorkspacePort,
                this.gitRepositoryPort,
                this.graphRepository
        );
        lenient().when(this.localProjectWorkspacePort.resolveProjectWorkspace(PROJECT_ID)).thenReturn(PROJECT_WORKSPACE);
        lenient().when(this.repositoryLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(
                this.repository(REPOSITORY_A_ID, PROJECT_ID, "git@example/backend.git"),
                this.repository(REPOSITORY_B_ID, PROJECT_ID, "git@example/frontend.git")
        ));
        lenient().when(this.gitRepositoryPort.resolveRepositoryName("git@example/backend.git")).thenReturn("backend");
        lenient().when(this.gitRepositoryPort.resolveRepositoryName("git@example/frontend.git")).thenReturn("frontend");
        this.states.put(REPOSITORY_A_ID, new ProjectRepositoryWorkspaceState(REPOSITORY_A_ID, REPOSITORY_A, true));
        this.states.put(REPOSITORY_B_ID, new ProjectRepositoryWorkspaceState(REPOSITORY_B_ID, REPOSITORY_B, true));
        lenient().when(this.localProjectWorkspacePort.resolveRepositoryWorkspaceStates(eq(PROJECT_ID), any()))
                .thenAnswer(invocation -> Map.copyOf(this.states));
    }

    @Test
    void perScopeResolvesOnlyExactTargetRepository() {
        assertThat(this.resolver.resolve(PROJECT_ID, REPOSITORY_A_ID, List.of(REPOSITORY_A_ID, REPOSITORY_B_ID)))
                .isEqualTo(new ExecutionWorkspace(REPOSITORY_A, List.of(REPOSITORY_A)));
        assertThat(this.resolver.resolve(PROJECT_ID, REPOSITORY_B_ID, List.of(REPOSITORY_A_ID, REPOSITORY_B_ID)))
                .isEqualTo(new ExecutionWorkspace(REPOSITORY_B, List.of(REPOSITORY_B)));
    }

    @Test
    void globalUsesProjectCwdAndPreservesSnapshotRootOrder() {
        final ExecutionWorkspace workspace = this.resolver.resolve(
                PROJECT_ID, null, List.of(REPOSITORY_B_ID, REPOSITORY_A_ID));

        assertThat(workspace.cwd()).isEqualTo(PROJECT_WORKSPACE);
        assertThat(workspace.workspaceRoots()).containsExactly(REPOSITORY_B, REPOSITORY_A);
        assertThat(workspace.cwd()).isNotIn(REPOSITORY_A, REPOSITORY_B);
    }

    @Test
    void globalWithOneRepositoryStillUsesProjectCwd() {
        assertThat(this.resolver.resolve(PROJECT_ID, null, List.of(REPOSITORY_A_ID)))
                .isEqualTo(new ExecutionWorkspace(PROJECT_WORKSPACE, List.of(REPOSITORY_A)));
    }

    @Test
    void globalWithoutRepositoriesUsesExplicitProjectWorkspace() {
        assertThat(this.resolver.resolve(PROJECT_ID, null, List.of()))
                .isEqualTo(new ExecutionWorkspace(PROJECT_WORKSPACE, List.of()));
    }

    @Test
    void missingSelectedCheckoutFailsClosed() {
        this.states.put(REPOSITORY_B_ID, new ProjectRepositoryWorkspaceState(REPOSITORY_B_ID, REPOSITORY_B, false));

        assertThatThrownBy(() -> this.resolver.resolve(
                PROJECT_ID, null, List.of(REPOSITORY_A_ID, REPOSITORY_B_ID)))
                .isInstanceOf(ExecutionWorkspaceException.class)
                .hasMessage("Required Forge repository checkout is unavailable.");
    }

    @Test
    void repositoryFromAnotherProjectFailsClosed() {
        lenient().when(this.repositoryLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(
                this.repository(REPOSITORY_A_ID, PROJECT_ID, "git@example/backend.git")
        ));

        assertThatThrownBy(() -> this.resolver.resolve(
                PROJECT_ID, FOREIGN_REPOSITORY_ID, List.of(FOREIGN_REPOSITORY_ID)))
                .isInstanceOf(ExecutionWorkspaceException.class)
                .hasMessage("Workflow repository does not belong to the owning project.");
    }

    @Test
    void scopedRepositoryOutsideSnapshotFailsClosed() {
        assertThatThrownBy(() -> this.resolver.resolve(
                PROJECT_ID, REPOSITORY_B_ID, List.of(REPOSITORY_A_ID)))
                .isInstanceOf(ExecutionWorkspaceException.class)
                .hasMessage("Scoped repository is not present in the workflow repository snapshot.");
    }

    @Test
    void repeatedAndConcurrentResolutionCannotLeakRepositoryIdentity() throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            final var a = executor.submit(() -> this.resolver.resolve(
                    PROJECT_ID, REPOSITORY_A_ID, List.of(REPOSITORY_A_ID, REPOSITORY_B_ID)));
            final var b = executor.submit(() -> this.resolver.resolve(
                    PROJECT_ID, REPOSITORY_B_ID, List.of(REPOSITORY_A_ID, REPOSITORY_B_ID)));

            assertThat(a.get()).isEqualTo(new ExecutionWorkspace(REPOSITORY_A, List.of(REPOSITORY_A)));
            assertThat(b.get()).isEqualTo(new ExecutionWorkspace(REPOSITORY_B, List.of(REPOSITORY_B)));
        }
    }

    @Test
    void persistedNodeWorkspaceWinsOverTaskScopeIncludingExplicitEmptySnapshot() {
        final var run = workflowRun();
        final var invocation = invocation(run.id());
        when(this.graphRepository.findNode(run.id(), invocation.sourceNodeId()))
                .thenReturn(java.util.Optional.of(snapshot(run.id(), invocation.sourceNodeId(), List.of(REPOSITORY_B_ID))));
        assertThat(this.resolver.resolve(run, invocation).workspaceRoots()).containsExactly(REPOSITORY_B);
        when(this.graphRepository.findNode(run.id(), invocation.sourceNodeId()))
                .thenReturn(java.util.Optional.of(snapshot(run.id(), invocation.sourceNodeId(), List.of())));
        assertThat(this.resolver.resolve(run, invocation).workspaceRoots()).isEmpty();
    }

    @Test
    void missingSnapshotFailsInsteadOfFallingBackToTaskRepositories() {
        final var run = workflowRun();
        assertThatThrownBy(() -> this.resolver.resolve(run, invocation(run.id())))
                .isInstanceOf(ExecutionWorkspaceException.class).hasMessage("Snapshotted workflow node is unavailable.");
    }

    @Test
    void missingRequiredSnapshotCheckoutFailsEvenWhenTaskCheckoutsAreAvailable() {
        final var run = workflowRun();
        final var invocation = invocation(run.id());
        when(this.graphRepository.findNode(run.id(), invocation.sourceNodeId()))
                .thenReturn(java.util.Optional.of(snapshot(run.id(), invocation.sourceNodeId(), List.of(REPOSITORY_B_ID))));
        this.states.put(REPOSITORY_B_ID, new ProjectRepositoryWorkspaceState(REPOSITORY_B_ID, REPOSITORY_B, false));
        assertThatThrownBy(() -> this.resolver.resolve(run, invocation))
                .isInstanceOf(ExecutionWorkspaceException.class).hasMessage("Required Forge repository checkout is unavailable.");
    }

    private com.sitionix.forgeagent.domain.model.WorkflowRun workflowRun() {
        return new com.sitionix.forgeagent.domain.model.WorkflowRun(UUID.randomUUID(), PROJECT_ID, UUID.randomUUID(), null,
                "wf", "input", com.sitionix.forgeagent.domain.model.WorkflowRunStatus.QUEUED,
                List.of(), List.of(), List.of(), null, null, null, Instant.EPOCH, null, null, List.of(REPOSITORY_A_ID));
    }

    private com.sitionix.forgeagent.domain.model.NodeRun invocation(UUID runId) {
        return new com.sitionix.forgeagent.domain.model.NodeRun(UUID.randomUUID(), runId, UUID.randomUUID(), UUID.randomUUID(),
                "agent", "instructions", null, com.sitionix.forgeagent.domain.model.NodeInputMode.DEPENDENCIES_ONLY,
                new com.sitionix.forgeagent.domain.model.NodePosition(0,0), UUID.randomUUID(), null, null, null, null,
                com.sitionix.forgeagent.domain.model.NodeRunStatus.PENDING, null, null, null, Instant.EPOCH, null, null, null);
    }

    private com.sitionix.forgeagent.domain.model.RunNode snapshot(UUID runId, UUID nodeId, List<UUID> ids) {
        return new com.sitionix.forgeagent.domain.model.RunNode(runId, nodeId, UUID.randomUUID(), "agent", "instructions", null,
                null, com.sitionix.forgeagent.domain.model.NodeInputMode.DEPENDENCIES_ONLY,
                new com.sitionix.forgeagent.domain.model.NodePosition(0,0), com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL,
                com.sitionix.forgeagent.domain.model.NodeContextMode.FRESH_EACH_NODE_RUN, null,
                com.sitionix.forgeagent.domain.model.NodeType.AGENT, ids);
    }

    private ProjectRepositoryLink repository(final UUID id, final UUID projectId, final String remoteUrl) {
        return new ProjectRepositoryLink(id, projectId, remoteUrl, Instant.EPOCH);
    }
}
