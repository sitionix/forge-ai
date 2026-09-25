package com.sitionix.forgeagent.api.remoteaccess;
import com.sitionix.forgeagent.application.remoteaccess.*;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.RemoteAccessSessionStatus;
import com.sitionix.forgeagent.domain.model.RemoteAccessSwitchStatus;
import com.sitionix.forgeagent.domain.port.RemoteAccessSetup;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name="forge.agent.remote-access.management-enabled",havingValue="true")
@RequestMapping("/api/v1/remote-access")
public class RemoteAccessController {
    private final RemoteAccessManagement management;
    private final RemoteAccessInvitations invitations;
    private final RemoteAccessMutualPairing pairing;
    private final RemoteAccessSetup setup;
    private final RemoteAccessApiMapper mapper;
    private final RemoteAccessControlService control;
    @GetMapping("/capabilities") public RemoteAccessDtos.Capabilities capabilities() { return mapper.capabilities(setup.capabilities()); }
    @GetMapping("/control") public RemoteAccessDtos.Control control() { return mapper.control(control.status()); }
    @PostMapping("/control/enable") public RemoteAccessDtos.Control enable() { return mapper.control(control.enable()); }
    @PostMapping("/control/disable") public ResponseEntity<RemoteAccessDtos.Control> disable() {
        var result=control.disable();
        return ResponseEntity.status(result.status()==RemoteAccessSwitchStatus.DISABLED?200:202).body(mapper.control(result));
    }
    @GetMapping("/invitations") public List<RemoteAccessDtos.Invitation> invitations() {
        return invitations.list().stream().filter(value -> !pairing.internalInvitation(value.id())).map(mapper::invitation).toList();
    }
    @PostMapping("/invitations") public ResponseEntity<RemoteAccessDtos.InvitationCreated> invite(@Valid @RequestBody(required=false) RemoteAccessDtos.InvitationRequest body) {
        return ResponseEntity.status(201).body(mapper.created(invitations.create(setup.advertisedEndpoint(body==null?null:body.advertisedHost()),setup.displayName())));
    }
    @DeleteMapping("/invitations/{id}") public ResponseEntity<Void> cancel(@PathVariable UUID id) { invitations.cancel(id); return ResponseEntity.noContent().build(); }
    @PostMapping("/sessions") public ResponseEntity<RemoteAccessDtos.Session> connect(@Valid @RequestBody RemoteAccessDtos.ConnectRequest body) {
        var result=pairing.connect(body.pairingToken(),setup.displayName());
        if (result.status()!=RemoteAccessSessionStatus.ACTIVE && result.status()!=RemoteAccessSessionStatus.PROVISIONING) {
            throw new ConflictException("REMOTE_ACCESS_PAIRING_CONFLICT","Pairing attempt is no longer provisioning");
        }
        return ResponseEntity.status(pairing.connected(result.id())?201:202).body(session(result));
    }
    @GetMapping("/sessions") public List<RemoteAccessDtos.Session> sessions() { return management.list().stream().map(this::session).toList(); }
    @GetMapping("/sessions/{id}") public RemoteAccessDtos.Session get(@PathVariable UUID id) { return session(management.get(id)); }
    @PostMapping("/sessions/{id}/check") public RemoteAccessDtos.Session check(@PathVariable UUID id) {
        pairing.resumeIfPending(id);
        return session(management.check(id));
    }
    @DeleteMapping("/sessions/{id}") public ResponseEntity<RemoteAccessDtos.Session> revoke(@PathVariable UUID id) {
        var result=management.revoke(id);
        if (result.status()!=RemoteAccessSessionStatus.REVOKED && result.status()!=RemoteAccessSessionStatus.REVOKING) {
            throw new ConflictException("REMOTE_ACCESS_REVOKE_CONFLICT","Session changed concurrently; retry revoke");
        }
        return ResponseEntity.status(management.bridgeRevoked(id)?200:202).body(session(result));
    }
    private RemoteAccessDtos.Session session(com.sitionix.forgeagent.domain.model.RemoteAccessSession value) {
        return mapper.session(value,pairing.pairId(value.id()),pairing.connected(value.id()),management.bridgeRevoked(value.id()));
    }
}
