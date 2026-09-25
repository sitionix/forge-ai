package com.sitionix.forgeai.infrastructure.agentclient.remoteaccess;
import com.sitionix.forgeai.domain.remoteaccess.*;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.*;
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="forge.remote-access.enabled",havingValue="true")
public class RemoteAccessClientAdapter implements RemoteAccessClient {
    private final RemoteAccessHttpClient http;
    private final RemoteAccessClientMapper mapper;
    public RemoteAccessModels.Capabilities capabilities() { return mapper.domain(execute(http::capabilities)); }
    public RemoteAccessModels.Control control() { return mapper.domain(execute(http::control)); }
    public RemoteAccessModels.Control enable() {
        var result=mapper.domain(execute(http::enable));
        if (result.status()!=RemoteAccessModels.SwitchStatus.ENABLED) throw invalid();
        return result;
    }
    public RemoteAccessModels.Control disable() {
        var response=execute(http::disable);
        if (response.getBody()==null || !(response.getStatusCode().value()==200
                && response.getBody().status()==RemoteAccessModels.SwitchStatus.DISABLED
                || response.getStatusCode().value()==202
                && response.getBody().status()==RemoteAccessModels.SwitchStatus.DISABLING)) throw invalid();
        return mapper.domain(response.getBody());
    }
    public List<RemoteAccessModels.Invitation> invitations() { return execute(http::invitations).stream().map(mapper::domain).toList(); }
    public RemoteAccessModels.InvitationCreated invite(RemoteAccessModels.InvitationRequest request) {
        var body=mapper.request(request);return mapper.domain(execute(() -> http.invite(body)));
    }
    public void cancel(UUID id) { execute(() -> { http.cancel(id);return Boolean.TRUE; }); }
    public RemoteAccessModels.Session connect(RemoteAccessModels.ConnectRequest request) {
        var body=mapper.request(request);var response=execute(() -> http.connect(body));
        if (response==null || response.getBody()==null || response.getBody().bridgeId()==null
                || !(response.getStatusCode().value()==201 && response.getBody().bridgeReady()
                    && response.getBody().status()==RemoteAccessModels.Status.ACTIVE
                    || response.getStatusCode().value()==202 && !response.getBody().bridgeReady()
                    && (response.getBody().status()==RemoteAccessModels.Status.ACTIVE
                        || response.getBody().status()==RemoteAccessModels.Status.PROVISIONING))) throw invalid();
        return mapper.domain(response.getBody());
    }
    public List<RemoteAccessModels.Session> sessions() { return execute(http::sessions).stream().map(mapper::domain).toList(); }
    public RemoteAccessModels.Session get(UUID id) { return mapper.domain(execute(() -> http.get(id))); }
    public RemoteAccessModels.Session check(UUID id) { return mapper.domain(execute(() -> http.check(id))); }
    public RemoteAccessModels.Session revoke(UUID id) {
        var response=execute(() -> http.revoke(id));
        if (response==null || response.getBody()==null || !(response.getStatusCode().value()==200
                && response.getBody().bridgeRevoked() && response.getBody().status()==RemoteAccessModels.Status.REVOKED
                || response.getStatusCode().value()==202 && !response.getBody().bridgeRevoked()
                && (response.getBody().status()==RemoteAccessModels.Status.REVOKED
                    || response.getBody().status()==RemoteAccessModels.Status.REVOKING))) throw invalid();
        return mapper.domain(response.getBody());
    }
    private <T> T execute(Supplier<T> call) {
        try { return Objects.requireNonNull(call.get()); }
        catch (RestClientResponseException failure) {
            RemoteAccessClientDtos.Error error;
            try { error=failure.getResponseBodyAs(RemoteAccessClientDtos.Error.class); }
            catch (RuntimeException malformed) { throw invalid(); }
            int status=failure.getStatusCode().value();
            if (status<400 || status>599 || error==null || error.code()==null || error.message()==null
                    || error.correlationId()==null) throw invalid();
            // Never retain the HTTP exception/cause, which may contain a secret-bearing body.
            throw new RemoteAccessClientException(status,error.code(),error.message(),error.correlationId());
        } catch (ResourceAccessException unavailable) {
            throw new RemoteAccessClientException(503,"REMOTE_ACCESS_UNAVAILABLE","Remote Access unavailable",UUID.randomUUID().toString());
        } catch (RestClientException | NullPointerException malformed) { throw invalid(); }
    }
    private RemoteAccessClientException invalid() {
        return new RemoteAccessClientException(502,"UPSTREAM_INVALID_RESPONSE","Invalid Remote Access response",UUID.randomUUID().toString());
    }
}
