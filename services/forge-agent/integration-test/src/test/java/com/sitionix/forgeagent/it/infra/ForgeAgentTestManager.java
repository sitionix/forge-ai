package com.sitionix.forgeagent.it.infra;

import com.sitionix.forgeit.core.annotation.ForgeFeatures;
import com.sitionix.forgeit.core.api.ForgeIT;
import com.sitionix.forgeit.mockmvc.api.MockMvcSupport;
import com.sitionix.forgeit.postgresql.api.PostgresqlSupport;

@ForgeFeatures(value = {
        MockMvcSupport.class,
        PostgresqlSupport.class
})
public interface ForgeAgentTestManager extends ForgeIT {
}
