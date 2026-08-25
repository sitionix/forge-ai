package com.sitionix.forgeagent.it.tests;

import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.CLONE_PROJECT_REPOSITORY;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.DELETE_PROJECT;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.IMPORT_PROJECT_REPOSITORY;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.IMPORT_PROJECT_REPOSITORY_ERROR;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.LIST_PROJECT_REPOSITORIES;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.PULL_PROJECT_REPOSITORY;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.PULL_PROJECT_REPOSITORY_ERROR;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.REFRESH_PROJECT_REPOSITORY;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.REFRESH_PROJECT_REPOSITORY_ERROR;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectRepositoryEntity;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

@IntegrationTest
class ForgeAgentProjectRepositoryIT {

    private static final UUID PROJECT_ALPHA_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

    @Autowired
    private ForgeAgentTestManager forgeIt;

    @BeforeEach
    void cleanLocalWorkspace() throws IOException {
        this.deleteRecursively(this.forgeRoot().resolve("forge-projects").resolve(PROJECT_ALPHA_ID.toString()));
    }

    @Test
    void givenProject_whenImportRepositories_thenRepositoriesArePersistedAndListed() {
        this.seedProject();

        this.forgeIt.mockMvc()
                .ping(IMPORT_PROJECT_REPOSITORY)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestImportProjectRepository.json")
                .expectStatus(HttpStatus.CREATED)
                .expectResponse("responseImportProjectRepository.json", "id", "createdAt")
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(IMPORT_PROJECT_REPOSITORY)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestImportSecondProjectRepository.json")
                .expectStatus(HttpStatus.CREATED)
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(ProjectRepositoryEntity.class).getAll())
                .extracting(ProjectRepositoryEntity::getRemoteUrl)
                .containsExactlyInAnyOrder(
                        "git@gitlab.com:company/service-a.git",
                        "https://github.com/company/service-b.git"
                );

