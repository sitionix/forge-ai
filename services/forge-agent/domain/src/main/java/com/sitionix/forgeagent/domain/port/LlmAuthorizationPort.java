package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.LlmAuthorizationState;
import com.sitionix.forgeagent.domain.model.LlmLoginAttempt;
import java.util.UUID;

/** Incoming operator authorization contract; implemented by the application service. */
public interface LlmAuthorizationPort {
    LlmAuthorizationState readAccount();
    LlmLoginAttempt startLogin(String browserBinding);
    LlmLoginAttempt readLogin(UUID loginId, String browserBinding);
    LlmLoginAttempt cancelLogin(UUID loginId, String browserBinding);
    LlmAuthorizationState logout();
}
