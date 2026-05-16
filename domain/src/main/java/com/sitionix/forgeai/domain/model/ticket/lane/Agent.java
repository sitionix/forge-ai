package com.sitionix.forgeai.domain.model.ticket.lane;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.props.AgentPropertiesProvider;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@Getter
@RequiredArgsConstructor
public enum Agent implements ExecuteAgent<AgentTicketPayload>{
    ANALYZER("analyzer", "analyzeAgentExecutor"),
    ARCHITECT("architect", "architectAgentExecutor"),
    API("api", "apiAgentExecutor"),
    EVENT("event", "eventAgentExecutor"),
    QA_LEAD("qa_lead", "qaLeadAgentExecutor"),
    IMPLEMENT_BE("implement_be", "beAgentExecutor"),
    IMPLEMENT_FE("implement_fe", "feAgentExecutor"),
    TEST_UNIT("test_unit", "testUnitAgentExecutor"),
    TEST_IT("test_it", "testItAgentExecutor"),
    TEST_UI("test_ui", "testUiAgentExecutor");

    private final String id;
    private final String executorBeanName;

    @Setter
    private AgentPropertiesProvider.AgentConfigView info;

    @Setter
    private ExecuteAgent<AgentTicketPayload> executor;

    public AgentPropertiesProvider.AgentConfigView getInfo() {
        if (this.info == null) {
            throw new IllegalStateException("No agent info configured for agent: " + this.id);
        }
        return this.info;
    }

    public static Agent byId(final String id) {
        return Arrays.stream(values())
                .filter(value -> value.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown agent id: " + id));
    }

    @Override
    public void executeLane(final ReadyToStartLane lane) {
        this.getExecutor().executeLane(lane);
    }

    @Override
    public void executeTicket(final AgentTicket<AgentTicketPayload> ticket) {
        this.getExecutor().executeTicket(ticket);
    }

}
