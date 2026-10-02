package com.sitionix.forgeagent.application.mcp;

import com.sitionix.forgeagent.domain.exception.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** Resolves protocol credentials on Agent only; refresh uses the existing connection row lock. */
public class McpCredentialService {
    private final McpConnectionRepository connections;
    private final ForgeInstanceIdentityRepository identity;
    private final McpCredentialCipher rawCipher;
    private final McpOAuthCredentialCipher cipher;
    private final McpOAuthClient client;
    private final McpRuntimeGrantRepository grants;
    private final McpRuntimeToolView views;
    private final Clock clock;
    public McpCredentialService(McpConnectionRepository connections,ForgeInstanceIdentityRepository identity,
            McpCredentialCipher rawCipher,McpOAuthCredentialCipher cipher,McpOAuthClient client,
            McpRuntimeGrantRepository grants,McpRuntimeToolView views,Clock clock) {
        this.connections=connections;this.identity=identity;this.rawCipher=rawCipher;this.cipher=cipher;
        this.client=client;this.grants=grants;this.views=views;this.clock=clock;
    }
    public byte[] resolve(McpConnection observed) {
        UUID owner=identity.getOrCreate(),id=observed.id();
        if (!observed.installationId().equals(owner)) throw McpOAuthException.reconnect();
        if (observed.authType()==McpAuthType.NONE) return null;
        if (observed.authType()!=McpAuthType.OAUTH) {
            var encrypted=connections.credential(owner,id).orElseThrow(()->new McpProbeException(McpProbeException.Reason.AUTH_REQUIRED));
            return rawCipher.decrypt(owner,id,"credential",encrypted);
        }
        var current=connections.change(owner,id,state->{
            var c=state.connection();
            if (!c.credentialConfigured() || c.authType()!=McpAuthType.OAUTH || c.enabled()!=observed.enabled()
                    || !Objects.equals(c.oauthAuthorizationId(),observed.oauthAuthorizationId())
                    || !c.endpoint().equals(observed.endpoint()) || !c.oauthConfiguration().equals(observed.oauthConfiguration()))
                throw McpOAuthException.reconnect();
            var credentials=cipher.decrypt(owner,id,state.credential());var tokens=credentials.tokens();
            if (tokens==null) return unusable(state);
            if (tokens.expiresAt()==null || tokens.expiresAt().isAfter(clock.instant())) return state;
            if (tokens.refreshToken()==null || (tokens.refreshExpiresAt()!=null && !tokens.refreshExpiresAt().isAfter(clock.instant())))
                return unusable(state);
            McpOAuthTokens refreshed;
            try { refreshed=client.refresh(c.oauthConfiguration(),credentials); }
            catch (McpOAuthException failure) {
                if (!failure.code().equals("MCP_OAUTH_RECONNECT_REQUIRED")) throw failure;
                return unusable(state);
            }
            if (refreshed.grantedScopes()!=null && (!refreshed.grantedScopes().containsAll(c.oauthConfiguration().scopes())
                    || (tokens.grantedScopes()!=null && !refreshed.grantedScopes().equals(tokens.grantedScopes()))))
                return unusable(state);
            return new McpConnectionState(c,cipher.encrypt(owner,id,new McpOAuthCredentials(credentials.clientSecret(),refreshed)));
        }).orElseThrow(McpOAuthException::reconnect);
        if (!current.connection().credentialConfigured()) {
            throw McpOAuthException.reconnect();
        }
        return cipher.decrypt(owner,id,current.credential()).tokens().accessToken().getBytes(StandardCharsets.UTF_8);
    }
    /** No replay: an observed provider auth rejection requires an explicit operator reconnect. */
    public void authorizationFailed(McpConnection observed) {
        if (observed.authType()!=McpAuthType.OAUTH) return;
        connections.change(observed.installationId(),observed.id(),state ->
                Objects.equals(state.connection().oauthAuthorizationId(),observed.oauthAuthorizationId()) ? unusable(state) : state);
    }
    private McpConnectionState unusable(McpConnectionState state) {
        var c=state.connection();
        var invalid=new McpConnection(c.id(),c.installationId(),c.displayName(),c.endpoint(),c.authType(),c.enabled(),c.projectAccess(),
                Set.of(),false,c.createdAt(),clock.instant(),null,"MCP_OAUTH_RECONNECT_REQUIRED",c.oauthConfiguration(),c.oauthAuthorizationId());
        var credentials=cipher.decrypt(c.installationId(),c.id(),state.credential());
        var encrypted=cipher.encrypt(c.installationId(),c.id(),new McpOAuthCredentials(credentials.clientSecret(),null));
        revoke(c.id());
        return new McpConnectionState(invalid,encrypted);
    }
    private void revoke(UUID id) { grants.revokeConnection(id);views.revokeConnection(id); }
}
