package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.nio.file.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class LocalCliDockerLogAdapter implements DockerLogPort {
    private final TypedProcessExecutor executor;
    public List<LogTargetCandidate> discover(SshConnection ssh){
        List<String> command=docker(ssh,"ps","-a","--format","{{.ID}}\\t{{.Names}}\\t{{.Status}}\\t{{.Image}}\\t{{.Label \"com.docker.compose.project\"}}\\t{{.Label \"com.docker.compose.service\"}}");
        return executor.output(command,null).stream().filter(s->!s.isBlank()).map(this::candidate).toList();
    }
    public List<LogTargetCandidate> discoverComposeServices(Path repository,SshConnection ssh){
        if(repository==null || ssh!=null) return List.of();
        for(String name:List.of("compose.yaml","compose.yml","docker-compose.yaml","docker-compose.yml")){
            Path file=repository.resolve(name);
            if(Files.isRegularFile(file)) return executor.output(List.of("docker","compose","-f",file.toString(),"config","--services"),repository).stream().filter(s->!s.isBlank()).map(s->new LogTargetCandidate(s,s,LogTargetStatus.AVAILABLE,null,null,s,false)).toList();
        }
        return List.of();
    }
    public void validate(String container,String service,String file,SshConnection ssh){
        if(nonblank(service)) executor.output(compose(ssh,file,"config","--services"),null).stream().filter(service::equals).findFirst().orElseThrow(()->new ValidationException("Compose service is unavailable"));
        else {require(container,"Docker container is required");executor.output(docker(ssh,"container","inspect",container),null);}
    }
    public LogStream stream(String container,String service,String file,int lines,SshConnection ssh){
        int safe=Math.max(1,Math.min(lines,10000));
        if(nonblank(service)) return executor.stream(compose(ssh,file,"logs","--tail",String.valueOf(safe),"--follow","--no-color",service),null);
        require(container,"Docker container is required");return executor.stream(docker(ssh,"logs","--tail",String.valueOf(safe),"--follow",container),null);
    }
    private LogTargetCandidate candidate(String row){String[] p=row.split("\\t",-1);return new LogTargetCandidate(p[0],p.length>1?p[1]:p[0],p.length>2&&p[2].startsWith("Up")?LogTargetStatus.RUNNING:LogTargetStatus.STOPPED,p.length>3?p[3]:null,p.length>4?p[4]:null,p.length>5?p[5]:null,false);}
    private List<String> docker(SshConnection ssh,String...args){List<String> c=base(ssh);c.add("docker");c.addAll(List.of(args));return c;}
    private List<String> compose(SshConnection ssh,String file,String...args){List<String> c=base(ssh);c.add("docker");c.add("compose");if(nonblank(file)){c.add("-f");c.add(file);}c.addAll(List.of(args));return c;}
    private List<String> base(SshConnection s){var c=new ArrayList<String>();if(s!=null){c.addAll(List.of("ssh","-o","BatchMode=yes","-o","ConnectTimeout=10","-p",String.valueOf(s.port()),"-i",s.privateKeyPath(),s.username()+"@"+s.host(),"--"));}return c;}
    private boolean nonblank(String s){return s!=null&&!s.isBlank();} private void require(String s,String m){if(!nonblank(s))throw new ValidationException(m);}
}
