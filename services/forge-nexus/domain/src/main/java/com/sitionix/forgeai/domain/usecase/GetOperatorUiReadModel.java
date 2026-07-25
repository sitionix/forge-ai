package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.operator.read.OperatorUiLaneDetailResponse;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiTicketGraphResponse;
import com.sitionix.forgeai.domain.model.operator.read.OperatorUiTicketListResponse;
import java.util.UUID;

public interface GetOperatorUiReadModel {

    OperatorUiTicketListResponse tickets(Integer limit);

    OperatorUiTicketGraphResponse graph(UUID ticketId);

    OperatorUiLaneDetailResponse lane(UUID ticketId, UUID laneId);
}
