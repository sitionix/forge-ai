package com.sitionix.forgeagent;

import com.sitionix.forgeagent.api.remoteaccess.RemoteAccessServiceFilter;
import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessExecutionService;
import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessControlService;
import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessInvitations;
import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessManagement;
import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessMutualPairing;
import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessAccessorPairing;
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
    @Bean RemoteAccessMutualPairing remoteAccessMutualPairing(RemoteAccessAccessorPairing accessor,
            RemoteAccessInvitations invitations,RemoteAccessSetup setup,RemoteAccessPairingTokens tokens,
            ForgeInstanceIdentityRepository identity,RemoteAccessPairRepository pairs,
            RemoteAccessSessionRepository sessions,RemoteAccessReverseInvitationStore secrets,
            RemoteAccessReverseExchange exchange,java.time.Clock clock,RemoteAccessManagement management) {
        return new RemoteAccessMutualPairing(accessor,invitations,setup,tokens,identity,pairs,sessions,secrets,exchange,clock,management);
    }
    @Bean RemoteAccessControlService remoteAccessControlService(RemoteAccessSwitch access,
            RemoteAccessInvitations invitations, RemoteAccessManagement management, RemoteAccessSetup setup) {
        return new RemoteAccessControlService(access, invitations, management, setup);
    }
    @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<RemoteAccessServiceFilter> remoteAccessServiceFilter(
            @Value("${forge.agent.remote-access.service-secret-file}") Path path,
            @Value("${forge.mcp.enabled:false}") boolean mcp,
            @Value("${forge.mcp.service-credential-file:#{null}}") Path mcpPath) {
        if (mcp && new com.sitionix.forgeagent.api.security.ProtectedCredentialFile(mcpPath)
                .matchesBearer("Bearer "+RemoteAccessServiceFilter.readCredential(path)))
            throw new IllegalStateException("Service credentials must be distinct");
        var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new RemoteAccessServiceFilter(path));
        registration.addUrlPatterns("/*");registration.setDispatcherTypes(java.util.EnumSet.allOf(jakarta.servlet.DispatcherType.class));
        registration.setAsyncSupported(true);registration.setOrder(Integer.MIN_VALUE+1);
        return registration;
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
            @Value("${forge.agent.remote-access.ssh-port:22}") int port,
            @Value("${forge.agent.remote-access.ssh-username:forge-ssh}") String username,
            @Value("${forge.agent.remote-access.channel-enabled:false}") boolean channel) {
        return new RemoteAccessSetup() {
            public String displayName() { return displayName; }
            public RemoteAccessEndpoint advertisedEndpoint(String host) {
                if (!channel) throw new IllegalStateException("Grantor channel unavailable");
                var chosen=host!=null && !host.isBlank()?host
                        : advertisedHost.isBlank()?uniqueLocalAddress():advertisedHost;
                var endpoint=new RemoteAccessEndpoint(chosen,port,username);
                tokens.validateEndpoint(endpoint);return endpoint;
            }
            private String uniqueLocalAddress() {
                try {
                    var candidates=java.net.NetworkInterface.networkInterfaces()
                            .filter(interfaceInfo -> {
                                try { return interfaceInfo.isUp() && !interfaceInfo.isLoopback() && !interfaceInfo.isVirtual(); }
                                catch (java.net.SocketException unavailable) { return false; }
                            })
                            .flatMap(interfaceInfo -> interfaceInfo.inetAddresses())
                            .filter(address -> address instanceof java.net.Inet4Address && address.isSiteLocalAddress())
                            .map(java.net.InetAddress::getHostAddress).distinct().toList();
                    if (candidates.size()!=1) throw new IllegalStateException("Choose a reachable SSH address for this machine");
                    return candidates.getFirst();
                } catch (java.net.SocketException unavailable) {
                    throw new IllegalStateException("Cannot inspect local SSH addresses",unavailable);
                }
            }
            public RemoteAccessEndpoint advertisedEndpointForPeer(RemoteAccessEndpoint peer) {
                if (!channel) throw new IllegalStateException("Grantor channel unavailable");
                try (var route=new java.net.DatagramSocket()) {
                    route.connect(java.net.InetAddress.getByName(peer.host()),peer.port());
                    var source=route.getLocalAddress();
                    if (source.isAnyLocalAddress() || source.isLoopbackAddress() || source.isLinkLocalAddress()
                            || source.isMulticastAddress()) throw new IllegalStateException("No routable local SSH address for peer");
                    var endpoint=new RemoteAccessEndpoint(source.getHostAddress(),port,username);
                    tokens.validateEndpoint(endpoint);
                    return endpoint;
                } catch (java.net.SocketException failure) {
                    throw new IllegalStateException("Cannot determine local SSH address for peer",failure);
                } catch (java.net.UnknownHostException failure) {
                    throw new IllegalArgumentException("Peer SSH host cannot be resolved",failure);
                }
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
                if (advertisedHost.isBlank()) {
                    try { uniqueLocalAddress(); }
                    catch (IllegalStateException ambiguous) { diagnostics.add("ADVERTISED_HOST_REQUIRED"); }
                }
                var rootfs=Path.of("/srv/forge-remote/rootfs");
                if (!Files.isDirectory(rootfs) || !Files.isExecutable(rootfs.resolve("bin/sh"))
                        || !Files.isExecutable(rootfs.resolve("usr/bin/python3"))) {
                    diagnostics.add("WORKLOAD_ROOTFS_NOT_READY");
                }
                if (!execution.ready()) diagnostics.add("WORKLOAD_AUTHORITY_NOT_READY");
                return new RemoteAccessCapabilities(diagnostics.stream().allMatch("ADVERTISED_HOST_REQUIRED"::equals),
                        List.copyOf(operations),List.copyOf(diagnostics));
            }
        };
    }
}
