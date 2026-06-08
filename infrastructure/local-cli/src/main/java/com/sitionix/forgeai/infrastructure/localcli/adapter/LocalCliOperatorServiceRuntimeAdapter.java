package com.sitionix.forgeai.infrastructure.localcli.adapter;

import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceRuntimeState;
import com.sitionix.forgeai.domain.port.OperatorServiceRuntimePort;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class LocalCliOperatorServiceRuntimeAdapter implements OperatorServiceRuntimePort {

    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HEALTH_TIMEOUT)
            .build();

    @Override
    public OperatorServiceRuntimeState healthcheck(final String healthcheckUrl) {
        if (!this.hasText(healthcheckUrl)) {
            return new OperatorServiceRuntimeState("DOWN", null, "Healthcheck URL is not configured.");
        }
        return this.health(healthcheckUrl);
    }

    @Override
    public OperatorServiceRuntimeState container(final String expectedName) {
        if (!this.hasText(expectedName)) {
            return new OperatorServiceRuntimeState("NOT_CONFIGURED", null, "Runtime target is not configured.");
        }
        final CommandResult result = this.run("docker", "ps", "--all", "--format", "{{.Names}}\t{{.Status}}");
        if (!result.success()) {
            return new OperatorServiceRuntimeState("UNKNOWN", null, result.stderr());
        }
        OperatorServiceRuntimeState partialMatch = null;
        for (String line : result.stdout().split("\\R")) {
            final String[] parts = line.split("\\t", 2);
            if (parts.length == 0 || !this.hasText(parts[0])) {
                continue;
            }
            final OperatorServiceRuntimeState state = this.state(parts[0], parts.length > 1 ? parts[1] : "");
            if (parts[0].equals(expectedName)) {
                return state;
            }
            if (partialMatch == null && parts[0].contains(expectedName)) {
                partialMatch = state;
            }
        }
        return partialMatch == null
                ? new OperatorServiceRuntimeState("DOWN", expectedName, "Container is not present locally.")
                : partialMatch;
    }

    private OperatorServiceRuntimeState health(final String healthcheckUrl) {
        try {
            final HttpRequest request = HttpRequest.newBuilder(URI.create(healthcheckUrl))
                    .timeout(HEALTH_TIMEOUT)
                    .GET()
                    .build();
            final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            final boolean up = response.statusCode() >= 200 && response.statusCode() < 300;
            return new OperatorServiceRuntimeState(
                    up ? "UP" : "DOWN",
                    healthcheckUrl,
                    "HTTP " + response.statusCode()
            );
        } catch (IllegalArgumentException exception) {
            return new OperatorServiceRuntimeState(
                    "UNKNOWN",
                    healthcheckUrl,
                    "Invalid healthcheck URL: " + exception.getMessage()
            );
        } catch (IOException exception) {
            return new OperatorServiceRuntimeState("DOWN", healthcheckUrl, exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new OperatorServiceRuntimeState("UNKNOWN", healthcheckUrl, exception.getMessage());
        }
    }

    private OperatorServiceRuntimeState state(final String containerName, final String dockerStatus) {
        final String status = dockerStatus.startsWith("Up") ? "UP" : "DOWN";
        return new OperatorServiceRuntimeState(status, containerName, dockerStatus);
    }

    private CommandResult run(final String... command) {
        try {
            final Process process = new ProcessBuilder(command).start();
            final String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            final String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            final int exitCode = process.waitFor();
            return new CommandResult(exitCode, stdout, stderr);
        } catch (IOException exception) {
            return new CommandResult(-1, "", exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "", exception.getMessage());
        }
    }

    private boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {

        private boolean success() {
            return this.exitCode == 0;
        }
    }
}
