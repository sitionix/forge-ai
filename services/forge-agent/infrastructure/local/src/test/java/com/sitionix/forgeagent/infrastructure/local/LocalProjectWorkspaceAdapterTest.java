package com.sitionix.forgeagent.infrastructure.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.model.ProjectRepositoryCloneAttempt;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceState;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceReference;
import com.sitionix.forgeagent.domain.port.LocalProjectWorkspaceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalProjectWorkspaceAdapterTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID REPOSITORY_A_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID REPOSITORY_B_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @TempDir
    private Path forgeRoot;

    private LocalProjectWorkspaceAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(this.forgeRoot.resolve(".git"));
        this.adapter = new LocalProjectWorkspaceAdapter(new ForgeRootResolver(this.forgeRoot.resolve("services/forge-agent")));
    }

    @Test
    void preparesUniqueManagedStagingTargetAndFinalTarget() {
        final ProjectRepositoryCloneAttempt firstAttempt = this.adapter.prepareCloneAttempt(
                PROJECT_ID,
                new ProjectRepositoryWorkspaceReference(REPOSITORY_A_ID, "service-a")
        );
        final ProjectRepositoryCloneAttempt secondAttempt = this.adapter.prepareCloneAttempt(
                PROJECT_ID,
                new ProjectRepositoryWorkspaceReference(REPOSITORY_A_ID, "service-a")
        );

        final Path projectWorkspace = this.forgeRoot.resolve("forge-projects").resolve(PROJECT_ID.toString());
        assertThat(firstAttempt.finalPath()).isEqualTo(projectWorkspace.resolve("service-a"));
        assertThat(firstAttempt.stagingPath()).isDirectory();
        assertThat(firstAttempt.stagingPath().getParent()).isEqualTo(projectWorkspace.resolve(".forge-clone-attempts"));
        assertThat(secondAttempt.stagingPath()).isNotEqualTo(firstAttempt.stagingPath());
    }

    @Test
    void prepareCreatesProjectWorkspaceWhenRequired() {
        this.adapter.prepareCloneAttempt(PROJECT_ID, new ProjectRepositoryWorkspaceReference(REPOSITORY_A_ID, "service-a"));

        assertThat(this.forgeRoot.resolve("forge-projects").resolve(PROJECT_ID.toString())).isDirectory();
    }

    @Test
    void resolvesMultipleCloneStatesInBatch() throws Exception {
        Files.createDirectories(this.repositoryPath("service-a").resolve(".git"));
        Files.createDirectories(this.repositoryPath("service-b"));

        final Map<UUID, ProjectRepositoryWorkspaceState> states = this.adapter.resolveRepositoryWorkspaceStates(PROJECT_ID, List.of(
                new ProjectRepositoryWorkspaceReference(REPOSITORY_A_ID, "service-a"),
                new ProjectRepositoryWorkspaceReference(REPOSITORY_B_ID, "service-b")
        ));

        assertThat(states.get(REPOSITORY_A_ID).cloned()).isTrue();
        assertThat(states.get(REPOSITORY_A_ID).path()).isEqualTo(this.repositoryPath("service-a"));
        assertThat(states.get(REPOSITORY_B_ID).cloned()).isFalse();
        assertThat(states.get(REPOSITORY_B_ID).path()).isEqualTo(this.repositoryPath("service-b"));
    }

    @Test
    void nonexistentRepositoryIsNotCloned() {
        final Map<UUID, ProjectRepositoryWorkspaceState> states = this.adapter.resolveRepositoryWorkspaceStates(PROJECT_ID, List.of(
                new ProjectRepositoryWorkspaceReference(REPOSITORY_A_ID, "service-a")
        ));

        assertThat(states.get(REPOSITORY_A_ID).cloned()).isFalse();
        assertThat(states.get(REPOSITORY_A_ID).path()).isEqualTo(this.repositoryPath("service-a"));
    }

    @Test
    void rootResolverAcceptsGitDirectory() {
        assertThat(new ForgeRootResolver(this.forgeRoot.resolve("nested/service")).resolveForgeRoot())
                .isEqualTo(this.forgeRoot);
    }

    @Test
    void rootResolverAcceptsGitFileMarker(@TempDir final Path worktreeRoot) throws Exception {
        Files.writeString(worktreeRoot.resolve(".git"), "gitdir: /tmp/worktrees/forge-ai/.git");

        assertThat(new ForgeRootResolver(worktreeRoot.resolve("services/forge-agent")).resolveForgeRoot())
                .isEqualTo(worktreeRoot);
    }

    @Test
    void rootResolverFailsClosedWhenNoForgeRoot(@TempDir final Path noRoot) {
        assertThatThrownBy(() -> new ForgeRootResolver(noRoot.resolve("nested")).resolveForgeRoot())
                .isInstanceOf(LocalProjectWorkspaceException.class)
                .hasMessage("Forge root could not be resolved.");
    }

    @Test
    void workspaceCreationFailureUsesTypedException() throws Exception {
        Files.writeString(this.forgeRoot.resolve("forge-projects"), "not a directory");

        assertThatThrownBy(() -> this.adapter.prepareCloneAttempt(
                PROJECT_ID,
                new ProjectRepositoryWorkspaceReference(REPOSITORY_A_ID, "service-a")
        ))
                .isInstanceOf(LocalProjectWorkspaceException.class)
                .hasMessage("Failed to prepare Forge repository clone attempt.");
    }

    @Test
    void cloneTargetOutsideWorkspaceUsesTypedException() {
        assertThatThrownBy(() -> this.adapter.prepareCloneAttempt(
                PROJECT_ID,
                new ProjectRepositoryWorkspaceReference(REPOSITORY_A_ID, "../service-a")
        ))
                .isInstanceOf(LocalProjectWorkspaceException.class)
                .hasMessage("Repository name resolves outside Forge project workspace.");
    }

    @Test
    void finalRepositoryBecomesClonedOnlyAfterFinalization() throws Exception {
        final ProjectRepositoryWorkspaceReference reference = new ProjectRepositoryWorkspaceReference(REPOSITORY_A_ID, "service-a");
        final ProjectRepositoryCloneAttempt attempt = this.adapter.prepareCloneAttempt(PROJECT_ID, reference);
        Files.createDirectories(attempt.stagingPath().resolve(".git"));

        assertThat(this.adapter.resolveRepositoryWorkspaceStates(PROJECT_ID, List.of(reference)).get(REPOSITORY_A_ID).cloned()).isFalse();

        this.adapter.finalizeCloneAttempt(attempt);

        assertThat(attempt.stagingPath()).doesNotExist();
        assertThat(attempt.finalPath()).isDirectory();
        assertThat(this.adapter.resolveRepositoryWorkspaceStates(PROJECT_ID, List.of(reference)).get(REPOSITORY_A_ID).cloned()).isTrue();
    }

    @Test
    void cleanupRemovesOnlyIncompleteStagingAttempt() throws Exception {
        final ProjectRepositoryWorkspaceReference reference = new ProjectRepositoryWorkspaceReference(REPOSITORY_A_ID, "service-a");
        final ProjectRepositoryCloneAttempt attempt = this.adapter.prepareCloneAttempt(PROJECT_ID, reference);
        Files.writeString(attempt.stagingPath().resolve("partial"), "partial clone");

        this.adapter.cleanupCloneAttempt(attempt);

        assertThat(attempt.stagingPath()).doesNotExist();
        assertThat(attempt.finalPath()).doesNotExist();
        assertThat(this.adapter.resolveRepositoryWorkspaceStates(PROJECT_ID, List.of(reference)).get(REPOSITORY_A_ID).cloned()).isFalse();
    }

    @Test
    void cleanupRemovesIncompleteStagingAttemptEvenWhenGitDirectoryExists() throws Exception {
        final ProjectRepositoryCloneAttempt attempt = this.adapter.prepareCloneAttempt(
                PROJECT_ID,
                new ProjectRepositoryWorkspaceReference(REPOSITORY_A_ID, "service-a")
        );
        Files.createDirectories(attempt.stagingPath().resolve(".git"));

        this.adapter.cleanupCloneAttempt(attempt);

        assertThat(attempt.stagingPath()).doesNotExist();
    }

    @Test
    void cleanupDoesNotDeleteExistingFinalClone() throws Exception {
        final ProjectRepositoryCloneAttempt attempt = this.adapter.prepareCloneAttempt(
                PROJECT_ID,
                new ProjectRepositoryWorkspaceReference(REPOSITORY_A_ID, "service-a")
        );
        Files.createDirectories(attempt.finalPath().resolve(".git"));
        Files.createDirectories(attempt.stagingPath().resolve(".git"));

        this.adapter.cleanupCloneAttempt(attempt);

        assertThat(attempt.stagingPath()).doesNotExist();
        assertThat(attempt.finalPath().resolve(".git")).isDirectory();
    }

    @Test
    void concurrentCloneAttemptCannotReplaceAlreadyFinalizedRepository() throws Exception {
        final ProjectRepositoryWorkspaceReference reference = new ProjectRepositoryWorkspaceReference(REPOSITORY_A_ID, "service-a");
        final ProjectRepositoryCloneAttempt firstAttempt = this.adapter.prepareCloneAttempt(PROJECT_ID, reference);
        final ProjectRepositoryCloneAttempt secondAttempt = this.adapter.prepareCloneAttempt(PROJECT_ID, reference);
        Files.createDirectories(firstAttempt.stagingPath().resolve(".git"));
        Files.createDirectories(secondAttempt.stagingPath().resolve(".git"));

        this.adapter.finalizeCloneAttempt(firstAttempt);

        assertThatThrownBy(() -> this.adapter.finalizeCloneAttempt(secondAttempt))
                .isInstanceOf(LocalProjectWorkspaceException.class)
                .hasMessage("Forge repository clone target already exists.");
        assertThat(firstAttempt.finalPath().resolve(".git")).isDirectory();
        assertThat(secondAttempt.stagingPath().resolve(".git")).isDirectory();
    }

    private Path repositoryPath(final String repositoryName) {
        return this.forgeRoot.resolve("forge-projects").resolve(PROJECT_ID.toString()).resolve(repositoryName);
    }
}
