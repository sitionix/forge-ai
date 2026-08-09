package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectUseCasesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ProjectRepository projectRepository;

    private ProjectUseCases useCases;

    @BeforeEach
    void setUp() {
        this.useCases = new ProjectUseCases(this.projectRepository, CLOCK);
    }

    @Test
    void rejectsBlankProjectName() {
        assertThatThrownBy(() -> this.useCases.createProject(new CreateProjectCommand("  ")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Project name is required.");

        verify(this.projectRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsDuplicateNormalizedProjectName() {
        when(this.projectRepository.existsByNormalizedName("sitionix")).thenReturn(true);

        assertThatThrownBy(() -> this.useCases.createProject(new CreateProjectCommand(" Sitionix ")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("A project with this name already exists.");
    }

    @Test
    void trimsAndPersistsProject() {
        final ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        when(this.projectRepository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        final Project result = this.useCases.createProject(new CreateProjectCommand(" Sitionix "));

        assertThat(result.name()).isEqualTo("Sitionix");
        assertThat(result.normalizedName()).isEqualTo("sitionix");
        assertThat(result.createdAt()).isEqualTo(Instant.parse("2026-08-04T00:00:00Z"));
        assertThat(captor.getValue().id()).isNotNull();
    }
}
