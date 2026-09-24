package com.sitionix.forgeagent;

import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessAccessorExecution;
import com.sitionix.forgeagent.infrastructure.local.remoteaccess.RemoteAccessLocalExecServer;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name="forge.agent.remote-access.local-exec-enabled",havingValue="true")
public class RemoteAccessLocalExecConfiguration {
    @Bean(initMethod="start",destroyMethod="close")
    RemoteAccessLocalExecServer remoteAccessLocalExecServer(
            RemoteAccessAccessorExecution execution,
            @Value("${forge.agent.remote-access.local-exec-operator-user:}") String operatorUser) {
        return new RemoteAccessLocalExecServer(Path.of("/run/forge-remote/local-exec/agent.sock"),
                operatorUser,execution::start);
    }
}
