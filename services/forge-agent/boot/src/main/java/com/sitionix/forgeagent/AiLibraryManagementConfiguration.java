package com.sitionix.forgeagent;

import com.sitionix.forgeagent.api.remoteaccess.RemoteAccessServiceFilter;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import java.nio.file.Files;
import com.sitionix.forgeagent.api.security.ServiceTokenFile;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;

/** Reuses the local service identity without requiring Remote Access to be enabled. */
@Configuration(proxyBeanMethods=false)
@Conditional(AiLibraryManagementConfiguration.IdentityAvailable.class)
public class AiLibraryManagementConfiguration {
 static final String TOKEN_PROPERTY="${forge.ai-library.service-token-file:${forge.agent.remote-access.service-secret-file:${FORGE_CODEX_SERVICE_TOKEN_FILE:/etc/forge-ai/codex-service/token}}}";
 static final class IdentityAvailable implements Condition {
  public boolean matches(ConditionContext context,org.springframework.core.type.AnnotatedTypeMetadata metadata) {
   Environment env=context.getEnvironment();
   return env.containsProperty("forge.ai-library.service-token-file") || env.containsProperty("forge.agent.remote-access.service-secret-file")
     || Files.isRegularFile(Path.of(env.getProperty("FORGE_CODEX_SERVICE_TOKEN_FILE","/etc/forge-ai/codex-service/token")));
  }
 }
 @Bean RemoteAccessManagementConfiguration.ManagementBind managementBind(
   @Value("${server.forward-headers-strategy:none}") String forwarding) {
  return new RemoteAccessManagementConfiguration.ManagementBind(forwarding);
 }
 @Bean FilterRegistrationBean<RemoteAccessServiceFilter> aiLibraryServiceAuthentication(
   @Value(TOKEN_PROPERTY) Path secret, Environment environment) {
  var registration=new FilterRegistrationBean<>(new RemoteAccessServiceFilter(!environment.containsProperty("forge.ai-library.service-token-file") && environment.containsProperty("forge.agent.remote-access.service-secret-file") ? RemoteAccessServiceFilter.readCredential(secret) : ServiceTokenFile.read(secret)));
  registration.addUrlPatterns("/api/v1/projects/*");
  registration.setDispatcherTypes(java.util.EnumSet.allOf(jakarta.servlet.DispatcherType.class));
  registration.setAsyncSupported(true);
  registration.setOrder(Integer.MIN_VALUE+1);
  return registration;
 }
}
