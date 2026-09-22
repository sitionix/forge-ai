package com.sitionix.forgeagent;

import com.sitionix.forgeagent.domain.port.RemoteAccessChannelAuthority;
import com.sitionix.forgeagent.infrastructure.local.remoteaccess.RemoteAccessChannelServer;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name="forge.agent.remote-access.channel-enabled",havingValue="true")
public class RemoteAccessChannelConfiguration {
    @Bean(initMethod="start",destroyMethod="close")
    RemoteAccessChannelServer remoteAccessChannelServer(RemoteAccessChannelAuthority authority) {
        return new RemoteAccessChannelServer(Path.of("/run/forge-remote/channel/authority.sock"),
                "forge-ssh","forge-ssh",authority);
    }
}
