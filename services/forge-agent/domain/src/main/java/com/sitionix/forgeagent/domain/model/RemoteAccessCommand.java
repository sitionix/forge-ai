package com.sitionix.forgeagent.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.List;

public record RemoteAccessCommand(List<String> argv,String cwd,int timeoutSeconds) {
    public RemoteAccessCommand {
        argv=List.copyOf(argv);
        if (argv.isEmpty() || argv.size()>128 || !argv.getFirst().startsWith("/")
                || argv.stream().anyMatch(v -> v.indexOf('\0')>=0 || v.length()>4096)
                || argv.stream().mapToInt(v -> v.getBytes(StandardCharsets.UTF_8).length).sum()>8192
                || cwd==null || !cwd.startsWith("/") || cwd.length()>1024 || cwd.chars().anyMatch(Character::isISOControl)
                || List.of(cwd.split("/")).contains("..") || timeoutSeconds<1 || timeoutSeconds>86400) {
            throw new IllegalArgumentException("Invalid remote command");
        }
    }
    @Override public String toString() { return "RemoteAccessCommand[arguments redacted]"; }
}
