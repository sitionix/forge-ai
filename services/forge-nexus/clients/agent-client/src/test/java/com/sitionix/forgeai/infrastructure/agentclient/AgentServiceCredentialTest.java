package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentServiceCredentialTest {
    @TempDir Path directory;
    @Test void protectedFileSuppliesBearerOnlyForFixedSecureOrLoopbackAgentOrigin() throws Exception {
        byte[] raw=new byte[32]; Arrays.fill(raw,(byte)6);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Path file=directory.resolve("service"); Files.writeString(file,token);
        Files.setPosixFilePermissions(file,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));
        var credential=new AgentServiceCredential(file,URI.create("https://agent.example:8443"));
        assertThat(credential.authorization()).isEqualTo("Bearer "+token);
        assertThat(credential.toString()).doesNotContain(token);
        assertThatThrownBy(() -> new AgentServiceCredential(file,URI.create("http://agent.example:8080")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new AgentServiceCredential(file,URI.create("http://127.0.0.1:7091")).authorization()).isEqualTo("Bearer "+token);
        Files.setPosixFilePermissions(file,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.GROUP_READ));
        assertThatThrownBy(() -> new AgentServiceCredential(file,URI.create("https://agent.example")))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(token);
    }
}
