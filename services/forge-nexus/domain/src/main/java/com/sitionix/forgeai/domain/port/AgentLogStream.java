package com.sitionix.forgeai.domain.port;

import java.nio.ByteBuffer;

public interface AgentLogStream extends AutoCloseable {
  int read(ByteBuffer buffer);

  @Override
  void close();
}
