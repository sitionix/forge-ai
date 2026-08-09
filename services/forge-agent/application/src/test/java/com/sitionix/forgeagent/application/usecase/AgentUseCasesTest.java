package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.application.graph.DependencyGraphValidator;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.AgentDependency;
import com.sitionix.forgeagent.domain.model.AgentDetails;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.port.AgentDefinitionRepository;
import com.sitionix.forgeagent.domain.port.AgentDependencyRepository;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentUseCasesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC);
    private static final AgentOutputSchema OUTPUT_SCHEMA = AgentOutputSchema.ofCanonicalJsonObject("{}");

    private final UUID projectId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID otherProjectId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID architectId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private final UUID backendId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private final UUID analyzerId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private AgentDefinitionRepository agentDefinitionRepository;

    @Mock
    private AgentDependencyRepository agentDependencyRepository;

    private AgentUseCases useCases;

    @BeforeEach
    void setUp() {
        this.useCases = new AgentUseCases(
                this.projectRepository,
                this.agentDefinitionRepository,
                this.agentDependencyRepository,
                new DependencyGraphValidator(),
                CLOCK
        );
    }

    @Test
    void rejectsBlankAgentName() {
        this.givenProject();

        assertThatThrownBy(() -> this.useCases.createAgent(this.projectId, new SaveAgentCommand(" ", "Instructions", OUTPUT_SCHEMA, List.of())))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Agent name is required.");
    }

    @Test
    void rejectsBlankInstructions() {
        this.givenProject();

        assertThatThrownBy(() -> this.useCases.createAgent(this.projectId, new SaveAgentCommand("Analyzer", "  ", OUTPUT_SCHEMA, List.of())))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Agent instructions are required.");
    }

    @Test
    void rejectsDuplicateAgentNameWithinProject() {
        this.givenProject();
        when(this.agentDefinitionRepository.existsByProjectIdAndNormalizedName(this.projectId, "analyzer")).thenReturn(true);

        assertThatThrownBy(() -> this.useCases.createAgent(this.projectId, new SaveAgentCommand("Analyzer", "Instructions", OUTPUT_SCHEMA, List.of())))
                .isInstanceOf(ConflictException.class)
                .hasMessage("An agent with this name already exists in this project.");
    }

    @Test
    void rejectsUnknownDependency() {
        this.givenProject();
        when(this.agentDefinitionRepository.findByProjectId(this.projectId)).thenReturn(List.of());
        when(this.agentDefinitionRepository.findByIds(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> this.useCases.createAgent(this.projectId, new SaveAgentCommand("Analyzer", "Instructions", OUTPUT_SCHEMA, List.of(this.architectId))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Dependencies must reference existing agents.");
    }

    @Test
    void rejectsCrossProjectDependency() {
        this.givenProject();
        final AgentDefinition external = this.agent(this.architectId, this.otherProjectId, "Architect");
        when(this.agentDefinitionRepository.findByProjectId(this.projectId)).thenReturn(List.of());
        when(this.agentDefinitionRepository.findByIds(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(external));

        assertThatThrownBy(() -> this.useCases.createAgent(this.projectId, new SaveAgentCommand("Analyzer", "Instructions", OUTPUT_SCHEMA, List.of(this.architectId))))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Dependencies must belong to the same project.");
    }

    @Test
    void createsAgentAndPersistsDependencies() {
        this.givenProject();
        final AgentDefinition architect = this.agent(this.architectId, this.projectId, "Architect");
        when(this.agentDefinitionRepository.findByProjectId(this.projectId)).thenReturn(List.of(architect), List.of(architect));
        when(this.agentDefinitionRepository.findByIds(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(architect));
        when(this.agentDependencyRepository.findByProjectId(this.projectId)).thenReturn(List.of());
        final AtomicReference<AgentDefinition> saved = new AtomicReference<>();
        when(this.agentDefinitionRepository.save(any())).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return saved.get();
        });
        when(this.agentDefinitionRepository.findById(any())).thenAnswer(invocation -> Optional.of(saved.get()));

        final AgentDetails created = this.useCases.createAgent(
                this.projectId,
                new SaveAgentCommand(" Analyzer ", "Instructions", OUTPUT_SCHEMA, List.of(this.architectId, this.architectId))
        );

        assertThat(created.name()).isEqualTo("Analyzer");
        final ArgumentCaptor<UUID> agentIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(this.agentDependencyRepository).replaceDependencies(agentIdCaptor.capture(), org.mockito.ArgumentMatchers.argThat(ids -> ids.size() == 1 && ids.contains(this.architectId)));
    }

    @Test
    void updateReplacesDependenciesAtomicallyAfterValidation() {
        final AgentDefinition architect = this.agent(this.architectId, this.projectId, "Architect");
        final AgentDefinition backend = this.agent(this.backendId, this.projectId, "Backend");
        final AgentDefinition analyzer = this.agent(this.analyzerId, this.projectId, "Analyzer");
        final AtomicReference<AgentDefinition> saved = new AtomicReference<>();
        this.givenProjectLock();
        when(this.agentDefinitionRepository.findById(this.backendId)).thenAnswer(invocation -> Optional.ofNullable(saved.get()).or(() -> Optional.of(backend)));
        when(this.agentDefinitionRepository.findByIds(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(architect));
        when(this.agentDefinitionRepository.findByProjectId(this.projectId)).thenReturn(List.of(architect, backend, analyzer), List.of(architect, backend, analyzer));
        when(this.agentDependencyRepository.findByProjectId(this.projectId)).thenReturn(List.of(new AgentDependency(this.backendId, this.analyzerId)));
        when(this.agentDefinitionRepository.save(any())).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return saved.get();
        });

        final AgentDetails updated = this.useCases.updateAgent(
                this.backendId,
                new SaveAgentCommand("Backend Updated", "New instructions", OUTPUT_SCHEMA, List.of(this.architectId))
        );

        assertThat(updated.name()).isEqualTo("Backend Updated");
        verify(this.agentDependencyRepository).replaceDependencies(
                org.mockito.ArgumentMatchers.eq(this.backendId),
                org.mockito.ArgumentMatchers.argThat(ids -> ids.size() == 1 && ids.contains(this.architectId))
        );
    }

    @Test
    void failedUpdateLeavesPreviousDependenciesUnchanged() {
        final AgentDefinition architect = this.agent(this.architectId, this.projectId, "Architect");
        final AgentDefinition backend = this.agent(this.backendId, this.projectId, "Backend");
        final AgentDefinition analyzer = this.agent(this.analyzerId, this.projectId, "Analyzer");
        this.givenProjectLock();
        when(this.agentDefinitionRepository.findById(this.backendId)).thenReturn(Optional.of(backend));
        when(this.agentDefinitionRepository.findByIds(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(architect));
        when(this.agentDefinitionRepository.findByProjectId(this.projectId)).thenReturn(List.of(architect, backend, analyzer));
        when(this.agentDependencyRepository.findByProjectId(this.projectId)).thenReturn(List.of(
                new AgentDependency(this.architectId, this.analyzerId),
                new AgentDependency(this.analyzerId, this.backendId)
        ));

        assertThatThrownBy(() -> this.useCases.updateAgent(
                this.backendId,
                new SaveAgentCommand("Backend", "New instructions", OUTPUT_SCHEMA, List.of(this.architectId))
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Dependency graph contains a cycle.");

        verify(this.agentDefinitionRepository, never()).save(any());
        verify(this.agentDependencyRepository, never()).replaceDependencies(any(), any());
    }

    @Test
    void createAcquiresProjectMutationLockBeforeGraphReads() {
        final AgentDefinition architect = this.agent(this.architectId, this.projectId, "Architect");
        final AtomicReference<AgentDefinition> saved = new AtomicReference<>();
        this.givenProjectLock();
        when(this.agentDefinitionRepository.findByProjectId(this.projectId)).thenReturn(List.of(architect), List.of(architect));
        when(this.agentDefinitionRepository.findByIds(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(architect));
        when(this.agentDependencyRepository.findByProjectId(this.projectId)).thenReturn(List.of());
        when(this.agentDefinitionRepository.save(any())).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return saved.get();
        });
        when(this.agentDefinitionRepository.findById(any())).thenAnswer(invocation -> Optional.of(saved.get()));

        this.useCases.createAgent(
                this.projectId,
                new SaveAgentCommand("Analyzer", "Instructions", OUTPUT_SCHEMA, List.of(this.architectId))
        );

        final InOrder order = org.mockito.Mockito.inOrder(
                this.projectRepository,
                this.agentDefinitionRepository,
                this.agentDependencyRepository
        );
        order.verify(this.projectRepository).findByIdForUpdate(this.projectId);
        order.verify(this.agentDefinitionRepository).existsByProjectIdAndNormalizedName(this.projectId, "analyzer");
        order.verify(this.agentDefinitionRepository).findByProjectId(this.projectId);
        order.verify(this.agentDefinitionRepository).findByIds(org.mockito.ArgumentMatchers.anyCollection());
        order.verify(this.agentDependencyRepository).findByProjectId(this.projectId);
        order.verify(this.agentDefinitionRepository).save(any());
        order.verify(this.agentDependencyRepository).replaceDependencies(any(), any());
    }

    @Test
    void updateReloadsAgentAfterAcquiringProjectMutationLock() {
        final AgentDefinition architect = this.agent(this.architectId, this.projectId, "Architect");
        final AgentDefinition backendBeforeLock = this.agent(this.backendId, this.projectId, "Backend");
        final AgentDefinition backendAfterLock = this.agent(this.backendId, this.projectId, "Backend Current");
        final AtomicReference<AgentDefinition> saved = new AtomicReference<>();
        this.givenProjectLock();
        when(this.agentDefinitionRepository.findById(this.backendId))
                .thenReturn(Optional.of(backendBeforeLock), Optional.of(backendAfterLock))
                .thenAnswer(invocation -> Optional.of(saved.get()));
        when(this.agentDefinitionRepository.findByIds(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(architect));
        when(this.agentDefinitionRepository.findByProjectId(this.projectId)).thenReturn(List.of(architect, backendAfterLock), List.of(architect, backendAfterLock));
        when(this.agentDependencyRepository.findByProjectId(this.projectId)).thenReturn(List.of());
        when(this.agentDefinitionRepository.save(any())).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return saved.get();
        });

        this.useCases.updateAgent(
                this.backendId,
                new SaveAgentCommand("Backend Updated", "Instructions", OUTPUT_SCHEMA, List.of(this.architectId))
        );

        final InOrder order = org.mockito.Mockito.inOrder(this.agentDefinitionRepository, this.projectRepository);
        order.verify(this.agentDefinitionRepository).findById(this.backendId);
        order.verify(this.projectRepository).findByIdForUpdate(this.projectId);
        order.verify(this.agentDefinitionRepository).findById(this.backendId);
        verify(this.agentDefinitionRepository).existsByProjectIdAndNormalizedNameExcludingId(this.projectId, "backend updated", this.backendId);
    }

    @Test
    void missingLockedProjectMapsToProjectNotFound() {
        when(this.projectRepository.findByIdForUpdate(this.projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.useCases.createAgent(
                this.projectId,
                new SaveAgentCommand("Analyzer", "Instructions", OUTPUT_SCHEMA, List.of())
        ))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo("PROJECT_NOT_FOUND");

        verify(this.agentDefinitionRepository, never()).findByProjectId(any());
        verify(this.agentDependencyRepository, never()).findByProjectId(any());
    }

    @Test
    void listAndGetDoNotRequestProjectMutationLock() {
        final AgentDefinition architect = this.agent(this.architectId, this.projectId, "Architect");
        when(this.projectRepository.findById(this.projectId)).thenReturn(Optional.of(this.project()));
        when(this.agentDefinitionRepository.findByProjectId(this.projectId)).thenReturn(List.of(architect));
        when(this.agentDependencyRepository.findByProjectId(this.projectId)).thenReturn(List.of());
        when(this.agentDefinitionRepository.findById(this.architectId)).thenReturn(Optional.of(architect));
        when(this.agentDependencyRepository.findDependsOnIds(this.architectId)).thenReturn(List.of());

        this.useCases.listProjectAgents(this.projectId);
        this.useCases.getAgent(this.architectId);

        verify(this.projectRepository, never()).findByIdForUpdate(any());
    }

    private void givenProject() {
        this.givenProjectLock();
    }

    private void givenProjectLock() {
        when(this.projectRepository.findByIdForUpdate(this.projectId)).thenReturn(Optional.of(this.project()));
    }

    private Project project() {
        return new Project(
                this.projectId,
                "Sitionix",
                "sitionix",
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-04T00:00:00Z")
        );
    }

    private AgentDefinition agent(final UUID agentId, final UUID projectId, final String name) {
        return new AgentDefinition(
                agentId,
                projectId,
                name,
                name.toLowerCase(),
                "Instructions",
                OUTPUT_SCHEMA,
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-04T00:00:00Z")
        );
    }
}
