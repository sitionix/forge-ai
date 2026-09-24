package com.sitionix.forgeagent.api.security;

import jakarta.servlet.DispatcherType;
import java.nio.file.Path;
import java.util.EnumSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="forge.mcp.enabled",havingValue="true")
public class AgentManagementSecurityConfiguration {
    @Bean ProtectedCredentialFile agentServiceCredential(@Value("${forge.mcp.service-credential-file}") Path path) {
        return new ProtectedCredentialFile(path);
    }
    @Bean FilterRegistrationBean<AgentManagementAuthenticationFilter> agentManagementGuard(ProtectedCredentialFile credential) {
        var bean = new FilterRegistrationBean<>(new AgentManagementAuthenticationFilter(credential));
        bean.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST,DispatcherType.ASYNC,DispatcherType.ERROR,DispatcherType.FORWARD));
        bean.addUrlPatterns("/*");
        bean.setOrder(Integer.MIN_VALUE);
        return bean;
    }
}
