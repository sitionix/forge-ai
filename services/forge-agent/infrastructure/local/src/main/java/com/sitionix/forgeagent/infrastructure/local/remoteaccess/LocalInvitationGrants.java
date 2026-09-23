package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import com.sitionix.forgeagent.domain.model.RemoteAccessInvitation;
import com.sitionix.forgeagent.domain.model.RemoteAccessSession;
import com.sitionix.forgeagent.domain.model.RemoteAccessRole;
import com.sitionix.forgeagent.domain.port.RemoteAccessSessionGrants;
import com.sitionix.forgeagent.domain.port.RemoteAccessInvitationGrants;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import jdk.net.ExtendedSocketOptions;
import org.springframework.stereotype.Component;

/** Fixed local supervisor operations, never shell commands or caller-selected filesystem paths. */
@Component
public final class LocalInvitationGrants implements RemoteAccessInvitationGrants, RemoteAccessSessionGrants {
    private final Path socket;
    private final String supervisorUser;
    public LocalInvitationGrants() { this(Path.of("/run/forge-remote/admin/invitations.sock"),"root"); }
    LocalInvitationGrants(Path socket,String supervisorUser) { this.socket=socket; this.supervisorUser=supervisorUser; }

    @Override public String hostPublicKey() {
        return LocalPairingTokens.validatedPublicKey(request("HOST\n"));
    }
    @Override public void install(RemoteAccessInvitation invitation) { change("INSTALL",invitation); }
    @Override public void remove(RemoteAccessInvitation invitation) { change("REMOVE",invitation); }

    @Override public void install(RemoteAccessSession session) { changeSession("SESSION_INSTALL",session); }
    @Override public void remove(RemoteAccessSession session) { changeSession("SESSION_REMOVE",session); }

    private void changeSession(String operation,RemoteAccessSession session) {
        if (session.localRole()!=RemoteAccessRole.GRANTOR) throw new IllegalArgumentException("Only grantors install session grants");
        String key=LocalPairingTokens.validatedPublicKey(session.sessionPublicKey());
        String result=request(operation+" "+session.grantorInstanceId()+" "+session.id()+" "+key+"\n");
        if (!result.equals("OK")) throw new IllegalStateException("Session supervisor rejected operation");
    }

    private void change(String operation,RemoteAccessInvitation invitation) {
        String key=LocalPairingTokens.validatedPublicKey(invitation.pairingPublicKey());
        String result=request(operation+" "+invitation.grantorInstanceId()+" "+invitation.id()+" "+key+"\n");
        if (!result.equals("OK")) throw new IllegalStateException("Invitation supervisor rejected operation");
    }

    private String request(String frame) {
        try { return RemoteAccessSupervisorConnection.exchange(socket,supervisorUser,frame,3); }
        catch (IllegalStateException unavailable) { throw new IllegalStateException("Invitation supervisor unavailable"); }
    }

}
