package com.sitionix.forgeai.domain.model.ticket.lane;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import java.util.Map;

public interface ExecuteAgent<P extends AgentTicketPayload> {

    void executeLane(final ReadyToStartLane lane);

    void validateFinalCompletionPayload(ReadyToStartLane lane, Map<String, Object> completionPayload);

    void completeLane(ReadyToStartLane lane, Map<String, Object> completionPayload);
}
