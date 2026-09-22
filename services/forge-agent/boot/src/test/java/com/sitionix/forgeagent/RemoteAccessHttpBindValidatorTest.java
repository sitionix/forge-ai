package com.sitionix.forgeagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;

class RemoteAccessHttpBindValidatorTest {
    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ServletWebServerFactoryAutoConfiguration.class))
            .withUserConfiguration(RemoteAccessHttpBindValidator.class);

    @Test void disabledChannelPreservesMissingAndLanBind() {
        context.withPropertyValues("forge.agent.remote-access.channel-enabled=false").run(actual -> {
            assertThat(actual).hasNotFailed();
            assertThat(actual.getBean(TomcatServletWebServerFactory.class).getAddress()).isNull();
        });
        context.withPropertyValues("forge.agent.remote-access.channel-enabled=false", "server.address=192.0.2.10").run(actual -> {
            assertThat(actual).hasNotFailed();
            assertThat(actual.getBean(TomcatServletWebServerFactory.class).getAddress().getHostAddress()).isEqualTo("192.0.2.10");
        });
    }

    @Test void enabledLoopbackAddressesAndLocalHostnameStartContext() {
        for (String address : new String[]{"127.0.0.1","::1","localhost"}) {
            context.withPropertyValues("forge.agent.remote-access.channel-enabled=true", "server.address="+address).run(actual -> {
                assertThat(actual).hasNotFailed();
                assertThat(actual.getBean(TomcatServletWebServerFactory.class).getAddress().isLoopbackAddress()).isTrue();
            });
        }
    }

    @Test void enabledMissingWildcardAndLanBindFailDuringStartup() {
        context.withPropertyValues("forge.agent.remote-access.channel-enabled=true").run(actual -> {
            assertThat(actual).hasFailed();
            assertThat(actual.getStartupFailure()).hasRootCauseMessage("Remote Access channel requires an explicit loopback server.address");
        });
        for (String address : new String[]{"","0.0.0.0","::","192.0.2.10"}) {
            context.withPropertyValues("forge.agent.remote-access.channel-enabled=true", "server.address="+address).run(actual -> {
                assertThat(actual).hasFailed();
                assertThat(actual.getStartupFailure()).hasRootCauseMessage("Remote Access channel requires an explicit loopback server.address");
            });
        }
    }

    @Test void typedHostnameResolutionCannotHideNonLoopbackAddress() throws Exception {
        var factory = new TomcatServletWebServerFactory();
        var validator = new RemoteAccessHttpBindValidator();
        factory.setAddress(InetAddress.getByAddress("loopback.example", new byte[]{127,0,0,1}));
        validator.customize(factory);
        factory.setAddress(InetAddress.getByAddress("lan.example", new byte[]{(byte)192,0,2,10}));
        assertThatThrownBy(() -> validator.customize(factory)).isInstanceOf(IllegalStateException.class);
    }
}
