package com.sitionix.forgeagent.domain.port;

import java.io.BufferedReader;

public interface LogStream extends AutoCloseable {
    BufferedReader reader();
    boolean isAlive();
    @Override void close();
}
