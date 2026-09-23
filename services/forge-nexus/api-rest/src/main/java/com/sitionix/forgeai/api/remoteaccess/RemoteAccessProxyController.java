package com.sitionix.forgeai.api.remoteaccess;
import com.sitionix.forgeai.domain.remoteaccess.*;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name="forge.remote-access.enabled",havingValue="true")
@RequestMapping("/api/v1/infrastructure/agents/remote-access")
public class RemoteAccessProxyController {
    private final RemoteAccessOperations operations;
    private final RemoteAccessProxyMapper mapper;
    @GetMapping("/capabilities") public RemoteAccessProxyDtos.Capabilities capabilities() { return mapper.response(operations.capabilities()); }
    @GetMapping("/invitations") public List<RemoteAccessProxyDtos.Invitation> invitations() { return operations.invitations().stream().map(mapper::response).toList(); }
    @PostMapping("/invitations") public ResponseEntity<RemoteAccessProxyDtos.InvitationCreated> invite(@Valid @RequestBody(required=false) RemoteAccessProxyDtos.InvitationRequest body) {
        return ResponseEntity.status(201).body(mapper.response(operations.invite(mapper.command(body==null?new RemoteAccessProxyDtos.InvitationRequest(null):body))));
    }
    @DeleteMapping("/invitations/{id}") public ResponseEntity<Void> cancel(@PathVariable UUID id) { operations.cancel(id);return ResponseEntity.noContent().build(); }
    @PostMapping("/sessions") public ResponseEntity<RemoteAccessProxyDtos.Session> connect(@Valid @RequestBody RemoteAccessProxyDtos.ConnectRequest body) {
        var result=operations.connect(mapper.command(body));
        return ResponseEntity.status(result.status()==RemoteAccessModels.Status.ACTIVE?201:202).body(mapper.response(result));
    }
    @GetMapping("/sessions") public List<RemoteAccessProxyDtos.Session> sessions() { return operations.sessions().stream().map(mapper::response).toList(); }
    @GetMapping("/sessions/{id}") public RemoteAccessProxyDtos.Session get(@PathVariable UUID id) { return mapper.response(operations.get(id)); }
    @PostMapping("/sessions/{id}/check") public RemoteAccessProxyDtos.Session check(@PathVariable UUID id) { return mapper.response(operations.check(id)); }
    @DeleteMapping("/sessions/{id}") public ResponseEntity<RemoteAccessProxyDtos.Session> revoke(@PathVariable UUID id) {
        var result=operations.revoke(id);
        return ResponseEntity.status(result.status()==RemoteAccessModels.Status.REVOKED?200:202).body(mapper.response(result));
    }
}
