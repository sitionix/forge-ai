package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneDependency;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.props.AgentPropertiesProvider;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.StartForgeAiTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StartForgeAiTaskUseCaseTest {

    private StartForgeAiTask startForgeAiTask;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private ServicePropertiesProvider props;

    @BeforeEach
    void setUp() {
        this.startForgeAiTask = new StartForgeAiTaskUseCase(this.ticketRepository, this.props);
        this.configureAgents();
        lenient().when(this.ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        for (final Agent agent : Agent.values()) {
            agent.setInfo(null);
        }
    }

    @Test
    void givenStartCommand_whenExecute_thenCreatesPerScopeAnalyzerLanes() {
        //given
        final ForgeAiStartCommand command = this.getCommand();
        final Map<String, ServicePropertiesProvider.ServiceConfigView> services = this.getServiceMap();
        when(this.props.getServices()).thenReturn(services);

        //when
        final Ticket actual = this.startForgeAiTask.execute(command);

        //then
        final List<Lane> analyzerLanes = actual.getLanes().stream()
                .filter(lane -> lane.getAgent() == Agent.ANALYZER)
                .toList();

        assertThat(analyzerLanes).hasSize(2);
        assertThat(analyzerLanes.stream().map(Lane::getScope).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("automationservice-sox", "backendforfrontendservice-sox");
        assertThat(analyzerLanes.stream().map(Lane::getStatus).collect(Collectors.toSet()))
                .containsExactly(LaneStatus.READY_TO_START);
        assertThat(analyzerLanes.stream().allMatch(lane -> lane.getDependsOn().isEmpty())).isTrue();
    }

    @Test
    void givenStartCommand_whenExecute_thenCreatesSingleGlobalApiLane() {
        //given
        final ForgeAiStartCommand command = this.getCommand();
        final Map<String, ServicePropertiesProvider.ServiceConfigView> services = this.getServiceMap();
        when(this.props.getServices()).thenReturn(services);

        //when
        final Ticket actual = this.startForgeAiTask.execute(command);

        //then
        final List<Lane> apiLanes = actual.getLanes().stream()
                .filter(lane -> lane.getAgent() == Agent.API)
                .toList();

        assertThat(apiLanes).hasSize(1);
        assertThat(apiLanes.getFirst().getScope()).isEqualTo("GLOBAL");
    }

    @Test
    void givenStartCommand_whenExecute_thenApiDependsOnAllArchitectScopes() {
        //given
        final ForgeAiStartCommand command = this.getCommand();
        final Map<String, ServicePropertiesProvider.ServiceConfigView> services = this.getServiceMap();
        when(this.props.getServices()).thenReturn(services);

        //when
        final Ticket actual = this.startForgeAiTask.execute(command);

        //then
        final Lane apiLane = actual.getLanes().stream()
                .filter(lane -> lane.getAgent() == Agent.API)
                .findFirst()
                .orElseThrow();

        assertThat(apiLane.getDependsOn()).containsExactlyInAnyOrder(
                this.getDependency(Agent.ARCHITECT, "automationservice-sox"),
                this.getDependency(Agent.ARCHITECT, "backendforfrontendservice-sox")
        );
    }

    @Test
    void givenStartCommand_whenExecute_thenCreatesSingleGlobalReviewerLane() {
        //given
        final ForgeAiStartCommand command = this.getCommand();
        final Map<String, ServicePropertiesProvider.ServiceConfigView> services = this.getServiceMap();
        when(this.props.getServices()).thenReturn(services);

        //when
        final Ticket actual = this.startForgeAiTask.execute(command);

        //then
        final List<Lane> reviewerLanes = actual.getLanes().stream()
                .filter(lane -> lane.getAgent() == Agent.REVIEWER)
                .toList();

        assertThat(reviewerLanes).hasSize(1);
        assertThat(reviewerLanes.getFirst().getScope()).isEqualTo("GLOBAL");
        assertThat(reviewerLanes.getFirst().getDependsOn()).containsExactlyInAnyOrder(
                this.getDependency(Agent.TEST_UNIT, "automationservice-sox"),
                this.getDependency(Agent.TEST_UNIT, "backendforfrontendservice-sox")
        );
    }

    @Test
    void givenStartCommand_whenExecute_thenImplementBeDependsOnArchitectAndGlobalApiAndGlobalEvent() {
        //given
        final ForgeAiStartCommand command = this.getCommand();
        final Map<String, ServicePropertiesProvider.ServiceConfigView> services = this.getServiceMap();
        when(this.props.getServices()).thenReturn(services);

        //when
        final Ticket actual = this.startForgeAiTask.execute(command);

        //then
        final Lane lane = actual.getLanes().stream()
                .filter(value -> value.getAgent() == Agent.IMPLEMENT_BE)
                .filter(value -> Objects.equals(value.getScope(), "automationservice-sox"))
                .findFirst()
                .orElseThrow();

        assertThat(lane.getDependsOn()).containsExactlyInAnyOrder(
                this.getDependency(Agent.ARCHITECT, "automationservice-sox"),
                this.getDependency(Agent.API, "GLOBAL"),
                this.getDependency(Agent.EVENT, "GLOBAL")
        );
    }

    @Test
    void givenBackendServices_whenExecute_thenDoesNotCreateFrontendLanes() {
        //given
        final ForgeAiStartCommand command = this.getCommand();
        final Map<String, ServicePropertiesProvider.ServiceConfigView> services = this.getServiceMap();
        when(this.props.getServices()).thenReturn(services);

        //when
        final Ticket actual = this.startForgeAiTask.execute(command);

        //then
        assertThat(actual.getLanes().stream().noneMatch(lane -> lane.getAgent() == Agent.IMPLEMENT_FE)).isTrue();
        assertThat(actual.getLanes().stream().noneMatch(lane -> lane.getAgent() == Agent.TEST_UI)).isTrue();
    }

    @Test
    void givenFrontendService_whenExecute_thenCreatesOnlyFrontendAndSharedLanes() {
        //given
        final ForgeAiStartCommand command = ForgeAiStartCommand.builder()
                .ticket("SITIONIX-1")
                .task("hi")
                .serviceIds(List.of("spa"))
                .build();
        final ServicePropertiesProvider.ServiceConfigView spa = mock(ServicePropertiesProvider.ServiceConfigView.class);
        when(spa.getPath()).thenReturn("sitionix-spa");
        when(spa.getGroup()).thenReturn(ServiceGroup.FRONTEND);
        when(this.props.getServices()).thenReturn(Map.of("spa", spa));

        //when
        final Ticket actual = this.startForgeAiTask.execute(command);

        //then
        assertThat(actual.getLanes().stream().anyMatch(lane -> lane.getAgent() == Agent.IMPLEMENT_FE)).isTrue();
        assertThat(actual.getLanes().stream().noneMatch(lane -> lane.getAgent() == Agent.IMPLEMENT_BE)).isTrue();
        assertThat(actual.getLanes().stream().noneMatch(lane -> lane.getAgent() == Agent.TEST_IT)).isTrue();
        assertThat(actual.getLanes().stream().noneMatch(lane -> lane.getAgent() == Agent.TEST_UI)).isTrue();
        assertThat(actual.getLanes().stream().noneMatch(lane -> lane.getAgent() == Agent.EVENT)).isTrue();
    }

    private ForgeAiStartCommand getCommand() {
        return ForgeAiStartCommand.builder()
                .ticket("SITIONIX-1")
                .task("hi")
                .serviceIds(List.of("atmssox", "bffssox"))
                .build();
    }

    private Map<String, ServicePropertiesProvider.ServiceConfigView> getServiceMap() {
        return Map.of(
                "atmssox", this.getService("automationservice-sox"),
                "bffssox", this.getService("backendforfrontendservice-sox")
        );
    }

    private ServicePropertiesProvider.ServiceConfigView getService(final String path) {
        final ServicePropertiesProvider.ServiceConfigView service = mock(ServicePropertiesProvider.ServiceConfigView.class);
        when(service.getPath()).thenReturn(path);
        when(service.getGroup()).thenReturn(ServiceGroup.BACKEND);
        return service;
    }

    private LaneDependency getDependency(final Agent type, final String scope) {
        return LaneDependency.builder()
                .type(type)
                .scope(scope)
                .build();
    }

    private void configureAgents() {
        final AgentPropertiesProvider.AgentConfigView analyzer = this.getAgent("analyzer", ScopeMode.PER_SCOPE, Set.of(ServiceGroup.BACKEND, ServiceGroup.FRONTEND), List.of());
        final AgentPropertiesProvider.AgentConfigView architect = this.getAgent("architect", ScopeMode.PER_SCOPE, Set.of(ServiceGroup.BACKEND, ServiceGroup.FRONTEND), List.of(Agent.ANALYZER));
        final AgentPropertiesProvider.AgentConfigView api = this.getAgent("api", ScopeMode.GLOBAL, Set.of(ServiceGroup.BACKEND, ServiceGroup.FRONTEND), List.of(Agent.ARCHITECT));
        final AgentPropertiesProvider.AgentConfigView event = this.getAgent("event", ScopeMode.GLOBAL, Set.of(ServiceGroup.BACKEND), List.of(Agent.ARCHITECT));
        final AgentPropertiesProvider.AgentConfigView qaLead = this.getAgent("qa_lead", ScopeMode.PER_SCOPE, Set.of(ServiceGroup.BACKEND, ServiceGroup.FRONTEND), List.of(Agent.ANALYZER));
        final AgentPropertiesProvider.AgentConfigView implementBe = this.getAgent("implement_be", ScopeMode.PER_SCOPE, Set.of(ServiceGroup.BACKEND), List.of(Agent.ARCHITECT, Agent.API, Agent.EVENT));
        final AgentPropertiesProvider.AgentConfigView implementFe = this.getAgent("implement_fe", ScopeMode.PER_SCOPE, Set.of(ServiceGroup.FRONTEND), List.of(Agent.ARCHITECT, Agent.API));
        final AgentPropertiesProvider.AgentConfigView testUnit = this.getAgent("test_unit", ScopeMode.PER_SCOPE, Set.of(ServiceGroup.BACKEND), List.of(Agent.IMPLEMENT_BE));
        final AgentPropertiesProvider.AgentConfigView testIt = this.getAgent("test_it", ScopeMode.PER_SCOPE, Set.of(ServiceGroup.BACKEND), List.of(Agent.IMPLEMENT_BE, Agent.QA_LEAD));
        final AgentPropertiesProvider.AgentConfigView testUi = this.getAgent("test_ui", ScopeMode.PER_SCOPE, Set.of(ServiceGroup.FRONTEND), List.of(Agent.IMPLEMENT_FE, Agent.QA_LEAD));
        final AgentPropertiesProvider.AgentConfigView reviewer = this.getAgent("reviewer", ScopeMode.GLOBAL, Set.of(ServiceGroup.BACKEND), List.of(Agent.TEST_UNIT));
        when(testUi.isEnabled()).thenReturn(false);

        this.bind(Agent.ANALYZER, analyzer);
        this.bind(Agent.ARCHITECT, architect);
        this.bind(Agent.API, api);
        this.bind(Agent.EVENT, event);
        this.bind(Agent.QA_LEAD, qaLead);
        this.bind(Agent.IMPLEMENT_BE, implementBe);
        this.bind(Agent.IMPLEMENT_FE, implementFe);
        this.bind(Agent.TEST_UNIT, testUnit);
        this.bind(Agent.TEST_IT, testIt);
        this.bind(Agent.TEST_UI, testUi);
        this.bind(Agent.REVIEWER, reviewer);
    }

    private void bind(final Agent agent, final AgentPropertiesProvider.AgentConfigView view) {
        agent.setInfo(view);
    }

    private AgentPropertiesProvider.AgentConfigView getAgent(final String id,
                                                             final ScopeMode scopeMode,
                                                             final Set<ServiceGroup> groups,
                                                             final List<Agent> dependsOn) {
        final AgentPropertiesProvider.AgentConfigView view = mock(AgentPropertiesProvider.AgentConfigView.class);
        final List<Agent> mutableDependsOn = new ArrayList<>(dependsOn);
        lenient().when(view.getScopeMode()).thenReturn(scopeMode);
        lenient().when(view.getGroups()).thenReturn(groups);
        lenient().when(view.getDependsOn()).thenReturn(mutableDependsOn);
        lenient().when(view.isEnabled()).thenReturn(true);
        return view;
    }
}
