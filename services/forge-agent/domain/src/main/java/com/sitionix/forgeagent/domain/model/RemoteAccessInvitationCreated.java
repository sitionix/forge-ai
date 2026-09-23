package com.sitionix.forgeagent.domain.model;
public record RemoteAccessInvitationCreated(RemoteAccessInvitation invitation, RemoteAccessPairingToken token) {
    @Override public String toString() { return "RemoteAccessInvitationCreated[invitationId=" + invitation.id() + ", token=REDACTED]"; }
}
