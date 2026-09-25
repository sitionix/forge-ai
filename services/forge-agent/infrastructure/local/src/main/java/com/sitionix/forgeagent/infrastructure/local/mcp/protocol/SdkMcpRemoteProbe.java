package com.sitionix.forgeagent.infrastructure.local.mcp.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import com.sitionix.forgeagent.domain.exception.McpProbeException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.McpRemoteProbe;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import javax.net.ssl.SSLContext;

/** SDK protocol boundary. Only typed, bounded summaries cross into the application. */
public final class SdkMcpRemoteProbe implements McpRemoteProbe {
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final int maxPages;
    private final int maxTools;
    private final Set<String> allowedPrivateEndpoints;
    private final SSLContext sslContext;
    private final ObjectMapper canonical;
    private final JacksonMcpJsonMapper protocolJson;

    public SdkMcpRemoteProbe(Duration connectTimeout, Duration requestTimeout, int maxResponseBytes,
                             int maxPages, int maxTools, Set<String> allowedPrivateEndpoints) {
        this(connectTimeout, requestTimeout, maxResponseBytes, maxPages, maxTools, allowedPrivateEndpoints, null);
    }

    public SdkMcpRemoteProbe(Duration connectTimeout, Duration requestTimeout, int maxResponseBytes,
                             int maxPages, int maxTools, Set<String> allowedPrivateEndpoints, SSLContext sslContext) {
        this(connectTimeout, requestTimeout, maxResponseBytes, maxPages, maxTools,
                allowedPrivateEndpoints, sslContext, new ObjectMapper());
    }

