package com.sitionix.forgeai.application.operator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ShellTicketOperatorTerminalLauncher implements TicketOperatorTerminalLauncher {

    private static final Logger log = Logger.getLogger(ShellTicketOperatorTerminalLauncher.class.getName());

    private final TicketOperatorTerminalProperties properties;

    @Override
    public boolean openTicketTerminal(final UUID ticketId,
                                      final String ticketKey,
                                      final String baseUrl,
                                      final String watcherId,
                                      final String verbosity) {
        if (!this.properties.isEnabled() || "none".equalsIgnoreCase(this.properties.getLauncher())) {
            return false;
        }
        final Path repoRoot = Path.of("").toAbsolutePath().normalize();
        final Path script = repoRoot.resolve("scripts/forge-ai-open-ticket-terminal.sh");
        if (!Files.isRegularFile(script)) {
            log.warning("Ticket terminal opener script not found: " + script);
            return false;
        }
        final ProcessBuilder processBuilder = new ProcessBuilder(
                "bash",
                script.toString(),
                ticketId.toString(),
                baseUrl,
                watcherId,
                verbosity,
                ticketKey == null || ticketKey.isBlank() ? ticketId.toString() : ticketKey,
                this.properties.getLauncher()
        );
        processBuilder.directory(repoRoot.toFile());
        processBuilder.redirectErrorStream(true);
        try {
            final Process process = processBuilder.start();
            final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            final int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warning("Ticket terminal opener failed for ticketId=" + ticketId
                        + " exitCode=" + exitCode
                        + (output.isBlank() ? "" : " output=\"" + output.replace('"', '\'') + "\""));
                return false;
            }
            return true;
        } catch (final IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.log(Level.WARNING, "Ticket terminal opener failed for ticketId=" + ticketId, ex);
            return false;
        }
    }
}
