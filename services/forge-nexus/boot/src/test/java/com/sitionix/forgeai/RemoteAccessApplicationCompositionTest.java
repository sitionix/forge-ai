package com.sitionix.forgeai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class RemoteAccessApplicationCompositionTest {
    @TempDir Path directory;

    @Test void dedicatedApplicationComposesRemoteAccessWithoutGeneralForge() throws Exception {
        Path service = credential("service", 'a');
        Path operator = credential("operator", 'b');
        Class<?> application = Class.forName("com.sitionix.forgeremote.nexus.RemoteAccessNexusApplication");
        new WebApplicationContextRunner().withUserConfiguration(application)
                .withPropertyValues("forge.remote-access.enabled=true",
                        "forge.remote-access.service-secret-file=" + service,
                        "forge.remote-access.operator-secret-file=" + operator,
                        "forge.remote-access.operator-origin=http://127.0.0.1:9100",
                        "forge.ai.infrastructure.agent.base-url=http://127.0.0.1:7092",
                        "forge.ai.infrastructure.agent.connect-timeout=5s",
                        "forge.ai.infrastructure.agent.read-timeout=30s",
                        "server.address=127.0.0.1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(com.sitionix.forgeai.api.remoteaccess.RemoteAccessProxyController.class);
                    assertThat(context).hasBean("springSecurityFilterChain");
                    assertThat(context).doesNotHaveBean(com.sitionix.forgeai.api.remoteaccess.CombinedOperatorSessionController.class);
                    assertThat(context).doesNotHaveBean(com.sitionix.forgeai.infrastructure.agentclient.ForgeAgentMcpClientAdapter.class);
                    assertThat(context).doesNotHaveBean(CombinedOperatorCredentialConfiguration.class);
                    assertThat(context).doesNotHaveBean(Application.class);
                });
    }

    private Path credential(String name, char value) throws Exception {
        Path file = directory.resolve(name);
        Files.writeString(file, String.valueOf(value).repeat(43));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }
}
