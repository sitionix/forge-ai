package com.sitionix.forgeagent;

import com.sitionix.forgeagent.api.remoteaccess.RemoteAccessServiceFilter;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;

/** Reuses the local service identity without requiring Remote Access to be enabled. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="forge.agent.remote-access.service-secret-file")
public class AiLibraryManagementConfiguration {
 @Bean RemoteAccessManagementConfiguration.ManagementBind managementBind(
   @Value("${server.forward-headers-strategy:none}") String forwarding) {
  return new RemoteAccessManagementConfiguration.ManagementBind(forwarding);
 }
 @Bean FilterRegistrationBean<RemoteAccessServiceFilter> aiLibraryServiceAuthentication(
   @Value("${forge.agent.remote-access.service-secret-file}") Path secret) {
  var registration=new FilterRegistrationBean<>(new RemoteAccessServiceFilter(secret));
  registration.addUrlPatterns("/api/v1/projects/*");
  registration.setDispatcherTypes(java.util.EnumSet.allOf(jakarta.servlet.DispatcherType.class));
  registration.setAsyncSupported(true);
  registration.setOrder(Integer.MIN_VALUE+1);
  return registration;
 }
}
