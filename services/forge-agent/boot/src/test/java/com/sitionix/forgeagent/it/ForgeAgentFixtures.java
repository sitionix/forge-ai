package com.sitionix.forgeagent.it;

import java.util.UUID;

final class ForgeAgentFixtures {

    static final UUID PROJECT_ALPHA_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    static final UUID PROJECT_BETA_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    static final UUID PROJECT_GAMMA_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");

    static final UUID AGENT_A_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    static final UUID AGENT_B_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    static final UUID AGENT_C_ID = UUID.fromString("20000000-0000-4000-8000-000000000003");
    static final UUID TARGET_AGENT_ID = UUID.fromString("20000000-0000-4000-8000-000000000004");
    static final UUID OTHER_PROJECT_AGENT_ID = UUID.fromString("20000000-0000-4000-8000-000000000005");
    static final UUID UNKNOWN_AGENT_ID = UUID.fromString("20000000-0000-4000-8000-000000009999");

    private ForgeAgentFixtures() {
    }
}
