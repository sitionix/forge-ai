package com.sitionix.forgeai.api;

import com.sitionix.forgeai.infrastructure.agentclient.ForgeAgentClientProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;import java.net.URI;import java.net.http.HttpClient;import java.net.http.HttpRequest;import java.net.http.HttpResponse;import java.time.Duration;import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;import org.springframework.web.bind.annotation.*;import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Narrow proxy for the project Logs API; paths are constructed only from typed identifiers. */
@RestController @RequiredArgsConstructor
public class ForgeAiProjectLogsProxyController {
 private final ForgeAgentClientProperties properties;
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
 @RequestMapping(path="/api/v1/infrastructure/agents/projects/{projectId}/log-sources",method={RequestMethod.GET,RequestMethod.POST}) public ResponseEntity<byte[]> sources(@PathVariable UUID projectId,@RequestBody(required=false) byte[] body,HttpServletRequest request){return forward(request.getMethod(),"/api/v1/projects/"+projectId+"/log-sources",body);}
 @RequestMapping(path="/api/v1/infrastructure/agents/projects/{projectId}/log-sources/{tail}",method={RequestMethod.PUT,RequestMethod.DELETE,RequestMethod.POST}) public ResponseEntity<byte[]> sourceAction(@PathVariable UUID projectId,@PathVariable String tail,@RequestBody(required=false) byte[] body,HttpServletRequest request){if(!tail.matches("discover|validate|[0-9a-fA-F-]{36}"))return ResponseEntity.notFound().build();return forward(request.getMethod(),"/api/v1/projects/"+projectId+"/log-sources/"+tail,body);}
 @RequestMapping(path="/api/v1/infrastructure/agents/projects/{projectId}/ssh-connections",method={RequestMethod.GET,RequestMethod.POST}) public ResponseEntity<byte[]> ssh(@PathVariable UUID projectId,@RequestBody(required=false) byte[] body,HttpServletRequest request){return forward(request.getMethod(),"/api/v1/projects/"+projectId+"/ssh-connections",body);}
 @GetMapping(path="/api/v1/infrastructure/agents/projects/{projectId}/logs/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public ResponseEntity<StreamingResponseBody> stream(@PathVariable UUID projectId,HttpServletRequest servlet){
  try{URI uri=properties.getBaseUrl().resolve("/api/v1/projects/"+projectId+"/logs/stream?"+servlet.getQueryString());var upstream=client.send(HttpRequest.newBuilder(uri).header("Accept",MediaType.TEXT_EVENT_STREAM_VALUE).GET().build(),HttpResponse.BodyHandlers.ofInputStream());StreamingResponseBody body=out->{try(var in=upstream.body()){in.transferTo(out);}finally{upstream.body().close();}};return ResponseEntity.status(upstream.statusCode()).contentType(MediaType.TEXT_EVENT_STREAM).body(body);}catch(Exception e){throw new IllegalStateException("Agent log stream unavailable",e);}
 }
 private ResponseEntity<byte[]> forward(String method,String path,byte[] body){try{var b=HttpRequest.newBuilder(properties.getBaseUrl().resolve(path)).header("Accept",MediaType.APPLICATION_JSON_VALUE);if(body!=null&&body.length>0)b.header("Content-Type",MediaType.APPLICATION_JSON_VALUE);b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));var r=client.send(b.build(),HttpResponse.BodyHandlers.ofByteArray());org.springframework.http.HttpHeaders h=new org.springframework.http.HttpHeaders();r.headers().firstValue("content-type").ifPresent(v->h.set("Content-Type",v));return new ResponseEntity<>(r.body(),h,HttpStatusCode.valueOf(r.statusCode()));}catch(Exception e){throw new IllegalStateException("Agent Logs API unavailable",e);}}
}
