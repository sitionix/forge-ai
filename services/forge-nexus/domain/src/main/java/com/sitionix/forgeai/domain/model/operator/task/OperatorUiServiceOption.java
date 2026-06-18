package com.sitionix.forgeai.domain.model.operator.task;

import java.util.List;

public record OperatorUiServiceOption(
        String id,
        String label,
        String path,
        String group,
        List<String> tags
) {
}
