package com.sitionix.forgeai.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import com.sitionix.forgeai.domain.usecase.TicketOperatorEventStream;
import com.sitionix.forgeai.mapper.ForgeAiOperatorApiMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class TicketOperatorStreamResourceFactory {

    private static final List<String> TERMINAL_EVENTS = List.of("TICKET_COMPLETED", "TICKET_CANCELLED", "TICKET_FAILED");

    private final ObjectMapper objectMapper;
    private final ForgeAiOperatorApiMapper forgeAiOperatorApiMapper;

    public TicketOperatorStreamResourceFactory(final ObjectMapper objectMapper,
                                               final ForgeAiOperatorApiMapper forgeAiOperatorApiMapper) {
        this.objectMapper = objectMapper;
        this.forgeAiOperatorApiMapper = forgeAiOperatorApiMapper;
    }

    public Resource create(final TicketOperatorEventStream stream, final boolean replay) {
        final PipedInputStream inputStream = new PipedInputStream();
        final PipedOutputStream outputStream;
        try {
            outputStream = new PipedOutputStream(inputStream);
        } catch (final IOException exception) {
            throw new IllegalStateException("Unable to open operator ticket stream", exception);
        }
        Thread.ofVirtual().start(() -> this.writeStream(stream, outputStream, replay));
        return new InputStreamResource(inputStream);
    }

    private void writeStream(final TicketOperatorEventStream stream,
                             final PipedOutputStream outputStream,
                             final boolean replay) {
        try (stream; outputStream; BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
            if (replay) {
                for (final TicketOperatorEvent event : stream.replay()) {
                    this.writeEvent(writer, event);
                }
                writer.flush();
            }
            while (true) {
                final TicketOperatorEvent event = stream.take();
                this.writeEvent(writer, event);
                writer.flush();
                if (TERMINAL_EVENTS.contains(event.getEventType())) {
                    break;
                }
            }
        } catch (final InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        } catch (final IOException ioException) {
            throw new IllegalStateException("Unable to write operator ticket stream", ioException);
        }
    }

    private void writeEvent(final BufferedWriter writer, final TicketOperatorEvent event) throws IOException {
        writer.write(this.objectMapper.writeValueAsString(this.forgeAiOperatorApiMapper.asTicketOperatorEvent(event)));
        writer.newLine();
    }
}
