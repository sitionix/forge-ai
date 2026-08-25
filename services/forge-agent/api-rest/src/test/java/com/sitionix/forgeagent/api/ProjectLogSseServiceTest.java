package com.sitionix.forgeagent.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sitionix.forgeagent.application.usecase.LogSourceUseCases;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.LogStream;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ProjectLogSseServiceTest {
  @Test
  void allSourcesCompleteAndCloseAfterIndependentFailure() throws Exception {
    var logs = mock(LogSourceUseCases.class);
    UUID project = UUID.randomUUID();
    var one = source(project, "one");
    var two = source(project, "two");
    var failed = new FakeStream("first\n", new LogStreamResult(7, "denied"));
    var healthy = new FakeStream("second\n", new LogStreamResult(0, ""));
    when(logs.requireEnabled(project, List.of(one.id(), two.id()))).thenReturn(List.of(one, two));
    when(logs.stream(project, one, 10)).thenReturn(failed);
    when(logs.stream(project, two, 10)).thenReturn(healthy);
    var emitter = new RecordingSseEmitter();
    new ProjectLogSseService(logs, ignored -> emitter)
        .stream(project, List.of(one.id(), two.id()), 10);
    awaitClosed(failed, healthy);
    assertThat(emitter.completed).isTrue();
    assertThat(emitter.events).hasSize(4);
  }

  @Test
  void startupFailureCleansAlreadyOpenedStreams() {
    var logs = mock(LogSourceUseCases.class);
    UUID project = UUID.randomUUID();
    var one = source(project, "one");
    var two = source(project, "two");
    var opened = new FakeStream("", new LogStreamResult(0, ""));
    when(logs.requireEnabled(project, List.of(one.id(), two.id()))).thenReturn(List.of(one, two));
    when(logs.stream(project, one, 10)).thenReturn(opened);
    when(logs.stream(project, two, 10)).thenThrow(new IllegalStateException("unavailable"));
    assertThatThrownBy(
            () ->
                new ProjectLogSseService(logs, ignored -> new RecordingSseEmitter())
                    .stream(project, List.of(one.id(), two.id()), 10))
        .isInstanceOf(IllegalStateException.class);
    assertThat(opened.closed).isTrue();
  }

  private LogSource source(UUID project, String name) {
    return new LogSource(
        UUID.randomUUID(),
        project,
        name,
        null,
        LogConnectionType.LOCAL,
        null,
        LogProviderType.DOCKER,
        new DockerLogConfiguration(name, null, null),
        true,
        Instant.EPOCH,
        Instant.EPOCH);
  }

  private void awaitClosed(FakeStream... streams) throws Exception {
    for (int i = 0; i < 100 && Arrays.stream(streams).anyMatch(s -> !s.closed.get()); i++)
      TimeUnit.MILLISECONDS.sleep(10);
    assertThat(streams).allMatch(s -> s.closed.get());
  }

  private static final class RecordingSseEmitter extends SseEmitter {
    final List<SseEventBuilder> events = new CopyOnWriteArrayList<>();
    volatile boolean completed;

    @Override
    public void send(SseEventBuilder builder) {
      events.add(builder);
    }

    @Override
    public void complete() {
      completed = true;
    }
  }

  private static final class FakeStream implements LogStream {
    final BufferedReader reader;
    final LogStreamResult result;
    final AtomicBoolean closed = new AtomicBoolean();

    FakeStream(String lines, LogStreamResult result) {
      reader = new BufferedReader(new StringReader(lines));
      this.result = result;
    }

    public BufferedReader reader() {
      return reader;
    }

    public boolean isAlive() {
      return !closed.get();
    }

    public LogStreamResult awaitCompletion() {
      return result;
    }

    public void close() {
      closed.set(true);
    }
  }
}
