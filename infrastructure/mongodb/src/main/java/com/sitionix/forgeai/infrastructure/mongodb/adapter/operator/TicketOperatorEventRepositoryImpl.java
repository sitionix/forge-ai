package com.sitionix.forgeai.infrastructure.mongodb.adapter.operator;

import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import com.sitionix.forgeai.domain.repository.TicketOperatorEventRepository;
import com.sitionix.forgeai.infrastructure.mongodb.TicketOperatorEventEntityMapper;
import com.sitionix.forgeai.infrastructure.mongodb.repository.operator.TicketOperatorEventJpaRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TicketOperatorEventRepositoryImpl implements TicketOperatorEventRepository {

    private final TicketOperatorEventJpaRepository repository;
    private final TicketOperatorEventEntityMapper mapper;

    @Override
    public TicketOperatorEvent save(final TicketOperatorEvent event) {
        return this.mapper.asDomain(this.repository.save(this.mapper.asDocument(event)));
    }

    @Override
    public List<TicketOperatorEvent> findRecentByTicketId(final UUID ticketId, final int limit) {
        if (ticketId == null || limit <= 0) {
            return List.of();
        }
        final List<TicketOperatorEvent> events = new ArrayList<>(this.repository
                .findByTicketIdOrderByTimestampDesc(ticketId, PageRequest.of(0, limit))
                .stream()
                .map(this.mapper::asDomain)
                .toList());
        Collections.reverse(events);
        return events;
    }

    @Override
    public void deleteByTicketId(final UUID ticketId) {
        this.repository.deleteByTicketId(ticketId);
    }
}
