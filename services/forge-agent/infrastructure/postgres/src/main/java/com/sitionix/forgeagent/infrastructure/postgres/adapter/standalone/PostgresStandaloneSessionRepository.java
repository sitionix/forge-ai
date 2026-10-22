package com.sitionix.forgeagent.infrastructure.postgres.adapter.standalone;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.exception.*;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventCandidate;
import com.sitionix.forgeagent.domain.model.execution.EffectiveAgentDefinition;
import com.sitionix.forgeagent.domain.model.standalone.*;
import com.sitionix.forgeagent.domain.port.standalone.StandaloneSessionRepository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Standalone storage owns its own strict relationships; Workflow tables are never involved. */
@Repository
public class PostgresStandaloneSessionRepository implements StandaloneSessionRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final DataSource dataSource;
    public PostgresStandaloneSessionRepository(JdbcTemplate jdbc,ObjectMapper json,DataSource dataSource) { this.jdbc=jdbc;this.json=json;this.dataSource=dataSource; }
    private static final String FENCE="t.id=? AND t.session_id=? AND t.lease_owner=? AND t.lease_token=? AND t.status='RUNNING' AND t.lease_expires_at>clock_timestamp() AND t.deadline>clock_timestamp()";
    private Object[] fence(StandaloneExecutionClaim c) { return new Object[]{c.turnId(),c.session().id(),c.owner(),c.token()}; }
    private static ConflictException stale() { return new ConflictException("STALE_SESSION_LEASE","Session execution ownership was lost."); }
    private String encode(Object v) { try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException("Snapshot serialization failed",e);} }
    private <T>T decode(String s,Class<T> type) { try{return json.readValue(s,type);}catch(Exception e){throw new IllegalStateException("Stored session snapshot is incompatible",e);} }
    private StandaloneAgentSession session(ResultSet r,int row)throws SQLException {
        final List<UUID> ids;try{ids=json.readValue(r.getString("repository_ids"),new TypeReference<List<UUID>>(){});}catch(Exception e){throw new IllegalStateException("Invalid repository snapshot",e);}
        return new StandaloneAgentSession(r.getObject("id",UUID.class),r.getObject("project_id",UUID.class),decode(r.getString("definition_snapshot"),EffectiveAgentDefinition.class),ids,
                r.getString("workspace_fingerprint"),r.getString("provider_conversation_id"),r.getString("provider_version"),r.getString("status"),r.getLong("revision"),
                r.getObject("active_turn_id",UUID.class),r.getString("failure_code"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),r.getTimestamp("last_activity_at").toInstant());
    }
    @Override public Optional<StandaloneAgentSession> find(UUID project,UUID id) { return jdbc.query("SELECT * FROM standalone_agent_sessions WHERE project_id=? AND id=?",this::session,project,id).stream().findFirst(); }
    private StandaloneAgentSession lock(UUID project,UUID id) {
        return jdbc.query("SELECT * FROM standalone_agent_sessions WHERE project_id=? AND id=? FOR UPDATE",this::session,project,id).stream().findFirst()
                .orElseThrow(()->new NotFoundException("AGENT_SESSION_NOT_FOUND","Session was not found."));
    }
    @Override public Optional<StandaloneCommandReceipt> creationReceipt(UUID project,UUID request,String hash) {
        return jdbc.query("SELECT s.id,s.create_request_hash,s.revision,t.id turn_id FROM standalone_agent_sessions s JOIN standalone_agent_turns t ON t.session_id=s.id AND t.request_id=s.create_request_id WHERE s.project_id=? AND s.create_request_id=?",(r,n)->{
            if(!hash.equals(r.getString("create_request_hash")))throw new ConflictException("IDEMPOTENCY_CONFLICT","Request identity was already used for different input.");
            return new StandaloneCommandReceipt(r.getObject("id",UUID.class),r.getObject("turn_id",UUID.class),r.getLong("revision"),false);
        },project,request).stream().findFirst();
    }
    @Override @Transactional public StandaloneCommandReceipt create(StandaloneAgentSession s,UUID request,String hash,String text) {
        // Serialize idempotent create even when there is no session row to lock yet.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"standalone-create:"+s.projectId()+":"+request);
        var old=jdbc.query("SELECT * FROM standalone_agent_sessions WHERE project_id=? AND create_request_id=?",this::session,s.projectId(),request);
        if(!old.isEmpty()) {
            var prior=old.getFirst();String saved=jdbc.queryForObject("SELECT create_request_hash FROM standalone_agent_sessions WHERE id=?",String.class,prior.id());
            if(!hash.equals(saved))throw new ConflictException("IDEMPOTENCY_CONFLICT","Request identity was already used for different input.");
            UUID turn=jdbc.queryForObject("SELECT id FROM standalone_agent_turns WHERE session_id=? AND request_id=?",UUID.class,prior.id(),request);
            return new StandaloneCommandReceipt(prior.id(),turn,prior.revision(),false);
        }
        jdbc.update("INSERT INTO standalone_agent_sessions(id,project_id,create_request_id,create_request_hash,definition_snapshot,repository_ids,workspace_fingerprint,provider_id,status) VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?,'CREATED')",
                s.id(),s.projectId(),request,hash,encode(s.definition()),encode(s.repositoryIds()),s.workspaceFingerprint(),s.definition().model().providerId());
        return insertTurn(s.id(),request,text);
    }
    private StandaloneCommandReceipt insertTurn(UUID session,UUID request,String text) {
        UUID turn=UUID.randomUUID();jdbc.update("INSERT INTO standalone_agent_turns(id,session_id,request_id,request_text,status) VALUES (?,?,?,?,'QUEUED')",turn,session,request,text);
        Long seq=jdbc.queryForObject("UPDATE standalone_agent_sessions SET active_turn_id=?,status='RUNNING',revision=revision+1,last_message_sequence=last_message_sequence+1,updated_at=clock_timestamp(),last_activity_at=clock_timestamp(),failure_code=NULL WHERE id=? RETURNING last_message_sequence",Long.class,turn,session);
        jdbc.update("INSERT INTO standalone_agent_messages(id,session_id,turn_id,sequence,role,text) VALUES (?,?,?,?,'USER',?)",UUID.randomUUID(),session,turn,seq,text);
        long revision=jdbc.queryForObject("SELECT revision FROM standalone_agent_sessions WHERE id=?",Long.class,session);
        return new StandaloneCommandReceipt(session,turn,revision,true);
    }
    @Override @Transactional public StandaloneCommandReceipt send(UUID project,UUID id,UUID request,long expected,String text) {
        var s=lock(project,id);
        var previous=jdbc.query("SELECT id,request_text FROM standalone_agent_turns WHERE session_id=? AND request_id=?",(r,n)->Map.entry(r.getObject("id",UUID.class),r.getString("request_text")),id,request);
        if(!previous.isEmpty()) {
            if(!text.equals(previous.getFirst().getValue()))throw new ConflictException("IDEMPOTENCY_CONFLICT","Request identity was already used for different input.");
            return new StandaloneCommandReceipt(id,previous.getFirst().getKey(),s.revision(),false);
        }
        StandaloneSessionPolicy.send(s.status(),s.activeTurnId()!=null,s.revision(),expected);
        return insertTurn(id,request,text);
    }
    @Override public List<StandaloneAgentSession> list(UUID project,String search,String before,int limit) {
        Instant time=Instant.parse("9999-01-01T00:00:00Z");UUID id=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        if(before!=null&&!before.isBlank())try { var p=before.split("\\|",-1);time=Instant.parse(p[0]);id=UUID.fromString(p[1]); }catch(RuntimeException e){throw new ValidationException("INVALID_SESSION_CURSOR","Invalid session cursor.");}
        return jdbc.query("SELECT * FROM standalone_agent_sessions WHERE project_id=? AND strpos(lower(definition_snapshot->>'name'),lower(?))>0 AND (last_activity_at,id)<(?,?) ORDER BY last_activity_at DESC,id DESC LIMIT ?",this::session,project,search,Timestamp.from(time),id,limit);
    }
    @Override public List<StandaloneAgentMessage> messages(UUID project,UUID id,long after,int limit) {
        return jdbc.query("SELECT m.* FROM standalone_agent_messages m JOIN standalone_agent_sessions s ON s.id=m.session_id WHERE s.project_id=? AND s.id=? AND m.sequence>? ORDER BY m.sequence LIMIT ?",(r,n)->new StandaloneAgentMessage(r.getObject("id",UUID.class),id,r.getObject("turn_id",UUID.class),r.getLong("sequence"),r.getString("role"),r.getString("text"),r.getTimestamp("created_at").toInstant()),project,id,after,limit);
    }
    @Override public List<StandaloneAgentEvent> events(UUID project,UUID id,long after,int limit) {
        return jdbc.query("SELECT e.* FROM standalone_agent_events e JOIN standalone_agent_sessions s ON s.id=e.session_id WHERE s.project_id=? AND s.id=? AND e.sequence>? ORDER BY e.sequence LIMIT ?",(r,n)->new StandaloneAgentEvent(id,r.getObject("turn_id",UUID.class),r.getLong("sequence"),r.getString("type"),r.getString("status"),r.getString("phase"),r.getString("payload"),r.getTimestamp("occurred_at").toInstant()),project,id,after,limit);
    }
    private void dispatchLock(UUID session) { jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"standalone-dispatch:"+session); }
    @Override @Transactional public StandaloneCommandReceipt cancel(UUID project,UUID id,UUID request,long expected) {
        dispatchLock(id);var s=lock(project,id);
        var saved=jdbc.query("SELECT * FROM standalone_agent_cancel_requests WHERE session_id=? AND request_id=?",(r,n)->new StandaloneCommandReceipt(id,r.getObject("turn_id",UUID.class),r.getLong("result_revision"),false),id,request);
        if(!saved.isEmpty())return saved.getFirst();
        if(s.revision()!=expected)throw new ConflictException("STALE_SESSION_REVISION","Session revision changed.");
        if(s.activeTurnId()!=null) {
            int queued=jdbc.update("UPDATE standalone_agent_turns SET status='CANCELLED',finished_at=clock_timestamp(),cancel_requested=TRUE WHERE id=? AND status='QUEUED'",s.activeTurnId());
            if(queued==1)jdbc.update("UPDATE standalone_agent_sessions SET status='READY',active_turn_id=NULL,revision=revision+1,updated_at=clock_timestamp() WHERE id=?",id);
            else { jdbc.update("UPDATE standalone_agent_turns SET cancel_requested=TRUE WHERE id=?",s.activeTurnId());jdbc.update("UPDATE standalone_agent_sessions SET revision=revision+1,updated_at=clock_timestamp() WHERE id=?",id); }
        }
        var current=find(project,id).orElseThrow();jdbc.update("INSERT INTO standalone_agent_cancel_requests(session_id,request_id,expected_revision,turn_id,result_revision) VALUES (?,?,?,?,?)",id,request,expected,s.activeTurnId(),current.revision());
        return new StandaloneCommandReceipt(id,s.activeTurnId(),current.revision(),true);
    }
    @Override @Transactional public Optional<StandaloneExecutionClaim> claim(String owner,int timeoutSeconds) {
        var ids=jdbc.query("SELECT t.id FROM standalone_agent_turns t WHERE t.status='QUEUED' ORDER BY t.created_at LIMIT 1 FOR UPDATE SKIP LOCKED",(r,n)->r.getObject(1,UUID.class));
        if(ids.isEmpty())return Optional.empty();UUID id=ids.getFirst();
        jdbc.update("UPDATE standalone_agent_turns SET status='RUNNING',lease_owner=?,lease_token=lease_token+1,lease_expires_at=clock_timestamp()+interval '30 seconds',deadline=clock_timestamp()+(?*interval '1 second') WHERE id=?",owner,timeoutSeconds,id);
        return Optional.of(claimById(id));
    }
    private StandaloneExecutionClaim claimById(UUID id) {
        return jdbc.query("SELECT t.*,s.project_id FROM standalone_agent_turns t JOIN standalone_agent_sessions s ON s.id=t.session_id WHERE t.id=?",(r,n)->new StandaloneExecutionClaim(find(r.getObject("project_id",UUID.class),r.getObject("session_id",UUID.class)).orElseThrow(),id,r.getString("request_text"),r.getString("provider_turn_id"),r.getString("lease_owner"),r.getLong("lease_token"),r.getTimestamp("deadline").toInstant(),r.getBoolean("cancel_requested"),r.getBoolean("dispatch_started")),id).getFirst();
    }
    @Override public boolean current(StandaloneExecutionClaim c) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM standalone_agent_turns t JOIN standalone_agent_sessions s ON s.active_turn_id=t.id WHERE "+FENCE+" AND NOT t.cancel_requested)",Boolean.class,fence(c))); }
    @Override public boolean current(UUID project,UUID session,UUID turn,String owner,long token) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM standalone_agent_turns t JOIN standalone_agent_sessions s ON s.active_turn_id=t.id AND s.id=t.session_id WHERE "+FENCE+" AND NOT t.cancel_requested AND s.project_id=?)",Boolean.class,turn,session,owner,token,project));
    }
    @Override public boolean renew(StandaloneExecutionClaim c) { return jdbc.update("UPDATE standalone_agent_turns t SET lease_expires_at=clock_timestamp()+interval '30 seconds' WHERE "+FENCE+" AND NOT cancel_requested",fence(c))==1; }
    @Override public boolean runtimeStarted(StandaloneExecutionClaim c) {
        return jdbc.update("UPDATE standalone_agent_turns t SET cleanup_pending=TRUE WHERE "+FENCE,fence(c))==1;
    }
    @Override public boolean runtimeCleaned(StandaloneExecutionClaim c) {
        return jdbc.update("UPDATE standalone_agent_turns SET cleanup_pending=FALSE WHERE id=? AND session_id=? AND lease_owner=? AND lease_token=?",c.turnId(),c.session().id(),c.owner(),c.token())==1;
    }
    @Override @Transactional public boolean persistConversation(StandaloneExecutionClaim c,String id,String version) {
        lock(c.session().projectId(),c.session().id());if(!current(c))return false;
        return jdbc.update("UPDATE standalone_agent_sessions SET provider_conversation_id=?,provider_version=? WHERE id=? AND (provider_conversation_id IS NULL OR provider_conversation_id=?)",id,version,c.session().id(),id)==1;
    }
    @Override public boolean persistTurn(StandaloneExecutionClaim c,String id) {
        var args=new ArrayList<Object>();args.add(id);args.addAll(Arrays.asList(fence(c)));
        return jdbc.update("UPDATE standalone_agent_turns t SET provider_turn_id=? WHERE "+FENCE+" AND NOT cancel_requested AND (provider_turn_id IS NULL OR provider_turn_id=?)",java.util.stream.Stream.concat(args.stream(),java.util.stream.Stream.of(id)).toArray())==1;
    }
    @Override @Transactional(propagation=Propagation.NOT_SUPPORTED) public void dispatch(StandaloneExecutionClaim c,Runnable write) {
        try(Connection connection=dataSource.getConnection()) {
            String key="standalone-dispatch:"+c.session().id();
            try {
                advisory(connection,"pg_advisory_lock",key);
                // Validate with the same connection that owns the advisory lock, no pool re-entry.
                try(var check=connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM standalone_agent_turns t WHERE "+FENCE+" AND NOT cancel_requested)")) {
                    Object[] args=fence(c);for(int i=0;i<args.length;i++)check.setObject(i+1,args[i]);
                    try(var result=check.executeQuery()){result.next();if(!result.getBoolean(1))throw stale();}
                }
                try(var update=connection.prepareStatement("UPDATE standalone_agent_turns SET dispatch_started=TRUE WHERE id=?")){update.setObject(1,c.turnId());update.executeUpdate();}
                write.run();
            } finally { try{advisory(connection,"pg_advisory_unlock",key);}catch(SQLException e){connection.abort(Runnable::run);throw e;} }
        }catch(SQLException e){throw new IllegalStateException("Could not fence standalone dispatch",e);}
    }
    private void advisory(Connection c,String function,String key)throws SQLException { try(var s=c.prepareStatement("SELECT "+function+"(hashtextextended(?,0))")){s.setString(1,key);s.execute();} }
    @Override @Transactional public boolean appendEvent(StandaloneExecutionClaim c,AgentExecutionEventCandidate e) {
        lock(c.session().projectId(),c.session().id());if(!current(c))return false;
        if(e.providerEventKey()!=null && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM standalone_agent_events WHERE turn_id=? AND provider_event_key=?)",Boolean.class,c.turnId(),e.providerEventKey())))return true;
        long seq=jdbc.queryForObject("UPDATE standalone_agent_sessions SET last_event_sequence=last_event_sequence+1,last_activity_at=clock_timestamp() WHERE id=? RETURNING last_event_sequence",Long.class,c.session().id());
        jdbc.update("INSERT INTO standalone_agent_events(session_id,turn_id,sequence,type,status,phase,provider_event_key,payload,occurred_at) VALUES (?,?,?,?,?,?,?,?,?)",c.session().id(),c.turnId(),seq,e.type().name(),e.status()==null?null:e.status().name(),e.phase(),e.providerEventKey(),e.payload(),Timestamp.from(e.occurredAt()));return true;
    }
    @Override @Transactional public boolean finish(StandaloneExecutionClaim c,String status,String text,String code,boolean uncertain) {
        lock(c.session().projectId(),c.session().id());
        var active=jdbc.query("SELECT cancel_requested FROM standalone_agent_turns t WHERE "+FENCE+(uncertain?"":" AND NOT cleanup_pending"),(r,n)->r.getBoolean(1),fence(c));if(active.isEmpty())return false;
        var completion=StandaloneSessionPolicy.complete(status,text,active.getFirst(),uncertain);
        text=completion.assistantText();
        jdbc.update("UPDATE standalone_agent_turns SET status=?,failure_code=?,finished_at=clock_timestamp(),lease_expires_at=NULL WHERE id=?",completion.turnStatus(),code,c.turnId());
        if(completion.assistantText()!=null) {
            long sequence=jdbc.queryForObject("UPDATE standalone_agent_sessions SET last_message_sequence=last_message_sequence+1 WHERE id=? RETURNING last_message_sequence",Long.class,c.session().id());
            jdbc.update("INSERT INTO standalone_agent_messages(id,session_id,turn_id,sequence,role,text) VALUES (?,?,?,?,'ASSISTANT',?)",UUID.randomUUID(),c.session().id(),c.turnId(),sequence,text);
        }
        jdbc.update("UPDATE standalone_agent_sessions SET status=?,active_turn_id=?,failure_code=?,revision=revision+1,updated_at=clock_timestamp(),last_activity_at=clock_timestamp() WHERE id=?",completion.sessionStatus(),completion.retainActiveTurn()?c.turnId():null,code,c.session().id());return true;
    }
    @Override public List<StandaloneExecutionClaim> expired(int limit) { return jdbc.query("SELECT id FROM standalone_agent_turns WHERE status IN ('RUNNING','RECOVERING') AND lease_expires_at<=clock_timestamp() ORDER BY created_at LIMIT ?",(r,n)->claimById(r.getObject(1,UUID.class)),limit); }
    @Override @Transactional public boolean quarantine(StandaloneExecutionClaim c,String code) {
        dispatchLock(c.session().id());lock(c.session().projectId(),c.session().id());
        if(jdbc.update("UPDATE standalone_agent_turns SET status='RECOVERY_REQUIRED',failure_code=?,lease_token=lease_token+1 WHERE id=? AND lease_token=? AND status IN ('RUNNING','RECOVERING') AND lease_expires_at<=clock_timestamp()",code,c.turnId(),c.token())==0)return false;
        jdbc.update("UPDATE standalone_agent_sessions SET status='RECOVERY_REQUIRED',failure_code=?,revision=revision+1,updated_at=clock_timestamp() WHERE id=?",code,c.session().id());return true;
    }
    @Override public List<UUID> cancellationRequests(int limit) { return jdbc.query("SELECT id FROM standalone_agent_turns WHERE status='RUNNING' AND cancel_requested LIMIT ?",(r,n)->r.getObject(1,UUID.class),limit); }
    @Override @Transactional public Optional<StandaloneExecutionClaim> claimRecovery(String owner) {
        var candidates=jdbc.query("SELECT t.id,t.session_id FROM standalone_agent_turns t WHERE t.status='RECOVERY_REQUIRED' AND NOT t.recovery_attempted AND NOT t.cleanup_pending ORDER BY created_at LIMIT 1",(r,n)->Map.entry(r.getObject(1,UUID.class),r.getObject(2,UUID.class)));
        if(candidates.isEmpty())return Optional.empty();var candidate=candidates.getFirst();dispatchLock(candidate.getValue());
        var identity=jdbc.query("SELECT project_id FROM standalone_agent_sessions WHERE id=?",(r,n)->r.getObject(1,UUID.class),candidate.getValue());if(identity.isEmpty())return Optional.empty();
        lock(identity.getFirst(),candidate.getValue());
        if(jdbc.update("UPDATE standalone_agent_turns SET status='RECOVERING',lease_owner=?,lease_token=lease_token+1,lease_expires_at=clock_timestamp()+interval '45 seconds',recovery_attempted=TRUE WHERE id=? AND status='RECOVERY_REQUIRED' AND NOT recovery_attempted AND NOT cleanup_pending",owner,candidate.getKey())!=1)return Optional.empty();
        return Optional.of(claimById(candidate.getKey()));
    }
    @Override @Transactional public boolean reconcileRecovery(StandaloneExecutionClaim c,String outcome,String text,String code) {
        dispatchLock(c.session().id());lock(c.session().projectId(),c.session().id());
        var completion=StandaloneSessionPolicy.complete(outcome==null?"FAILED":outcome,text,false,outcome==null);
        if(jdbc.update("UPDATE standalone_agent_turns SET status=?,failure_code=?,finished_at=clock_timestamp(),lease_expires_at=NULL WHERE id=? AND session_id=? AND lease_owner=? AND lease_token=? AND status='RECOVERING' AND NOT cleanup_pending AND lease_expires_at>clock_timestamp()",completion.turnStatus(),code,c.turnId(),c.session().id(),c.owner(),c.token())==0)return false;
        if(completion.assistantText()!=null) {
            long seq=jdbc.queryForObject("UPDATE standalone_agent_sessions SET last_message_sequence=last_message_sequence+1 WHERE id=? RETURNING last_message_sequence",Long.class,c.session().id());
            jdbc.update("INSERT INTO standalone_agent_messages(id,session_id,turn_id,sequence,role,text)VALUES(?,?,?,?,'ASSISTANT',?) ON CONFLICT(turn_id,role) DO NOTHING",UUID.randomUUID(),c.session().id(),c.turnId(),seq,text);
        }
        jdbc.update("UPDATE standalone_agent_sessions SET status=?,active_turn_id=?,failure_code=?,revision=revision+1,updated_at=clock_timestamp(),last_activity_at=clock_timestamp() WHERE id=?",completion.sessionStatus(),completion.retainActiveTurn()?c.turnId():null,code,c.session().id());return true;
    }
}
