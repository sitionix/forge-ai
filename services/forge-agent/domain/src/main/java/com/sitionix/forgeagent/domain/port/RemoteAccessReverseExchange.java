package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.RemoteAccessSession;
import java.util.UUID;

/** Sends the internal reverse invitation over an authenticated forward SSH session. */
public interface RemoteAccessReverseExchange {
    UUID exchange(RemoteAccessSession forwardSession,String reverseToken);
}
