package com.sitionix.forgeagent;

import com.sitionix.forgeagent.api.remoteaccess.RemoteAccessServiceFilter;
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
                return new RemoteAccessCapabilities(operations.contains("CONNECT") || operations.contains("GIVE_ACCESS"),List.copyOf(operations),List.copyOf(diagnostics));
            }
        };
    }
}
