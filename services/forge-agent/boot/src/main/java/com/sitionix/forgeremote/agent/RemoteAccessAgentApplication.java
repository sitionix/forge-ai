package com.sitionix.forgeremote.agent;

import com.sitionix.forgeagent.*;
import com.sitionix.forgeagent.application.config.ForgeAgentApplicationConfiguration;
import com.sitionix.forgeagent.infrastructure.local.LocalRemoteAccessCredentialStore;
import com.sitionix.forgeagent.infrastructure.local.LocalRemoteAccessReverseInvitationStore;
import com.sitionix.forgeagent.infrastructure.postgres.adapter.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Composition root for the existing dedicated Remote Access authority process. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan(basePackages = {
        "com.sitionix.forgeagent.api.remoteaccess",
        "com.sitionix.forgeagent.application.remoteaccess",
        "com.sitionix.forgeagent.infrastructure.local.remoteaccess"})
@Import({ForgeAgentApplicationConfiguration.class, RemoteAccessManagementConfiguration.class,
        RemoteAccessChannelConfiguration.class, RemoteAccessLocalExecConfiguration.class,
        RemoteAccessHttpBindValidator.class, RemoteAccessPairingReconciliation.class,
        RemoteAccessExecutionRecovery.class, RemoteAccessInvitationCleanup.class,
        LocalRemoteAccessCredentialStore.class, LocalRemoteAccessReverseInvitationStore.class,
        PostgresForgeInstanceIdentityRepository.class, PostgresRemoteAccessSwitchRepository.class,
        PostgresRemoteAccessPairRepository.class, PostgresRemoteAccessSessionRepository.class,
        PostgresRemoteAccessInvitationRepository.class})
public class RemoteAccessAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(RemoteAccessAgentApplication.class, args);
    }
}
