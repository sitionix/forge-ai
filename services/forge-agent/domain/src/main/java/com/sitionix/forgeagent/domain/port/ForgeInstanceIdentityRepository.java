package com.sitionix.forgeagent.domain.port;

import java.util.UUID;

public interface ForgeInstanceIdentityRepository {
    UUID getOrCreate();
}
