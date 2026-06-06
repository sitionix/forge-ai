package com.sitionix.forgeai.application.operator;

import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.stereotype.Service;

@Service
public class TicketOperatorEventService {

    private static final int BUFFER_LIMIT = 500;

    private final Map<UUID, Deque<TicketOperatorEvent>> recentEvents = new ConcurrentHashMap<>();
    private final Map<UUID, CopyOnWriteArrayList<BlockingQueue<TicketOperatorEvent>>> subscribers = new ConcurrentHashMap<>();

    public void publish(final TicketOperatorEvent event) {
        if (event == null || event.getTicketId() == null) {
            return;
        }
        final Deque<TicketOperatorEvent> buffer = this.recentEvents.computeIfAbsent(event.getTicketId(), ignored -> new ArrayDeque<>());
        synchronized (buffer) {
            buffer.addLast(event);
            while (buffer.size() > BUFFER_LIMIT) {
                buffer.removeFirst();
            }
        }
        this.subscribers.getOrDefault(event.getTicketId(), new CopyOnWriteArrayList<>())
                .forEach(queue -> queue.offer(event));
    }

    public List<TicketOperatorEvent> recentEvents(final UUID ticketId) {
        final Deque<TicketOperatorEvent> buffer = this.recentEvents.get(ticketId);
        if (buffer == null) {
            return List.of();
        }
        synchronized (buffer) {
            return List.copyOf(buffer);
        }
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
