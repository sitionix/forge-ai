"""Bounded metadata reader. Project roots come only from Forge's workspace port."""
import hashlib
import errno
import json
import os
from pathlib import PurePosixPath
import stat
import sys

MAX_FILE = 65536
MAX_TOTAL = 2097152
MAX_OUTPUT = 1048576
MAX_FILES = 512
MAX_ENTRIES = 2048
FLAGS = os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW | os.O_NONBLOCK
DIRECTORY = FLAGS | os.O_DIRECTORY
items = []
resolved = []
manifest = {}
request = {}
manifest_bytes = 0
manifest_entries = 0
diagnostics = set()
runtime_compatible = False
seen = set()
files = entries = total = output_bytes = 0


class LimitReached(Exception):
    pass


class RuntimeUnsupported(Exception):
    pass


def verified_parser():
    """TOML 1.0 contract on characterized CPython minor lines, never a backport."""
    if (sys.implementation.name != 'cpython' or sys.version_info[:2] not in
            ((3, 11), (3, 12), (3, 14)) or sys.version_info.releaselevel != 'final'):
        raise RuntimeUnsupported()
    try:
        import tomllib
        sample = ('name = "R\\u00e9viewer"\ndescription = \'Reviews # changes\'\n'
                  'developer_instructions = """Keep complete instructions.\n'
                  'Inspect \\"quoted\\" text and \\u03bb carefully."""\n'
                  'model = \'native-model\'\nmodel_reasoning_effort = \'high\'\n')
        if tomllib.loads(sample) != {
                'name': 'Réviewer', 'description': 'Reviews # changes',
                'developer_instructions': 'Keep complete instructions.\nInspect "quoted" text and λ carefully.',
                'model': 'native-model', 'model_reasoning_effort': 'high'}:
            raise RuntimeUnsupported()
        for invalid in ('x=1\nx=2', 'x="\\e"', 'x="\\x41"', 'x="unterminated',
                        '[x]\n[x]', 'x=01', 'x="raw\x01control"'):
            try:
                tomllib.loads(invalid)
            except tomllib.TOMLDecodeError:
                continue
            raise RuntimeUnsupported()
        return tomllib
    except Exception as failure:
        raise RuntimeUnsupported() from failure


def directory(path):
    fd = os.open('/', DIRECTORY)
    try:
        for component in PurePosixPath(path).parts[1:]:
            child = os.open(component, DIRECTORY, dir_fd=fd)
            os.close(fd)
            fd = child
        return fd
    except BaseException:
        os.close(fd)
        raise