    public SdkMcpRemoteProbe(Duration connectTimeout, Duration requestTimeout, int maxResponseBytes,
                             int maxPages, int maxTools, Set<String> allowedPrivateEndpoints,
                             SSLContext sslContext, ObjectMapper objectMapper) {
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                || maxResponseBytes < 1024 || maxPages < 1 || maxTools < 1
                || allowedPrivateEndpoints == null || objectMapper == null)
            throw new IllegalArgumentException("Invalid MCP probe configuration");
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.maxResponseBytes = maxResponseBytes;
        this.maxPages = maxPages;
        this.maxTools = maxTools;
        this.allowedPrivateEndpoints = Set.copyOf(allowedPrivateEndpoints);
        this.sslContext = sslContext;
        this.canonical = objectMapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.protocolJson = new JacksonMcpJsonMapper(objectMapper.copy());
    }

    @Override public McpProbeReport probe(URI endpoint, McpAuthType authType, byte[] credential) {
        validateEndpoint(endpoint);
        HttpRequest.Builder request = HttpRequest.newBuilder().timeout(requestTimeout);
        addCredential(request, authType, credential);
        String base = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
        String path = endpoint.getRawPath();
        var http = new McpProbeHttpClientBuilder();
        if (sslContext != null) http.sslContext(sslContext);
        var transport = HttpClientStreamableHttpTransport.builder(base)
                .jsonMapper(protocolJson)
                .endpoint(path == null || path.isEmpty() ? "/" : path)
                .requestBuilder(request)
                .clientBuilder(http)
                .connectTimeout(connectTimeout)
                .maxResponseSize(maxResponseBytes)
                .openConnectionOnStartup(false)
                .build();
        boolean initialized = false;
        try (var client = McpClient.sync(transport).initializationTimeout(requestTimeout)
                .requestTimeout(requestTimeout).build()) {
            var initResult = client.initialize();
            initialized = true;
            if (initResult == null || initResult.protocolVersion() == null
                    || initResult.capabilities() == null || initResult.capabilities().tools() == null)
                throw new McpProbeException(McpProbeException.Reason.UNSUPPORTED_PROTOCOL);
            List<McpToolSummary> tools = new ArrayList<>();
            Set<String> names = new HashSet<>();
            Set<String> cursors = new HashSet<>();
            String cursor = null;
            for (int page = 0; page < maxPages; page++) {
                McpSchema.ListToolsResult result = client.listTools(cursor);
                if (result == null || result.tools() == null)
                    throw new McpProbeException(McpProbeException.Reason.INVALID_RESPONSE);
                for (var tool : result.tools()) {
                    if (tool == null || tool.name() == null || tool.name().isBlank()
                            || tool.inputSchema() == null || !names.add(tool.name()) || tools.size() >= maxTools)
                        throw new McpProbeException(McpProbeException.Reason.INVALID_RESPONSE);
                    tools.add(new McpToolSummary(tool.name(), tool.description(), fingerprint(tool.inputSchema())));
                }
                cursor = result.nextCursor();
                if (cursor == null || cursor.isBlank())
                    return new McpProbeReport(initResult.protocolVersion(), tools);
                if (!cursors.add(cursor)) throw new McpProbeException(McpProbeException.Reason.INVALID_RESPONSE);
            }
            throw new McpProbeException(McpProbeException.Reason.INVALID_RESPONSE);
        } catch (McpProbeException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (http.authStatus() == 401) throw new McpProbeException(McpProbeException.Reason.AUTH_REQUIRED);
            if (http.authStatus() == 403) throw new McpProbeException(McpProbeException.Reason.FORBIDDEN);
            if (!initialized && hasSdkInvalidProtocol(exception))
                throw new McpProbeException(McpProbeException.Reason.UNSUPPORTED_PROTOCOL);
            if (http.sawSuccessfulPost() && hasCause(exception, io.modelcontextprotocol.spec.McpTransportException.class))
                throw new McpProbeException(McpProbeException.Reason.INVALID_RESPONSE);
            if (http.completedSuccessfulPost() && hasCause(exception, java.util.concurrent.TimeoutException.class))
                throw new McpProbeException(McpProbeException.Reason.INVALID_RESPONSE);
            throw new McpProbeException(McpProbeException.Reason.UNAVAILABLE);
        }
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (type.isInstance(cause)) return true;
        return false;
    }

    private static boolean hasSdkInvalidProtocol(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof io.modelcontextprotocol.spec.McpError mcp
                    && mcp.getJsonRpcError() != null && mcp.getJsonRpcError().code() == -32602)
                return true;
        return false;
    }

    private String fingerprint(Object schema) {
        try {
            byte[] bytes = canonical.writeValueAsBytes(schema);
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new McpProbeException(McpProbeException.Reason.INVALID_RESPONSE);
        }
    }

    private void validateEndpoint(URI endpoint) {
        if (endpoint == null || endpoint.getHost() == null || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme())))
            throw new McpProbeException(McpProbeException.Reason.ENDPOINT_DENIED);
        int port = endpoint.getPort() < 0 ? ("https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80) : endpoint.getPort();
        if (port < 1 || port > 65535) throw new McpProbeException(McpProbeException.Reason.ENDPOINT_DENIED);
        try {
            boolean allowed = allowedPrivateEndpoints.contains(endpoint.getHost().toLowerCase(Locale.ROOT) + ":" + port);
            InetAddress[] addresses = InetAddress.getAllByName(endpoint.getHost());
            if (addresses.length == 0) throw new McpProbeException(McpProbeException.Reason.ENDPOINT_DENIED);
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress())
                    throw new McpProbeException(McpProbeException.Reason.ENDPOINT_DENIED);
                byte[] raw = address.getAddress();
                if (raw.length == 16 && ((raw[0] & 0xfe) == 0xfc) && !allowed)
                    throw new McpProbeException(McpProbeException.Reason.ENDPOINT_DENIED);
                if (raw.length == 4 && (raw[0] & 0xff) == 100 && ((raw[1] & 0xc0) == 64) && !allowed)
                    throw new McpProbeException(McpProbeException.Reason.ENDPOINT_DENIED);
                if ((address.isLoopbackAddress() || address.isSiteLocalAddress()) && !allowed)
                    throw new McpProbeException(McpProbeException.Reason.ENDPOINT_DENIED);
            }
        } catch (McpProbeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new McpProbeException(McpProbeException.Reason.UNAVAILABLE);
        }
    }

    private static void addCredential(HttpRequest.Builder request, McpAuthType type, byte[] credential) {
        if (type == McpAuthType.NONE) return;
        if (credential == null || credential.length == 0) throw new McpProbeException(McpProbeException.Reason.AUTH_REQUIRED);
        if (type == McpAuthType.BEARER) {
            request.header("Authorization", "Bearer " + new String(credential, StandardCharsets.UTF_8));
            return;
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(credential))) {
            int count = input.readInt();
            if (count < 1 || count > 20) throw new IllegalArgumentException();
            for (int i = 0; i < count; i++) request.header(input.readUTF(), input.readUTF());
            if (input.available() != 0) throw new IllegalArgumentException();
        } catch (Exception exception) {
            throw new McpProbeException(McpProbeException.Reason.INVALID_RESPONSE);
        }
    }
}
