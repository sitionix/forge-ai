package com.sitionix.forgeai.api.agentproxy;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ManualNodeSelectionRequest(@NotNull UUID outputPortId) {
}
