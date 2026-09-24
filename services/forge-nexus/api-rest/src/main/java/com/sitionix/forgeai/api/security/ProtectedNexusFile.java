package com.sitionix.forgeai.api.security;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Bounded no-follow read for control-owned credential files. */
public final class ProtectedNexusFile {
    private ProtectedNexusFile() { }
    public static byte[] read(Path input) {
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
            var before = Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            try (FileChannel channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer=ByteBuffer.allocate(257);
                while (buffer.hasRemaining() && channel.read(buffer)>0) { }
                if (buffer.position()==0 || buffer.position()>256 || channel.read(ByteBuffer.allocate(1))!=-1) throw unavailable();
                var after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if (!Objects.equals(before.fileKey(),after.fileKey()) || before.size()!=after.size()) throw unavailable();
                return Arrays.copyOf(buffer.array(),buffer.position());
            }
        } catch (Exception exception) { throw unavailable(); }
    }
    public static byte[] token(Path path) {
        try {
            byte[] encoded=read(path);
            String value=new String(encoded,java.nio.charset.StandardCharsets.US_ASCII).strip();
            byte[] decoded=Base64.getUrlDecoder().decode(value);
            Arrays.fill(encoded,(byte)0);
            if (decoded.length<32 || decoded.length>64 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value))
                throw unavailable();
            return decoded;
        } catch (Exception exception) { throw unavailable(); }
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Protected credential unavailable"); }
}
