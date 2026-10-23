package com.sitionix.forgeai.infrastructure.agentclient.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.library.LibrarySnapshot;
import com.sitionix.forgeai.domain.exception.AgentClientException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounds headers and body together, and cancels instead of draining oversized streams. */
final class AiLibraryHttpTransport implements AutoCloseable {
 private final ObjectMapper json;private final Duration timeout;private final int byteLimit;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
 AiLibraryHttpTransport(ObjectMapper json,Duration timeout,int byteLimit){this.json=json;this.timeout=timeout;this.byteLimit=byteLimit;}
 LibrarySnapshot query(URI uri,String token,Object requestBody) throws Exception {
  var request=HttpRequest.newBuilder(uri).timeout(timeout).header("Authorization","Bearer "+token)
   .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(requestBody))).build();
  var subscriber=new LimitedBody(byteLimit);
  var pending=http.sendAsync(request,info->subscriber);
  try {
   var response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
   if(response.statusCode()==404)throw new AgentClientException(404,"{\"code\":\"PROJECT_NOT_FOUND\",\"message\":\"Project was not found.\"}",Map.of(),null);
   if(response.statusCode()<200||response.statusCode()>=300)throw new IllegalStateException("Inventory service unavailable");
   return json.readValue(response.body(),LibrarySnapshot.class);
  }finally{subscriber.cancel();pending.cancel(true);}
 }
 @Override public void close(){http.shutdownNow();}
 private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
  private final int limit;private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  private final CompletableFuture<byte[]> result=new CompletableFuture<>();private Flow.Subscription subscription;
  LimitedBody(int limit){this.limit=limit;}
  @Override public CompletionStage<byte[]> getBody(){return result;}
  @Override public synchronized void onSubscribe(Flow.Subscription value){if(subscription!=null||result.isDone()){value.cancel();return;}subscription=value;value.request(1);}
  @Override public synchronized void onNext(List<ByteBuffer> buffers){
   for(var buffer:buffers){if(buffer.remaining()>limit-bytes.size()){cancel();result.completeExceptionally(new IllegalStateException("Inventory response exceeded limit"));return;}byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);}
   if(subscription!=null)subscription.request(1);
  }
  @Override public void onError(Throwable failure){result.completeExceptionally(failure);}
  @Override public synchronized void onComplete(){result.complete(bytes.toByteArray());}
  synchronized void cancel(){if(subscription!=null)subscription.cancel();result.completeExceptionally(new CancellationException("Inventory request closed"));}
 }
}