        this.forgeIt.mockMvc()
                .ping(LIST_PROJECT_REPOSITORIES)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseListProjectRepositories.json", "id", "createdAt")
                .assertAndCreate();
    }

    @Test
    void givenBlankRemoteUrl_whenImportRepository_thenValidationErrorIsReturned() {
        this.seedProject();

        this.forgeIt.mockMvc()
                .ping(IMPORT_PROJECT_REPOSITORY_ERROR)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestImportBlankProjectRepository.json")
                .expectStatus(HttpStatus.BAD_REQUEST)
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(ProjectRepositoryEntity.class).getAll()).isEmpty();
    }

    @Test
    void givenUnreachableRemote_whenImportRepository_thenValidationErrorIsReturnedAndNothingIsPersisted() {
        this.seedProject();

        this.forgeIt.mockMvc()
                .ping(IMPORT_PROJECT_REPOSITORY_ERROR)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestImportInvalidProjectRepository.json")
                .expectStatus(HttpStatus.BAD_REQUEST)
                .expectResponse("responseUnreachableRepositoryUrlError.json")
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(ProjectRepositoryEntity.class).getAll()).isEmpty();
    }

    @Test
    void givenImportedRepository_whenCloneRepository_thenListReturnsClonedStateFromFilesystem() {
        this.seedProject();

        this.forgeIt.mockMvc()
                .ping(IMPORT_PROJECT_REPOSITORY)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestImportProjectRepository.json")
                .expectStatus(HttpStatus.CREATED)
                .assertAndCreate();

        final UUID repositoryId = this.forgeIt.postgresql().get(ProjectRepositoryEntity.class).getAll().getFirst().getId();

        this.forgeIt.mockMvc()
                .ping(CLONE_PROJECT_REPOSITORY)
                .withPathParameters(PathParams.create()
                        .add("projectId", PROJECT_ALPHA_ID)
                        .add("repositoryId", repositoryId))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseCloneProjectRepository.json", "id", "createdAt")
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(LIST_PROJECT_REPOSITORIES)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseListProjectRepositoriesCloned.json", "id", "createdAt")
                .assertAndCreate();
    }

    @Test
    void givenClonedRepository_whenPullRepository_thenTypedResponseIsReturnedAndNoGitStateIsPersisted() {
        this.seedProject();

        this.forgeIt.mockMvc()
                .ping(IMPORT_PROJECT_REPOSITORY)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestImportProjectRepository.json")
                .expectStatus(HttpStatus.CREATED)
                .assertAndCreate();

        final UUID repositoryId = this.forgeIt.postgresql().get(ProjectRepositoryEntity.class).getAll().getFirst().getId();

        this.forgeIt.mockMvc()
                .ping(CLONE_PROJECT_REPOSITORY)
                .withPathParameters(PathParams.create()
                        .add("projectId", PROJECT_ALPHA_ID)
                        .add("repositoryId", repositoryId))
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(PULL_PROJECT_REPOSITORY)
                .withPathParameters(PathParams.create()
                        .add("projectId", PROJECT_ALPHA_ID)
                        .add("repositoryId", repositoryId))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responsePullProjectRepository.json", "id", "createdAt")
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(ProjectRepositoryEntity.class).getAll())
                .singleElement()
                .satisfies(entity -> {
                    assertThat(entity.getId()).isEqualTo(repositoryId);
                    assertThat(entity.getRemoteUrl()).isEqualTo("git@gitlab.com:company/service-a.git");
                });
    }

    @Test
    void givenClonedRepository_whenRefreshRepository_thenTypedResponseIsReturnedAndNoGitStateIsPersisted() {
        this.seedProject();

        this.forgeIt.mockMvc()
                .ping(IMPORT_PROJECT_REPOSITORY)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestImportProjectRepository.json")
                .expectStatus(HttpStatus.CREATED)
                .assertAndCreate();

        final UUID repositoryId = this.forgeIt.postgresql().get(ProjectRepositoryEntity.class).getAll().getFirst().getId();

        this.forgeIt.mockMvc()
                .ping(CLONE_PROJECT_REPOSITORY)
                .withPathParameters(PathParams.create()
                        .add("projectId", PROJECT_ALPHA_ID)
                        .add("repositoryId", repositoryId))
                .expectStatus(HttpStatus.OK)
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(REFRESH_PROJECT_REPOSITORY)
                .withPathParameters(PathParams.create()
                        .add("projectId", PROJECT_ALPHA_ID)
                        .add("repositoryId", repositoryId))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseRefreshProjectRepository.json", "id", "createdAt")
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(ProjectRepositoryEntity.class).getAll())
                .singleElement()
                .satisfies(entity -> {
                    assertThat(entity.getId()).isEqualTo(repositoryId);
                    assertThat(entity.getRemoteUrl()).isEqualTo("git@gitlab.com:company/service-a.git");
                });
    }

    @Test
    void givenUnclonedRepository_whenRefreshRepository_thenConflictIsReturned() {
        this.seedProject();

        this.forgeIt.mockMvc()
                .ping(IMPORT_PROJECT_REPOSITORY)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestImportProjectRepository.json")
                .expectStatus(HttpStatus.CREATED)
                .assertAndCreate();

        final UUID repositoryId = this.forgeIt.postgresql().get(ProjectRepositoryEntity.class).getAll().getFirst().getId();

        this.forgeIt.mockMvc()
                .ping(REFRESH_PROJECT_REPOSITORY_ERROR)
                .withPathParameters(PathParams.create()
                        .add("projectId", PROJECT_ALPHA_ID)
                        .add("repositoryId", repositoryId))
                .expectStatus(HttpStatus.CONFLICT)
                .expectResponse("responseProjectRepositoryNotClonedError.json")
                .assertAndCreate();
    }

    @Test
    void givenUnclonedRepository_whenPullRepository_thenConflictIsReturned() {
        this.seedProject();

        this.forgeIt.mockMvc()
                .ping(IMPORT_PROJECT_REPOSITORY)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestImportProjectRepository.json")
                .expectStatus(HttpStatus.CREATED)
                .assertAndCreate();

        final UUID repositoryId = this.forgeIt.postgresql().get(ProjectRepositoryEntity.class).getAll().getFirst().getId();

        this.forgeIt.mockMvc()
                .ping(PULL_PROJECT_REPOSITORY_ERROR)
                .withPathParameters(PathParams.create()
                        .add("projectId", PROJECT_ALPHA_ID)
                        .add("repositoryId", repositoryId))
                .expectStatus(HttpStatus.CONFLICT)
                .expectResponse("responseProjectRepositoryNotClonedError.json")
                .assertAndCreate();
    }

    @Test
    void givenInvalidClonedCheckout_whenListRepositories_thenCheckoutRemainsClonedButGitStateIsInvalid() {
        this.seedProject();

        this.forgeIt.mockMvc()
                .ping(IMPORT_PROJECT_REPOSITORY)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestImportInvalidCheckoutProjectRepository.json")
                .expectStatus(HttpStatus.CREATED)
                .assertAndCreate();

        this.createInvalidManagedCheckout("invalid-checkout");

        this.forgeIt.mockMvc()
                .ping(LIST_PROJECT_REPOSITORIES)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseListProjectRepositoriesInvalidCheckout.json", "id", "createdAt")
                .assertAndCreate();
    }

    @Test
    void givenProjectWithRepositories_whenDeleteProject_thenRepositoriesAreDeletedByCascade() {
        this.seedProject();

        this.forgeIt.mockMvc()
                .ping(IMPORT_PROJECT_REPOSITORY)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestImportProjectRepository.json")
                .expectStatus(HttpStatus.CREATED)
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(DELETE_PROJECT)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .expectStatus(HttpStatus.NO_CONTENT)
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(ProjectRepositoryEntity.class).getAll()).isEmpty();
    }

    private void seedProject() {
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT.withJson("project_alpha.json"))
                .build();
    }

    private void createInvalidManagedCheckout(final String repositoryName) {
        try {
            final Path repositoryPath = this.forgeRoot()
                    .resolve("forge-projects")
                    .resolve(PROJECT_ALPHA_ID.toString())
                    .resolve(repositoryName);
            Files.createDirectories(repositoryPath.resolve(".git"));
            Files.writeString(repositoryPath.resolve("invalid-git-checkout"), "invalid");
        } catch (final IOException exception) {
            throw new IllegalStateException("Failed to create invalid checkout fixture.", exception);
        }
    }

    private Path forgeRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    private void deleteRecursively(final Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (final Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
