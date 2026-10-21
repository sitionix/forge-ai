package com.sitionix.forgeai.infrastructure.agentclient.library;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.Set;

/** Reads the installer-owned service identity; never provider session credentials. */
public final class ServiceTokenFile {
 private ServiceTokenFile() {}
 public static String read(Path path) {
  try {
   Path absolute=path.toAbsolutePath().normalize();
   for(Path parent=absolute;parent!=null;parent=parent.getParent()) {
    if(Files.isSymbolicLink(parent)) throw new IOException();
    if(!parent.equals(absolute)) {
     int mode=((Number)Files.getAttribute(parent,"unix:mode",LinkOption.NOFOLLOW_LINKS)).intValue();
     if((mode & 0022)!=0 && (mode & 01000)==0) throw new IOException();
    }
   }
   var info=Files.readAttributes(absolute,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
   if(!info.isRegularFile() || !info.permissions().equals(PosixFilePermissions.fromString("rw-------"))
     || !info.owner().equals(Files.getOwner(Path.of("/proc/self"))) || info.size()<32 || info.size()>256
     || ((Number)Files.getAttribute(absolute,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()!=1) throw new IOException();
   final String token;
   try(var channel=Files.newByteChannel(absolute,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))) {
    var bytes=ByteBuffer.allocate(257);while(bytes.hasRemaining() && channel.read(bytes)>0){}
    bytes.flip();token=StandardCharsets.US_ASCII.decode(bytes).toString();
   }
   if(!token.matches("[A-Za-z0-9_-]{32,256}")) throw new IOException();
   return token;
  } catch(IOException failure) { throw new IllegalStateException("Service identity is unavailable"); }
 }
}
