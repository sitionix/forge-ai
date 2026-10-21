package com.sitionix.forgeagent;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
class AiLibraryManagementConfigurationTest {
 @TempDir Path directory;
 @Test void authenticatesLibraryWithExistingIdentityWhenRemoteAccessIsDisabled() throws Exception {
  var secret=directory.resolve("service");Files.writeString(secret,"s".repeat(43));Files.setAttribute(secret,"unix:mode",0600);
  new ApplicationContextRunner().withUserConfiguration(AiLibraryManagementConfiguration.class)
   .withPropertyValues("forge.agent.remote-access.management-enabled=false","forge.agent.remote-access.service-secret-file="+secret)
   .run(context->assertThat(context).hasNotFailed().hasBean("aiLibraryServiceAuthentication"));
 }
 @Test void existingIdentityRetainsLoopbackAndForwardingGuards() throws Exception {
  var secret=directory.resolve("bind-service");Files.writeString(secret,"s".repeat(43));Files.setAttribute(secret,"unix:mode",0600);
  new ApplicationContextRunner().withUserConfiguration(AiLibraryManagementConfiguration.class)
   .withPropertyValues("forge.agent.remote-access.management-enabled=false","forge.agent.remote-access.service-secret-file="+secret,"server.forward-headers-strategy=framework")
   .run(context->{var bind=context.getBean(RemoteAccessManagementConfiguration.ManagementBind.class);var factory=new org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory();
    try{factory.setAddress(java.net.InetAddress.getLoopbackAddress());}catch(Exception failure){throw new IllegalStateException(failure);}
    assertThatThrownBy(()->bind.customize(factory)).isInstanceOf(IllegalStateException.class).hasMessageContaining("forwarded headers");
   });
 }
 @Test void missingIdentityDoesNotCreateUnprotectedFallback(){new ApplicationContextRunner().withUserConfiguration(AiLibraryManagementConfiguration.class).run(context->assertThat(context).hasNotFailed().doesNotHaveBean("aiLibraryServiceAuthentication"));}
}
