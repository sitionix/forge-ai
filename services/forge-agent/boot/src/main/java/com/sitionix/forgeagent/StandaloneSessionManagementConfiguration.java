package com.sitionix.forgeagent;
import com.sitionix.forgeagent.api.security.*;
import com.sitionix.forgeagent.api.remoteaccess.RemoteAccessServiceFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.nio.file.Path;
@Configuration(proxyBeanMethods=false)
@Conditional(AiLibraryManagementConfiguration.IdentityAvailable.class)
public class StandaloneSessionManagementConfiguration {
 @Bean FilterRegistrationBean<StandaloneSessionServiceFilter> standaloneServiceAuthentication(@Value(AiLibraryManagementConfiguration.TOKEN_PROPERTY)Path path,Environment environment){
  String token=!environment.containsProperty("forge.ai-library.service-token-file")&&environment.containsProperty("forge.agent.remote-access.service-secret-file")?RemoteAccessServiceFilter.readCredential(path):ServiceTokenFile.read(path);
  var registration=new FilterRegistrationBean<>(new StandaloneSessionServiceFilter(token));registration.addUrlPatterns("/api/v1/projects/*");registration.setDispatcherTypes(java.util.EnumSet.allOf(jakarta.servlet.DispatcherType.class));registration.setOrder(Integer.MIN_VALUE+2);registration.setAsyncSupported(true);return registration;
 }
}
