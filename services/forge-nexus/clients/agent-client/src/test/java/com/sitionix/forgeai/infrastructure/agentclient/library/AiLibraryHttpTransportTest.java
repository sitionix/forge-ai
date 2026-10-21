package com.sitionix.forgeai.infrastructure.agentclient.library;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AiLibraryHttpTransportTest {
 @Test void boundsEntireResponseIncludingStalledBody() throws Exception {
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/query",e->{try{e.sendResponseHeaders(200,0);e.getResponseBody().write('{');e.getResponseBody().flush();Thread.sleep(2000);}catch(Exception ignored){}finally{e.close();}});server.start();
  long started=System.nanoTime();
  try(var transport=new AiLibraryHttpTransport(new ObjectMapper(),Duration.ofMillis(150),1024)) {
   assertThrows(Exception.class,()->transport.query(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/query"),"fixture",Map.of()));
   assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<1500);
  }finally{server.stop(0);}
 }
 @Test void cancelsOversizeResponseInsteadOfDrainingItsTail() throws Exception {
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/query",e->{try{e.sendResponseHeaders(200,0);e.getResponseBody().write(new byte[2048]);e.getResponseBody().flush();Thread.sleep(2000);}catch(Exception ignored){}finally{e.close();}});server.start();
  long started=System.nanoTime();
  try(var transport=new AiLibraryHttpTransport(new ObjectMapper(),Duration.ofSeconds(1),1024)) {
   assertThrows(Exception.class,()->transport.query(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/query"),"fixture",Map.of()));
   assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<1500);
  }finally{server.stop(0);}
 }
}
