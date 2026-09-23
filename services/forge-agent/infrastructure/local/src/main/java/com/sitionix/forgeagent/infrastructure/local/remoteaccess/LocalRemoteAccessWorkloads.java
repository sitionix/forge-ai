package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import com.sitionix.forgeagent.domain.port.RemoteAccessWorkloads;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class LocalRemoteAccessWorkloads implements RemoteAccessWorkloads {
    private final Path socket=Path.of("/run/forge-remote/workload-admin/control.sock");
    @Override public void reconcile(UUID epoch) { request("RECONCILE "+epoch,60); }
    @Override public void heartbeat(UUID epoch) { request(socket.resolveSibling("heartbeat.sock"),"HEARTBEAT "+epoch,2); }
    @Override public void start(UUID session,UUID attachment,UUID epoch) { request("START "+session+" "+attachment+" "+epoch,3); }
    @Override public void stop(UUID session) { request("STOP "+session,60); }
    private void request(String frame,int timeout) { request(socket,frame,timeout); }
    private void request(Path target,String frame,int timeout) {
        if (!RemoteAccessSupervisorConnection.exchange(target,"root",frame+"\n",timeout).equals("OK")) {
            throw new IllegalStateException("Managed workload supervisor rejected operation");
        }
    }
}
