package com.sitionix.forgeai.domain.model.ticket.lane;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.props.AgentPropertiesProvider;
import java.util.Arrays;
import java.util.Map;
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
    private AgentPropertiesProvider.AgentConfigView info;

    @Setter
    private ExecuteAgent<? extends AgentTicketPayload> executor;

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

    public void executeLane(final ReadyToStartLane lane) {
        if (!this.getInfo().isEnabled()) {
            return;
        }
        this.getExecutor().executeLane(lane);
    }

    public void completeLane(final LaneCompletionRouter router,
                             final ReadyToStartLane lane,
                             final Map<String, Object> completionPayload) {
        switch (this) {
            case ANALYZER -> router.completeAnalyzer(lane, completionPayload);
            case ARCHITECT -> router.completeArchitect(lane, completionPayload);
            case API -> router.completeApi(lane, completionPayload);
            case QA_LEAD -> router.completeQaLead(lane, completionPayload);
            case IMPLEMENT_BE -> router.completeImplementBe(lane, completionPayload);
            case IMPLEMENT_FE -> router.completeImplementFe(lane, completionPayload);
            case TEST_UNIT -> router.completeTestUnit(lane, completionPayload);
            case TEST_IT -> router.completeTestIt(lane, completionPayload);
            case TEST_UI -> router.completeTestUi(lane, completionPayload);
            case REVIEWER -> router.completeReviewer(lane, completionPayload);
            case EVENT -> router.completeEvent(lane, completionPayload);
        }
    }

    public void validateLaneCompletion(final LaneCompletionValidationRouter router,
                                       final ReadyToStartLane lane,
                                       final Map<String, Object> completionPayload) {
        switch (this) {
            case ANALYZER -> router.validateAnalyzer(lane, completionPayload);
            case ARCHITECT -> router.validateArchitect(lane, completionPayload);
            case API -> router.validateApi(lane, completionPayload);
            case QA_LEAD -> router.validateQaLead(lane, completionPayload);
            case IMPLEMENT_BE, IMPLEMENT_FE, TEST_UI, TEST_UNIT, EVENT, REVIEWER, TEST_IT ->
                    router.validateScope(lane, completionPayload);
        }
    }

    public interface LaneCompletionRouter {
        void completeAnalyzer(ReadyToStartLane lane, Map<String, Object> completionPayload);

        void completeArchitect(ReadyToStartLane lane, Map<String, Object> completionPayload);

        void completeApi(ReadyToStartLane lane, Map<String, Object> completionPayload);

        void completeQaLead(ReadyToStartLane lane, Map<String, Object> completionPayload);

        void completeImplementBe(ReadyToStartLane lane, Map<String, Object> completionPayload);

        void completeImplementFe(ReadyToStartLane lane, Map<String, Object> completionPayload);

        void completeTestUnit(ReadyToStartLane lane, Map<String, Object> completionPayload);

        void completeTestIt(ReadyToStartLane lane, Map<String, Object> completionPayload);

        void completeTestUi(ReadyToStartLane lane, Map<String, Object> completionPayload);

        void completeReviewer(ReadyToStartLane lane, Map<String, Object> completionPayload);

        void completeEvent(ReadyToStartLane lane, Map<String, Object> completionPayload);
    }

    public interface LaneCompletionValidationRouter {
        void validateAnalyzer(ReadyToStartLane lane, Map<String, Object> completionPayload);

        void validateArchitect(ReadyToStartLane lane, Map<String, Object> completionPayload);

        void validateApi(ReadyToStartLane lane, Map<String, Object> completionPayload);

        void validateQaLead(ReadyToStartLane lane, Map<String, Object> completionPayload);

        void validateScope(ReadyToStartLane lane, Map<String, Object> completionPayload);
    }

}
