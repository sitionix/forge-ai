package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.CompleteArchitectLaneCommand;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.usecase.CompleteArchitectLane;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CompleteArchitectLaneUseCase implements CompleteArchitectLane {

    private static final String GLOBAL_SCOPE = ScopeMode.GLOBAL_SCOPE;

    private final CreateAgentTask createAgentTask;

    @Override
    public void complete(final CompleteArchitectLaneCommand command) {
        if (command.getImplementBeTicket() != null) {
            this.createAgentTask.create(command.getImplementBeTicket(), command.getSourceLaneId());
        }
        if (command.getImplementFeTicket() != null) {
            this.createAgentTask.create(command.getImplementFeTicket(), command.getSourceLaneId());
        }

        if (Boolean.FALSE.equals(command.getApiRequired())) {
            this.createAgentTask.markAsNotNeeded(command.getSourceLaneId(), GLOBAL_SCOPE, Agent.API);
        } else if (command.getApiTicket() != null) {
            command.getApiTicket().setScope(GLOBAL_SCOPE);
            this.createAgentTask.create(command.getApiTicket(), command.getSourceLaneId());
        }

        if (Boolean.FALSE.equals(command.getEventRequired())) {
            this.createAgentTask.markAsNotNeeded(command.getSourceLaneId(), GLOBAL_SCOPE, Agent.EVENT);
        } else if (command.getEventTicket() != null) {
            command.getEventTicket().setScope(GLOBAL_SCOPE);
            this.createAgentTask.create(command.getEventTicket(), command.getSourceLaneId());
        }
    }
}
