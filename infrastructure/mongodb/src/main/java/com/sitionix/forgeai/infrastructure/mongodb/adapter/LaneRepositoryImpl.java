package com.sitionix.forgeai.infrastructure.mongodb.adapter;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.infrastructure.mongodb.LaneEntityMapper;
import com.sitionix.forgeai.infrastructure.mongodb.entity.LaneDependencyDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.LaneDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.repository.LaneJpaRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LaneRepositoryImpl implements LaneRepository {

    private final LaneEntityMapper laneEntityMapper;
    private final LaneJpaRepository laneJpaRepository;

    @Override
    public Lane findLaneToProduce(final UUID relatedLaneId, final String scope, final Agent agent) {
        return this.findLaneToProduceOptional(relatedLaneId, scope, agent)
                .orElseThrow(() -> new IllegalStateException(
                        "Expected exactly one lane for relatedLaneId=" + relatedLaneId + ", scope=" + scope + ", agent=" + agent + ", found=0"));
    }

    @Override
    public Optional<Lane> findLaneToProduceOptional(final UUID relatedLaneId, final String scope, final Agent agent) {
        final List<LaneDocument> lanes = this.laneJpaRepository.findLanesToProduce(relatedLaneId, scope, agent);
        if (lanes.isEmpty()) {
            return Optional.empty();
        }
        if (lanes.size() > 1) {
            throw new IllegalStateException(
                    "Expected exactly one lane for relatedLaneId=" + relatedLaneId + ", scope=" + scope + ", agent=" + agent + ", found=" + lanes.size());
        }
        return Optional.of(this.laneEntityMapper.asLane(lanes.getFirst()));
    }

    @Override
    public void assignInputTaskId(final UUID laneId, final UUID inputTaskId) {
        this.validateInputTaskAssignment(laneId, inputTaskId);
        final long updated = this.laneJpaRepository.assignInputTaskId(laneId, inputTaskId);
        if (updated == 0) {
            throw new IllegalArgumentException("Lane not found with id: " + laneId);
        }
    }

    private void validateInputTaskAssignment(final UUID laneId, final UUID inputTaskId) {
        final TicketDocument ticket = this.laneJpaRepository.findTicketByLaneId(laneId)
                .orElseThrow(() -> new IllegalArgumentException("Lane not found with id: " + laneId));

        final LaneDocument laneDocument = ticket.getLanes().stream()
                .filter(value -> Objects.equals(value.getId(), laneId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Lane not found with id: " + laneId));

        if (Objects.equals(laneDocument.getScope(), ScopeMode.GLOBAL_SCOPE)) {
            return;
        }
        if (Objects.isNull(laneDocument.getInputTaskIds()) || laneDocument.getInputTaskIds().isEmpty()) {
            return;
        }
        if (laneDocument.getInputTaskIds().contains(inputTaskId)) {
            return;
        }
        throw new IllegalStateException("Multiple input task ids are allowed only for GLOBAL lane scope. laneId="
                + laneId + ", scope=" + laneDocument.getScope());
    }

    @Override
    public List<Lane> findProducedLanes(final UUID sourceLaneId) {
        final TicketDocument ticket = this.laneJpaRepository.findTicketByLaneId(sourceLaneId)
                .orElseThrow(() -> new IllegalArgumentException("Lane not found with id: " + sourceLaneId));

        final LaneDocument sourceLane = ticket.getLanes().stream()
                .filter(value -> Objects.equals(value.getId(), sourceLaneId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Lane not found with id: " + sourceLaneId));

        return ticket.getLanes().stream()
                .filter(value -> this.dependsOnSourceLane(value, sourceLane))
                .map(this.laneEntityMapper::asLane)
                .toList();
    }

    private boolean dependsOnSourceLane(final LaneDocument lane, final LaneDocument sourceLane) {
        if (Objects.isNull(lane.getDependsOn())) {
            return false;
        }
        return lane.getDependsOn().stream()
                .anyMatch(dependency -> this.isSourceDependency(dependency, sourceLane));
    }

    private boolean isSourceDependency(final LaneDependencyDocument dependency, final LaneDocument sourceLane) {
        return Objects.equals(dependency.getType(), sourceLane.getType())
                && Objects.equals(dependency.getScope(), sourceLane.getScope());
    }
}
