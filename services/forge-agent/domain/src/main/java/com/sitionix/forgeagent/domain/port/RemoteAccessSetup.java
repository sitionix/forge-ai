package com.sitionix.forgeagent.domain.port;
import com.sitionix.forgeagent.domain.model.*;
public interface RemoteAccessSetup {
    RemoteAccessCapabilities capabilities();
    RemoteAccessEndpoint advertisedEndpoint(String host);
    RemoteAccessEndpoint advertisedEndpointForPeer(RemoteAccessEndpoint peer);
    String displayName();
}
