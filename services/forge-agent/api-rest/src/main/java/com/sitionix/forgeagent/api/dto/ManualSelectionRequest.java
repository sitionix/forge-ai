package com.sitionix.forgeagent.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ManualSelectionRequest(@NotNull UUID outputPortId) {
}
