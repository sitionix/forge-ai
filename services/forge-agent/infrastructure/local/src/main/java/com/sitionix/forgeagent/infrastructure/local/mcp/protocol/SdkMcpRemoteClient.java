package com.sitionix.forgeagent.infrastructure.local.mcp.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import com.sitionix.forgeagent.domain.exception.McpProbeException;
import com.sitionix.forgeagent.domain.exception.McpToolCallException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.McpRemoteProbe;
import com.sitionix.forgeagent.domain.port.McpRemoteToolClient;
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
import java.util.function.Function;
import com.fasterxml.jackson.core.type.TypeReference;
import javax.net.ssl.SSLContext;

/** SDK protocol boundary. Only typed, bounded summaries cross into the application. */
public final class SdkMcpRemoteClient implements McpRemoteProbe, McpRemoteToolClient {
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration toolCallTimeout;
    private final int maxResponseBytes;
    private final int maxPages;
    private final int maxTools;
    private final Set<String> allowedPrivateEndpoints;
    private final SSLContext sslContext;
    private final ObjectMapper canonical;
    private final JacksonMcpJsonMapper protocolJson;

    public SdkMcpRemoteClient(Duration connectTimeout, Duration requestTimeout, int maxResponseBytes,
                             int maxPages, int maxTools, Set<String> allowedPrivateEndpoints) {
        this(connectTimeout, requestTimeout, maxResponseBytes, maxPages, maxTools, allowedPrivateEndpoints, null);
    }

    public SdkMcpRemoteClient(Duration connectTimeout, Duration requestTimeout, int maxResponseBytes,
                             int maxPages, int maxTools, Set<String> allowedPrivateEndpoints, SSLContext sslContext) {
        this(connectTimeout, requestTimeout, maxResponseBytes, maxPages, maxTools,
                allowedPrivateEndpoints, sslContext, new ObjectMapper());
    }

    public SdkMcpRemoteClient(Duration connectTimeout, Duration requestTimeout, int maxResponseBytes,
                             int maxPages, int maxTools, Set<String> allowedPrivateEndpoints,
                             SSLContext sslContext, ObjectMapper objectMapper) {
        this(connectTimeout, requestTimeout, maxResponseBytes, maxPages, maxTools,
                allowedPrivateEndpoints, sslContext, objectMapper, Duration.ofSeconds(30));
    }

