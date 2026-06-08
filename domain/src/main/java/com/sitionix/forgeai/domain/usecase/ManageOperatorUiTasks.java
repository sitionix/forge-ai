package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.operator.task.OperatorUiCreateTaskCommand;
import com.sitionix.forgeai.domain.model.operator.task.OperatorUiServiceCatalogResponse;
import com.sitionix.forgeai.domain.model.operator.task.OperatorUiTaskMutationResponse;
import java.util.UUID;

public interface ManageOperatorUiTasks {

    OperatorUiServiceCatalogResponse services();

    OperatorUiTaskMutationResponse create(OperatorUiCreateTaskCommand command);

    OperatorUiTaskMutationResponse execute(UUID ticketId);

    void retryLane(UUID ticketId, UUID laneId);

    void delete(UUID ticketId);
}
