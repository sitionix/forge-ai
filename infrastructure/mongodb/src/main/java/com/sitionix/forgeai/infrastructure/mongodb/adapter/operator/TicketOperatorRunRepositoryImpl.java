package com.sitionix.forgeai.infrastructure.mongodb.adapter.operator;

import com.sitionix.forgeai.domain.model.operator.TicketOperatorRun;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorRunStatus;
import com.sitionix.forgeai.domain.repository.TicketOperatorRunRepository;
import com.sitionix.forgeai.infrastructure.mongodb.TicketOperatorRunEntityMapper;
import com.sitionix.forgeai.infrastructure.mongodb.repository.operator.TicketOperatorRunJpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TicketOperatorRunRepositoryImpl implements TicketOperatorRunRepository {

    private final TicketOperatorRunJpaRepository repository;
    private final TicketOperatorRunEntityMapper mapper;

    @Override
    public TicketOperatorRun save(final TicketOperatorRun run) {
        return this.mapper.asDomain(this.repository.save(this.mapper.asDocument(run)));
    }

    @Override
    public Optional<TicketOperatorRun> findByTicketId(final UUID ticketId) {
        return this.repository.findById(ticketId).map(this.mapper::asDomain);
    }

    @Override
    public List<TicketOperatorRun> findActiveRuns() {
        return this.repository.findByStatusNotIn(List.of(
                        TicketOperatorRunStatus.CANCELLED,
                        TicketOperatorRunStatus.COMPLETED,
                        TicketOperatorRunStatus.FAILED,
                        TicketOperatorRunStatus.DISCONNECTED
                )).stream()
                .map(this.mapper::asDomain)
                .toList();
    }
}
