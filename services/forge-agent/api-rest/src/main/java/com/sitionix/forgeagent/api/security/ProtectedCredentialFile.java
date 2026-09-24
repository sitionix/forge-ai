package com.sitionix.forgeagent.api.security;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Constant-time service credential check against a protected file. */
public final class ProtectedCredentialFile {
    private final byte[] digest;
    public ProtectedCredentialFile(Path file) {
        byte[] decoded;
        try {
            byte[] encoded = read(file);
            String token = new String(encoded,java.nio.charset.StandardCharsets.US_ASCII).strip();
            decoded = Base64.getUrlDecoder().decode(token);
            if (decoded.length < 32 || decoded.length > 64 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token))
                throw unavailable();
            Arrays.fill(encoded,(byte)0);
            digest = sha256(decoded);
            Arrays.fill(decoded,(byte)0);
        } catch (Exception exception) { throw unavailable(); }
    }
    public boolean matchesBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 512) return false;
        try {
            String token = authorization.substring(7);
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            boolean canonical = decoded.length >= 32 && decoded.length <= 64
                    && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token);
            byte[] candidateDigest = sha256(decoded);
            Arrays.fill(decoded,(byte)0);
            return canonical && MessageDigest.isEqual(digest,candidateDigest);
        } catch (IllegalArgumentException exception) { return false; }
    }
    private static byte[] sha256(byte[] input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (NoSuchAlgorithmException exception) { throw unavailable(); }
    }
    private static byte[] read(Path input) {
        try {
            Path path = input.toAbsolutePath().normalize();
            if (!input.isAbsolute() || !path.equals(input) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw unavailable();
            int controlUid = ((Number)Files.getAttribute(Path.of("/proc/self"),"unix:uid")).intValue();
            int rootUid = ((Number)Files.getAttribute(Path.of("/"),"unix:uid",LinkOption.NOFOLLOW_LINKS)).intValue();
            for (Path current=path;current!=null;current=current.getParent()) {
                int uid = ((Number)Files.getAttribute(current,"unix:uid",LinkOption.NOFOLLOW_LINKS)).intValue();
                int mode = ((Number)Files.getAttribute(current,"unix:mode",LinkOption.NOFOLLOW_LINKS)).intValue();
                boolean stickyTemp = current.equals(Path.of("/tmp")) && uid == rootUid && (mode & 01000) != 0;
                if (Files.isSymbolicLink(current) || (uid != rootUid && uid != controlUid)
                        || ((mode & 0022) != 0 && !stickyTemp)
                        || (current.equals(path) && ((mode & 0077) != 0 || ((Number)Files.getAttribute(current,"unix:nlink")).intValue() != 1)))
                    throw unavailable();
            }
            try (FileChannel channel = FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.allocate(257);
                while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
                if (buffer.position() == 0 || buffer.position() > 256 || channel.read(ByteBuffer.allocate(1)) != -1) throw unavailable();
                return Arrays.copyOf(buffer.array(),buffer.position());
            }
        } catch (Exception exception) { throw unavailable(); }
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Management credential unavailable"); }
    @Override public String toString() { return "ProtectedCredentialFile[redacted]"; }
}
