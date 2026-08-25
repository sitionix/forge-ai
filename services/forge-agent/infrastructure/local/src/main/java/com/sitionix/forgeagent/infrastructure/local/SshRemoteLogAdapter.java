package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class SshRemoteLogAdapter implements RemoteLogPort {
    private final TypedProcessExecutor executor;
    public List<LogTargetCandidate> discover(SshConnection c,LogProviderType provider){
        if(provider!=LogProviderType.SYSTEMD) return List.of();
        return executor.output(command(c,"systemctl","list-units","--type=service","--all","--no-legend","--plain"),null).stream().map(String::strip).filter(s->!s.isBlank()).map(s->s.split("\\s+",2)[0]).map(u->new LogTargetCandidate(u,u,LogTargetStatus.AVAILABLE,null,null,null,false)).toList();
    }
    public void validate(SshConnection c,LogProviderType p,LogProviderConfiguration cfg){executor.output(validation(c,p,cfg),null);}
    public LogStream stream(SshConnection c,LogProviderType p,LogProviderConfiguration cfg,int lines){
        int safe=Math.max(1,Math.min(lines,10000));
        return switch(p){case SYSTEMD->{String unit=((SystemdLogConfiguration)cfg).unit();require(unit);yield executor.stream(command(c,"journalctl","--unit",unit,"--lines",String.valueOf(safe),"--follow","--output","short-iso"),null);}case FILE->{String path=((FileLogConfiguration)cfg).path();require(path);yield executor.stream(command(c,"tail","--lines",String.valueOf(safe),"--follow=name","--",path),null);}default->throw new ValidationException("Unsupported remote provider");};
    }
    private List<String> validation(SshConnection c,LogProviderType p,LogProviderConfiguration cfg){return switch(p){case SYSTEMD->{String u=((SystemdLogConfiguration)cfg).unit();require(u);yield command(c,"systemctl","status","--",u);}case FILE->{String f=((FileLogConfiguration)cfg).path();require(f);yield command(c,"test","-r",f);}default->throw new ValidationException("Unsupported remote provider");};}
    private List<String> command(SshConnection s,String... remote){if(s==null)throw new ValidationException("SSH connection is required");var c=new ArrayList<>(List.of("ssh","-o","BatchMode=yes","-o","ConnectTimeout=10","-p",String.valueOf(s.port()),"-i",s.privateKeyPath(),s.username()+"@"+s.host(),"--"));c.addAll(List.of(remote));return c;}
    private void require(String value){if(value==null||value.isBlank())throw new ValidationException("Provider target is required");}
}
