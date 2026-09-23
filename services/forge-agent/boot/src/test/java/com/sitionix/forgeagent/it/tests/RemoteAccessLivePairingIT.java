package com.sitionix.forgeagent.it.tests;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

/** Real SSH + production authority + PostgreSQL; deliberately separate from stub-authority OS tests. */
class RemoteAccessLivePairingIT {
    @Test void twoPersistedPeersPairAndRecoverThroughTheProductionSshBoundary() throws Exception {
        Path project=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(project!=null && !Files.isDirectory(project.resolve("scripts/remote-access"))) project=project.getParent();
        if(project==null) throw new IllegalStateException("Remote-access package source not found");
        var image=new ImageFromDockerfile("forge-remote-stage4-java-it",false)
                .withFileFromClasspath("Dockerfile","remote-access-stage4/Dockerfile");
        try(var network=Network.newNetwork();
                var database=new PostgreSQLContainer<>("postgres:16-alpine").withNetwork(network).withNetworkAliases("pairing-db");
                var ssh=new GenericContainer<>(image).withNetwork(network)
                        .waitingFor(Wait.forLogMessage(".*STAGE4_SSH_READY.*",1)).withStartupTimeout(Duration.ofSeconds(45))) {
            ssh.withCopyFileToContainer(MountableFile.forHostPath(project.resolve("scripts/remote-access")),"/opt/forge-remote-package");
            ssh.withCopyFileToContainer(MountableFile.forClasspathResource("remote-access-stage4/start.py"),"/fixture/start.py");
            var paths=new ArrayList<String>();
            String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
            int index=0;
            for(String entry:classpath.split(java.io.File.pathSeparator)) {
                Path source=Path.of(entry);
                if(!Files.exists(source)) continue;
                String target="/fixture/classpath/entry"+(index++)+(Files.isDirectory(source)?"":".jar");
                ssh.withCopyFileToContainer(MountableFile.forHostPath(source),target);
                paths.add(target);
            }
            database.start();
            ssh.start();
            var result=ssh.execInContainer("runuser","-u","forge-control","--",
                    "java","-cp",String.join(":",paths),RemoteAccessLivePairingFixture.class.getName(),
                    "jdbc:postgresql://pairing-db:5432/"+database.getDatabaseName(),database.getUsername(),database.getPassword());
            assertThat(result.getExitCode()).withFailMessage("Fixture failed:%n%s%n%s",result.getStdout(),result.getStderr()).isZero();
            result.getStdout().lines().filter(line -> line.startsWith("PASS ")).forEach(System.out::println);
            assertThat(result.getStdout()).contains("PASS persisted two-peer ACTIVE", "PASS consumed invitation and wrong session key denied",
                    "PASS lost acknowledgement restart recovery", "PASS reservation before install restart recovery", "PASS concurrent SSH redemption exactly one grantor session",
                    "PASS expired grant removed and unconfirmed accessor credential retained",
                    "PASS grantor JVM crash before install recovered", "PASS accessor JVM crash after confirmation recovered");
        }
    }
}
