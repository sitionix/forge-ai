package com.sitionix.forgeagent.domain.port;
import com.sitionix.forgeagent.domain.model.RemoteAccessInvitation;
public interface RemoteAccessInvitationGrants {
    String hostPublicKey();
    void install(RemoteAccessInvitation invitation);
    void remove(RemoteAccessInvitation invitation);
}
