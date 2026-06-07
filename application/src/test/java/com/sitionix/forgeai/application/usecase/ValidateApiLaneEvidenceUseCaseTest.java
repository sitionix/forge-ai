package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.exception.ApiLaneEvidenceValidationException;
import com.sitionix.forgeai.domain.model.github.GithubCheckStatus;
import com.sitionix.forgeai.domain.model.github.GithubPullRequestCheckResult;
import com.sitionix.forgeai.domain.model.github.GithubRepositoryCheckResult;
import com.sitionix.forgeai.domain.model.github.GithubWorkflowRunCheckResult;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiLaneEvidenceDependency;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiLaneEvidencePayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.port.GithubEvidencePort;
import com.sitionix.forgeai.domain.props.AgentPropertiesProvider;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidateApiLaneEvidenceUseCaseTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private GithubEvidencePort githubEvidencePort;

    private ValidateApiLaneEvidenceUseCase useCase;

    @BeforeEach
    void setUp() {
        this.useCase = new ValidateApiLaneEvidenceUseCase(
                this.ticketRepository,
                this.githubEvidencePort,
                this.servicePropertiesProvider(),
                this.agentPropertiesProvider()
        );
        lenient().when(this.githubEvidencePort.checkPullRequest(anyString())).thenReturn(
                GithubPullRequestCheckResult.builder().status(GithubCheckStatus.VERIFIED).details("ok").build()
        );
        lenient().when(this.githubEvidencePort.checkWorkflowRun(anyString(), anyLong())).thenReturn(
                GithubWorkflowRunCheckResult.builder().runId(1L).status(GithubCheckStatus.VERIFIED).details("ok").build()
        );
        lenient().when(this.githubEvidencePort.checkRepository(anyString())).thenReturn(
                GithubRepositoryCheckResult.builder().status(GithubCheckStatus.VERIFIED).details("ok").build()
        );
    }

    @Test
    void givenMissingRequiredScopeEvidence_whenValidate_thenThrow() {
        final UUID laneId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).build();
        final ApiLaneEvidencePayload evidence = ApiLaneEvidencePayload.builder()
                .prUrl("https://github.com/sitionix/app-afesox/pull/164")
                .repo("sitionix/app-afesox")
                .dependencies(List.of(ApiLaneEvidenceDependency.builder().scope("automationservice-sox").runId(1L).build()))
                .build();

        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        assertThatThrownBy(() -> this.useCase.validate(laneId, Set.of("backendforfrontendservice-sox"), evidence))
                .isInstanceOf(ApiLaneEvidenceValidationException.class)
                .hasMessageContaining("missing generated dependency evidence");
    }

    @Test
    void givenRequiredScopeEvidencePresentWithExtraScopes_whenValidate_thenPass() {
        final UUID laneId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).build();
        final ApiLaneEvidencePayload evidence = ApiLaneEvidencePayload.builder()
                .prUrl("https://github.com/sitionix/app-afesox/pull/164")
                .repo("sitionix/app-afesox")
                .dependencies(List.of(
                        ApiLaneEvidenceDependency.builder().scope("backendforfrontendservice-sox").runId(11L).build(),
                        ApiLaneEvidenceDependency.builder().scope("automationservice-sox").runId(12L).build()
                ))
                .build();

        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        assertThatCode(() -> this.useCase.validate(laneId, Set.of("backendforfrontendservice-sox"), evidence)).doesNotThrowAnyException();
    }

    @Test
    void givenRequiredContractScopeMissingInEvidence_whenValidate_thenThrow() {
        final UUID laneId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).build();
        final ApiLaneEvidencePayload evidence = ApiLaneEvidencePayload.builder()
                .prUrl("https://github.com/sitionix/app-afesox/pull/164")
                .repo("sitionix/app-afesox")
                .dependencies(List.of(
                        ApiLaneEvidenceDependency.builder().scope("backendforfrontendservice-sox").runId(11L).build()
                ))
                .build();

        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        assertThatThrownBy(() -> this.useCase.validate(laneId, Set.of("automationservice-sox"), evidence))
                .isInstanceOf(ApiLaneEvidenceValidationException.class)
                .hasMessageContaining("missing generated dependency evidence");
    }

    @Test
    void givenArchitectScopeIsGlobal_whenValidate_thenGlobalIsNotRequiredDependency() {
        final UUID laneId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).build();
        final ApiLaneEvidencePayload evidence = ApiLaneEvidencePayload.builder()
                .prUrl("https://github.com/sitionix/app-afesox/pull/164")
                .repo("sitionix/app-afesox")
                .dependencies(List.of(ApiLaneEvidenceDependency.builder().scope("backendforfrontendservice-sox").runId(11L).build()))
                .build();

        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        assertThatCode(() -> this.useCase.validate(laneId, Set.of("backendforfrontendservice-sox"), evidence))
                .doesNotThrowAnyException();
    }

    @Test
    void givenRepositoryWithoutOwner_whenValidate_thenThrow() {
        final UUID laneId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).build();
        final ApiLaneEvidencePayload evidence = ApiLaneEvidencePayload.builder()
                .prUrl("https://github.com/sitionix/app-afesox/pull/164")
                .repo("app-afesox")
                .dependencies(List.of(ApiLaneEvidenceDependency.builder().scope("backendforfrontendservice-sox").runId(11L).build()))
                .build();

        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        assertThatThrownBy(() -> this.useCase.validate(laneId, Set.of("backendforfrontendservice-sox"), evidence))
                .isInstanceOf(ApiLaneEvidenceValidationException.class)
                .hasMessageContaining("repository has invalid format");
    }

    @Test
    void givenRepositoryDoesNotExist_whenValidate_thenThrow() {
        final UUID laneId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).build();
        final ApiLaneEvidencePayload evidence = ApiLaneEvidencePayload.builder()
                .prUrl("https://github.com/sitionix/app-afesox/pull/164")
                .repo("sitionix/app-afesox")
                .dependencies(List.of(ApiLaneEvidenceDependency.builder().scope("backendforfrontendservice-sox").runId(11L).build()))
                .build();

        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));
        when(this.githubEvidencePort.checkRepository("sitionix/app-afesox"))
                .thenReturn(GithubRepositoryCheckResult.builder().status(GithubCheckStatus.NOT_FOUND).details("not found").build());

        assertThatThrownBy(() -> this.useCase.validate(laneId, Set.of("backendforfrontendservice-sox"), evidence))
                .isInstanceOf(ApiLaneEvidenceValidationException.class)
                .hasMessageContaining("repository not found");
    }

    @Test
    void givenRepositoryNotConfiguredForRequiredScope_whenValidate_thenThrow() {
        final UUID laneId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).build();
        final ApiLaneEvidencePayload evidence = ApiLaneEvidencePayload.builder()
                .prUrl("https://github.com/Sitionix/app-afesox/pull/164")
                .repo("Sitionix/other-api-contracts")
                .dependencies(List.of(ApiLaneEvidenceDependency.builder().scope("backendforfrontendservice-sox").runId(11L).build()))
                .build();

        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        assertThatThrownBy(() -> this.useCase.validate(laneId, Set.of("backendforfrontendservice-sox"), evidence))
                .isInstanceOf(ApiLaneEvidenceValidationException.class)
                .hasMessageContaining("is not configured for required API scopes");
    }

    private ServicePropertiesProvider servicePropertiesProvider() {
        return () -> Map.of(
                "atmssox",
                new TestServiceConfigView(
                        "automationservice-sox",
                        new TestDeployConfigView("Sitionix/automationservice-sox"),
                        Map.of("api", new TestContractRefView("app-afesox"))
                ),
                "bffssox",
                new TestServiceConfigView(
                        "backendforfrontendservice-sox",
                        new TestDeployConfigView("Sitionix/backendforfrontendservice-sox"),
                        Map.of("api", new TestContractRefView("app-afesox"))
                )
        );
    }

    private AgentPropertiesProvider agentPropertiesProvider() {
        return () -> List.of(new TestAgentConfigView(Agent.API.getId(), "api"));
    }

    private record TestAgentConfigView(
            String id,
            String workspaceContractRef
    ) implements AgentPropertiesProvider.AgentConfigView {

        @Override
        public String getId() {
            return this.id;
        }

        @Override
        public ScopeMode getScopeMode() {
            return ScopeMode.GLOBAL;
        }

        @Override
        public Set<ServiceGroup> getGroups() {
            return Set.of();
        }

        @Override
        public List<Agent> getDependsOn() {
            return List.of();
        }

        @Override
        public List<Agent> getProduces() {
            return List.of();
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public Optional<String> getWorkspaceContractRef() {
            return Optional.ofNullable(this.workspaceContractRef);
        }
    }

    private record TestServiceConfigView(
            String path,
            ServicePropertiesProvider.DeployConfigView deploy,
            Map<String, ServicePropertiesProvider.ContractRefView> contractRefs
    ) implements ServicePropertiesProvider.ServiceConfigView {

        @Override
        public String getLabel() {
            return this.path;
        }

        @Override
        public String getPath() {
            return this.path;
        }

        @Override
        public ServiceGroup getGroup() {
            return null;
        }

        @Override
        public List<String> getTags() {
            return List.of();
        }

        @Override
        public List<String> getTests() {
            return List.of();
        }

        @Override
        public List<String> getDomainKeywords() {
            return List.of();
        }

        @Override
        public List<String> getOwnsBusinessAreas() {
            return List.of();
        }

        @Override
        public List<String> getArchitectureRefs() {
            return List.of();
        }

        @Override
        public Map<String, ServicePropertiesProvider.ContractRefView> getContractRefs() {
            return this.contractRefs;
        }

        @Override
        public ServicePropertiesProvider.DeployConfigView getDeploy() {
            return this.deploy;
        }

        @Override
        public ServicePropertiesProvider.DbConfigView getDb() {
            return null;
        }
    }

    private record TestDeployConfigView(String repo) implements ServicePropertiesProvider.DeployConfigView {

        @Override
        public String getType() {
            return null;
        }

        @Override
        public String getRepo() {
            return this.repo;
        }

        @Override
        public ServicePropertiesProvider.DeployUnitConfigView getService() {
            return null;
        }

        @Override
        public ServicePropertiesProvider.DeployUnitConfigView getDb() {
            return null;
        }
    }

    private record TestContractRefView(String sourceRepo) implements ServicePropertiesProvider.ContractRefView {

        @Override
        public String getSourceRepo() {
            return this.sourceRepo;
        }

        @Override
        public String getApiFamily() {
            return null;
        }

        @Override
        public String getEventFamily() {
            return null;
        }

        @Override
        public String getServiceCode() {
            return null;
        }

        @Override
        public String getRoot() {
            return null;
        }

        @Override
        public List<String> getSchemas() {
            return List.of();
        }

        @Override
        public List<String> getOperations() {
            return List.of();
        }

        @Override
        public List<String> getTopics() {
            return List.of();
        }

        @Override
        public List<String> getPayloads() {
            return List.of();
        }

        @Override
        public List<String> getGeneratedArtifacts() {
            return List.of();
        }

        @Override
        public List<String> getConsumerArtifacts() {
            return List.of();
        }

        @Override
        public List<String> getFrontendPackages() {
            return List.of();
        }
    }
}
