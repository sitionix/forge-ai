package com.sitionix.forgeagent.it.tests;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

/** Explicitly opt-in: needs an isolated privileged Docker systemd/cgroup namespace, no host mounts. */
@EnabledIfSystemProperty(named="forge.remote-access.live-execution",matches="true")
class RemoteAccessLiveExecutionIT {
    @Test void realProductionAuthorityAndSshCommandsAreContainedAndRevocable() throws Exception {
        Path project=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(project!=null && !Files.isDirectory(project.resolve("scripts/remote-access")))project=project.getParent();
        if(project==null)throw new IllegalStateException("Remote access package missing");
        var image=new ImageFromDockerfile("forge-remote-stage5-java-it",false)
            .withFileFromPath("Dockerfile",project.resolve("scripts/remote-access/tests/stage5/Dockerfile"))
            .withFileFromPath("scripts/remote-access",project.resolve("scripts/remote-access"));
        try(var network=Network.newNetwork();
            var database=new PostgreSQLContainer<>("postgres:16-alpine").withNetwork(network).withNetworkAliases("execution-db");
            var machine=new GenericContainer<>(image).withNetwork(network).withPrivilegedMode(true)
                .withTmpFs(Map.of("/run","rw","/run/lock","rw","/tmp","rw"))
                .waitingFor(Wait.forSuccessfulCommand("systemctl is-system-running")).withStartupTimeout(Duration.ofSeconds(60))) {
            database.start();machine.start();
            var paths=new ArrayList<String>();int index=0;
            String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
            for(String entry:classpath.split(java.io.File.pathSeparator)) {
                Path source=Path.of(entry);if(!Files.exists(source))continue;
                String target="/fixture/classpath/entry"+(index++)+(Files.isDirectory(source)?"":".jar");
                machine.copyFileToContainer(MountableFile.forHostPath(source),target);paths.add(target);
            }
            var result=machine.execInContainer("python3","/opt/forge-remote-package/tests/stage5/run_fixture.py",
                String.join(":",paths),"jdbc:postgresql://execution-db:5432/"+database.getDatabaseName(),database.getUsername(),database.getPassword());
            assertThat(result.getExitCode()).withFailMessage("Stage 5 fixture failed:%n%s%n%s",result.getStdout(),result.getStderr()).isZero();
            result.getStdout().lines().filter(line -> line.startsWith("PASS ") || line.startsWith("STAGE5_")).forEach(System.out::println);
            assertThat(result.getStdout()).contains("STAGE5_FIXTURE_PASS", "PASS unavailable authority lease stops existing workload",
                "PASS actual supervisor SIGKILL stops bound workload units", "PASS revoke stops setsid descendants",
                "PASS real SSH close cancellation removes main, setsid child, systemd unit, registry and fence; unrelated session survives");
        }
    }
}
