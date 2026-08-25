package com.sitionix.forgeagent.api;

import com.sitionix.forgeagent.application.usecase.LogSourceUseCases;
import com.sitionix.forgeagent.domain.model.LogEvent;
import com.sitionix.forgeagent.domain.model.LogSource;
import com.sitionix.forgeagent.domain.port.LogStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ProjectLogSseService {
  private final LogSourceUseCases logs;
  private final LongFunction<SseEmitter> emitterFactory;

  @Autowired
  public ProjectLogSseService(final LogSourceUseCases logs) {
    this(logs, SseEmitter::new);
  }

  ProjectLogSseService(
      final LogSourceUseCases logs, final LongFunction<SseEmitter> emitterFactory) {
    this.logs = logs;
    this.emitterFactory = emitterFactory;
  }

  public SseEmitter stream(final UUID projectId, final List<UUID> sourceIds, final int lines) {
    final List<LogSource> sources = this.logs.requireEnabled(projectId, sourceIds);
    final var session =
        new Session(
            this.emitterFactory.apply(0L),
            Executors.newVirtualThreadPerTaskExecutor(),
            sources.size());
    session.bindCleanup();
    try {
      for (final LogSource source : sources) {
        final LogStream stream = this.logs.stream(projectId, source, lines);
        session.streams.add(stream);
        session.tasks.add(session.executor.submit(() -> this.pump(session, source, stream)));
      }
      return session.emitter;
    } catch (final RuntimeException exception) {
      session.cleanup();
      throw exception;
    }
  }

  private void pump(final Session session, final LogSource source, final LogStream stream) {
    try (stream) {
      while (!session.closed.get()) {
        final String line;
        try {
          line = stream.reader().readLine();
        } catch (final IOException exception) {
          this.sourceError(session, source, "Log source failed");
          return;
        }
        if (line == null) {
          break;
        }
        try {
          session.send(
              SseEmitter.event()
                  .name("log")
                  .data(new LogEvent(source.id(), source.name(), Instant.now(), line)));
        } catch (final IOException exception) {
          session.cleanup();
          return;
        }
      }
      final var result = stream.awaitCompletion();
      if (!result.successful() && !session.closed.get()) {
        this.sourceError(
            session, source, "Log provider exited with code " + result.exitCode());
      }
    } catch (final RuntimeException exception) {
      if (!session.closed.get()) {
        this.sourceError(session, source, "Log source failed");
      }
    } finally {
      if (session.remaining.decrementAndGet() == 0) session.finish();
    }
  }

  private void sourceError(
      final Session session, final LogSource source, final String message) {
    try {
      session.send(
          SseEmitter.event()
              .name("source-error")
              .data(
                  Map.of(
                      "sourceId", source.id(),
                      "sourceName", source.name(),
                      "message", message)));
    } catch (final IOException exception) {
      session.cleanup();
    }
  }

  private static final class Session {
    private final SseEmitter emitter;
    private final ExecutorService executor;
    private final AtomicInteger remaining;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<LogStream> streams = new CopyOnWriteArrayList<>();
    private final List<Future<?>> tasks = new CopyOnWriteArrayList<>();

    private Session(
        final SseEmitter emitter, final ExecutorService executor, final int sourceCount) {
      this.emitter = emitter;
      this.executor = executor;
      this.remaining = new AtomicInteger(sourceCount);
    }

    private void bindCleanup() {
      this.emitter.onCompletion(this::cleanup);
      this.emitter.onTimeout(this::cleanup);
      this.emitter.onError(ignored -> this.cleanup());
    }

    private synchronized void send(final SseEmitter.SseEventBuilder event) throws IOException {
      if (!this.closed.get()) this.emitter.send(event);
    }

    private void cleanup() {
      if (this.closed.compareAndSet(false, true)) this.closeResources();
    }

    private synchronized void finish() {
      if (this.closed.get()) return;
      try {
        this.emitter.send(
            SseEmitter.event().name("stream-complete").data(Map.of("terminal", true)));
        this.closed.set(true);
        this.emitter.complete();
        this.closeResources();
      } catch (final IOException exception) {
        this.cleanup();
      }
    }

    private void closeResources() {
      new ArrayList<>(this.streams).forEach(LogStream::close);
      new ArrayList<>(this.tasks).forEach(task -> task.cancel(true));
      this.executor.shutdownNow();
    }
  }
}
