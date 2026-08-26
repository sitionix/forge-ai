package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.model.LogStreamResult;
import com.sitionix.forgeagent.domain.port.LogStream;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

final class ProcessLogStream implements LogStream {
  private final Process process;
  private final BufferedReader reader;
  private final PipedWriter mergedOutput;
  private final StringBuilder errorOutput = new StringBuilder();
  private final Thread stdoutReader;
  private final Thread stderrReader;
  private final AtomicInteger activeReaders = new AtomicInteger(2);
  private final AtomicBoolean closed = new AtomicBoolean();

  ProcessLogStream(final Process process) {
    this.process = process;
    try {
      this.mergedOutput = new PipedWriter();
      this.reader = new BufferedReader(new PipedReader(this.mergedOutput, 64 * 1024));
    } catch (final IOException exception) {
      process.destroyForcibly();
      throw new UncheckedIOException(exception);
    }
    this.stdoutReader = this.pump(process.getInputStream(), false, "forge-log-stdout");
    this.stderrReader = this.pump(process.getErrorStream(), true, "forge-log-stderr");
  }

  public BufferedReader reader() {
    return reader;
  }

  public boolean isAlive() {
    return process.isAlive();
  }

  public LogStreamResult awaitCompletion() {
    try {
      final int exitCode = process.waitFor();
      stdoutReader.join();
      stderrReader.join();
      return new LogStreamResult(exitCode, errorOutput.toString());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return new LogStreamResult(-1, "Log stream interrupted");
    }
  }

  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    try {
      reader.close();
    } catch (IOException ignored) {
    }
    try {
      mergedOutput.close();
    } catch (IOException ignored) {
    }
    process.destroy();
    try {
      if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
    stdoutReader.interrupt();
    stderrReader.interrupt();
  }

  private Thread pump(final InputStream input, final boolean error, final String name) {
    return Thread.ofVirtual()
        .name(name)
        .start(
            () -> {
              try (var lines =
                  new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                  if (error && errorOutput.length() < 16_384) {
                    if (!errorOutput.isEmpty()) errorOutput.append('\n');
                    errorOutput.append(line);
                  }
                  synchronized (mergedOutput) {
                    mergedOutput.write(line);
                    mergedOutput.write('\n');
                    mergedOutput.flush();
                  }
                }
              } catch (IOException ignored) {
              } finally {
                if (activeReaders.decrementAndGet() == 0)
                  try {
                    mergedOutput.close();
                  } catch (IOException ignored) {
                  }
              }
            });
  }
}
