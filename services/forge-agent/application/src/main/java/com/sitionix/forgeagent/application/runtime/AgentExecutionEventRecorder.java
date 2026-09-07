package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.AgentExecutionEventAppendResult;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventCandidate;
import com.sitionix.forgeagent.domain.model.AgentSessionExecutionClaim;
import com.sitionix.forgeagent.domain.port.AgentExecutionEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionEventRecorder {
    private final AgentExecutionEventRepository repository;

    public void activate(final AgentSessionExecutionClaim claim) {
        try {
            if (!this.repository.activate(claim)) {
                log.warn("Agent event capture activation rejected by fence sessionId={} turnId={}",
                        claim.sessionId(), claim.turnId());
            }
        } catch (final RuntimeException failure) {
            this.degrade(claim, "activate", failure);
        }
    }

    public void record(final AgentSessionExecutionClaim claim, final AgentExecutionEventCandidate candidate) {
        try {
            final AgentExecutionEventAppendResult result = this.repository.append(claim, candidate);
            if (result == AgentExecutionEventAppendResult.STALE) {
                log.warn("Agent event append rejected by fence sessionId={} turnId={} type={}",
                        claim.sessionId(), claim.turnId(), candidate.type());
            }
        } catch (final RuntimeException failure) {
            this.degrade(claim, "append", failure);
        }
    }

    public void complete(final AgentSessionExecutionClaim claim, final AgentExecutionEventCandidate terminalEvent) {
        try {
            final AgentExecutionEventAppendResult result = this.repository.append(claim, terminalEvent);
            if (result == AgentExecutionEventAppendResult.STALE) {
                log.warn("Agent terminal event rejected by fence sessionId={} turnId={}",
                        claim.sessionId(), claim.turnId());
                return;
            }
            if (!this.repository.markComplete(claim)) {
                log.warn("Agent event capture completion rejected sessionId={} turnId={}",
                        claim.sessionId(), claim.turnId());
            }
        } catch (final RuntimeException failure) {
            this.degrade(claim, "complete", failure);
        }
    }

    public void degrade(final AgentSessionExecutionClaim claim, final String operation,
                        final RuntimeException failure) {
        log.warn("Agent event capture {} failed sessionId={} turnId={} errorType={}",
                operation, claim.sessionId(), claim.turnId(), failure.getClass().getSimpleName());
        try {
            if (!this.repository.markDegraded(claim)) {
                log.warn("Agent event capture degradation rejected sessionId={} turnId={}",
                        claim.sessionId(), claim.turnId());
            }
        } catch (final RuntimeException degradationFailure) {
            log.warn("Agent event capture degradation failed sessionId={} turnId={} errorType={}",
                    claim.sessionId(), claim.turnId(), degradationFailure.getClass().getSimpleName());
        }
    }
}