def scan(root):
    global files, entries, total, output_bytes
    root_fd = directory(root)
    codex_fd = agents_fd = None
    try:
        try:
            codex_fd = os.open('.codex', DIRECTORY, dir_fd=root_fd)
            agents_fd = os.open('agents', DIRECTORY, dir_fd=codex_fd)
        except FileNotFoundError:
            return
        names = []
        with os.scandir(agents_fd) as listing:
            for entry in listing:
                entries += 1
                if entries > MAX_ENTRIES:
                    raise LimitReached()
                if entry.name.endswith('.toml'):
                    names.append(entry.name)
        for name in sorted(names):
            files += 1
            if files > MAX_FILES:
                raise LimitReached()
            fd = None
            try:
                name.encode('utf-8', errors='strict')
                fd = os.open(name, FLAGS, dir_fd=agents_fd)
                info = os.fstat(fd)
                if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                    diagnostics.add('UNSAFE_RESOURCE')
                    continue
                if info.st_size > MAX_FILE:
                    diagnostics.add('LIMIT_EXCEEDED')
                    continue
                body = bytearray()
                while len(body) <= MAX_FILE:
                    chunk = os.read(fd, min(8192, MAX_FILE + 1 - len(body)))
                    if not chunk:
                        break
                    body.extend(chunk)
                total += len(body)
                if total > MAX_TOTAL:
                    raise LimitReached()
                if len(body) > MAX_FILE:
                    diagnostics.add('LIMIT_EXCEEDED')
                    continue
                parsed = tomllib.loads(body.decode('utf-8', errors='strict'))
                required = [parsed.get(key) for key in ('name', 'description', 'developer_instructions')]
                if any(not isinstance(value, str) or not value.strip() for value in required) or len(required[0]) > 512:
                    diagnostics.add('DISCOVERY_FAILED')
                    continue
                identity = root + '/.codex/agents/' + name
                if identity in seen:
                    continue
                # Full private resolution is a separate server-only operation, never the inventory DTO.
                if request.get('resolveId') == hashlib.sha256(('codex\0agent-file\0' + identity).encode()).hexdigest():
                    allowed = {'name', 'description', 'developer_instructions', 'model', 'model_reasoning_effort'}
                    if set(parsed) - allowed:
                        raise ValueError('Unsupported definition configuration')
                    if any(key in parsed and (not isinstance(parsed[key], str) or not parsed[key].strip()) for key in ('model','model_reasoning_effort')):
                        raise ValueError('Invalid model configuration')
                    resolved.append({'identity': identity, 'name': required[0], 'instructions': required[2],
                                     'model': parsed.get('model'), 'effort': parsed.get('model_reasoning_effort'),
                                     'fingerprint': hashlib.sha256(body).hexdigest()})
                # Instructions, model settings and unknown configuration never leave this reader.
                item = {'name': required[0].strip(), 'description': required[1].strip()[:2000], 'identity': identity}
                encoded = json.dumps(item, ensure_ascii=False).encode('utf-8')
                output_bytes += len(encoded) + 2
                if output_bytes > MAX_OUTPUT - 1024:
                    raise LimitReached()
                seen.add(identity)
                items.append(item)
            except LimitReached:
                raise
            except OSError as failure:
                diagnostics.add('UNSAFE_RESOURCE' if failure.errno in (errno.ELOOP, errno.ENOTDIR) else 'DISCOVERY_FAILED')
            except (ValueError, UnicodeError, RecursionError):
                diagnostics.add('DISCOVERY_FAILED')
            finally:
                if fd is not None:
                    os.close(fd)
    finally:
        for fd in (agents_fd, codex_fd, root_fd):
            if fd is not None:
                os.close(fd)


def scan_resources(root, global_source=False):
    global manifest_bytes, manifest_entries
    def read_file(parent, name, identity, agent=False):
        global manifest_bytes
        fd=os.open(name,FLAGS,dir_fd=parent)
        try:
            info=os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                raise ValueError()
            data=os.read(fd,1048577)
            manifest_bytes+=len(data)
            if len(data)>1048576 or manifest_bytes>16777216:
                raise LimitReached()
            if agent:
                parsed=tomllib.loads(data.decode('utf-8',errors='strict'))
                if set(parsed)-{'name','description','developer_instructions','model','model_reasoning_effort'}:
                    raise ValueError()
            manifest[identity]=hashlib.sha256(data).hexdigest()
            if info.st_mode & 0o111:manifest['@executable:'+identity]='executable'
        finally:
            os.close(fd)
    def tree(parent,name,identity,depth=0,agent=False):
        global manifest_entries
        if depth>16: raise LimitReached()
        fd=os.open(name,DIRECTORY,dir_fd=parent)
        try:
            entries=[]
            with os.scandir(fd) as listing:
                for entry in listing:
                    manifest_entries+=1
                    if manifest_entries>10000: raise LimitReached()
                    entries.append(entry)
            entries.sort(key=lambda e:e.name)
            for entry in entries:
                info=entry.stat(follow_symlinks=False)
                child=identity+'/'+entry.name
                if stat.S_ISDIR(info.st_mode):tree(fd,entry.name,child,depth+1,agent)
                elif stat.S_ISREG(info.st_mode):read_file(fd,entry.name,child,agent and entry.name.endswith('.toml'))
                else:raise ValueError()
        finally:
            os.close(fd)
    manifest[('@global:' if global_source else '@project:')+root]='directory'
    fd=directory(root)
    try:
        if not global_source:
            for name in ('AGENTS.md','AGENTS.override.md'):
                try:read_file(fd,name,root+'/'+name)
                except FileNotFoundError:manifest[root+'/'+name]=hashlib.sha256(b'').hexdigest()
        for config in ('.codex','.agents'):
            sub=None
            try:
                sub=os.open(config,DIRECTORY,dir_fd=fd)
                for folder in (('skills','agents') if config=='.codex' else ('skills',)):
                    try:tree(sub,folder,root+'/'+config+'/'+folder,agent=folder=='agents')
                    except FileNotFoundError:pass
                if config=='.codex' and not global_source:
                    try:read_file(sub,'config.toml',root+'/'+config+'/config.toml')
                    except FileNotFoundError:manifest[root+'/.codex/config.toml']=hashlib.sha256(b'').hexdigest()
            except FileNotFoundError:
                if config=='.codex' and not global_source:manifest[root+'/.codex/config.toml']=hashlib.sha256(b'').hexdigest()
            finally:
                if sub is not None:os.close(sub)
    finally:
        os.close(fd)


