package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.LogStreamResult;
import java.io.BufferedReader;

public interface LogStream extends AutoCloseable {
  BufferedReader reader();

  boolean isAlive();

  LogStreamResult awaitCompletion();

  @Override
  void close();
}
