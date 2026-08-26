package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.model.*;
import java.util.UUID;

public record SaveLogSourceCommand(String name, UUID serviceId, LogConnectionType connectionType,
                                   UUID sshConnectionId, LogProviderType provider,
                                   LogProviderConfiguration configuration, boolean enabled) { }
