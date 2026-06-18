package com.sitionix.forgeai.domain.model.operator.task;

import java.util.List;

public record OperatorUiCreateTaskCommand(
        String ticket,
        String task,
        List<String> serviceIds,
        String sourceTerminalTty
) {
}
