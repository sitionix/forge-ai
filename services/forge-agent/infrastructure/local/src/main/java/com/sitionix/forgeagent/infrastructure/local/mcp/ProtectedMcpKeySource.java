package com.sitionix.forgeagent.infrastructure.local.mcp;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** File-only AES key source. Content and parsing errors never enter exception messages. */
public final class ProtectedMcpKeySource implements McpLocalKeySource {
    private final Path file;
    public ProtectedMcpKeySource(Path file) { this.file = file; }

    @Override public McpLocalKeys keys() {
        try {
            String text = new String(readProtected(file),java.nio.charset.StandardCharsets.US_ASCII);
            String active = null;
            Map<String,byte[]> keys = new HashMap<>();
            for (String line : text.split("\n",-1)) {
                if (line.isEmpty()) continue;
                int equals = line.indexOf('=');
                if (equals < 1) throw unavailable();
                String name = line.substring(0,equals), value = line.substring(equals+1);
                if (name.equals("active")) {
                    if (active != null || !value.matches("[A-Za-z0-9_-]{1,40}")) throw unavailable();
                    active = value;
                } else if (name.startsWith("key.")) {
                    String id = name.substring(4);
                    if (!id.matches("[A-Za-z0-9_-]{1,40}") || keys.size() >= 16) throw unavailable();
                    byte[] decoded = Base64.getDecoder().decode(value);
                    if (decoded.length != 32 || keys.putIfAbsent(id,decoded) != null) throw unavailable();
                } else throw unavailable();
            }
            return new McpLocalKeys(active,keys);
        } catch (Exception exception) { throw unavailable(); }
    }

    public static byte[] readProtected(Path input) {
        try {
            Path path = input.toAbsolutePath().normalize();
            if (!input.isAbsolute() || !path.equals(input) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw unavailable();
            int controlUid = ((Number)Files.getAttribute(Path.of("/proc/self"),"unix:uid")).intValue();
            int rootUid = ((Number)Files.getAttribute(Path.of("/"),"unix:uid",LinkOption.NOFOLLOW_LINKS)).intValue();
            for (Path current=path;current!=null;current=current.getParent()) {
                int uid = ((Number)Files.getAttribute(current,"unix:uid",LinkOption.NOFOLLOW_LINKS)).intValue();
                int mode = ((Number)Files.getAttribute(current,"unix:mode",LinkOption.NOFOLLOW_LINKS)).intValue();
                boolean stickyRootTemp = current.equals(Path.of("/tmp")) && uid == rootUid && (mode & 01000) != 0;
                if (Files.isSymbolicLink(current) || (uid != rootUid && uid != controlUid)
                        || ((mode & 0022) != 0 && !stickyRootTemp)
                        || (current.equals(path) && ((mode & 0077) != 0 || ((Number)Files.getAttribute(current,"unix:nlink")).intValue() != 1)))
                    throw unavailable();
            }
            var before = Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            try (FileChannel channel = FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.allocate(4097);
                while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
                if (buffer.position() == 0 || buffer.position() > 4096 || channel.read(ByteBuffer.allocate(1)) != -1) throw unavailable();
                var after = Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if (!Objects.equals(before.fileKey(),after.fileKey()) || before.size() != after.size()) throw unavailable();
                return Arrays.copyOf(buffer.array(),buffer.position());
            }
        } catch (Exception exception) { throw unavailable(); }
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("MCP key configuration unavailable"); }
}
