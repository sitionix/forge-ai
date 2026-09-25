package com.sitionix.forgeai.api.remoteaccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(name="forge.remote-access.enabled",havingValue="true")
public class RemoteAccessSecurityConfiguration {
    @Bean @Order(1)
    @ConditionalOnProperty(name="forge.remote-access.enabled",havingValue="true")
    SecurityFilterChain remoteAccessSecurity(HttpSecurity http,RemoteAccessOperatorAuthentication authentication,
            @org.springframework.beans.factory.annotation.Value("${forge.mcp.enabled:false}") boolean combined) throws Exception {
        String prefix="/api/v1/infrastructure/agents/remote-access";
        var mapper=new ObjectMapper();
        org.springframework.security.web.util.matcher.RequestMatcher login=request -> request.getMethod().equals("POST")
                && com.sitionix.forgeai.api.security.OperatorPublicRoutes.path(request).equals(prefix+"/operator/login");
        org.springframework.security.web.util.matcher.RequestMatcher localSession=request -> request.getMethod().equals("GET")
                && com.sitionix.forgeai.api.security.OperatorPublicRoutes.path(request).equals(prefix+"/operator/session");
        if (!combined) {
            var remotePath=org.springframework.web.util.pattern.PathPatternParser.defaultInstance.parse(prefix+"/{*path}");
            http.securityMatcher(request -> remotePath.matches(org.springframework.http.server.RequestPath.parse(
                    com.sitionix.forgeai.api.security.OperatorPublicRoutes.path(request),"").pathWithinApplication()));
        }
        http.authorizeHttpRequests(auth -> {
                if (combined) auth.requestMatchers(com.sitionix.forgeai.api.security.OperatorPublicRoutes::matches).permitAll();
                auth.requestMatchers(login,localSession).permitAll().anyRequest().hasRole("REMOTE_ACCESS_OPERATOR");
            })
            .securityContext(context -> context.securityContextRepository(new HttpSessionSecurityContextRepository()))
            .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(login))
            .requestCache(cache -> cache.disable()).logout(logout -> logout.disable())
            .addFilterBefore(new RemoteAccessBrowserFilter(authentication,mapper,combined),CsrfFilter.class)
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request,response,error) -> {
                    response.setStatus(401);response.setContentType("application/json");
                    mapper.writeValue(response.getOutputStream(),new RemoteAccessProxyDtos.Error("REMOTE_ACCESS_UNAUTHORIZED","Operator authentication required",UUID.randomUUID().toString()));
                })
                .accessDeniedHandler((request,response,error) -> {
                    response.setStatus(403);response.setContentType("application/json");
                    mapper.writeValue(response.getOutputStream(),new RemoteAccessProxyDtos.Error("REMOTE_ACCESS_FORBIDDEN","Operator authorization or CSRF token required",UUID.randomUUID().toString()));
                }));
        return http.build();
    }
    @Bean org.springframework.security.core.userdetails.UserDetailsService remoteAccessNoPasswordLogin() {
        return name -> { throw new org.springframework.security.core.userdetails.UsernameNotFoundException("No password login"); };
    }
}
