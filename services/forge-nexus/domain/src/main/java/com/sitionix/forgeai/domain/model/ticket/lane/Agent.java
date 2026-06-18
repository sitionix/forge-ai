package com.sitionix.forgeai.domain.model.ticket.lane;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.props.AgentConfigView;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@Getter
@RequiredArgsConstructor
public enum Agent {
    ANALYZER("analyzer", "analyzeAgentExecutor"),
    ARCHITECT("architect", "architectAgentExecutor"),
    API("api", "apiAgentExecutor"),
    EVENT("event", "eventAgentExecutor"),
    QA_LEAD("qa_lead", "qaLeadAgentExecutor"),
    IMPLEMENT_BE("implement_be", "beAgentExecutor"),
    IMPLEMENT_FE("implement_fe", "feAgentExecutor"),
    TEST_UNIT("test_unit", "testUnitAgentExecutor"),
    TEST_IT("test_it", "testItAgentExecutor"),
    TEST_UI("test_ui", "testUiAgentExecutor"),
    REVIEWER("reviewer", "reviewerAgentExecutor");

    private final String id;
    private final String executorBeanName;

    @Setter
    private AgentConfigView info;

    @Setter
    private ExecuteAgent<? extends AgentTicketPayload> executor;

    public ExecuteAgent<? extends AgentTicketPayload> getExecutor() {
        if (this.executor == null) {
            throw new IllegalStateException("No executor configured for agent: " + this.id);
        }
        return this.executor;
    }

    public AgentConfigView getInfo() {
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

    public void executeLane(final ReadyToStartLane lane) {
        if (!this.getInfo().isEnabled()) {
            return;
        }
        this.getExecutor().executeLane(lane);
    }

    public void validateFinalCompletionPayload(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        this.getExecutor().validateFinalCompletionPayload(lane, completionPayload);
    }

    public void completeLane(final ReadyToStartLane lane, final Map<String, Object> completionPayload) {
        this.getExecutor().completeLane(lane, completionPayload);
    }

    public Optional<Class<? extends AgentTicketPayload>> inputPayloadTypeFrom(final Agent sourceAgent) {
        return Optional.ofNullable(this.getInfo().getInputPayloadTypes().get(sourceAgent))
                .map(value -> value.getPayloadClass());
    }

    public boolean writesProducedLaneOutputs() {
        return this.getInfo().writesProducedLaneOutputs();
    }

    public boolean requiresApiCompletionEvidence() {
        return this.getInfo().requiresApiCompletionEvidence();
    }

    public boolean requiresCompletionOutputForEveryTarget() {
        return this.getInfo().requiresCompletionOutputForEveryTarget();
    }

    public Optional<Class<? extends AgentTicketPayload>> completionReportPayloadType() {
        return this.getInfo().getCompletionReportPayloadType()
                .map(value -> value.getPayloadClass());
    }

}
