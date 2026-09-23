package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import com.sitionix.forgeagent.domain.model.RemoteAccessPrivateKey;
import com.sitionix.forgeagent.domain.model.RemoteAccessSession;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

/** One SSH process owns these ephemeral, owner-only files. */
final class RemoteAccessSshCredentials implements AutoCloseable {
    final Path directory,key,knownHosts;
    RemoteAccessSshCredentials(RemoteAccessSession session,RemoteAccessPrivateKey privateKey) throws IOException {
        directory=Files.createTempDirectory("forge-control-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        key=directory.resolve("identity");knownHosts=directory.resolve("known_hosts");
        try {
            var mode=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
            Files.createFile(key,mode);Files.createFile(knownHosts,mode);
            byte[] bytes=privateKey.copyBytes();
            try { Files.write(key,bytes); } finally { Arrays.fill(bytes,(byte)0); }
            Files.writeString(knownHosts,"["+session.endpoint().host()+"]:"+session.endpoint().port()+" "
                    +LocalPairingTokens.validatedPublicKey(session.pinnedHostPublicKey())+"\n");
        } catch (IOException | RuntimeException failure) { close();throw failure; }
    }
    @Override public synchronized void close() throws IOException {
        try { Files.deleteIfExists(key); }
        finally { try { Files.deleteIfExists(knownHosts); } finally { Files.deleteIfExists(directory); } }
    }
}
