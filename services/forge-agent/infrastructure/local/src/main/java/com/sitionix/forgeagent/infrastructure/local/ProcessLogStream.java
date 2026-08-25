package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.port.LogStream;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class ProcessLogStream implements LogStream {
    private final Process process;
    private final BufferedReader reader;
    ProcessLogStream(Process process) { this.process=process; this.reader=new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)); }
    public BufferedReader reader(){return reader;}
    public boolean isAlive(){return process.isAlive();}
    public void close(){
        try { reader.close(); } catch(IOException ignored) { }
        process.destroy();
        try { if(!process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) process.destroyForcibly(); }
        catch(InterruptedException e){Thread.currentThread().interrupt();process.destroyForcibly();}
    }
}
