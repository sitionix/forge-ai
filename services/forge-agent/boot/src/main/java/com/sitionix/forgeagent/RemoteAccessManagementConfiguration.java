package com.sitionix.forgeagent;

import com.sitionix.forgeagent.api.remoteaccess.RemoteAccessServiceFilter;
import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessExecutionService;
import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessControlService;
import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessInvitations;
import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessManagement;
import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessSwitch;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.AbstractConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;

@Configuration
@ConditionalOnProperty(name="forge.agent.remote-access.management-enabled",havingValue="true")
public class RemoteAccessManagementConfiguration {
    @Bean RemoteAccessControlService remoteAccessControlService(RemoteAccessSwitch access,
            RemoteAccessInvitations invitations, RemoteAccessManagement management, RemoteAccessSetup setup) {
        return new RemoteAccessControlService(access, invitations, management, setup);
    }
    @Bean RemoteAccessServiceFilter remoteAccessServiceFilter(@Value("${forge.agent.remote-access.service-secret-file}") Path path) {
        return new RemoteAccessServiceFilter(path);
    }
    @Bean ManagementBind managementBind(@Value("${server.forward-headers-strategy:none}") String forwarding) { return new ManagementBind(forwarding); }
    static final class ManagementBind implements WebServerFactoryCustomizer<AbstractConfigurableWebServerFactory>,Ordered {
        private final String forwarding;
        ManagementBind(String forwarding) { this.forwarding=forwarding; }
        public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
        public void customize(AbstractConfigurableWebServerFactory factory) {
            if (!"none".equalsIgnoreCase(forwarding)) throw new IllegalStateException("Remote Access must not trust forwarded headers");
            if (factory.getAddress()==null || !factory.getAddress().isLoopbackAddress()) throw new IllegalStateException("Remote Access management requires explicit loopback bind");
        }
    }
    @Bean RemoteAccessSetup remoteAccessSetup(RemoteAccessInvitationGrants grants,RemoteAccessPairingTokens tokens,
            RemoteAccessExecutionService execution,
            @Value("${forge.agent.remote-access.display-name:Forge}") String displayName,
            @Value("${forge.agent.remote-access.advertised-host:}") String advertisedHost,
            @Value("${forge.agent.remote-access.ssh-port:2222}") int port,
            @Value("${forge.agent.remote-access.ssh-username:forge-ssh}") String username,
            @Value("${forge.agent.remote-access.channel-enabled:false}") boolean channel) {
        return new RemoteAccessSetup() {
            public String displayName() { return displayName; }
            public RemoteAccessEndpoint advertisedEndpoint(String host) {
                if (!channel) throw new IllegalStateException("Grantor channel unavailable");
                var endpoint=new RemoteAccessEndpoint(host==null?advertisedHost:host,port,username);
                tokens.validateEndpoint(endpoint);return endpoint;
            }
            public RemoteAccessCapabilities capabilities() {
                var operations=new ArrayList<>(List.of("LIST","CHECK","REVOKE"));
                var diagnostics=new ArrayList<String>();
                if (Files.isExecutable(Path.of("/usr/bin/ssh"))) operations.add("CONNECT");
                else diagnostics.add("SSH_CLIENT_UNAVAILABLE");
                if (!channel) diagnostics.add("GRANTOR_CHANNEL_DISABLED");
                else {
                    try { grants.hostPublicKey(); operations.add("GIVE_ACCESS"); }
                    catch (RuntimeException unavailable) { diagnostics.add("GRANTOR_SETUP_UNAVAILABLE"); }
                }
                if (advertisedHost.isBlank()) diagnostics.add("ADVERTISED_HOST_REQUIRED");
                var rootfs=Path.of("/srv/forge-remote/rootfs");
                if (!Files.isDirectory(rootfs) || !Files.isExecutable(rootfs.resolve("bin/sh"))
                        || !Files.isExecutable(rootfs.resolve("usr/bin/python3"))) {
                    diagnostics.add("WORKLOAD_ROOTFS_NOT_READY");
                }
                if (!execution.ready()) diagnostics.add("WORKLOAD_AUTHORITY_NOT_READY");
                return new RemoteAccessCapabilities(diagnostics.isEmpty(),List.copyOf(operations),List.copyOf(diagnostics));
            }
        };
    }
}
