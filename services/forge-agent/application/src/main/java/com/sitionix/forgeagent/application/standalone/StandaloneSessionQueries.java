package com.sitionix.forgeagent.application.standalone;
import com.sitionix.forgeagent.domain.exception.*;
import com.sitionix.forgeagent.domain.model.standalone.*;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.standalone.StandaloneSessionRepository;
import java.util.*;
import org.springframework.stereotype.Service;
@Service
public class StandaloneSessionQueries {
 private final StandaloneSessionRepository sessions;private final ProjectRepository projects;
 public StandaloneSessionQueries(StandaloneSessionRepository sessions,ProjectRepository projects){this.sessions=sessions;this.projects=projects;}
 public StandaloneAgentSession get(UUID project,UUID id){project(project);return sessions.find(project,id).orElseThrow(()->new NotFoundException("AGENT_SESSION_NOT_FOUND","Session was not found."));}
 public List<StandaloneAgentSession> list(UUID project,String search,String before,int limit){project(project);limit(limit,100);if(search==null||search.length()>200||before!=null&&before.length()>128)throw new ValidationException("INVALID_SESSION_QUERY","Invalid session search or cursor.");return sessions.list(project,search,before,limit);}
 public List<StandaloneAgentMessage> messages(UUID project,UUID id,long after,int limit){get(project,id);cursor(after,limit);return sessions.messages(project,id,after,limit);}
 public List<StandaloneAgentEvent> events(UUID project,UUID id,long after,int limit){get(project,id);cursor(after,limit);return sessions.events(project,id,after,limit);}
 private void project(UUID id){if(projects.findById(id).isEmpty())throw new NotFoundException("PROJECT_NOT_FOUND","Project was not found.");}
 private void cursor(long after,int limit){if(after<0)throw new ValidationException("INVALID_SESSION_CURSOR","Invalid session cursor.");limit(limit,200);}
 private void limit(int value,int max){if(value<1||value>max)throw new ValidationException("INVALID_SESSION_LIMIT","Invalid session page size.");}
}
