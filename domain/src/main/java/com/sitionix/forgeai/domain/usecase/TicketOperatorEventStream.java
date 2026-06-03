package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.operator.TicketOperatorEvent;
import java.util.List;

public interface TicketOperatorEventStream extends AutoCloseable {

    List<TicketOperatorEvent> replay();

    TicketOperatorEvent take() throws InterruptedException;

    @Override
    void close();
}
