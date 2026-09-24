package com.sitionix.forgeai.infrastructure.agentclient.remoteaccess;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.*;
@HttpExchange("/api/v1/remote-access")
public interface RemoteAccessHttpClient {
    @GetExchange("/capabilities") RemoteAccessClientDtos.Capabilities capabilities();
    @GetExchange("/invitations") List<RemoteAccessClientDtos.Invitation> invitations();
    @PostExchange("/invitations") RemoteAccessClientDtos.InvitationCreated invite(@RequestBody RemoteAccessClientDtos.InvitationRequest request);
    @DeleteExchange("/invitations/{id}") void cancel(@PathVariable UUID id);
    @PostExchange("/sessions") ResponseEntity<RemoteAccessClientDtos.Session> connect(@RequestBody RemoteAccessClientDtos.ConnectRequest request);
    @GetExchange("/sessions") List<RemoteAccessClientDtos.Session> sessions();
    @GetExchange("/sessions/{id}") RemoteAccessClientDtos.Session get(@PathVariable UUID id);
    @PostExchange("/sessions/{id}/check") RemoteAccessClientDtos.Session check(@PathVariable UUID id);
    @DeleteExchange("/sessions/{id}") ResponseEntity<RemoteAccessClientDtos.Session> revoke(@PathVariable UUID id);
}