    public SdkMcpRemoteClient(Duration connectTimeout, Duration requestTimeout, int maxResponseBytes,
                             int maxPages, int maxTools, Set<String> allowedPrivateEndpoints,
                             SSLContext sslContext, ObjectMapper objectMapper, Duration toolCallTimeout) {
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                || toolCallTimeout == null || toolCallTimeout.isNegative() || toolCallTimeout.isZero()
                || maxResponseBytes < 1024 || maxPages < 1 || maxTools < 1
                || allowedPrivateEndpoints == null || objectMapper == null)
            throw new IllegalArgumentException("Invalid MCP client configuration");
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.toolCallTimeout = toolCallTimeout;
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
        var http = new McpHttpClientBuilder();
        var transport = transport(endpoint, authType, credential, http, requestTimeout);
        boolean initialized = false;
        try (var client = McpClient.sync(transport).initializationTimeout(requestTimeout)
                .requestTimeout(requestTimeout).build()) {
            String protocolVersion = initialize(client);
            initialized = true;
            return new McpProbeReport(protocolVersion, listTools(client::listTools).stream()
                    .map(tool -> new McpToolSummary(tool.name(), tool.description(), fingerprint(tool.inputSchema())))
                    .toList());
        } catch (McpProbeException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw transportFailure(http, exception, initialized);
        }
    }

    @Override public McpToolCallResult call(URI endpoint, McpAuthType authType, byte[] credential,
                                            String toolName, String expectedSchemaFingerprint, String argumentsJson) {
        return call(endpoint, authType, credential, toolName, expectedSchemaFingerprint,
                argumentsJson, () -> true);
    }

    @Override public McpToolCallResult call(URI endpoint, McpAuthType authType, byte[] credential,
                                            String toolName, String expectedSchemaFingerprint, String argumentsJson,
                                            java.util.function.BooleanSupplier admitBeforeDispatch) {
        if (toolName == null || toolName.isBlank() || expectedSchemaFingerprint == null
                || !expectedSchemaFingerprint.matches("sha256:[0-9a-f]{64}") || argumentsJson == null
                || admitBeforeDispatch == null)
            throw new IllegalArgumentException("Invalid MCP tool call");
        Map<String, Object> arguments;
        try {
            var argumentObject = canonical.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(argumentsJson);
            if (argumentObject == null || !argumentObject.isObject())
                throw new IllegalArgumentException("Invalid MCP arguments");
            arguments = canonical.convertValue(argumentObject, new TypeReference<Map<String, Object>>() {});
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Invalid MCP arguments");
        }
        validateEndpoint(endpoint);
        var http = new McpHttpClientBuilder();
        var transport = transport(endpoint, authType, credential, http, toolCallTimeout);
        long deadline = System.nanoTime() + toolCallTimeout.toNanos();
        boolean initialized = false;
        var client = McpClient.async(transport).initializationTimeout(toolCallTimeout)
                .requestTimeout(toolCallTimeout).build();
        try {
            var initialization = client.initialize().block(remaining(deadline));
            if (initialization == null || initialization.protocolVersion() == null
                    || initialization.capabilities() == null || initialization.capabilities().tools() == null)
                throw new McpProbeException(McpProbeException.Reason.UNSUPPORTED_PROTOCOL);
            initialized = true;
            boolean current = listTools(cursor -> client.listTools(cursor).block(remaining(deadline)))
                    .stream().anyMatch(tool -> tool.name().equals(toolName)
                    && fingerprint(tool.inputSchema()).equals(expectedSchemaFingerprint));
            if (!current) throw new McpToolCallException(McpToolCallException.Kind.SCHEMA_CHANGED, null);
            if (!admitBeforeDispatch.getAsBoolean())
                throw new McpProbeException(McpProbeException.Reason.UNAVAILABLE);
            var result = client.callTool(new McpSchema.CallToolRequest(toolName, arguments))
                    .block(remaining(deadline));
            if (result == null || result.content() == null)
                throw new McpProbeException(McpProbeException.Reason.INVALID_RESPONSE);
            String content = canonical.readTree(protocolJson.writeValueAsString(result)).path("content").toString();
            String structured = result.structuredContent() == null ? null
                    : protocolJson.writeValueAsString(result.structuredContent());
            return new McpToolCallResult(Boolean.TRUE.equals(result.isError()), content, structured);
        } catch (McpProbeException | McpToolCallException exception) {
            throw exception;
        } catch (java.io.IOException exception) {
            throw new McpProbeException(McpProbeException.Reason.INVALID_RESPONSE);
        } catch (RuntimeException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause())
                if (cause instanceof io.modelcontextprotocol.spec.McpError mcp && mcp.getJsonRpcError() != null)
                    throw new McpToolCallException(McpToolCallException.Kind.PROTOCOL_FAILURE,
                            mcp.getJsonRpcError().code());
            if (http.authStatus() == 0 && hasCause(exception, java.util.concurrent.TimeoutException.class))
                throw new McpProbeException(McpProbeException.Reason.UNAVAILABLE);
            throw transportFailure(http, exception, initialized);
        } finally {
            client.close();
        }
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new McpProbeException(McpProbeException.Reason.UNAVAILABLE);
        return Duration.ofNanos(nanos);
    }

    private static String initialize(io.modelcontextprotocol.client.McpSyncClient client) {
        var result = client.initialize();
        if (result == null || result.protocolVersion() == null
                || result.capabilities() == null || result.capabilities().tools() == null)
            throw new McpProbeException(McpProbeException.Reason.UNSUPPORTED_PROTOCOL);
        return result.protocolVersion();
    }

    /** Full SDK schemas are used only by the gateway's transient per-grant tool view. */
    public List<McpSchema.Tool> discoverApprovedTools(URI endpoint, McpAuthType authType,
                                                       byte[] credential, Set<McpAllowedTool> approved) {
        if (approved == null || approved.isEmpty()) throw new IllegalArgumentException("No approved MCP tools");
        validateEndpoint(endpoint);
        var http = new McpHttpClientBuilder();
        var transport = transport(endpoint, authType, credential, http, requestTimeout);
        boolean initialized = false;
        try (var client = McpClient.sync(transport).initializationTimeout(requestTimeout)
                .requestTimeout(requestTimeout).build()) {
            initialize(client);
            initialized = true;
            var current = listTools(client::listTools);
            var selected = current.stream().filter(tool -> approved.contains(
                    new McpAllowedTool(tool.name(), fingerprint(tool.inputSchema())))).toList();
            if (selected.size() != approved.size())
                throw new McpToolCallException(McpToolCallException.Kind.SCHEMA_CHANGED, null);
            return selected;
        } catch (McpProbeException | McpToolCallException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw transportFailure(http, exception, initialized);
        }
    }

    private List<McpSchema.Tool> listTools(Function<String, McpSchema.ListToolsResult> fetch) {
        List<McpSchema.Tool> tools = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < maxPages; page++) {
            McpSchema.ListToolsResult result = fetch.apply(cursor);
            if (result == null || result.tools() == null)
                throw new McpProbeException(McpProbeException.Reason.INVALID_RESPONSE);
            for (var tool : result.tools()) {
                if (tool == null || tool.name() == null || tool.name().isBlank()
                        || tool.inputSchema() == null || !names.add(tool.name()) || tools.size() >= maxTools)
                    throw new McpProbeException(McpProbeException.Reason.INVALID_RESPONSE);
                tools.add(tool);
            }
            cursor = result.nextCursor();
            if (cursor == null || cursor.isBlank()) return tools;
            if (!cursors.add(cursor)) throw new McpProbeException(McpProbeException.Reason.INVALID_RESPONSE);
        }
        throw new McpProbeException(McpProbeException.Reason.INVALID_RESPONSE);
    }

    private HttpClientStreamableHttpTransport transport(URI endpoint, McpAuthType authType, byte[] credential,
                                                         McpHttpClientBuilder http, Duration timeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder().timeout(timeout);
        addCredential(request, authType, credential);
        String base = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
        String path = endpoint.getRawPath();
        if (sslContext != null) http.sslContext(sslContext);
        return HttpClientStreamableHttpTransport.builder(base)
                .jsonMapper(protocolJson)
                .endpoint(path == null || path.isEmpty() ? "/" : path)
                .requestBuilder(request)
                .clientBuilder(http)
                .connectTimeout(connectTimeout)
                .maxResponseSize(maxResponseBytes)
                .openConnectionOnStartup(false)
                .build();
    }

    private static McpProbeException transportFailure(McpHttpClientBuilder http, RuntimeException exception,
                                                       boolean initialized) {
        if (http.authStatus() == 401) return new McpProbeException(McpProbeException.Reason.AUTH_REQUIRED);
        if (http.authStatus() == 403) return new McpProbeException(McpProbeException.Reason.FORBIDDEN);
        if (!initialized && hasSdkInvalidProtocol(exception))
            return new McpProbeException(McpProbeException.Reason.UNSUPPORTED_PROTOCOL);
        if (http.sawSuccessfulPost() && hasCause(exception, io.modelcontextprotocol.spec.McpTransportException.class))
            return new McpProbeException(McpProbeException.Reason.INVALID_RESPONSE);
        if (http.completedSuccessfulPost() && hasCause(exception, java.util.concurrent.TimeoutException.class))
            return new McpProbeException(McpProbeException.Reason.INVALID_RESPONSE);
        return new McpProbeException(McpProbeException.Reason.UNAVAILABLE);
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
