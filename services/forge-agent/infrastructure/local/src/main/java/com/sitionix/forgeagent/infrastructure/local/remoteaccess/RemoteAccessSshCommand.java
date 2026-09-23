package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import com.sitionix.forgeagent.domain.model.RemoteAccessRole;
import com.sitionix.forgeagent.domain.model.RemoteAccessSession;
import com.sitionix.forgeagent.domain.model.RemoteAccessSessionStatus;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/** Builds only the Stage 2 status probe; does not execute commands or retry transport failures. */
public final class RemoteAccessSshCommand {
    private RemoteAccessSshCommand() { }

    public static List<String> status(RemoteAccessSession session,Path identity,Path knownHosts) throws IOException {
        if (session.localRole()!=RemoteAccessRole.ACCESSOR || session.localPrivateKeyReference()==null
                || session.status()!=RemoteAccessSessionStatus.ACTIVE && session.status()!=RemoteAccessSessionStatus.PROVISIONING) {
            throw new IllegalArgumentException("Session does not permit an accessor status probe");
        }
        var endpoint=session.endpoint();
        if (!endpoint.host().matches("[A-Za-z0-9][A-Za-z0-9.:-]{0,253}")
                || !endpoint.username().matches("[a-z_][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("Invalid managed SSH endpoint");
        }
        validateFile(identity);
        validateFile(knownHosts);
        String expected="["+endpoint.host()+"]:"+endpoint.port()+" "+validatedHostKey(session.pinnedHostPublicKey())+"\n";
        if (Files.size(knownHosts)>1024 || !Files.readString(knownHosts).equals(expected)) {
            throw new IllegalArgumentException("Known hosts does not match the pinned session identity");
        }
        var argv=new ArrayList<>(List.of("ssh","-F","/dev/null","-T"));
        for(String option:List.of("BatchMode=yes","IdentitiesOnly=yes","IdentityAgent=none",
                "PreferredAuthentications=publickey","PasswordAuthentication=no","KbdInteractiveAuthentication=no",
                "HostbasedAuthentication=no","GSSAPIAuthentication=no","NumberOfPasswordPrompts=0",
                "StrictHostKeyChecking=yes","GlobalKnownHostsFile=/dev/null","UpdateHostKeys=no",
                "VerifyHostKeyDNS=no","CanonicalizeHostname=no","ProxyCommand=none","ProxyJump=none",
                "PermitLocalCommand=no","ControlMaster=no","ControlPath=none","ControlPersist=no",
                "ClearAllForwardings=yes","ForwardAgent=no","ForwardX11=no","RequestTTY=no","ConnectTimeout=5",
                "UserKnownHostsFile="+knownHosts.toAbsolutePath())) {
            argv.add("-o"); argv.add(option);
        }
        argv.addAll(List.of("-i",identity.toAbsolutePath().toString(),"-p",Integer.toString(endpoint.port()),
                "--",endpoint.username()+"@"+endpoint.host(),"status"));
        return List.copyOf(argv);
    }

    private static void validateFile(Path path) throws IOException {
        if(!path.toAbsolutePath().toString().matches("/[A-Za-z0-9/._-]+")) {
            throw new IllegalArgumentException("Managed credential paths must not contain SSH expansion characters");
        }
        for(Path current=path.toAbsolutePath();current!=null;current=current.getParent()) {
            if(Files.isSymbolicLink(current)) throw new IllegalArgumentException("Managed credential paths cannot be symbolic links");
            if(Files.isDirectory(current,LinkOption.NOFOLLOW_LINKS)) {
                var parent=Files.readAttributes(current,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                String owner=parent.owner().getName();
                int mode=(int)Files.getAttribute(current,"unix:mode",LinkOption.NOFOLLOW_LINKS);
                if ((!owner.equals("root") && !owner.equals(System.getProperty("user.name")))
                        || ((mode & 0022)!=0 && (mode & 01000)==0)) {
                    throw new IllegalArgumentException("Managed credential ancestor permits untrusted replacement");
                }
            }
        }
        var attributes=Files.readAttributes(path,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isRegularFile() || !attributes.owner().getName().equals(System.getProperty("user.name"))
                || !attributes.permissions().equals(PosixFilePermissions.fromString("rw-------"))) {
            throw new IllegalArgumentException("Managed credentials require owner-only regular files");
        }
    }

    private static String validatedHostKey(String value) {
        String[] parts=value.split(" ",-1);
        if(parts.length!=2 || !parts[0].equals("ssh-ed25519")) throw new IllegalArgumentException("Only Ed25519 host identity is supported");
        byte[] blob=Base64.getDecoder().decode(parts[1]);
        byte[] prefix=ByteBuffer.allocate(19).putInt(11).put("ssh-ed25519".getBytes(StandardCharsets.US_ASCII)).putInt(32).array();
        if(blob.length!=51 || !Arrays.equals(Arrays.copyOf(blob,19),prefix)
                || !Base64.getEncoder().encodeToString(blob).equals(parts[1])) {
            throw new IllegalArgumentException("Invalid Ed25519 host identity");
        }
        return value;
    }
}
