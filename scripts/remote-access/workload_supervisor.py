#!/usr/bin/python3 -I
"""Root workload broker. forge-control alone admits; forge-ssh only attaches streams."""
import array
import concurrent.futures
import json
import os
import pathlib
import pwd
import select
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
import uuid

# Installed sibling is root-owned; never import from the calling directory.
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parent))
from workload_units import Registry, canonical, command_request, command_argv, prepared_context

REGISTRY=pathlib.Path('/var/lib/forge-remote/executions')
CONTEXTS=pathlib.Path('/etc/forge-remote/workspaces')
ADMIN=pathlib.Path('/run/forge-remote/workload-admin/control.sock')
HEARTBEAT=ADMIN.with_name('heartbeat.sock')
PEER=pathlib.Path('/run/forge-remote/workload-peer/attach.sock')


class Supervisor:
    def __init__(self,registry,contexts,clock=time.monotonic):
        self.registry=Registry(registry);self.contexts=contexts;self.clock=clock
        self.epoch=None;self.deadline=None;self.tickets={};self.processes={};self.cancelled=set()
        self.lock=threading.RLock()
        self.gates=[threading.RLock() for _ in range(256)]

    def gate(self,session):return self.gates[uuid.UUID(canonical(session)).int%256]

    def reconcile(self,epoch):
        canonical(epoch)
        with self.lock:
            self.epoch=None;self.deadline=None;self.tickets.clear();self.mark_cancelled()
        # Fence all in-flight START critical sections before cleaning registered units.
        for gate in self.gates:gate.acquire()
        try:
            self.registry.cleanup(reap=self.reap_launcher)
            with self.lock:self.epoch=epoch;self.deadline=self.clock()+10
        finally:
            for gate in reversed(self.gates):gate.release()

    def heartbeat(self,epoch):
        with self.lock:
            self.require_authority(epoch)
            self.deadline=self.clock()+10

    def require_authority(self,epoch):
        if epoch!=self.epoch or self.deadline is None or self.clock()>=self.deadline:
            raise RuntimeError('Workload authority unavailable')

    def attach(self,session,request,streams=None):
        canonical(session);command_request(request)
        with self.lock:
            if len(self.tickets)>=32:raise RuntimeError('Attachment capacity exceeded')
            ticket=str(uuid.uuid4())
            self.tickets[ticket]=(session,request,self.clock()+5,streams)
            return ticket

    def start(self,session,ticket,epoch,streams=None):
        with self.gate(session):
            with self.lock:
                self.require_authority(epoch)
                entry=self.tickets.get(ticket)
                if entry is None or entry[0]!=session or self.clock()>=entry[2]:raise ValueError('Invalid attachment')
                del self.tickets[ticket]
            context=prepared_context(self.contexts,session)
            actual_streams=entry[3] if streams is None else streams
            if actual_streams is None or len(actual_streams)!=3:raise ValueError('Missing command streams')
            with self.lock:
                # Context lookup/registration must not revive an expired authority.
                self.require_authority(epoch)
                self.registry.register(session,ticket)
                process=subprocess.Popen(command_argv(session,ticket,context,entry[1],self.registry.fence(ticket)),
                    stdin=actual_streams[0],stdout=actual_streams[1],stderr=actual_streams[2],close_fds=True,
                    env={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin','LANG':'C.UTF-8'})
                self.processes[ticket]=(session,process)
            return process

    def stop(self,session):
        canonical(session)
        with self.gate(session):
            with self.lock:
                self.tickets={key:value for key,value in self.tickets.items() if value[0]!=session}
                self.mark_cancelled(session)
            self.registry.cleanup(session,reap=self.reap_launcher)

    def prepare(self,session):
        canonical(session)
        with self.gate(session):
            # The long-lived supervisor has ProtectSystem=strict. A short-lived,
            # purpose-bound root unit may create the session OS user and manifest.
            subprocess.run([
                '/usr/bin/systemd-run', '--quiet', '--wait', '--collect', '--service-type=exec',
                '--expand-environment=no', '--unit=forge-remote-prepare-'+session,
                '--property=RuntimeMaxSec=25s', '--property=TimeoutStopSec=5s',
                '--property=PrivateNetwork=yes', '--property=ProtectHome=yes',
                '--property=NoNewPrivileges=yes', '--property=UMask=0077',
                '--', '/usr/libexec/forge-remote/prepare-workspace',
                '--session', session, '--rootfs', '/srv/forge-remote/rootfs',
            ], check=True, timeout=30, stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            prepared_context(self.contexts,session)

    def mark_cancelled(self,session=None):
        for ticket,(owner,process) in self.processes.items():
            if (session is None or owner==session) and process.poll() is None:self.cancelled.add(ticket)

    def exit_code(self,ticket,code):
        with self.lock:return 143 if ticket in self.cancelled else (code if 0<=code<=255 else 255)

    def finish(self,session,ticket):
        # Stop descendants even when the main command has exited; never stop another command.
        with self.gate(session):
            with self.lock:
                self.tickets.pop(ticket,None)
                unit=next((unit for execution,unit in self.registry.entries(session) if execution==ticket),None)
            if unit is not None:
                self.registry.cleanup(session,reap=self.reap_launcher,execution_id=ticket)
            with self.lock:self.processes.pop(ticket,None);self.cancelled.discard(ticket)

    def reap_launcher(self,ticket):
        with self.lock:running=self.processes.get(ticket)
        if running is None:return
        process=running[1]
        if process.poll() is None:
            process.terminate()
            try:process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill();process.wait(timeout=2)
        else:process.wait(timeout=2)

    def tick(self):
        with self.lock:
            expired=self.deadline is not None and self.clock()>=self.deadline
            if expired:self.epoch=None;self.deadline=None;self.tickets.clear();self.mark_cancelled()
        if expired:
            for gate in self.gates:gate.acquire()
            try:self.registry.cleanup(reap=self.reap_launcher)
            finally:
                for gate in reversed(self.gates):gate.release()


def peer_uid(connection):return struct.unpack('3i',connection.getsockopt(socket.SOL_SOCKET,socket.SO_PEERCRED,12))[1]


def admin_request(connection,supervisor,control_uid,heartbeat_only=False):
    connection.settimeout(3)
    try:
        if peer_uid(connection)!=control_uid:raise PermissionError()
        data=bytearray()
        while len(data)<=256 and not data.endswith(b'\n'):
            chunk=connection.recv(257-len(data))
            if not chunk:raise ValueError()
            data.extend(chunk)
        fields=data.decode('ascii').rstrip('\n').split(' ')
        if data.count(b'\n')!=1 or not data.endswith(b'\n'):raise ValueError()
        if heartbeat_only and (len(fields)!=2 or fields[0]!='HEARTBEAT'):raise ValueError()
        if len(fields)==2 and fields[0]=='RECONCILE':supervisor.reconcile(canonical(fields[1]))
        elif len(fields)==2 and fields[0]=='HEARTBEAT':supervisor.heartbeat(canonical(fields[1]))
        elif len(fields)==2 and fields[0]=='STOP':supervisor.stop(canonical(fields[1]))
        elif len(fields)==2 and fields[0]=='PREPARE':supervisor.prepare(canonical(fields[1]))
        elif len(fields)==4 and fields[0]=='START':supervisor.start(canonical(fields[1]),canonical(fields[2]),canonical(fields[3]))
        else:raise ValueError()
        connection.sendall(b'OK\n')
    except (OSError,ValueError,RuntimeError,subprocess.SubprocessError):
        try:connection.sendall(b'DENIED\n')
        except OSError:pass


def heartbeat_request(connection,supervisor,control_uid):
    admin_request(connection,supervisor,control_uid,heartbeat_only=True)


def attached_command(connection,supervisor,ssh_uid):
    descriptors=[];ticket=None;session=None
    try:
        connection.settimeout(3)
        if peer_uid(connection)!=ssh_uid:raise PermissionError()
        data,ancillary,flags,_=connection.recvmsg(16384,socket.CMSG_SPACE(3*array.array('i').itemsize))
        for level,kind,content in ancillary:
            if level!=socket.SOL_SOCKET or kind!=socket.SCM_RIGHTS:raise ValueError()
            values=array.array('i');values.frombytes(content);descriptors.extend(values)
        if flags or len(descriptors)!=3:raise ValueError()
        request=json.loads(data)
        if set(request)!={'sessionId','command'}:raise ValueError()
        session=canonical(request['sessionId'])
        ticket=supervisor.attach(session,command_request(request['command']),descriptors)
        connection.sendall((ticket+'\n').encode('ascii'))
        connection.settimeout(None)
        deadline=time.monotonic()+5
        while True:
            if select.select([connection],[],[],.1)[0]:
                # EOF or any unsolicited packet is cancellation, never another command.
                connection.recv(1)
                raise RuntimeError('Attachment cancelled')
            with supervisor.lock:
                running=supervisor.processes.get(ticket)
                pending=ticket in supervisor.tickets
            if running is not None:
                code=running[1].poll()
                if code is not None:
                    code=supervisor.exit_code(ticket,code)
                    supervisor.finish(session,ticket)
                    connection.sendall(('EXIT '+str(code)+'\n').encode('ascii'))
                    return
            elif not pending or time.monotonic()>=deadline:raise RuntimeError('Attachment expired')
    except (OSError,ValueError,RuntimeError,KeyError,TypeError,subprocess.SubprocessError):
        try:connection.sendall(b'DENIED\n')
        except OSError:pass
    finally:
        if ticket is not None:
            try:supervisor.finish(session,ticket)
            except (OSError,ValueError,RuntimeError,subprocess.SubprocessError):pass # durable registry remains for retry
        for descriptor in descriptors:os.close(descriptor)


def listen(path,group,kind):
    for parent in path.parents:
        info=parent.lstat()
        if not pathlib.Path(parent).is_dir() or info.st_uid!=0 or info.st_mode & 0o022:raise RuntimeError('Unsafe supervisor socket directory')
    os.chown(path.parent,0,group)
    listener=socket.socket(socket.AF_UNIX,kind)
    listener.bind(str(path));os.chown(path,0,group);os.chmod(path,0o660);listener.listen(16)
    return listener


def notify(value):
    address=os.environ.get('NOTIFY_SOCKET')
    if address:
        if address.startswith('@'):address='\0'+address[1:]
        with socket.socket(socket.AF_UNIX,socket.SOCK_DGRAM) as channel:channel.sendto(value.encode('ascii'),address)


def serve_connections(supervisor,admin,peer,heartbeat,control_uid,ssh_uid,stopping):
    # Attachments live as long as commands; neither they nor slow STOPs consume
    # the independent authority heartbeat budget. No unbounded executor queue.
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as attachments, \
         concurrent.futures.ThreadPoolExecutor(max_workers=8) as controls, \
         concurrent.futures.ThreadPoolExecutor(max_workers=2) as heartbeats:
        routes={peer:(attachments,threading.BoundedSemaphore(16),attached_command,ssh_uid),
                admin:(controls,threading.BoundedSemaphore(8),admin_request,control_uid),
                heartbeat:(heartbeats,threading.BoundedSemaphore(2),heartbeat_request,control_uid)}
        def serve(connection,slots,handler,uid):
            try:
                with connection:handler(connection,supervisor,uid)
            finally:slots.release()
        try:
            while not stopping.is_set():
                supervisor.tick();notify('WATCHDOG=1')
                for listener in select.select(list(routes),[],[],.5)[0]:
                    connection,_=listener.accept()
                    workers,slots,handler,uid=routes[listener]
                    if not slots.acquire(blocking=False):connection.close();continue
                    workers.submit(serve,connection,slots,handler,uid)
        finally:
            # Cancel running attachments before waiting for their worker threads.
            supervisor.reconcile(str(uuid.uuid4()))


def main():
    if os.geteuid()!=0:raise RuntimeError('Privileged workload supervisor required')
    version=subprocess.run(['/usr/bin/systemd-run','--version'],capture_output=True,text=True,check=True,timeout=3).stdout.split()
    if len(version)<2 or not version[1].isdigit() or int(version[1])<254:
        raise RuntimeError('NOT READY: systemd 254+ required for literal argv transport')
    control=pwd.getpwnam('forge-control');ssh=pwd.getpwnam('forge-ssh')
    supervisor=Supervisor(REGISTRY,CONTEXTS)
    supervisor.registry.cleanup() # fail closed before readiness
    admin=listen(ADMIN,control.pw_gid,socket.SOCK_STREAM)
    peer=listen(PEER,ssh.pw_gid,socket.SOCK_SEQPACKET)
    stopping=threading.Event()
    signal.signal(signal.SIGTERM,lambda *_:stopping.set())
    heartbeat=listen(HEARTBEAT,control.pw_gid,socket.SOCK_STREAM)
    notify('READY=1')
    try:serve_connections(supervisor,admin,peer,heartbeat,control.pw_uid,ssh.pw_uid,stopping)
    finally:admin.close();peer.close();heartbeat.close()


if __name__=='__main__':
    try:main()
    except (OSError,ValueError,RuntimeError,subprocess.SubprocessError):
        print('NOT READY: managed workload supervisor unavailable',file=sys.stderr);sys.exit(1)
