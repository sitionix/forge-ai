package com.sitionix.forgeai.application.laneexecution;

import com.sitionix.forgeai.application.agentexecutor.LaneCompletionContractResolver;
import com.sitionix.forgeai.domain.model.lanecompletion.ApiCompletionEvidence;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionOutputContract;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadContract;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadObjectContract;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.repository.CompletionPayloadContractRepository;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CompletionPayloadContractBuilder {

    private final LaneRepository laneRepository;
    private final LaneCompletionContractResolver laneCompletionContractResolver;
    private final CompletionPayloadContractRepository completionPayloadContractRepository;

    public CompletionPayloadContract build(final ReadyToStartLane lane) {
        final List<CompletionOutputContract> outputs = this.outputContracts(lane);
        final CompletionPayloadObjectContract apiEvidence = this.laneCompletionContractResolver.requiresApiCompletionEvidence(lane.getAgent())
                ? this.completionPayloadContractRepository.findByType(ApiCompletionEvidence.class)
                : null;
        final CompletionPayloadObjectContract report = this.laneCompletionContractResolver.completionReportPayloadType(lane.getAgent())
                .map(this.completionPayloadContractRepository::findByType)
                .orElse(null);
        return new CompletionPayloadContract(
                outputs,
                apiEvidence != null,
                apiEvidence,
                report
        );
    }

    private List<CompletionOutputContract> outputContracts(final ReadyToStartLane lane) {
        if (!this.laneCompletionContractResolver.writesProducedLaneOutputs(lane.getAgent())) {
            return List.of();
        }
        return this.laneRepository.findCompletionTargetLanes(lane.getLaneId()).stream()
                .map(targetLane -> this.outputContract(lane, targetLane))
                .toList();
    }

    private CompletionOutputContract outputContract(final ReadyToStartLane sourceLane, final Lane targetLane) {
        final Class<? extends AgentTicketPayload> payloadType =
                this.laneCompletionContractResolver.inputPayloadType(sourceLane.getAgent(), targetLane.getAgent());
        return new CompletionOutputContract(
                targetLane.getAgent().getId(),
                targetLane.getScope(),
                ScopeMode.producedPayloadScope(sourceLane.getScope(), targetLane.getScope()),
                true,
                this.completionPayloadContractRepository.findByType(payloadType)
        );
    }
}
