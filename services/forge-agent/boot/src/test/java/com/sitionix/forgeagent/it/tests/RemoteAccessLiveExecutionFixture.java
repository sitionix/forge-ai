package com.sitionix.forgeagent.it.tests;

import static org.assertj.core.api.Assertions.*;
import com.sitionix.forgeagent.application.remoteaccess.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.infrastructure.local.LocalRemoteAccessCredentialStore;
import com.sitionix.forgeagent.infrastructure.local.remoteaccess.*;
import com.sitionix.forgeagent.infrastructure.postgres.adapter.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

/** Real production peers in isolated DB schemas; root fixture only prepares explicit workspaces. */
public final class RemoteAccessLiveExecutionFixture {
    static final Clock CLOCK=Clock.systemUTC();
    static final LocalInvitationGrants GRANTS=new LocalInvitationGrants();
    static final LocalPairingTokens TOKENS=new LocalPairingTokens();
    static String url,user,password;
    static final BufferedReader ROOT=new BufferedReader(new InputStreamReader(System.in));
    static void operator(String request) throws IOException {
        System.out.println(request);System.out.flush();assertThat(ROOT.readLine()).isEqualTo("OK");
    }
    public static void main(String[] args) throws Exception {
        url=args[0];user=args[1];password=args[2];
        var a=new Peer("exec_a");var b=new Peer("exec_b");
        var endpoint=new RemoteAccessEndpoint("127.0.0.1",22222,"forge-ssh");
        var grants=new LocalRemoteAccessWorkloads();
        var execution=new RemoteAccessExecutionService(b.sessions,b.identity,grants,GRANTS,CLOCK);
        var grantor=new RemoteAccessGrantorPairing(b.invitations,b.sessions,b.identity,TOKENS,GRANTS,GRANTS,b.provisioning(),CLOCK);
        var accessor=new RemoteAccessAccessorPairing(a.sessions,a.identity,TOKENS,a.provisioning(),a.transport(),CLOCK);
        var commands=new RemoteAccessAccessorExecution(a.sessions,a.identity,a.transport(),a.credentials,new LocalRemoteAccessCommandTransport(a.credentials),CLOCK);
        try(var server=new RemoteAccessChannelServer(Path.of("/run/forge-remote/channel/authority.sock"),"forge-ssh","forge-ssh",
                new RemoteAccessChannelService(b.sessions,b.identity,CLOCK,b.invitations),grantor,execution);
            var readers=Executors.newVirtualThreadPerTaskExecutor()) {
            server.start();execution.maintain();
            var heartbeat=Executors.newSingleThreadScheduledExecutor();
            heartbeat.scheduleWithFixedDelay(() -> {try {execution.maintain();}catch(RuntimeException unavailable){System.out.println("RECOVERY: authority unavailable");}},0,2,TimeUnit.SECONDS);
            try {
                var invitation=new RemoteAccessInvitations(b.invitations,b.identity,TOKENS,GRANTS,CLOCK,b.transactions);
                var first=accessor.connect(invitation.create(endpoint,"B").token().value(),"A");
                var second=accessor.connect(invitation.create(endpoint,"B").token().value(),"A second");
                var third=accessor.connect(invitation.create(endpoint,"B").token().value(),"A crash");
                assertThat(first.status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
                operator("PREPARE "+first.id()+" "+second.id()+" "+third.id());
                String literal="a b 'quoted' $HOME $(touch /workspace/injected) %n %i";
                var echo=execute(commands,first.id(),List.of("/usr/bin/python3","-c","import sys; print(sys.argv[1]); print('err',file=sys.stderr); sys.exit(7)",literal),readers);
                assertThat(echo.code).isEqualTo(7);assertThat(echo.out).isEqualTo(literal+"\n");assertThat(echo.err).isEqualTo("err\n");
                System.out.println("PASS real SSH literal argv, separate stderr and nonzero exit");
                var large=execute(commands,first.id(),List.of("/usr/bin/python3","-c","import sys; data=sys.stdin.buffer.read(); sys.stdout.buffer.write(data*50000); sys.stdout.flush(); sys.stderr.buffer.write(b'e'*200000)"),readers,"abc");
                assertThat(large.code).isZero();assertThat(large.out).isEqualTo("abc".repeat(50000));assertThat(large.err).hasSize(200000);
                System.out.println("PASS real SSH stdin and large independent streams");
                try(var timed=commands.start(second.id(),new RemoteAccessCommand(List.of("/bin/sleep","120"),"/workspace",1))) {
                    timed.stdin().close();
                    assertThat(readers.submit(timed::await).get(15,TimeUnit.SECONDS)).isNotZero();
                }
                System.out.println("PASS real managed command timeout");
                String isolation="import os,socket\nfor p in ['/run/forge-remote/channel/authority.sock','/run/forge-remote/workload-admin/control.sock','/var/run/docker.sock','/fixture/state','/etc/forge-remote/host_ed25519']:\n assert not os.path.exists(p),p\ns=socket.socket(); s.settimeout(1); assert s.connect_ex(('127.0.0.1',22222))!=0\nassert os.getuid()!=0\nopen('/workspace/edited','w').write('persisted')\ntry: open('/control-write','w').write('bad'); raise AssertionError('rootfs writable')\nexcept PermissionError: pass\nexcept OSError: pass\nprint('isolated')";
                assertThat(execute(commands,first.id(),List.of("/usr/bin/python3","-c",isolation),readers).code).isZero();
                assertThat(execute(commands,first.id(),List.of("/bin/cat","/workspace/edited"),readers).out).isEqualTo("persisted");
                System.out.println("PASS workload isolation and persistent explicit workspace");
                try(var unrelated=commands.start(second.id(),new RemoteAccessCommand(List.of("/bin/sh","-c","echo $$; exec sleep 120"),"/workspace",150));
                    var cancelled=commands.start(first.id(),new RemoteAccessCommand(List.of("/bin/sh","-c","echo MAIN=$$; setsid /bin/sleep 120 & echo CHILD=$!; echo READY; wait"),"/workspace",150))) {
                    unrelated.stdin().close();cancelled.stdin().close();
                    var unrelatedOutput=new BufferedReader(new InputStreamReader(unrelated.stdout()));
                    String unrelatedPid=readers.submit(unrelatedOutput::readLine).get(5,TimeUnit.SECONDS);
                    assertThat(unrelatedPid).matches("[0-9]+");
                    var output=new BufferedReader(new InputStreamReader(cancelled.stdout()));
                    String main=readers.submit(output::readLine).get(5,TimeUnit.SECONDS);
                    String child=readers.submit(output::readLine).get(5,TimeUnit.SECONDS);
                    assertThat(main).matches("MAIN=[0-9]+");assertThat(child).matches("CHILD=[0-9]+");
                    assertThat(readers.submit(output::readLine).get(5,TimeUnit.SECONDS)).isEqualTo("READY");
                    operator("CAPTURE_CANCELLATION "+first.id()+" "+main.substring(5)+" "+child.substring(6));
                    cancelled.close();
                    assertThat(readers.submit(cancelled::await).get(5,TimeUnit.SECONDS)).isNotZero();
                    operator("VERIFY_CANCELLATION");
                    assertThat(b.sessions.findById(first.id()).orElseThrow().status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
                    assertThat(a.sessions.findById(first.id()).orElseThrow().status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
                    assertThat(execute(commands,second.id(),List.of("/usr/bin/python3","-c",
                        "import os,sys; os.kill(int(sys.argv[1]),0)",unrelatedPid),readers).code).isZero();
                }
                System.out.println("PASS real SSH close cancellation removes main, setsid child, systemd unit, registry and fence; unrelated session survives");
                try(var other=commands.start(second.id(),new RemoteAccessCommand(List.of("/bin/sh","-c","echo $$; exec sleep 120"),"/workspace",150));
                    var running=commands.start(first.id(),new RemoteAccessCommand(List.of("/bin/sh","-c","setsid /bin/sleep 120 & echo CHILD=$!; echo READY; wait"),"/workspace",150))) {
                    other.stdin().close();
                    String otherPid=new BufferedReader(new InputStreamReader(other.stdout())).readLine();
                    assertThat(otherPid).matches("[0-9]+");
                    running.stdin().close();
                    var output=new BufferedReader(new InputStreamReader(running.stdout()));
                    String child=output.readLine();assertThat(child).startsWith("CHILD=");
                    assertThat(output.readLine()).isEqualTo("READY");
                    assertThat(b.sessions.recordFailure(b.sessions.findById(first.id()).orElseThrow(),
                        "REMOTE_ACCESS_CLEANUP_PENDING","previous cleanup failure")).isTrue();
                    assertThat(a.sessions.recordFailure(a.sessions.findById(first.id()).orElseThrow(),
                        "REMOTE_ACCESS_REVOKE_UNCONFIRMED","previous remote failure")).isTrue();
                    var result=commands.revoke(first.id());
                    assertThat(result.status()).isEqualTo(RemoteAccessSessionStatus.REVOKED);
                    assertThat(b.sessions.findById(first.id()).orElseThrow().status()).isEqualTo(RemoteAccessSessionStatus.REVOKED);
                    assertThat(b.sessions.findById(first.id()).orElseThrow().failureCode()).isNull();
                    assertThat(b.sessions.findById(first.id()).orElseThrow().failureMessage()).isNull();
                    assertThat(a.sessions.findById(first.id()).orElseThrow().failureCode()).isNull();
                    assertThat(a.sessions.findById(first.id()).orElseThrow().failureMessage()).isNull();
                    assertThat(readers.submit(running::await).get(15,TimeUnit.SECONDS)).isNotZero();
                    assertThat(Files.exists(Path.of("/proc",child.substring(6)))).isFalse();
                    assertThat(execute(commands,second.id(),List.of("/usr/bin/python3","-c","import os,sys; os.kill(int(sys.argv[1]),0)",otherPid),readers).code).isZero();
                    assertThatThrownBy(() -> commands.start(first.id(),new RemoteAccessCommand(List.of("/bin/true"),"/workspace",5))).isInstanceOf(IllegalStateException.class);
                }
                assertThat(execute(commands,second.id(),List.of("/bin/echo","unrelated-alive"),readers).out).isEqualTo("unrelated-alive\n");
                System.out.println("PASS revoke stops setsid descendants, denies new execution and preserves another session");
                try(var running=commands.start(third.id(),new RemoteAccessCommand(List.of("/bin/sh","-c","echo READY; sleep 120"),"/workspace",150))) {
                    running.stdin().close();assertThat(new BufferedReader(new InputStreamReader(running.stdout())).readLine()).isEqualTo("READY");
                    heartbeat.shutdownNow();
                    assertThat(readers.submit(running::await).get(25,TimeUnit.SECONDS)).isNotZero();
                }
                System.out.println("PASS unavailable authority lease stops existing workload");
                assertThatThrownBy(execution::maintain).isInstanceOf(IllegalStateException.class);
                execution.maintain();
                var restartedHeartbeat=Executors.newSingleThreadScheduledExecutor();
                restartedHeartbeat.scheduleWithFixedDelay(() -> {try {execution.maintain();}catch(RuntimeException unavailable){}},0,2,TimeUnit.SECONDS);
                try {
                    try(var running=commands.start(third.id(),new RemoteAccessCommand(List.of("/bin/sh","-c","echo READY; sleep 120"),"/workspace",150))) {
                        running.stdin().close();assertThat(new BufferedReader(new InputStreamReader(running.stdout())).readLine()).isEqualTo("READY");
                        operator("CRASH_SUPERVISOR");
                        assertThat(readers.submit(running::await).get(25,TimeUnit.SECONDS)).isNotZero();
                    }
                    System.out.println("PASS actual supervisor SIGKILL stops bound workload units");
                    operator("WAIT_SUPERVISOR");
                    try {execution.maintain();}catch(IllegalStateException unavailable){}
                    execution.maintain();
                    try(var running=commands.start(third.id(),new RemoteAccessCommand(List.of("/bin/sh","-c","echo READY; sleep 120"),"/workspace",150))) {
                        running.stdin().close();assertThat(new BufferedReader(new InputStreamReader(running.stdout())).readLine()).isEqualTo("READY");
                        operator("HANG_SUPERVISOR");
                        assertThat(readers.submit(running::await).get(40,TimeUnit.SECONDS)).isNotZero();
                    }
                    System.out.println("PASS actual supervisor SIGSTOP watchdog stops bound workload units");
                    operator("WAIT_SUPERVISOR");
                } finally {restartedHeartbeat.shutdownNow();}
            } finally {heartbeat.shutdownNow();}
        }
    }
    record Result(int code,String out,String err) {}
    static Result execute(RemoteAccessAccessorExecution service,UUID id,List<String> argv,ExecutorService readers) throws Exception {return execute(service,id,argv,readers,"");}
    static Result execute(RemoteAccessAccessorExecution service,UUID id,List<String> argv,ExecutorService readers,String input) throws Exception {
        try(var running=service.start(id,new RemoteAccessCommand(argv,"/workspace",30))) {
            var out=readers.submit(() -> new String(running.stdout().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            var err=readers.submit(() -> new String(running.stderr().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            running.stdin().write(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));running.stdin().close();
            int code=readers.submit(running::await).get(45,TimeUnit.SECONDS);
            return new Result(code,out.get(5,TimeUnit.SECONDS),err.get(5,TimeUnit.SECONDS));
        }
    }
    static final class Peer {
        final PostgresRemoteAccessSessionRepository sessions;
        final PostgresRemoteAccessInvitationRepository invitations;
        final PostgresForgeInstanceIdentityRepository identity;
        final LocalRemoteAccessCredentialStore credentials;
        final DataSourceTransactionManager transactions;
        Peer(String schema) {
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
            var source=new DriverManagerDataSource(url+"?currentSchema="+schema,user,password);
            var jdbc=new JdbcTemplate(source);transactions=new DataSourceTransactionManager(source);
            sessions=new PostgresRemoteAccessSessionRepository(jdbc);invitations=new PostgresRemoteAccessInvitationRepository(jdbc);
            identity=new PostgresForgeInstanceIdentityRepository(jdbc);
            credentials=new LocalRemoteAccessCredentialStore(Path.of("/fixture/state",schema+"-keys"));
        }
        RemoteAccessProvisioningService provisioning() {return new RemoteAccessProvisioningService(invitations,sessions,identity,credentials,CLOCK,transactions);}
        LocalRemoteAccessPairingTransport transport() {return new LocalRemoteAccessPairingTransport(credentials);}
    }
}
