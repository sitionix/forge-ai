package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sitionix.forgeagent.domain.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteAccessSshCommandTest {
    @TempDir Path temp;
    private static final String HOST_KEY="ssh-ed25519 "+Base64.getEncoder().encodeToString(
            java.nio.ByteBuffer.allocate(51).putInt(11).put("ssh-ed25519".getBytes()).putInt(32).put(new byte[32]).array());

    @Test void dedicatedCommandDisablesInheritedIdentityConfigAndForwarding() throws Exception {
        var session=session("127.0.0.1",RemoteAccessSessionStatus.ACTIVE);
        Path identity=file("key","synthetic-private-material");
        Path known=file("known","[127.0.0.1]:2222 "+HOST_KEY+"\n");
        var command=RemoteAccessSshCommand.status(session,identity,known);
        assertThat(command).containsSubsequence("ssh","-F","/dev/null")
                .contains("IdentityAgent=none","IdentitiesOnly=yes","StrictHostKeyChecking=yes",
                        "ProxyCommand=none","ProxyJump=none","ControlMaster=no","ControlPath=none",
                        "PasswordAuthentication=no","KbdInteractiveAuthentication=no","PermitLocalCommand=no",
                        "ClearAllForwardings=yes","UpdateHostKeys=no","GlobalKnownHostsFile=/dev/null")
                .endsWith("--","forge-ssh@127.0.0.1","status");
        assertThat(command).doesNotContain("synthetic-private-material");
    }

    @Test void openSshEffectiveConfigurationHonorsTheProductionPolicy() throws Exception {
        var session=session("127.0.0.1",RemoteAccessSessionStatus.ACTIVE);
        var argv=new java.util.ArrayList<>(RemoteAccessSshCommand.status(session,
                file("key","synthetic-private-material"),file("known","[127.0.0.1]:2222 "+HOST_KEY+"\n")));
        argv.add(1,"-G");
        var process=new ProcessBuilder(argv).redirectErrorStream(true).start();
        String configuration=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        assertThat(configuration).contains("identityagent none", "identitiesonly yes", "stricthostkeychecking true",
                "controlmaster false", "permitlocalcommand no", "clearallforwardings yes", "forwardagent no",
                "passwordauthentication no", "kbdinteractiveauthentication no", "updatehostkeys false");
        assertThat(configuration).doesNotContain("proxycommand /", "proxyjump ");
    }

    @Test void mismatchedPinAndRevokedSessionFailBeforeCommandConstruction() throws Exception {
        Path identity=file("key","synthetic-private-material");
        Path known=file("known","wrong pin\n");
        assertThatThrownBy(() -> RemoteAccessSshCommand.status(session("127.0.0.1",RemoteAccessSessionStatus.ACTIVE),identity,known))
                .isInstanceOf(IllegalArgumentException.class);
        Files.writeString(known,"[127.0.0.1]:2222 "+HOST_KEY+"\n");
        assertThatThrownBy(() -> RemoteAccessSshCommand.status(session("127.0.0.1",RemoteAccessSessionStatus.REVOKED),identity,known))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void writableAncestorCannotReplaceThePinAfterValidation() throws Exception {
        var session=session("127.0.0.1",RemoteAccessSessionStatus.ACTIVE);
        Path parent=Files.createDirectory(temp.resolve("shared"));
        Files.setPosixFilePermissions(parent,PosixFilePermissions.fromString("rwxrwx---"));
        assertThat(Files.getPosixFilePermissions(parent)).contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE);
        assertThat((int)Files.getAttribute(parent,"unix:mode") & 01000).isZero();
        Path known=Files.writeString(parent.resolve("known"),"[127.0.0.1]:2222 "+HOST_KEY+"\n");
        Files.setPosixFilePermissions(known,PosixFilePermissions.fromString("rw-------"));
        Path identity=file("key","synthetic-private-material");
        assertThatThrownBy(() -> RemoteAccessSshCommand.status(session,identity,known))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void endpointAndIdentitySymlinkCannotInjectOrReplacePolicy() throws Exception {
        Path identity=file("key","synthetic-private-material");
        Path known=file("known","[127.0.0.1]:2222 "+HOST_KEY+"\n");
        assertThatThrownBy(() -> RemoteAccessSshCommand.status(session("-oProxyCommand=id",RemoteAccessSessionStatus.ACTIVE),identity,known))
                .isInstanceOf(IllegalArgumentException.class);
        Path link=temp.resolve("linked-key"); Files.createSymbolicLink(link,identity);
        assertThatThrownBy(() -> RemoteAccessSshCommand.status(session("127.0.0.1",RemoteAccessSessionStatus.ACTIVE),link,known))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Path file(String name,String content) throws Exception {
        Path path=Files.writeString(temp.resolve(name),content);
        Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
        return path;
    }
    private RemoteAccessSession session(String host,RemoteAccessSessionStatus status) {
        UUID id=UUID.randomUUID(); Instant now=Instant.parse("2026-09-22T00:00:00Z");
        var value=new RemoteAccessSession(id,UUID.randomUUID(),RemoteAccessRole.ACCESSOR,UUID.randomUUID(),UUID.randomUUID(),
                "peer",new RemoteAccessEndpoint(host,2222,"forge-ssh"),HOST_KEY,"session-key","SHA256:"+"A".repeat(43),
                id,RemoteAccessSessionStatus.PROVISIONING,now,now.plusSeconds(60),null,null,null,
                RemoteAccessConnectivity.UNKNOWN,null,null,null,null,0).activate(now);
        return status==RemoteAccessSessionStatus.REVOKED?value.requestRevoke(now).confirmRevoked(now):value;
    }
}