try:
    request = json.loads(sys.stdin.buffer.read(131073))
    tomllib = verified_parser()
    runtime_compatible = True
    roots = request['roots']
    project = request['projectRoot']
    if not isinstance(project, str) or project == '/' or not project.startswith('/') or '..' in PurePosixPath(project).parts or len(project) > 4096 or str(PurePosixPath(project)) != project:
        raise ValueError()
    if not isinstance(roots, list) or len(roots) > 32:
        raise ValueError()
    for root in roots:
        if (not isinstance(root, str) or len(root) > 4096 or root == '/' or
                not root.startswith('/') or '..' in PurePosixPath(root).parts or str(PurePosixPath(root)) != root or
                not PurePosixPath(root).is_relative_to(PurePosixPath(project))):
            raise ValueError()
    if request.get('resourceManifest'):
        expanded=set(roots)
        for entry in roots:
            current=PurePosixPath(entry)
            while current.is_relative_to(PurePosixPath(project)):
                expanded.add(str(current))
                if str(current)==project:break
                current=current.parent
        roots=sorted(expanded)
    global_home=request.get('globalHome')
    if global_home is not None:
        if (not isinstance(global_home,str) or global_home == '/' or not global_home.startswith('/') or '..' in PurePosixPath(global_home).parts or str(PurePosixPath(global_home)) != global_home or len(global_home)>4096):
            raise ValueError()
        try:
            if request.get('resourceManifest'): scan_resources(global_home,True)
            else: scan(global_home)
        except OSError as failure: diagnostics.add('UNSAFE_RESOURCE' if failure.errno in (errno.ELOOP,errno.ENOTDIR) else 'WORKSPACE_UNAVAILABLE')
    for root in roots:
        try:
            (scan_resources if request.get('resourceManifest') else scan)(root)
        except OSError as failure:
            diagnostics.add('UNSAFE_RESOURCE' if failure.errno in (errno.ELOOP, errno.ENOTDIR) else 'WORKSPACE_UNAVAILABLE')
except RuntimeUnsupported:
    diagnostics.add('RUNTIME_UNAVAILABLE')
except LimitReached:
    diagnostics.add('LIMIT_EXCEEDED')
except Exception:
    diagnostics.add('DISCOVERY_FAILED')
response = {'items': items, 'diagnostics': sorted(diagnostics), 'runtimeCompatible': runtime_compatible,
            **({'resolved': resolved} if request.get('resolveId') else {}),
            **({'manifest': manifest} if request.get('resourceManifest') else {})}
encoded = json.dumps(response, ensure_ascii=False)
if len(encoded.encode('utf-8')) > MAX_OUTPUT:
    response.update(items=[], resolved=[], manifest={}, diagnostics=['LIMIT_EXCEEDED'])
    encoded = json.dumps(response)
print(encoded)
