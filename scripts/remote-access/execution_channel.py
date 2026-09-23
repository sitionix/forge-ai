"""Single authenticated SSH command attachment. No peer-selected supervisor operation."""
import array
import json
import os
import select
import socket
import struct
import time
import uuid

SOCKET='/run/forge-remote/workload-peer/attach.sock'


def read_header():
    deadline=time.monotonic()+3;data=bytearray()
    while len(data)<16384:
        remaining=deadline-time.monotonic()
        if remaining<=0 or not select.select([0],[],[],remaining)[0]:raise TimeoutError('Command header deadline')
        value=os.read(0,1)
        if value==b'\n':
            result=json.loads(data)
            if not isinstance(result,dict):raise ValueError('Command object required')
            return result
        if not value:raise ValueError('Missing command header')
        data.extend(value)
    raise ValueError('Command header limit')


def wait_result(channel,outputs):
    # Non-PTY sshd need not kill a quiet forced command after disconnect. Watch
    # its output readers disappearing without consuming stdin (EOF is valid).
    events=select.poll()
    events.register(channel,select.POLLIN)
    for descriptor in outputs:events.register(descriptor,0)
    while True:
        ready=events.poll()
        if any(fd in outputs and flags & (select.POLLERR|select.POLLHUP|select.POLLNVAL) for fd,flags in ready):
            raise BrokenPipeError('SSH output disconnected')
        if any(fd==channel.fileno() for fd,flags in ready):return channel.recv(64)


def execute(binding,query):
    request=read_header()
    with socket.socket(socket.AF_UNIX,socket.SOCK_SEQPACKET) as channel:
        channel.settimeout(3);channel.connect(SOCKET)
        if struct.unpack('3i',channel.getsockopt(socket.SOL_SOCKET,socket.SO_PEERCRED,12))[1]!=0:
            raise PermissionError('Unexpected workload supervisor')
        payload=json.dumps({'sessionId':binding[2],'command':request},separators=(',',':')).encode('utf8')
        if len(payload)>16384:raise ValueError('Command header limit')
        if channel.sendmsg([payload],[(socket.SOL_SOCKET,socket.SCM_RIGHTS,array.array('i',[0,1,2]))])!=len(payload):raise OSError('Incomplete attachment')
        ticket=channel.recv(64).decode('ascii')
        if not ticket.endswith('\n') or str(uuid.UUID(ticket[:-1]))!=ticket[:-1]:raise ValueError('Attachment denied')
        result=query('EXEC '+' '.join(binding[1:])+' '+ticket[:-1]+'\n')
        if result!='STARTED\n':raise PermissionError('Execution denied')
        channel.settimeout(None)
        result=wait_result(channel,[1,2]).decode('ascii')
        if not result.startswith('EXIT ') or not result.endswith('\n'):raise ValueError('Execution result unavailable')
        code=int(result[5:-1])
        if not 0<=code<=255:raise ValueError('Invalid execution result')
        return code
