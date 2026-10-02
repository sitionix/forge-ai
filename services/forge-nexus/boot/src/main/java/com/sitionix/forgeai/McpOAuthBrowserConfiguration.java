package com.sitionix.forgeai;

import com.sitionix.forgeai.api.mcp.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(McpOAuthBrowserProperties.class)
class McpOAuthBrowserConfiguration {
    @Bean FilterRegistrationBean<McpOAuthCallbackLoggingFilter> mcpOAuthCallbackLoggingFilter(){
        var registration=new FilterRegistrationBean<>(new McpOAuthCallbackLoggingFilter());
        registration.addUrlPatterns("/api/v1/infrastructure/agents/integrations/mcp/oauth/callback");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);return registration;
    }
}
