package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.model.ProjectTask;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectTaskEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataProjectTaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostgresProjectTaskRepositoryTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID WORKFLOW_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID TASK_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID REPOSITORY_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant CREATED = Instant.parse("2026-08-10T12:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-08-10T12:01:00Z");

    @Mock
    private SpringDataProjectTaskRepository taskRepository;

    private PostgresProjectTaskRepository repository;

    @BeforeEach
    void setUp() {
        this.repository = new PostgresProjectTaskRepository(this.taskRepository);
    }

    @Test
    void savesTaskFields() {
        when(this.taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        final ProjectTask saved = this.repository.save(this.task(TASK_ID, CREATED));

        assertThat(saved).isEqualTo(this.task(TASK_ID, CREATED));
        final ArgumentCaptor<ProjectTaskEntity> captor = ArgumentCaptor.forClass(ProjectTaskEntity.class);
        verify(this.taskRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(TASK_ID);
        assertThat(captor.getValue().getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(captor.getValue().getTitle()).isEqualTo("Check calculation");
        assertThat(captor.getValue().getInput()).isEqualTo("Count the letters in Sitionix.");
        assertThat(captor.getValue().getWorkflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(CREATED);
        assertThat(captor.getValue().getUpdatedAt()).isEqualTo(UPDATED);
    }

    @Test
    void findsTaskById() {
        when(this.taskRepository.findById(TASK_ID)).thenReturn(Optional.of(this.entity(TASK_ID, CREATED)));

        assertThat(this.repository.findById(TASK_ID)).contains(this.task(TASK_ID, CREATED));
    }

    @Test
    void listsProjectTasksUsingPagedDeterministicHistoryRepositoryMethod() {
        final UUID olderTaskId = UUID.fromString("33333333-3333-4333-8333-333333333332");
        final PageRequest pageRequest = PageRequest.of(1, 2, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        when(this.taskRepository.findByProjectId(PROJECT_ID, pageRequest)).thenReturn(new PageImpl<>(
                List.of(
                        this.entity(TASK_ID, Instant.parse("2026-08-10T12:01:00Z")),
                        this.entity(olderTaskId, Instant.parse("2026-08-10T12:00:00Z"))
                ),
                pageRequest,
                5
        ));

        final var tasks = this.repository.findPageByProjectId(PROJECT_ID, 1, 2);

        assertThat(tasks.items()).extracting(ProjectTask::id).containsExactly(TASK_ID, olderTaskId);
        assertThat(tasks.totalItems()).isEqualTo(5);
        assertThat(tasks.totalPages()).isEqualTo(3);
        verify(this.taskRepository).findByProjectId(PROJECT_ID, pageRequest);
    }

    private ProjectTask task(final UUID taskId, final Instant createdAt) {
        return new ProjectTask(taskId, PROJECT_ID, "Check calculation", "Count the letters in Sitionix.", WORKFLOW_ID, List.of(REPOSITORY_ID), createdAt, UPDATED);
    }

    private ProjectTaskEntity entity(final UUID taskId, final Instant createdAt) {
        final ProjectTaskEntity entity = new ProjectTaskEntity();
        entity.setId(taskId);
        entity.setProjectId(PROJECT_ID);
        entity.setTitle("Check calculation");
        entity.setInput("Count the letters in Sitionix.");
        entity.setWorkflowId(WORKFLOW_ID);
        entity.setRepositoryIds(new java.util.ArrayList<>(List.of(REPOSITORY_ID)));
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(UPDATED);
        return entity;
    }
}
