package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Two independently authorized SSH directions, linked by the user-visible invitation. */
public record RemoteAccessPair(UUID id,RemoteAccessRole localForwardRole,UUID forwardSessionId,
        UUID reverseSessionId,UUID reverseInvitationId,Instant createdAt,long version) {
    public RemoteAccessPair {
        Objects.requireNonNull(id,"pair id");
        Objects.requireNonNull(localForwardRole,"forward role");
        Objects.requireNonNull(createdAt,"createdAt");
        if (version<0) throw new IllegalArgumentException("negative pair version");
        if ((localForwardRole==RemoteAccessRole.ACCESSOR)!=(reverseInvitationId!=null))
            throw new IllegalArgumentException("only connector owns a reverse invitation");
        if (forwardSessionId!=null && forwardSessionId.equals(reverseSessionId))
            throw new IllegalArgumentException("pair directions require distinct sessions");
    }

    public static RemoteAccessPair connector(UUID id,UUID reverseInvitationId,Instant now) {
        return new RemoteAccessPair(id,RemoteAccessRole.ACCESSOR,null,null,reverseInvitationId,now,0);
    }
    public static RemoteAccessPair inviter(UUID id,UUID forwardSessionId,Instant now) {
        return new RemoteAccessPair(id,RemoteAccessRole.GRANTOR,Objects.requireNonNull(forwardSessionId),null,null,now,0);
    }
    public RemoteAccessPair withForwardSession(UUID sessionId) {
        Objects.requireNonNull(sessionId);
        if (sessionId.equals(forwardSessionId)) return this;
        if (forwardSessionId!=null) throw new IllegalStateException("forward session is already bound");
        return new RemoteAccessPair(id,localForwardRole,sessionId,reverseSessionId,reverseInvitationId,createdAt,version+1);
    }
    public RemoteAccessPair withReverseSession(UUID sessionId) {
        Objects.requireNonNull(sessionId);
        if (sessionId.equals(reverseSessionId)) return this;
        if (reverseSessionId!=null) throw new IllegalStateException("reverse session is already bound");
        return new RemoteAccessPair(id,localForwardRole,forwardSessionId,sessionId,reverseInvitationId,createdAt,version+1);
    }
    public boolean connected(RemoteAccessSessionStatus forward,RemoteAccessSessionStatus reverse) {
        return forwardSessionId!=null && reverseSessionId!=null
                && forward==RemoteAccessSessionStatus.ACTIVE && reverse==RemoteAccessSessionStatus.ACTIVE;
    }
}
