package com.sitionix.forgeagent;

import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessInvitations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes obsolete owned authorization; expiry itself is always enforced by the channel authority. */
@Component
@ConditionalOnProperty(name="forge.agent.remote-access.channel-enabled",havingValue="true")
@RequiredArgsConstructor
@Slf4j
public class RemoteAccessInvitationCleanup {
    private final RemoteAccessInvitations invitations;

    @Scheduled(initialDelay=1000,fixedDelay=60000)
    public void reconcile() {
        try { invitations.cleanupUnavailable(); }
        catch (RuntimeException unavailable) {
            log.warn("Remote Access invitation authorization cleanup incomplete; will retry");
        }
    }
}
