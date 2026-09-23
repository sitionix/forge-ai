package com.sitionix.forgeagent.domain.port;
import java.io.InputStream;
import java.io.OutputStream;
/** Consume stdout and stderr concurrently; streams apply bounded OS backpressure. Close cancels. */
public interface RemoteAccessCommandExecution extends AutoCloseable {
    OutputStream stdin();
    InputStream stdout();
    InputStream stderr();
    int await() throws InterruptedException;
    @Override void close();
}
