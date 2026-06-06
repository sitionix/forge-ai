package com.sitionix.forgeai.infrastructure.codexcli.adapter.appserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

public final class FakeCodexAppServerMain {

    private FakeCodexAppServerMain() {
    }

    public static void main(final String[] args) throws Exception {
        final String scenario = args.length == 0 ? "success" : args[0];
        final ObjectMapper mapper = new ObjectMapper();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        final Writer writer = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);

        boolean initializeSeen = false;
        boolean initializedSeen = false;
        String threadId = "thr_fake";
        String pendingTurnId = null;
        String pendingPrompt = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            final JsonNode message = mapper.readTree(line);
            final String method = message.path("method").asText();
            if ("initialize".equals(method)) {
                initializeSeen = true;
                final JsonNode capabilities = message.path("params").path("capabilities");
                if (!capabilities.path("experimentalApi").asBoolean(false)) {
                    writeError(writer, message.path("id").asText(), -32602, "experimentalApi capability required", "{\"field\":\"capabilities.experimentalApi\"}");
                    return;
                }
                final ObjectNode result = mapper.createObjectNode();
                result.put("userAgent", "fake/1.0");
                result.put("codexHome", "/tmp/fake-codex-home");
                result.put("platformFamily", "unix");
                result.put("platformOs", "test");
                writeResult(writer, message.path("id").asText(), result);
                continue;
            }
            if ("initialized".equals(method)) {
                initializedSeen = true;
                continue;
            }
            if ("thread/start".equals(method)) {
                if (!initializeSeen || !initializedSeen) {
                    writeError(writer, message.path("id").asText(), -32002, "Server not initialized", "{\"expected\":\"initialize->initialized->thread/start\"}");
                    return;
                }
                if ("thread_start_error".equals(scenario)) {
                    System.err.println("fake stderr tail: invalid sandbox field");
                    writeError(writer, message.path("id").asText(), -32602, "Invalid params: unknown field sandbox", "{\"field\":\"sandbox\"}");
                    return;
                }
                threadId = "thr_" + scenario;
                final ObjectNode thread = mapper.createObjectNode();
                thread.put("id", threadId);
                final ObjectNode result = mapper.createObjectNode();
                result.set("thread", thread);
                writeResult(writer, message.path("id").asText(), result);
                continue;
            }
            if ("turn/start".equals(method)) {
                final JsonNode params = message.path("params");
                final String receivedThreadId = params.path("threadId").asText();
                if (!threadId.equals(receivedThreadId)) {
                    writeError(writer, message.path("id").asText(), -32602, "Unexpected threadId", "{\"expected\":\"" + threadId + "\"}");
                    return;
                }
                final ArrayNode input = (ArrayNode) params.path("input");
                if (input == null || input.size() != 1 || !"text".equals(input.get(0).path("type").asText())) {
                    writeError(writer, message.path("id").asText(), -32602, "turn/start must contain exactly one text input item", "{\"field\":\"input\"}");
                    return;
                }
                if (!input.get(0).has("text_elements") || !input.get(0).path("text_elements").isArray()) {
                    writeError(writer, message.path("id").asText(), -32602, "turn/start text input must include text_elements array", "{\"field\":\"input[0].text_elements\"}");
                    return;
                }
                final String prompt = input.get(0).path("text").asText();
                final String turnId = "turn_" + scenario;
                pendingTurnId = turnId;
                pendingPrompt = prompt;

                final ObjectNode turn = mapper.createObjectNode();
                turn.put("id", turnId);
                turn.putArray("items");
                turn.put("itemsView", "notLoaded");
                turn.put("status", "inProgress");
                turn.putNull("error");
                turn.putNull("startedAt");
                turn.putNull("completedAt");
                turn.putNull("durationMs");
                final ObjectNode turnResult = mapper.createObjectNode();
                turnResult.set("turn", turn);
                writeResult(writer, message.path("id").asText(), turnResult);

                if ("noisy_events".equals(scenario)) {
                    writeItemCompleted(writer, mapper, "other-thread", "other-turn", "ignored");
                    writeTurnCompleted(writer, mapper, "other-thread", "other-turn");
                }
                if ("progress_events".equals(scenario) || "heartbeat".equals(scenario) || "interrupt".equals(scenario)) {
                    writeTurnStarted(writer, mapper, threadId, turnId);
                    writePlanUpdated(writer, mapper, threadId, turnId);
                    writeCommandStarted(writer, mapper, threadId, turnId, "item_cmd_" + turnId, "rg \"scope\"", System.getProperty("user.dir"));
                    writeCommandOutput(writer, mapper, threadId, turnId, "item_cmd_" + turnId, "matched line");
                    if ("interrupt".equals(scenario)) {
                        continue;
                    }
                    if ("heartbeat".equals(scenario)) {
                        Thread.sleep(600L);
                    }
                    writeCommandCompleted(writer, mapper, threadId, turnId, "item_cmd_" + turnId, "rg \"scope\"", System.getProperty("user.dir"));
                    writeItemCompleted(writer, mapper, threadId, turnId, prompt);
                    writeTurnCompleted(writer, mapper, threadId, turnId);
                    continue;
                }
                writeItemCompleted(writer, mapper, threadId, turnId, prompt);
                writeTurnCompleted(writer, mapper, threadId, turnId);
                return;
            }
            if ("turn/interrupt".equals(method)) {
                writeResult(writer, message.path("id").asText(), mapper.createObjectNode());
                if (pendingTurnId != null) {
                    writeTurnCompleted(writer, mapper, threadId, pendingTurnId, "interrupted");
                }
                return;
            }
        }
    }

    private static void writeResult(final Writer writer, final String id, final JsonNode result) throws Exception {
        final ObjectMapper mapper = new ObjectMapper();
        final ObjectNode response = mapper.createObjectNode();
        response.put("id", id);
        response.set("result", result);
        writer.write(mapper.writeValueAsString(response));
        writer.write('\n');
        writer.flush();
    }

    private static void writeError(final Writer writer,
                                   final String id,
                                   final int code,
                                   final String message,
                                   final String dataJson) throws Exception {
        final ObjectMapper mapper = new ObjectMapper();
        final ObjectNode response = mapper.createObjectNode();
        response.put("id", id);
        final ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        error.set("data", mapper.readTree(dataJson));
        writer.write(mapper.writeValueAsString(response));
        writer.write('\n');
        writer.flush();
    }

    private static void writeItemCompleted(final Writer writer,
                                           final ObjectMapper mapper,
                                           final String threadId,
                                           final String turnId,
                                           final String text) throws Exception {
        final ObjectNode notification = mapper.createObjectNode();
        notification.put("method", "item/completed");
        final ObjectNode params = notification.putObject("params");
        params.put("threadId", threadId);
        params.put("turnId", turnId);
        params.put("completedAtMs", 1L);
        final ObjectNode item = params.putObject("item");
        item.put("type", "agentMessage");
        item.put("id", "msg_" + turnId);
        item.put("text", text);
        item.put("phase", "final_answer");
        item.putNull("memoryCitation");
        writer.write(mapper.writeValueAsString(notification));
        writer.write('\n');
        writer.flush();
    }

    private static void writeTurnStarted(final Writer writer,
                                         final ObjectMapper mapper,
                                         final String threadId,
                                         final String turnId) throws Exception {
        final ObjectNode notification = mapper.createObjectNode();
        notification.put("method", "turn/started");
        final ObjectNode params = notification.putObject("params");
        params.put("threadId", threadId);
        final ObjectNode turn = params.putObject("turn");
        turn.put("id", turnId);
        turn.put("status", "inProgress");
        writer.write(mapper.writeValueAsString(notification));
        writer.write('\n');
        writer.flush();
    }

    private static void writePlanUpdated(final Writer writer,
                                         final ObjectMapper mapper,
                                         final String threadId,
                                         final String turnId) throws Exception {
        final ObjectNode notification = mapper.createObjectNode();
        notification.put("method", "turn/plan/updated");
        final ObjectNode params = notification.putObject("params");
        params.put("threadId", threadId);
        params.put("turnId", turnId);
        params.put("explanation", "Inspect repository and classify scope-owned requirements");
        writer.write(mapper.writeValueAsString(notification));
        writer.write('\n');
        writer.flush();
    }

    private static void writeCommandStarted(final Writer writer,
                                            final ObjectMapper mapper,
                                            final String threadId,
                                            final String turnId,
                                            final String itemId,
                                            final String command,
                                            final String cwd) throws Exception {
        final ObjectNode notification = mapper.createObjectNode();
        notification.put("method", "item/started");
        final ObjectNode params = notification.putObject("params");
        params.put("threadId", threadId);
        params.put("turnId", turnId);
        final ObjectNode item = params.putObject("item");
        item.put("type", "commandExecution");
        item.put("id", itemId);
        item.put("command", command);
        item.put("cwd", cwd);
        item.put("status", "inProgress");
        writer.write(mapper.writeValueAsString(notification));
        writer.write('\n');
        writer.flush();
    }

    private static void writeCommandOutput(final Writer writer,
                                           final ObjectMapper mapper,
                                           final String threadId,
                                           final String turnId,
                                           final String itemId,
                                           final String delta) throws Exception {
        final ObjectNode notification = mapper.createObjectNode();
        notification.put("method", "item/commandExecution/outputDelta");
        final ObjectNode params = notification.putObject("params");
        params.put("threadId", threadId);
        params.put("turnId", turnId);
        params.put("itemId", itemId);
        params.put("delta", delta);
        writer.write(mapper.writeValueAsString(notification));
        writer.write('\n');
        writer.flush();
    }

    private static void writeCommandCompleted(final Writer writer,
                                              final ObjectMapper mapper,
                                              final String threadId,
                                              final String turnId,
                                              final String itemId,
                                              final String command,
                                              final String cwd) throws Exception {
        final ObjectNode notification = mapper.createObjectNode();
        notification.put("method", "item/completed");
        final ObjectNode params = notification.putObject("params");
        params.put("threadId", threadId);
        params.put("turnId", turnId);
        final ObjectNode item = params.putObject("item");
        item.put("type", "commandExecution");
        item.put("id", itemId);
        item.put("command", command);
        item.put("cwd", cwd);
        item.put("status", "completed");
        item.put("durationMs", 25L);
        writer.write(mapper.writeValueAsString(notification));
        writer.write('\n');
        writer.flush();
    }

    private static void writeTurnCompleted(final Writer writer,
                                           final ObjectMapper mapper,
                                           final String threadId,
                                           final String turnId) throws Exception {
        writeTurnCompleted(writer, mapper, threadId, turnId, "completed");
    }

    private static void writeTurnCompleted(final Writer writer,
                                           final ObjectMapper mapper,
                                           final String threadId,
                                           final String turnId,
                                           final String status) throws Exception {
        final ObjectNode notification = mapper.createObjectNode();
        notification.put("method", "turn/completed");
        final ObjectNode params = notification.putObject("params");
        params.put("threadId", threadId);
        final ObjectNode turn = params.putObject("turn");
        turn.put("id", turnId);
        turn.putArray("items");
        turn.put("itemsView", "notLoaded");
        turn.put("status", status);
        turn.putNull("error");
        turn.put("startedAt", 1L);
        turn.put("completedAt", 2L);
        turn.put("durationMs", 1L);
        writer.write(mapper.writeValueAsString(notification));
        writer.write('\n');
        writer.flush();
    }
}
