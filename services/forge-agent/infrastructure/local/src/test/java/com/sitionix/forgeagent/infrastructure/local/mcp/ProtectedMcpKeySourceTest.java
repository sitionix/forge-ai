package com.sitionix.forgeagent.infrastructure.local.mcp;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProtectedMcpKeySourceTest {
    @TempDir Path directory;

    @Test void readsActiveAndPreviousKeysOnlyFromProtectedFile() throws Exception {
        var file = keyFile("active=next\nkey.next="+encoded((byte)2)+"\nkey.old="+encoded((byte)1)+"\n");
        assertThat(ProtectedMcpKeySource.readProtected(file)).isNotEmpty();
        var keys = new ProtectedMcpKeySource(file).keys();
        assertThat(keys.activeId()).isEqualTo("next");
        assertThat(keys.key("old")).containsOnly((byte)1);
        assertThat(keys.key("next")).containsOnly((byte)2);
        assertThat(keys.toString()).doesNotContain(encoded((byte)1));
    }

    @Test void refusesMissingSymlinkWeakModeInvalidLengthAndUnknownActiveId() throws Exception {
        assertThatThrownBy(() -> new ProtectedMcpKeySource(directory.resolve("missing")).keys())
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(directory.toString());
        var file = keyFile("active=next\nkey.next="+encoded((byte)2)+"\n");
        Path link = directory.resolve("link"); Files.createSymbolicLink(link,file);
        assertThatThrownBy(() -> new ProtectedMcpKeySource(link).keys()).isInstanceOf(IllegalStateException.class);
        Files.setPosixFilePermissions(file,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.GROUP_READ));
        assertThatThrownBy(() -> new ProtectedMcpKeySource(file).keys()).isInstanceOf(IllegalStateException.class);
        Files.setPosixFilePermissions(file,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));
        Files.writeString(file,"active=next\nkey.next="+Base64.getEncoder().encodeToString(new byte[16])+"\n");
        assertThatThrownBy(() -> new ProtectedMcpKeySource(file).keys()).isInstanceOf(IllegalStateException.class);
        Files.writeString(file,"active=missing\nkey.next="+encoded((byte)2)+"\n");
        assertThatThrownBy(() -> new ProtectedMcpKeySource(file).keys()).isInstanceOf(IllegalStateException.class);
    }

    private Path keyFile(String content) throws Exception {
        var file = directory.resolve("keys"); Files.writeString(file,content);
        Files.setPosixFilePermissions(file,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));
        return file;
    }
    private static String encoded(byte value) { byte[] bytes = new byte[32]; Arrays.fill(bytes,value); return Base64.getEncoder().encodeToString(bytes); }
}
