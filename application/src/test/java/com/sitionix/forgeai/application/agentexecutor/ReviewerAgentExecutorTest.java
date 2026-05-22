package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.domain.model.ticket.agentticket.ReviewerPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(MockitoExtension.class)
class ReviewerAgentExecutorTest {

    private ReviewerAgentExecutor reviewerAgentExecutor;

    @BeforeEach
    void setUp() {
        this.reviewerAgentExecutor = new ReviewerAgentExecutor();
    }

    @Test
    void givenReadyToStartLane_whenExecuteLane_thenNoException() {
        //given
        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(UUID.randomUUID())
                .laneId(UUID.randomUUID())
                .build();

        //when
        //then
        assertThatCode(() -> this.reviewerAgentExecutor.executeLane(lane))
                .doesNotThrowAnyException();
    }
}
