package com.sitionix.forgeagent.application.remoteaccess;

import com.sitionix.forgeagent.domain.model.RemoteAccessSwitchStatus;

public record RemoteAccessControlStatus(RemoteAccessSwitchStatus status, boolean ready,
                                        int pendingSessions, int pendingInvitations,
                                        String diagnostic) { }
