package com.sitionix.forgeai;
import com.sitionix.forgeai.api.remoteaccess.RemoteAccessOperatorAuthentication;
import com.sitionix.forgeai.infrastructure.agentclient.remoteaccess.RemoteAccessSecretFile;
import java.net.URI;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
@Configuration
@ConditionalOnProperty(name="forge.remote-access.enabled",havingValue="true")
public class RemoteAccessOperatorConfiguration {
    /** Let the servlet container apply its canonical path mapping before the security firewall. */
    @Bean org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean remoteAccessSecurityRegistration() {
        var registration=new org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean("springSecurityFilterChain");
        registration.setUrlPatterns(java.util.List.of("/api/v1/infrastructure/agents/remote-access", "/api/v1/infrastructure/agents/remote-access/*"));
        registration.setOrder(-100);
        return registration;
    }
    @Bean RemoteAccessOperatorAuthentication remoteAccessOperatorAuthentication(
            @Value("${forge.remote-access.operator-secret-file}") Path operatorFile,
            @Value("${forge.remote-access.service-secret-file}") Path serviceFile,
            @Value("${forge.remote-access.operator-origin}") URI origin) {
        String operator=RemoteAccessSecretFile.read(operatorFile),service=RemoteAccessSecretFile.read(serviceFile);
        if (operator.equals(service)) throw new IllegalStateException("Operator and service credentials must be distinct");
        return new RemoteAccessOperatorAuthentication(operator.getBytes(StandardCharsets.UTF_8),origin,Clock.systemUTC());
    }
    @Bean OperatorBind operatorBind(RemoteAccessOperatorAuthentication authentication,
            @Value("${server.servlet.context-path:}") String contextPath,
            @Value("${server.forward-headers-strategy:none}") String forwarding) { return new OperatorBind(authentication,contextPath,forwarding); }
    static final class OperatorBind implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>,Ordered {
        private final RemoteAccessOperatorAuthentication authentication;private final String contextPath; private final String forwarding;
        OperatorBind(RemoteAccessOperatorAuthentication authentication,String contextPath,String forwarding) { this.authentication=authentication;this.contextPath=contextPath;this.forwarding=forwarding; }
        public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
        public void customize(TomcatServletWebServerFactory factory) {
            if (!"none".equalsIgnoreCase(forwarding)) throw new IllegalStateException("Remote Access must not trust forwarded headers");
            if (factory.getAddress()==null || !factory.getAddress().isLoopbackAddress()) throw new IllegalStateException("Remote Access Nexus requires explicit loopback bind");
            factory.getSession().setTimeout(java.time.Duration.ofMinutes(15));
            factory.getSession().getCookie().setHttpOnly(true);
            factory.getSession().getCookie().setSecure("https".equals(authentication.origin().getScheme()));
            factory.getSession().getCookie().setSameSite(org.springframework.boot.web.server.Cookie.SameSite.STRICT);
            factory.getSession().getCookie().setName("FORGE_REMOTE_OPERATOR");
            factory.getSession().getCookie().setPath(contextPath+"/api/v1/infrastructure/agents/remote-access");
        }
    }
}
