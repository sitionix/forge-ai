package com.sitionix.forgeai.api.agentproxy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

public record CreateAgentProjectTaskRequest(@NotBlank String title, @NotBlank String input, @NotNull UUID workflowId,
                                            @NotNull @NotEmpty List<@NotNull UUID> repositoryIds) {
}
