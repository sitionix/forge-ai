package com.sitionix.forgeai.infrastructure.mongodb.adapter;

import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneDependency;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.infrastructure.mongodb.LaneEntityMapper;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.infrastructure.mongodb.TicketEntityMapper;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.repository.TicketJpaRepository;
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
public class TicketRepositoryImpl implements TicketRepository {

    private final TicketJpaRepository ticketRepository;
    private final TicketEntityMapper ticketEntityMapper;
    private final LaneEntityMapper laneEntityMapper;
    private final MongoTemplate mongoTemplate;

    @Override
    public Ticket save(final Ticket ticket) {
        final TicketDocument document = this.ticketEntityMapper.asTicketDocument(ticket);
        final TicketDocument saved = this.ticketRepository.save(document);
        return this.ticketEntityMapper.asTicket(saved);
    }

    @Override
    public Optional<Ticket> findById(final UUID ticketId) {
        return this.ticketRepository.findById(ticketId)
                .map(this.ticketEntityMapper::asTicket);
    }

    @Override
    public List<ReadyToStartLane> findAllReadyToStartLanes() {
        return this.ticketRepository.findAllReadyToStartLanes().stream()
                .map(value -> ReadyToStartLane.builder()
                        .ticketId(value.getTicketId())
                        .ticketKey(value.getTicketKey())
                        .sourceTerminalTty(value.getSourceTerminalTty())
                        .laneId(value.getLaneId())
                        .agent(value.getAgent())
                        .scope(value.getScope())
                        .serviceId(value.getServiceId())
                        .attempt(value.getAttempt())
                        .build())
                .toList();
    }

    @Override
    public String findTicketContentById(final UUID ticketId) {
        return this.ticketRepository.findById(ticketId)
                .map(TicketDocument::getTaskDescription)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found with id: " + ticketId));
    }

    @Override
    public Optional<Lane> findByLaneId(final UUID laneId) {
        final Query query = Query.query(Criteria.where("lanes._id").is(laneId));
        final TicketDocument ticketDocument = this.mongoTemplate.findOne(query, TicketDocument.class);
        if (ticketDocument == null || ticketDocument.getLanes() == null) {
            return Optional.empty();
        }
        return ticketDocument.getLanes()
                .stream()
                .filter(laneDocument -> Objects.equals(laneDocument.getId(), laneId))
                .findFirst()
                .map(this.laneEntityMapper::asLane);
    }

    @Override
    public void updateLaneStatus(final UUID laneId, final LaneStatus laneStatus) {
        final Query query = Query.query(Criteria.where("lanes._id").is(laneId));
        final Update update = new Update().set("lanes.$.status", laneStatus);
        this.mongoTemplate.updateFirst(query, update, TicketDocument.class);
    }

    @Override
    public boolean moveLaneToInProgressIfReady(final UUID laneId) {
        final Query query = Query.query(Criteria.where("lanes").elemMatch(
                Criteria.where("_id").is(laneId).and("status").is(LaneStatus.READY_TO_START)
        ));
        final Update update = new Update().set("lanes.$.status", LaneStatus.IN_PROGRESS);
        return this.mongoTemplate.updateFirst(query, update, TicketDocument.class).getModifiedCount() > 0;
    }

    @Override
    public boolean isReadyToStart(final UUID laneId) {
        final Query query = Query.query(Criteria.where("lanes._id").is(laneId));
        final TicketDocument ticketDocument = this.mongoTemplate.findOne(query, TicketDocument.class);
        if (ticketDocument == null || ticketDocument.getLanes() == null) {
            return false;
        }

        final Optional<Lane> laneOptional = this.findLane(ticketDocument, laneId);
        if (laneOptional.isEmpty()) {
            return false;
        }
        final Lane lane = laneOptional.get();
        if (Objects.equals(lane.getAgent(), Agent.REVIEWER)) {
            return ticketDocument.getLanes().stream()
                    .filter(value -> !Objects.equals(value.getId(), laneId))
                    .allMatch(value -> Objects.equals(value.getStatus(), LaneStatus.COMPLETED)
                            || Objects.equals(value.getStatus(), LaneStatus.NOT_NEEDED));
        }
        if (this.hasNoDependencies(lane)) {
            return true;
        }

        return lane.getDependsOn().stream()
                .allMatch(dependency -> this.isDependencyCompleted(ticketDocument, dependency));
    }

    @Override
    public void moveReviewerToReadyToStartIfPossible(final UUID laneId) {
        final Query query = Query.query(Criteria.where("lanes._id").is(laneId));
        final TicketDocument ticketDocument = this.mongoTemplate.findOne(query, TicketDocument.class);
        if (ticketDocument == null || ticketDocument.getLanes() == null) {
            return;
        }

        final Optional<UUID> reviewerLaneIdOptional = ticketDocument.getLanes().stream()
                .filter(Objects::nonNull)
                .filter(value -> Objects.equals(value.getType(), Agent.REVIEWER))
                .filter(value -> Objects.equals(value.getStatus(), LaneStatus.NOT_STARTED))
                .filter(value -> Objects.nonNull(value.getId()))
                .map(value -> value.getId())
                .findFirst();
        if (reviewerLaneIdOptional.isEmpty()) {
            return;
        }

        final UUID reviewerLaneId = reviewerLaneIdOptional.get();
        final boolean allOtherLanesTerminal = ticketDocument.getLanes().stream()
                .filter(Objects::nonNull)
                .filter(value -> !Objects.equals(value.getId(), reviewerLaneId))
                .allMatch(value -> Objects.equals(value.getStatus(), LaneStatus.COMPLETED)
                        || Objects.equals(value.getStatus(), LaneStatus.NOT_NEEDED));
        if (!allOtherLanesTerminal) {
            return;
        }

        this.updateLaneStatus(reviewerLaneId, LaneStatus.READY_TO_START);
    }

    private Optional<Lane> findLane(final TicketDocument ticketDocument, final UUID laneId) {
        return ticketDocument.getLanes().stream()
                .filter(value -> Objects.equals(value.getId(), laneId))
                .findFirst()
                .map(this.laneEntityMapper::asLane);
    }

    private boolean hasNoDependencies(final Lane lane) {
        return lane.getDependsOn() == null || lane.getDependsOn().isEmpty();
    }

    private boolean isDependencyCompleted(final TicketDocument ticketDocument, final LaneDependency dependency) {
        return ticketDocument.getLanes().stream()
                .filter(value -> Objects.equals(value.getType(), dependency.getType())
                        && Objects.equals(value.getScope(), dependency.getScope()))
                .anyMatch(value -> Objects.equals(value.getStatus(), LaneStatus.COMPLETED)
                        || Objects.equals(value.getStatus(), LaneStatus.NOT_NEEDED));
    }
}
