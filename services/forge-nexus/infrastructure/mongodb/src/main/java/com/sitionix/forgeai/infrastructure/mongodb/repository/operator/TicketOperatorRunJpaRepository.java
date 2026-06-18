package com.sitionix.forgeai.infrastructure.mongodb.repository.operator;

import com.sitionix.forgeai.domain.model.operator.TicketOperatorRunStatus;
import com.sitionix.forgeai.infrastructure.mongodb.entity.operator.TicketOperatorRunDocument;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TicketOperatorRunJpaRepository extends MongoRepository<TicketOperatorRunDocument, UUID> {

    List<TicketOperatorRunDocument> findByStatusNotIn(Collection<TicketOperatorRunStatus> statuses);
}
