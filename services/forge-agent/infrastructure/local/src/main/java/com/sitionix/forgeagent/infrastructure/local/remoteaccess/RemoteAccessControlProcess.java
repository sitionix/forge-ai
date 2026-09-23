package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/** Bounded dedicated SSH control executor; no retries, shell, inherited stdin, or execution fallback. */
final class RemoteAccessControlProcess {
    private static final Semaphore CAPACITY=new Semaphore(4);
    private RemoteAccessControlProcess() { }

    static String execute(List<String> command,byte[] input,Duration timeout) throws Exception {
        if (input.length>4096 || timeout.isNegative() || timeout.isZero() || !CAPACITY.tryAcquire()) {
            throw new IOException("Control operation capacity exceeded");
        }
        Process process=null;
        ExecutorService io=Executors.newFixedThreadPool(2,r -> {
            var thread=new Thread(r,"remote-access-control-io"); thread.setDaemon(true); return thread;
        });
        long deadline=System.nanoTime()+timeout.toNanos();
        try {
            process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process running=process;
            Future<?> writer=io.submit(() -> {
                try (var stream=running.getOutputStream()) { stream.write(input); }
                catch(IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
            Future<byte[]> reader=io.submit(() -> {
                try (var stream=running.getInputStream()) {
                    byte[] bytes=stream.readNBytes(1025);
                    if(bytes.length>1024) throw new IOException("Control output limit exceeded");
                    return bytes;
                }
            });
            writer.get(remaining(deadline),TimeUnit.NANOSECONDS);
            byte[] output=reader.get(remaining(deadline),TimeUnit.NANOSECONDS);
            if(!process.waitFor(remaining(deadline),TimeUnit.NANOSECONDS) || process.exitValue()!=0) throw new IOException("Control process failed");
            for(byte value:output) if(value<0) throw new IOException("Invalid control response");
            return new String(output,StandardCharsets.US_ASCII);
        } finally {
            if(process!=null) {
                process.destroyForcibly();
                try { process.getOutputStream().close(); } catch(IOException ignored) { }
                try { process.getInputStream().close(); } catch(IOException ignored) { }
                try { process.getErrorStream().close(); } catch(IOException ignored) { }
            }
            io.shutdownNow();
            CAPACITY.release();
        }
    }
    private static long remaining(long deadline) throws TimeoutException {
        long remaining=deadline-System.nanoTime();
        if(remaining<=0) throw new TimeoutException("Control operation timed out");
        return remaining;
    }
}
