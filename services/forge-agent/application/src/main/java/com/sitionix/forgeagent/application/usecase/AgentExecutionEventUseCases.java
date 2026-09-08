package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventPage;
import com.sitionix.forgeagent.domain.port.AgentExecutionEventRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgentExecutionEventUseCases {
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 200;

    private final AgentExecutionEventRepository repository;

    @Transactional(readOnly = true)
    public AgentExecutionEventPage page(final UUID turnId, final long afterSequence, final int limit) {
        if (afterSequence < 0) {
            throw new ValidationException("INVALID_AGENT_EVENT_CURSOR", "Event cursor must not be negative.");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ValidationException("INVALID_AGENT_EVENT_PAGE_SIZE", "Event page size must be between 1 and 200.");
        }
        return this.repository.findPage(turnId, afterSequence, limit)
                .orElseThrow(() -> new NotFoundException(
                        "AGENT_EXECUTION_TURN_NOT_FOUND", "Agent execution turn was not found."));
    }
}
