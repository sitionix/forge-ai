package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.ServiceRuntimeInspectionPort;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class CliServiceRuntimeInspectionAdapter implements ServiceRuntimeInspectionPort {
  private final TypedProcessExecutor executor;
  public ServiceRuntimeView inspect(ProjectService service, SshConnection ssh) {
    try { return service.runtimeTarget().provider()==ServiceRuntimeProvider.DOCKER ? docker(service.runtimeTarget(),ssh) : systemd(service.runtimeTarget(),ssh); }
    catch (InfrastructureExecutionException | DateTimeParseException e) { return unknown(service.runtimeTarget(), Map.of("inspectionError", e.getMessage())); }
  }
  private ServiceRuntimeView docker(ServiceRuntimeTarget t,SshConnection ssh) {
    var args=List.of("docker","inspect","--format","{{.State.Status}}|{{.State.StartedAt}}|{{.State.ExitCode}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}|{{.Name}}|{{.Config.Image}}","--",RuntimeTargetValidator.docker(t.container(),"Docker container"));
    var line=output(args,ssh).stream().findFirst().orElseThrow(()->new InfrastructureExecutionException("RUNTIME_EMPTY","Docker returned no state"));
    var p=line.split("\\|",-1); var status=switch(p[0]){case "running"->ServiceRuntimeStatus.RUNNING;case "exited","created","paused"->ServiceRuntimeStatus.STOPPED;case "dead","removing","restarting"->ServiceRuntimeStatus.FAILED;default->ServiceRuntimeStatus.UNKNOWN;};
    Instant started=parse(p.length>1?p[1]:null); var metadata=new LinkedHashMap<String,String>(); if(p.length>2)metadata.put("exitCode",p[2]);if(p.length>4)metadata.put("containerName",p[4]);if(p.length>5)metadata.put("image",p[5]);
    return view(t,status,started,metadata,p.length>3&&!p[3].isBlank()?p[3]:null);
  }
  private ServiceRuntimeView systemd(ServiceRuntimeTarget t,SshConnection ssh) {
    var args=List.of("systemctl","show","--no-pager","--property=ActiveState,SubState,ExecMainStartTimestampMonotonic,MainPID,ExecMainStatus,Result","--",RuntimeTargetValidator.unit(t.unit()));
    var values=new LinkedHashMap<String,String>(); for(String line:output(args,ssh)){int i=line.indexOf('=');if(i>0)values.put(line.substring(0,i),line.substring(i+1));}
    String active=values.get("ActiveState"); var status=switch(active==null?"":active){case "active","activating","reloading"->ServiceRuntimeStatus.RUNNING;case "inactive","deactivating"->ServiceRuntimeStatus.STOPPED;case "failed"->ServiceRuntimeStatus.FAILED;default->ServiceRuntimeStatus.UNKNOWN;};
    var metadata=new LinkedHashMap<>(values); metadata.remove("ActiveState"); return view(t,status,null,metadata,null);
  }
  private List<String> output(List<String> args,SshConnection ssh){return executor.output(ssh==null?args:RemoteShellCommand.ssh(ssh,args),null,ssh);}
  private ServiceRuntimeView view(ServiceRuntimeTarget t,ServiceRuntimeStatus s,Instant started,Map<String,String> metadata,String health){return new ServiceRuntimeView(s,t.provider(),t.connection(),t.identity(),started,started==null?null:Duration.between(started,Instant.now()),Map.copyOf(metadata),health);}
  private ServiceRuntimeView unknown(ServiceRuntimeTarget t,Map<String,String> metadata){return new ServiceRuntimeView(ServiceRuntimeStatus.UNKNOWN,t.provider(),t.connection(),t.identity(),null,null,metadata,null);}
  private Instant parse(String value){if(value==null||value.isBlank()||value.startsWith("0001-"))return null;return Instant.parse(value);}
}
