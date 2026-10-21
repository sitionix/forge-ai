package com.sitionix.forgeagent.api.security;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ServiceTokenFileTest {
 @TempDir Path directory;
 @Test void acceptsInstalledTokenBoundsAndRejectsUnsafeFiles() throws Exception {
  var file=directory.resolve("token");
  for(int size:new int[]{32,64,256}) {
   Files.writeString(file,"t".repeat(size));Files.setAttribute(file,"unix:mode",0600);
   assertEquals("t".repeat(size),ServiceTokenFile.read(file));
  }
  Files.writeString(file,"t".repeat(31));assertThrows(IllegalStateException.class,()->ServiceTokenFile.read(file));
  Files.writeString(file,"t".repeat(257));assertThrows(IllegalStateException.class,()->ServiceTokenFile.read(file));
  Files.writeString(file,"t".repeat(64));Files.setAttribute(file,"unix:mode",0644);assertThrows(IllegalStateException.class,()->ServiceTokenFile.read(file));
  Files.setAttribute(file,"unix:mode",0600);
  var link=directory.resolve("link");Files.createSymbolicLink(link,file);assertThrows(IllegalStateException.class,()->ServiceTokenFile.read(link));
  var hard=directory.resolve("hard");Files.createLink(hard,file);assertThrows(IllegalStateException.class,()->ServiceTokenFile.read(file));
 }
}
