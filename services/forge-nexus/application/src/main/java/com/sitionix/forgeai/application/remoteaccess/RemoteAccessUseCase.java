package com.sitionix.forgeai.application.remoteaccess;
import com.sitionix.forgeai.domain.remoteaccess.*;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name="forge.remote-access.enabled",havingValue="true")
public class RemoteAccessUseCase implements RemoteAccessOperations {
    private final RemoteAccessClient client;
    public RemoteAccessModels.Capabilities capabilities() { return client.capabilities(); }
    public List<RemoteAccessModels.Invitation> invitations() { return client.invitations(); }
    public RemoteAccessModels.InvitationCreated invite(RemoteAccessModels.InvitationRequest request) { return client.invite(request); }
    public void cancel(UUID id) { client.cancel(id); }
    public RemoteAccessModels.Session connect(RemoteAccessModels.ConnectRequest request) { return client.connect(request); }
    public List<RemoteAccessModels.Session> sessions() { return client.sessions(); }
    public RemoteAccessModels.Session get(UUID id) { return client.get(id); }
    public RemoteAccessModels.Session check(UUID id) { return client.check(id); }
    public RemoteAccessModels.Session revoke(UUID id) { return client.revoke(id); }
}
