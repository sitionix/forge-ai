package com.sitionix.forgeai.infrastructure.mongodb.adapter;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.infrastructure.mongodb.LaneEntityMapper;
import com.sitionix.forgeai.infrastructure.mongodb.entity.LaneDependencyDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.LaneDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LaneRepositoryImpl implements LaneRepository {

    private final LaneEntityMapper laneEntityMapper;
    private final MongoTemplate mongoTemplate;

    @Override
    public Lane findLaneToProduce(final UUID relatedLaneId, final String scope, final Agent agent) {
        return this.findLaneToProduceOptional(relatedLaneId, scope, agent)
                .orElseThrow(() -> new IllegalStateException(
                        "Expected exactly one lane for relatedLaneId=" + relatedLaneId + ", scope=" + scope + ", agent=" + agent + ", found=0"));
    }

    @Override
    public Optional<Lane> findLaneToProduceOptional(final UUID relatedLaneId, final String scope, final Agent agent) {
        final List<Lane> lanes = this.findTicketContainingLane(relatedLaneId).getLanes().stream()
                .filter(lane -> Objects.equals(lane.getScope(), scope))
                .filter(lane -> Objects.equals(lane.getType(), agent))
                .map(this.laneEntityMapper::asLane)
                .toList();
        if (lanes.isEmpty()) {
            return Optional.empty();
        }
        if (lanes.size() > 1) {
            throw new IllegalStateException(
                    "Expected exactly one lane for relatedLaneId=" + relatedLaneId + ", scope=" + scope + ", agent=" + agent + ", found=" + lanes.size());
        }
        return Optional.of(lanes.getFirst());
    }

    @Override
    public void assignInputTaskId(final UUID laneId, final UUID inputTaskId) {
        final Query query = this.laneElementQuery(laneId);
        final Update update = new Update().addToSet("lanes.$.inputTaskIds", inputTaskId);
        final long updated = this.mongoTemplate.updateFirst(query, update, TicketDocument.class).getModifiedCount();
        if (updated == 0) {
            final TicketDocument ticket = this.findTicketContainingLane(laneId);
            final LaneDocument lane = this.findLaneDocument(ticket, laneId);
            if (lane.getInputTaskIds() == null) {
                lane.setInputTaskIds(new LinkedHashSet<>());
            }
            lane.getInputTaskIds().add(inputTaskId);
            this.mongoTemplate.save(ticket);
        }
    }

    @Override
    public List<Lane> findProducedLanes(final UUID sourceLaneId) {
        final TicketDocument ticket = this.findTicketContainingLane(sourceLaneId);
        final LaneDocument sourceLane = this.findLaneDocument(ticket, sourceLaneId);

        return ticket.getLanes().stream()
                .filter(value -> !Objects.equals(value.getId(), sourceLane.getId()))
                .filter(value -> this.dependsOnSourceLane(value, sourceLane))
                .map(this.laneEntityMapper::asLane)
                .toList();
    }

    @Override
    public List<Lane> findCompletionTargetLanes(final UUID sourceLaneId) {
        final TicketDocument ticket = this.findTicketContainingLane(sourceLaneId);
        final LaneDocument sourceLane = this.findLaneDocument(ticket, sourceLaneId);

        return ticket.getLanes().stream()
                .filter(value -> !Objects.equals(value.getId(), sourceLane.getId()))
                .filter(value -> this.isCompletionTarget(value, sourceLane))
                .map(this.laneEntityMapper::asLane)
                .toList();
    }

    private TicketDocument findTicketContainingLane(final UUID laneId) {
        return this.findTicketByLaneId(laneId)
                .orElseThrow(() -> new IllegalArgumentException("Lane not found with id: " + laneId));
    }

    private Optional<TicketDocument> findTicketByLaneId(final UUID laneId) {
        final TicketDocument indexed = this.mongoTemplate.findOne(this.laneIdQuery(laneId), TicketDocument.class);
        if (indexed != null) {
            return Optional.of(indexed);
        }
        return this.mongoTemplate.findAll(TicketDocument.class).stream()
                .filter(ticket -> ticket.getLanes() != null)
                .filter(ticket -> ticket.getLanes().stream().anyMatch(lane -> Objects.equals(lane.getId(), laneId)))
                .findFirst();
    }

    private LaneDocument findLaneDocument(final TicketDocument ticket, final UUID laneId) {
        return ticket.getLanes().stream()
                .filter(value -> Objects.equals(value.getId(), laneId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Lane not found with id: " + laneId));
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

    private boolean isCompletionTarget(final LaneDocument lane, final LaneDocument sourceLane) {
        if (sourceLane.getType() == null || sourceLane.getType().getInfo() == null || lane.getType() == null) {
            return false;
        }
        if (sourceLane.getType().getInfo().getProduces() == null
                || !sourceLane.getType().getInfo().getProduces().contains(lane.getType())) {
            return false;
        }
        return Objects.equals(lane.getScope(), sourceLane.getScope())
                || this.isGlobalScope(lane.getScope())
                || this.isGlobalScope(sourceLane.getScope());
    }

    private boolean isGlobalScope(final String scope) {
        return ScopeMode.GLOBAL_SCOPE.equals(scope);
    }

    private Query laneIdQuery(final UUID laneId) {
        return Query.query(new Criteria().orOperator(
                Criteria.where("lanes._id").is(laneId),
                Criteria.where("lanes._id").is(laneId.toString()),
                Criteria.where("lanes.id").is(laneId),
                Criteria.where("lanes.id").is(laneId.toString())
        ));
    }

    private Query laneElementQuery(final UUID laneId) {
        return Query.query(Criteria.where("lanes").elemMatch(new Criteria().orOperator(
                Criteria.where("_id").is(laneId),
                Criteria.where("_id").is(laneId.toString()),
                Criteria.where("id").is(laneId),
                Criteria.where("id").is(laneId.toString())
        )));
    }
}
