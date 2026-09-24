package com.sitionix.forgeai.api.security;

import jakarta.servlet.DispatcherType;
import java.net.URI;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="forge.mcp.enabled",havingValue="true")
@org.springframework.context.annotation.Conditional(McpOnlyCondition.class)
@EnableConfigurationProperties(McpManagementProperties.class)
public class OperatorManagementSecurityConfiguration {
    @Bean URI operatorOrigin(McpManagementProperties settings) {
        return Objects.requireNonNull(settings.getOperatorOrigin(),"Operator origin required");
    }
    @Bean ProtectedCredentialFile operatorBootstrap(McpManagementProperties settings) {
        Path path=Objects.requireNonNull(settings.getBootstrapCredentialFile(),"Operator bootstrap file required");
        Path service=Objects.requireNonNull(settings.getAgentServiceCredentialFile(),"Agent service file required");
        byte[] bootstrapBytes=ProtectedNexusFile.token(path);
        byte[] serviceBytes=ProtectedNexusFile.token(service);
        try {
            if (MessageDigest.isEqual(bootstrapBytes,serviceBytes))
                throw new IllegalStateException("Protected credentials must be distinct");
        } finally {
            Arrays.fill(bootstrapBytes,(byte)0);
            Arrays.fill(serviceBytes,(byte)0);
        }
        return new ProtectedCredentialFile(path);
    }
    @Bean OperatorSessionService operatorSessions(ProtectedCredentialFile bootstrap,McpManagementProperties settings) {
        return new OperatorSessionService(bootstrap,Clock.systemUTC(),settings.getSessionTtl(),1000);
    }
    @Bean FilterRegistrationBean<OperatorManagementAuthenticationFilter> operatorGuard(OperatorSessionService sessions,URI operatorOrigin) {
        var bean=new FilterRegistrationBean<>(new OperatorManagementAuthenticationFilter(sessions,operatorOrigin));
        bean.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST,DispatcherType.ASYNC,DispatcherType.ERROR,DispatcherType.FORWARD,DispatcherType.INCLUDE));
        bean.addUrlPatterns("/*");
        bean.setOrder(Integer.MIN_VALUE);
        return bean;
    }
}
