package com.sitionix.forgeai.infrastructure.mongodb.adapter;

import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneDependency;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
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
    public boolean isReadyToStart(final UUID laneId) {
        final Query query = Query.query(Criteria.where("lanes._id").is(laneId));
        final TicketDocument ticketDocument = this.mongoTemplate.findOne(query, TicketDocument.class);
        if (ticketDocument == null || ticketDocument.getLanes() == null) {
            return false;
        }

        final Lane lane = ticketDocument.getLanes()
                .stream()
                .filter(value -> Objects.equals(value.getId(), laneId))
                .findFirst()
                .map(this.laneEntityMapper::asLane)
                .orElse(null);
        if (lane == null) {
            return false;
        }
        if (lane.getInputTaskIds() == null || lane.getInputTaskIds().isEmpty()) {
            return false;
        }
        if (lane.getDependsOn() == null || lane.getDependsOn().isEmpty()) {
            return true;
        }

        return lane.getDependsOn().stream().allMatch(dependency -> this.isDependencyCompleted(ticketDocument, dependency));
    }

    private boolean isDependencyCompleted(final TicketDocument ticketDocument, final LaneDependency dependency) {
        return ticketDocument.getLanes().stream()
                .filter(value -> Objects.equals(value.getType(), dependency.getType())
                        && Objects.equals(value.getScope(), dependency.getScope()))
                .anyMatch(value -> Objects.equals(value.getStatus(), LaneStatus.COMPLETED)
                        || Objects.equals(value.getStatus(), LaneStatus.NOT_NEEDED));
    }
}
