package com.sitionix.forgeai.domain.usecase;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface ManageOperatorUiTasks {

    OperatorUiServiceCatalogResponse services();

    OperatorUiTaskMutationResponse create(OperatorUiCreateTaskCommand command);

    OperatorUiTaskMutationResponse execute(UUID ticketId);

    record OperatorUiServiceCatalogResponse(List<OperatorUiServiceOption> services) {
    }

    record OperatorUiServiceOption(
            String id,
            String label,
            String path,
            String group,
            List<String> tags
    ) {
    }

    record OperatorUiCreateTaskCommand(
            String ticket,
            String task,
            List<String> serviceIds,
            String sourceTerminalTty
    ) {
    }

    record OperatorUiTaskMutationResponse(
            UUID ticketId,
            String ticketKey,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
