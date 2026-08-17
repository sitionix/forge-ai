package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.model.ProjectRepositoryLink;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectRepositoryEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataProjectRepositoryLinkRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostgresProjectRepositoryLinkRepositoryTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID REPOSITORY_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant CREATED = Instant.parse("2026-08-17T09:00:00Z");

    @Mock
    private SpringDataProjectRepositoryLinkRepository springRepository;

    private PostgresProjectRepositoryLinkRepository repository;

    @BeforeEach
    void setUp() {
        this.repository = new PostgresProjectRepositoryLinkRepository(this.springRepository);
    }

    @Test
    void savesRepositoryFields() {
        when(this.springRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        final ProjectRepositoryLink saved = this.repository.save(this.repositoryLink(REPOSITORY_ID));

        assertThat(saved).isEqualTo(this.repositoryLink(REPOSITORY_ID));
        final ArgumentCaptor<ProjectRepositoryEntity> captor = ArgumentCaptor.forClass(ProjectRepositoryEntity.class);
        verify(this.springRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(REPOSITORY_ID);
        assertThat(captor.getValue().getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(captor.getValue().getRemoteUrl()).isEqualTo("git@gitlab.com:company/service-a.git");
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(CREATED);
    }

    @Test
    void listsRepositoriesUsingDeterministicProjectOrderRepositoryMethod() {
        final UUID secondRepositoryId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        when(this.springRepository.findByProjectIdOrderByCreatedAtAscIdAsc(PROJECT_ID)).thenReturn(List.of(
                this.entity(REPOSITORY_ID),
                this.entity(secondRepositoryId)
        ));

        final List<ProjectRepositoryLink> repositories = this.repository.findByProjectId(PROJECT_ID);

        assertThat(repositories).extracting(ProjectRepositoryLink::id).containsExactly(REPOSITORY_ID, secondRepositoryId);
        verify(this.springRepository).findByProjectIdOrderByCreatedAtAscIdAsc(PROJECT_ID);
    }

    private ProjectRepositoryLink repositoryLink(final UUID repositoryId) {
        return new ProjectRepositoryLink(repositoryId, PROJECT_ID, "git@gitlab.com:company/service-a.git", CREATED);
    }

    private ProjectRepositoryEntity entity(final UUID repositoryId) {
        final ProjectRepositoryEntity entity = new ProjectRepositoryEntity();
        entity.setId(repositoryId);
        entity.setProjectId(PROJECT_ID);
        entity.setRemoteUrl("git@gitlab.com:company/service-a.git");
        entity.setCreatedAt(CREATED);
        return entity;
    }
}
