package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.domain.model.ticket.agentticket.ReviewerPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.usecase.CompleteAgentLane;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class ReviewerAgentExecutorTest {

    @Mock
    private CompleteAgentLane completeAgentLane;

    private ReviewerAgentExecutor reviewerAgentExecutor;

    @BeforeEach
    void setUp() {
        this.reviewerAgentExecutor = new ReviewerAgentExecutor(this.completeAgentLane);
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.completeAgentLane);
    }

    @Test
    void givenReadyToStartLane_whenExecuteLane_thenCompleteLane() {
        //given
        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(UUID.randomUUID())
                .laneId(UUID.randomUUID())
                .build();

        //when
        this.reviewerAgentExecutor.executeLane(lane);

        //then
        verify(this.completeAgentLane).completeAndPrepareAgents(lane.getLaneId());
    }
}
