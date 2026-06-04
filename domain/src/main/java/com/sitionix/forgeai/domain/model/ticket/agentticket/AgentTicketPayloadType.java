package com.sitionix.forgeai.domain.model.ticket.agentticket;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AgentTicketPayloadType {
    ARCHITECT("architect", ArchitectPayload.class),
    QA_LEAD("qa_lead", QaLeadPayload.class),
    API("api", ApiPayload.class),
    EVENT("event", EventPayload.class),
    IMPLEMENT_BE("implement_be", ImplementBePayload.class),
    IMPLEMENT_FE("implement_fe", ImplementFePayload.class),
    TEST_UNIT("test_unit", TestUnitPayload.class),
    TEST_IT("test_it", TestItPayload.class),
    TEST_UI("test_ui", TestUiPayload.class),
    QA_LEAD_TEST_UNIT("qa_lead_test_unit", QaLeadTestUnitPayload.class),
    QA_LEAD_TEST_IT("qa_lead_test_it", QaLeadTestItPayload.class),
    QA_LEAD_TEST_UI("qa_lead_test_ui", QaLeadTestUiPayload.class),
    REVIEWER("reviewer", ReviewerPayload.class),
    TEST_IT_COMPLETION("test_it_completion", TestItCompletionPayload.class);

    private final String id;
    private final Class<? extends AgentTicketPayload> payloadClass;

    public static AgentTicketPayloadType byId(final String id) {
        return Arrays.stream(values())
                .filter(value -> value.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown agent ticket payload type: " + id));
    }
}
