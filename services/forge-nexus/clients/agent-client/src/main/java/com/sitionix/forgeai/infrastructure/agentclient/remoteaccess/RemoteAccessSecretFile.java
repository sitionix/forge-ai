package com.sitionix.forgeai.infrastructure.agentclient.remoteaccess;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
public final class RemoteAccessSecretFile {
    private RemoteAccessSecretFile() {}
    public static String read(Path path) {
        try {
            var absolute=path.toAbsolutePath().normalize();
            for (Path part=absolute;part!=null;part=part.getParent()) {
                if (Files.isSymbolicLink(part)) throw new IllegalStateException("Management credential path must not contain symlinks");
                if (!part.equals(absolute)) {
                    var mode=((Number)Files.getAttribute(part,"unix:mode",LinkOption.NOFOLLOW_LINKS)).intValue();
                    if ((mode & 0022)!=0 && (mode & 01000)==0) throw new IllegalStateException("Management credential ancestor is writable");
                }
            }
            var permissions=Files.getPosixFilePermissions(absolute,LinkOption.NOFOLLOW_LINKS);
            if (!Files.isRegularFile(absolute,LinkOption.NOFOLLOW_LINKS)
                    || !Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE).containsAll(permissions)
                    || !permissions.contains(PosixFilePermission.OWNER_READ) || Files.size(absolute)>256) {
                throw new IllegalStateException("Management credential requires a protected owner-only file");
            }
            var owner=Files.getOwner(absolute,LinkOption.NOFOLLOW_LINKS).getName();
            if (!owner.equals(System.getProperty("user.name")) && !owner.equals("root")) throw new IllegalStateException("Untrusted credential owner");
            String secret=Files.readString(absolute).strip();
            if (!secret.matches("[A-Za-z0-9_-]{43,128}")) throw new IllegalStateException("Invalid management credential format");
            return secret;
        } catch (IOException e) { throw new IllegalStateException("Management credential is unavailable"); }
    }
}
