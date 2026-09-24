package com.sitionix.forgeai.domain.remoteaccess;
import java.util.List;
import java.util.UUID;
public interface RemoteAccessOperations {
    RemoteAccessModels.Capabilities capabilities();
    List<RemoteAccessModels.Invitation> invitations();
    RemoteAccessModels.InvitationCreated invite(RemoteAccessModels.InvitationRequest request);
    void cancel(UUID id);
    RemoteAccessModels.Session connect(RemoteAccessModels.ConnectRequest request);
    List<RemoteAccessModels.Session> sessions();
    RemoteAccessModels.Session get(UUID id);
    RemoteAccessModels.Session check(UUID id);
    RemoteAccessModels.Session revoke(UUID id);
}
