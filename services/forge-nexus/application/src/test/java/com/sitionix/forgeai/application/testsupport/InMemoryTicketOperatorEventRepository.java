package com.sitionix.forgeai.application.testsupport;

import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import com.sitionix.forgeai.domain.repository.TicketOperatorEventRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryTicketOperatorEventRepository implements TicketOperatorEventRepository {

    private final Map<UUID, List<TicketOperatorEvent>> events = new ConcurrentHashMap<>();

    @Override
    public TicketOperatorEvent save(final TicketOperatorEvent event) {
        this.events.computeIfAbsent(event.getTicketId(), ignored -> new ArrayList<>()).add(event);
        return event;
    }

    @Override
    public List<TicketOperatorEvent> findRecentByTicketId(final UUID ticketId, final int limit) {
        return this.events.getOrDefault(ticketId, List.of()).stream()
                .sorted(Comparator.comparing(TicketOperatorEvent::getTimestamp))
                .skip(Math.max(0, this.events.getOrDefault(ticketId, List.of()).size() - limit))
                .toList();
    }

    @Override
    public void deleteByTicketId(final UUID ticketId) {
        this.events.remove(ticketId);
    }
}
