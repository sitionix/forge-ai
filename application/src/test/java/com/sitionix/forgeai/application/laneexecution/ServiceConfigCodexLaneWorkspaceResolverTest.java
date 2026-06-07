package com.sitionix.forgeai.application.laneexecution;

import com.sitionix.forgeai.domain.model.codex.CodexLaneWorkspace;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.props.AgentPropertiesProvider;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceConfigCodexLaneWorkspaceResolverTest {

    @TempDir
    private Path tempDir;

    @Test
    void givenPerScopeLane_whenResolve_thenUseServicePathAsCwdAndOnlyRuntimeRoot() throws Exception {
        final Path serviceRoot = Files.createDirectories(this.tempDir.resolve("service-repo"));
        final TicketRepository ticketRepository = mock(TicketRepository.class);
        final ServiceConfigCodexLaneWorkspaceResolver resolver = new ServiceConfigCodexLaneWorkspaceResolver(
                new FakeAgentPropertiesProvider(List.of()),
                new FakeServicePropertiesProvider(Map.of("service-id", this.service(serviceRoot, Map.of()))),
                ticketRepository
        );
        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(UUID.randomUUID())
                .agent(Agent.IMPLEMENT_BE)
                .scope("service-scope")
                .serviceId("service-id")
                .build();

        final CodexLaneWorkspace actual = resolver.resolve(lane);

        assertThat(actual.cwd()).isEqualTo(serviceRoot.toString());
        assertThat(actual.runtimeWorkspaceRoots()).containsExactly(serviceRoot.toString());
        verify(ticketRepository, never()).findById(lane.getTicketId());
    }

    @Test
    void givenGlobalContractLane_whenResolve_thenUseContractSourceRepoAsCwdAndExposeServiceRoots() throws Exception {
        final Path firstServiceRoot = Files.createDirectories(this.tempDir.resolve("first-service"));
        final Path secondServiceRoot = Files.createDirectories(this.tempDir.resolve("second-service"));
        final Path contractRoot = Files.createDirectories(this.tempDir.resolve("contract-repo"));
        final UUID ticketId = UUID.randomUUID();
        final TicketRepository ticketRepository = mock(TicketRepository.class);
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(Ticket.builder()
                .id(ticketId)
                .lanes(List.of(
                        this.lane("first-service-id"),
                        this.lane("second-service-id"),
                        this.lane("global")
                ))
                .build()));
        final Map<String, ServicePropertiesProvider.ServiceConfigView> services = new LinkedHashMap<>();
        services.put("first-service-id", this.service(firstServiceRoot, Map.of("api", this.contractRef(contractRoot))));
        services.put("second-service-id", this.service(secondServiceRoot, Map.of("api", this.contractRef(contractRoot))));
        final ServiceConfigCodexLaneWorkspaceResolver resolver = new ServiceConfigCodexLaneWorkspaceResolver(
                new FakeAgentPropertiesProvider(List.of(this.agentConfig(Agent.API, "api"))),
                new FakeServicePropertiesProvider(services),
                ticketRepository
        );
        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(ticketId)
                .agent(Agent.API)
                .scope(ScopeMode.GLOBAL_SCOPE)
                .serviceId("global")
                .build();

        final CodexLaneWorkspace actual = resolver.resolve(lane);

        assertThat(actual.cwd()).isEqualTo(contractRoot.toString());
        assertThat(actual.runtimeWorkspaceRoots()).containsExactly(
                contractRoot.toString(),
                firstServiceRoot.toString(),
                secondServiceRoot.toString()
        );
        verify(ticketRepository).findById(ticketId);
    }

    @Test
    void givenGlobalLaneWithoutWorkspaceContractRef_whenResolve_thenDoNotExposeContractRoots() throws Exception {
        final Path serviceRoot = Files.createDirectories(this.tempDir.resolve("service-repo"));
        final Path contractRoot = Files.createDirectories(this.tempDir.resolve("contract-repo"));
        final UUID ticketId = UUID.randomUUID();
        final TicketRepository ticketRepository = mock(TicketRepository.class);
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(Ticket.builder()
                .id(ticketId)
                .lanes(List.of(this.lane("service-id")))
                .build()));
        final ServiceConfigCodexLaneWorkspaceResolver resolver = new ServiceConfigCodexLaneWorkspaceResolver(
                new FakeAgentPropertiesProvider(List.of()),
                new FakeServicePropertiesProvider(Map.of("service-id", this.service(serviceRoot, Map.of("api", this.contractRef(contractRoot))))),
                ticketRepository
        );
        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(ticketId)
                .agent(Agent.REVIEWER)
                .scope(ScopeMode.GLOBAL_SCOPE)
                .serviceId("global")
                .build();

        final CodexLaneWorkspace actual = resolver.resolve(lane);

        assertThat(actual.cwd()).isEqualTo(serviceRoot.toString());
        assertThat(actual.runtimeWorkspaceRoots()).containsExactly(serviceRoot.toString());
    }

    @Test
    void givenProcessStartedFromBootModule_whenResolveRelativeServicePath_thenUseForgeAiRepositoryRoot() throws Exception {
        final Path workspaceRoot = Files.createDirectories(this.tempDir.resolve("workspace"));
        final Path forgeAiRoot = Files.createDirectories(workspaceRoot.resolve("forge-ai"));
        Files.createFile(forgeAiRoot.resolve("pom.xml"));
        Files.createDirectories(forgeAiRoot.resolve("boot/src/main/resources"));
        Files.createFile(forgeAiRoot.resolve("boot/src/main/resources/services.yaml"));
        final Path serviceRoot = Files.createDirectories(workspaceRoot.resolve("service-repo"));
        final String previousUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", forgeAiRoot.resolve("boot").toString());
        try {
            final TicketRepository ticketRepository = mock(TicketRepository.class);
            final ServiceConfigCodexLaneWorkspaceResolver resolver = new ServiceConfigCodexLaneWorkspaceResolver(
                    new FakeAgentPropertiesProvider(List.of()),
                    new FakeServicePropertiesProvider(Map.of("service-id", new FakeServiceConfigView("service-repo", Map.of()))),
                    ticketRepository
            );
            final ReadyToStartLane lane = ReadyToStartLane.builder()
                    .ticketId(UUID.randomUUID())
                    .agent(Agent.IMPLEMENT_BE)
                    .scope("service-scope")
                    .serviceId("service-id")
                    .build();

            final CodexLaneWorkspace actual = resolver.resolve(lane);

            assertThat(actual.cwd()).isEqualTo(serviceRoot.toString());
            assertThat(actual.runtimeWorkspaceRoots()).containsExactly(serviceRoot.toString());
        } finally {
            System.setProperty("user.dir", previousUserDir);
        }
    }

    private Lane lane(final String serviceId) {
        return Lane.builder()
                .id(UUID.randomUUID())
                .agent(Agent.ANALYZER)
                .serviceId(serviceId)
                .scope(serviceId)
                .status(LaneStatus.READY_TO_START)
                .build();
    }

    private ServicePropertiesProvider.ServiceConfigView service(
            final Path path,
            final Map<String, ServicePropertiesProvider.ContractRefView> refs
    ) {
        return new FakeServiceConfigView(path.toString(), refs);
    }

    private ServicePropertiesProvider.ContractRefView contractRef(final Path sourceRepo) {
        return new FakeContractRefView(sourceRepo.toString());
    }

    private AgentPropertiesProvider.AgentConfigView agentConfig(final Agent agent, final String workspaceContractRef) {
        return new FakeAgentConfigView(agent.getId(), workspaceContractRef);
    }

    private record FakeAgentPropertiesProvider(
            List<AgentPropertiesProvider.AgentConfigView> agents
    ) implements AgentPropertiesProvider {

        @Override
        public List<AgentConfigView> getAgents() {
            return this.agents;
        }
    }

    private record FakeAgentConfigView(
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

    private record FakeServicePropertiesProvider(
            Map<String, ServicePropertiesProvider.ServiceConfigView> services
    ) implements ServicePropertiesProvider {

        @Override
        public Map<String, ServiceConfigView> getServices() {
            return this.services;
        }
    }

    private record FakeServiceConfigView(
            String path,
            Map<String, ServicePropertiesProvider.ContractRefView> contractRefs
    ) implements ServicePropertiesProvider.ServiceConfigView {

        @Override
        public String getLabel() {
            return "Service";
        }

        @Override
        public String getPath() {
            return this.path;
        }

        @Override
        public ServiceGroup getGroup() {
            return ServiceGroup.BACKEND;
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
            return null;
        }

        @Override
        public ServicePropertiesProvider.DbConfigView getDb() {
            return null;
        }
    }

    private record FakeContractRefView(String sourceRepo) implements ServicePropertiesProvider.ContractRefView {

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
