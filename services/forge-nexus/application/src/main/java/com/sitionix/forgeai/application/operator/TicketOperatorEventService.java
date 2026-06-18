package com.sitionix.forgeai.application.operator;

import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import com.sitionix.forgeai.domain.repository.TicketOperatorEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketOperatorEventService {

    private static final int BUFFER_LIMIT = 500;

    private final TicketOperatorEventRepository ticketOperatorEventRepository;
    private final Map<UUID, CopyOnWriteArrayList<BlockingQueue<TicketOperatorEvent>>> subscribers = new ConcurrentHashMap<>();

    public void publish(final TicketOperatorEvent event) {
        if (event == null || event.getTicketId() == null) {
            return;
        }
        final TicketOperatorEvent savedEvent = this.ticketOperatorEventRepository.save(this.normalize(event));
        this.subscribers.getOrDefault(savedEvent.getTicketId(), new CopyOnWriteArrayList<>())
                .forEach(queue -> queue.offer(savedEvent));
    }

    public List<TicketOperatorEvent> recentEvents(final UUID ticketId) {
        if (ticketId == null) {
            return List.of();
        }
        return this.ticketOperatorEventRepository.findRecentByTicketId(ticketId, BUFFER_LIMIT);
    }

    public void clear(final UUID ticketId) {
        if (ticketId == null) {
            return;
        }
        this.ticketOperatorEventRepository.deleteByTicketId(ticketId);
        this.subscribers.remove(ticketId);
    }

    public Subscription subscribe(final UUID ticketId) {
        final BlockingQueue<TicketOperatorEvent> queue = new LinkedBlockingQueue<>();
        this.subscribers.computeIfAbsent(ticketId, ignored -> new CopyOnWriteArrayList<>()).add(queue);
        return new Subscription(ticketId, queue);
    }

    public List<TicketOperatorEvent> filterByVerbosity(final List<TicketOperatorEvent> events, final String verbosity) {
        return events.stream()
                .filter(event -> this.includeByVerbosity(event, verbosity))
                .toList();
    }

    public boolean includeByVerbosity(final TicketOperatorEvent event, final String verbosity) {
        final String mode = verbosity == null || verbosity.isBlank() ? "minimal" : verbosity;
        if (Objects.equals(mode, "verbose")) {
            return true;
        }
        if (Objects.equals(mode, "commands")) {
            return !"AGENT_MESSAGE_DELTA".equals(event.getEventType());
        }
        return switch (event.getEventType()) {
            case "COMMAND_STARTED", "COMMAND_COMPLETED", "COMMAND_OUTPUT", "AGENT_MESSAGE_DELTA", "PLAN", "PROCESS_STDERR", "HEARTBEAT" -> false;
            default -> true;
        };
    }

    private TicketOperatorEvent normalize(final TicketOperatorEvent event) {
        if (event.getTimestamp() != null) {
            return event;
        }
        return event.toBuilder()
                .timestamp(Instant.now())
                .build();
    }

    public final class Subscription implements AutoCloseable {
        private final UUID ticketId;
        private final BlockingQueue<TicketOperatorEvent> queue;

        private Subscription(final UUID ticketId, final BlockingQueue<TicketOperatorEvent> queue) {
            this.ticketId = ticketId;
            this.queue = queue;
        }

        public List<TicketOperatorEvent> drainAvailable() {
            final List<TicketOperatorEvent> drained = new ArrayList<>();
            this.queue.drainTo(drained);
            return drained;
        }

        public TicketOperatorEvent take() throws InterruptedException {
            return this.queue.take();
        }

        @Override
        public void close() {
            TicketOperatorEventService.this.subscribers
                    .getOrDefault(this.ticketId, new CopyOnWriteArrayList<>())
                    .remove(this.queue);
        }
    }
}
