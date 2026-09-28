package com.sitionix.forgeagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class RemoteAccessApplicationCompositionTest {
    @TempDir Path directory;

    @Test void dedicatedApplicationComposesRemoteAccessWithoutGeneralForge() throws Exception {
        Path service = directory.resolve("service");
        Files.writeString(service, "a".repeat(43));
        Files.setPosixFilePermissions(service, PosixFilePermissions.fromString("rw-------"));
        Class<?> application = Class.forName("com.sitionix.forgeremote.agent.RemoteAccessAgentApplication");
        new ApplicationContextRunner().withUserConfiguration(application)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withPropertyValues("spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                        "forge.agent.remote-access.management-enabled=true",
                        "forge.agent.remote-access.service-secret-file=" + service,
                        "server.address=127.0.0.1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(com.sitionix.forgeagent.api.remoteaccess.RemoteAccessController.class);
                    assertThat(context).hasSingleBean(com.sitionix.forgeagent.application.remoteaccess.RemoteAccessControlService.class);
                    assertThat(context).hasBean("remoteAccessServiceFilter");
                    assertThat(context).doesNotHaveBean(com.sitionix.forgeagent.api.mcp.McpAvailableController.class);
                    assertThat(context).doesNotHaveBean(com.sitionix.forgeagent.infrastructure.local.runtime.RuntimeProcessLauncher.class);
                    assertThat(context).doesNotHaveBean(ForgeAgentWorkerConfiguration.class);
                    assertThat(context).doesNotHaveBean(ForgeAgentApplication.class);
                });
    }
}
