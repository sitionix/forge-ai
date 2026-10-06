package com.sitionix.forgeagent.infrastructure.local.mcp.oauth;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.sitionix.forgeagent.domain.exception.McpOAuthException;
import com.sitionix.forgeagent.domain.exception.McpProbeException;
import com.sitionix.forgeagent.infrastructure.local.mcp.McpEndpointPolicy;
import java.io.IOException;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import com.sitionix.forgeagent.domain.model.McpOAuthConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.*;

/** Bounded metadata/registration HTTP only; never carries connection tokens or Forge credentials. */
final class McpOAuthMetadataHttpClient {
    private final HttpClient http;
    private final McpEndpointPolicy policy;
    private final Duration readTimeout;
    private final int maxBytes;
    private final ObjectMapper mapper;
    McpOAuthMetadataHttpClient(HttpClient http,McpEndpointPolicy policy,Duration readTimeout,int maxBytes,ObjectMapper mapper) {
        if(maxBytes<1 || maxBytes==Integer.MAX_VALUE)throw new IllegalArgumentException("OAuth metadata response limit must be positive");
        this.http=http;this.policy=policy;this.readTimeout=readTimeout;this.maxBytes=maxBytes;
        this.mapper=mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        this.mapper.coercionConfigFor(LogicalType.Integer).setCoercion(CoercionInputShape.String,CoercionAction.Fail);
        this.mapper.coercionConfigFor(LogicalType.Boolean).setCoercion(CoercionInputShape.String,CoercionAction.Fail);
        this.mapper.coercionConfigFor(LogicalType.Textual).setCoercion(CoercionInputShape.Integer,CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float,CoercionAction.Fail).setCoercion(CoercionInputShape.Boolean,CoercionAction.Fail);
    }
    Challenge challenge(URI uri,long deadline) {
        return client(uri,deadline).get().uri(uri).accept(MediaType.APPLICATION_JSON,MediaType.TEXT_EVENT_STREAM)
                .exchange((request,response)->{
                    try {return new Challenge(response.getStatusCode().value(),HttpHeaders.readOnlyHttpHeaders(response.getHeaders()));}
                    finally {closeBody(response);}
                });
    }
    /** Some Streamable HTTP servers reject GET or require a session; initialize observes their auth boundary. */
    Challenge initializeChallenge(URI uri,long deadline) {
        var initialize=new McpSchema.InitializeRequest(ProtocolVersions.MCP_2025_11_25,
                McpSchema.ClientCapabilities.builder().build(),new McpSchema.Implementation("Forge","Forge","1"));
        var message=new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,McpSchema.METHOD_INITIALIZE,1,initialize);
        Challenge response=client(uri,deadline).post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON,MediaType.TEXT_EVENT_STREAM).body(message)
                .exchange((request,result)->{try{return new Challenge(result.getStatusCode().value(),HttpHeaders.readOnlyHttpHeaders(result.getHeaders()));}finally{closeBody(result);}});
        String session=response.headers().getFirst("Mcp-Session-Id");
        if(response.status()>=200 && response.status()<300 && session!=null) {
            if(session.isBlank() || session.chars().anyMatch(c->c<0x21 || c>0x7e))throw McpOAuthException.invalidResponse();
            client(uri,deadline).delete().uri(uri).header(HttpHeaders.CONTENT_LENGTH,"0").header("Mcp-Session-Id",session)
                    .header("MCP-Protocol-Version",ProtocolVersions.MCP_2025_11_25)
                    .exchange((request,result)->{try {
                        int status=result.getStatusCode().value();
                        if(!(status>=200 && status<300) && status!=404 && status!=405)throw McpOAuthException.unavailable();
                        return null;
                    }finally{closeBody(result);}});
        }
        return response;
    }
    void validateOAuthUri(URI uri) {
        try {McpOAuthConfiguration.validateUri(uri);}catch(IllegalArgumentException invalid){throw McpOAuthException.invalidResponse();}
        if(!"https".equalsIgnoreCase(uri.getScheme()) && !policy.isAllowedPrivateEndpoint(uri))throw McpOAuthException.endpointDenied();
    }
    <T> T metadata(URI uri,long deadline,Class<T> type) {
        validateOAuthUri(uri);
        return client(uri,deadline).get().uri(uri).accept(MediaType.APPLICATION_JSON)
                .exchange((request,response)->{
                    try {if(response.getStatusCode().value()==404)return null;return decode(response,type,deadline);}
                    finally{closeBody(response);}
                });
    }
    <T> T register(URI uri,long deadline,Object body,Class<T> type) {
        validateOAuthUri(uri);
        return client(uri,deadline).post().uri(uri).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .body(body).exchange((request,response)->{try{return decode(response,type,deadline);}finally{closeBody(response);}});
    }
    private static void closeBody(org.springframework.http.client.ClientHttpResponse response) throws IOException {
        try {response.getBody().close();}
        catch(IOException emptyErrorBody) {
            // HttpURLConnection throws when these header-only responses have no error stream.
            // Their status/headers are sufficient for discovery and standard 404 fallback.
            int status=response.getStatusCode().value();
            if(status!=400 && status!=401 && status!=404 && status!=405)throw emptyErrorBody;
        }
    }
    private <T> T decode(org.springframework.http.client.ClientHttpResponse response,Class<T> type,long deadline) throws IOException {
        if(response.getStatusCode().is3xxRedirection())throw McpOAuthException.invalidResponse();
        if(!response.getStatusCode().is2xxSuccessful())throw McpOAuthException.unavailable();
        if(response.getHeaders().getContentLength()>maxBytes)throw McpOAuthException.invalidResponse();
        var contentType=response.getHeaders().getContentType();
        if(contentType==null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType))throw McpOAuthException.invalidResponse();
        byte[] bytes=new byte[maxBytes+1];int size=0;
        try(var body=response.getBody()) {
            while(size<bytes.length) {
                if(System.nanoTime()>=deadline)throw McpOAuthException.unavailable();
                int count=body.read(bytes,size,Math.min(8192,bytes.length-size));
                if(count<0)break;size+=count;
            }
        }
        if(size>maxBytes)throw McpOAuthException.invalidResponse();
        try {
            T value=mapper.readerFor(type).readValue(bytes,0,size);
            if(value==null)throw McpOAuthException.invalidResponse();
            return value;
        } catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw McpOAuthException.invalidResponse();}
    }
    private RestClient client(URI uri,long deadline) {
        if(System.nanoTime()>=deadline)throw McpOAuthException.unavailable();
        try {policy.validate(uri);}catch(McpProbeException denied){throw McpOAuthException.endpointDenied();}
        long remaining=deadline-System.nanoTime();
        if(remaining<java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1))throw McpOAuthException.unavailable();
        var factory=new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(java.net.HttpURLConnection connection,String method) throws IOException {
                super.prepareConnection(connection,method);connection.setInstanceFollowRedirects(false);
                if("DELETE".equals(method))connection.setDoOutput(false);
                if(connection instanceof javax.net.ssl.HttpsURLConnection secure)secure.setSSLSocketFactory(http.sslContext().getSocketFactory());
            }
        };
        factory.setProxy(java.net.Proxy.NO_PROXY);
        factory.setConnectTimeout(Duration.ofNanos(Math.min(remaining,http.connectTimeout().orElse(readTimeout).toNanos())));
        factory.setReadTimeout(Duration.ofNanos(Math.min(remaining,readTimeout.toNanos())));
        return RestClient.builder().requestFactory(factory).defaultHeader(HttpHeaders.CONNECTION,"close").build();
    }
    record Challenge(int status,HttpHeaders headers){}
}
