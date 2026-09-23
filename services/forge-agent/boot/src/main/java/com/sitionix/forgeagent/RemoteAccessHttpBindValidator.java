package com.sitionix.forgeagent;

import java.net.InetAddress;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.AbstractConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/** Checks the resolved factory address after Spring Boot has applied server.address. */
@Component
@ConditionalOnProperty(name = "forge.agent.remote-access.channel-enabled", havingValue = "true")
public class RemoteAccessHttpBindValidator
        implements WebServerFactoryCustomizer<AbstractConfigurableWebServerFactory>, Ordered {
    @Override
    public void customize(AbstractConfigurableWebServerFactory factory) {
        InetAddress address = factory.getAddress();
        if (address == null || !address.isLoopbackAddress()) {
            throw new IllegalStateException("Remote Access channel requires an explicit loopback server.address");
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
