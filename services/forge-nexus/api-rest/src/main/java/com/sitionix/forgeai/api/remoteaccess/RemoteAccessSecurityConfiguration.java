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
    SecurityFilterChain remoteAccessSecurity(HttpSecurity http,RemoteAccessOperatorAuthentication authentication) throws Exception {
        String prefix="/api/v1/infrastructure/agents/remote-access";
        var mapper=new ObjectMapper();
        http.securityMatcher(prefix,prefix+"/**")
            .authorizeHttpRequests(auth -> auth.requestMatchers(prefix+"/operator/login").permitAll().anyRequest().hasRole("REMOTE_ACCESS_OPERATOR"))
            .securityContext(context -> context.securityContextRepository(new HttpSessionSecurityContextRepository()))
            .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(prefix+"/operator/login"))
            .requestCache(cache -> cache.disable()).logout(logout -> logout.disable())
            .addFilterBefore(new RemoteAccessBrowserFilter(authentication,mapper),CsrfFilter.class)
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
