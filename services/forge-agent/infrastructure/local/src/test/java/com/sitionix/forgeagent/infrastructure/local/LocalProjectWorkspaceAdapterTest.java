package com.sitionix.forgeagent.infrastructure.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeagent.domain.model.ProjectRepositoryCloneTarget;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceReference;
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
    void resolvesForgeProjectTargetPath() {
        final ProjectRepositoryCloneTarget target = this.adapter.resolveCloneTarget(
                PROJECT_ID,
                new ProjectRepositoryWorkspaceReference(REPOSITORY_A_ID, "service-a")
        );

        assertThat(target.path()).isEqualTo(this.forgeRoot.resolve("forge-projects").resolve(PROJECT_ID.toString()).resolve("service-a"));
    }

    @Test
    void createsProjectWorkspaceWhenRequired() {
        this.adapter.ensureProjectWorkspace(PROJECT_ID);

        assertThat(this.forgeRoot.resolve("forge-projects").resolve(PROJECT_ID.toString())).isDirectory();
    }

    @Test
    void resolvesMultipleCloneStatesInBatch() throws Exception {
        Files.createDirectories(this.repositoryPath("service-a").resolve(".git"));
        Files.createDirectories(this.repositoryPath("service-b"));

        final Map<UUID, Boolean> states = this.adapter.resolveCloneStates(PROJECT_ID, List.of(
                new ProjectRepositoryWorkspaceReference(REPOSITORY_A_ID, "service-a"),
                new ProjectRepositoryWorkspaceReference(REPOSITORY_B_ID, "service-b")
        ));

        assertThat(states).containsEntry(REPOSITORY_A_ID, true);
        assertThat(states).containsEntry(REPOSITORY_B_ID, false);
    }

    @Test
    void nonexistentRepositoryIsNotCloned() {
        final Map<UUID, Boolean> states = this.adapter.resolveCloneStates(PROJECT_ID, List.of(
                new ProjectRepositoryWorkspaceReference(REPOSITORY_A_ID, "service-a")
        ));

        assertThat(states).containsEntry(REPOSITORY_A_ID, false);
    }

    private Path repositoryPath(final String repositoryName) {
        return this.forgeRoot.resolve("forge-projects").resolve(PROJECT_ID.toString()).resolve(repositoryName);
    }
}
