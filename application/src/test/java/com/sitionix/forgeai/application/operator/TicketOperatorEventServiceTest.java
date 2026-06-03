package com.sitionix.forgeai.application.operator;

import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TicketOperatorEventServiceTest {

    private final TicketOperatorEventService service = new TicketOperatorEventService();

    @Test
    void givenTwoTickets_whenPublish_thenSubscriptionAndReplayStayTicketScoped() throws Exception {
        final UUID ticketA = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
        final UUID ticketB = UUID.fromString("bbbbbbbb-1111-1111-1111-111111111111");

        try (var subscription = this.service.subscribe(ticketA)) {
            final TicketOperatorEvent eventA = this.event(ticketA, "LANE_STARTED", "a");
            final TicketOperatorEvent eventB = this.event(ticketB, "LANE_STARTED", "b");

            this.service.publish(eventA);
            this.service.publish(eventB);

            assertThat(subscription.take()).isEqualTo(eventA);
            assertThat(this.service.recentEvents(ticketA)).containsExactly(eventA);
            assertThat(this.service.recentEvents(ticketB)).containsExactly(eventB);
        }
    }

    @Test
    void givenMinimalVerbosity_whenFilter_thenHidePromptLikeNoise() {
        final UUID ticketId = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
        final TicketOperatorEvent visible = this.event(ticketId, "STEP_STARTED", "visible");
        final TicketOperatorEvent commandOutput = this.event(ticketId, "COMMAND_OUTPUT", "hidden");
        final TicketOperatorEvent delta = this.event(ticketId, "AGENT_MESSAGE_DELTA", "hidden");

        assertThat(this.service.filterByVerbosity(List.of(visible, commandOutput, delta), "minimal"))
                .containsExactly(visible);
    }

    private TicketOperatorEvent event(final UUID ticketId, final String type, final String message) {
        return TicketOperatorEvent.builder()
                .ticketId(ticketId)
                .eventType(type)
                .message(message)
                .timestamp(Instant.now())
                .build();
    }
}
