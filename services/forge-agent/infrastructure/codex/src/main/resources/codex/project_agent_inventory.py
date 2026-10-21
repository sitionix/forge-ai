"""Bounded metadata reader. Project roots come only from Forge's workspace port."""
import errno
import json
import os
from pathlib import PurePosixPath
import stat
import sys
try:
    import tomllib
except ModuleNotFoundError:
    import tomli as tomllib

MAX_FILE = 65536
MAX_TOTAL = 2097152
MAX_OUTPUT = 1048576
MAX_FILES = 512
MAX_ENTRIES = 2048
FLAGS = os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW | os.O_NONBLOCK
DIRECTORY = FLAGS | os.O_DIRECTORY
items = []
diagnostics = set()
seen = set()
files = entries = total = output_bytes = 0


class LimitReached(Exception):
    pass


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


try:
    request = json.loads(sys.stdin.buffer.read(131073))
    roots = request['roots']
    project = request['projectRoot']
    if not isinstance(project, str) or not project.startswith('/') or len(project) > 4096 or str(PurePosixPath(project)) != project:
        raise ValueError()
    if not isinstance(roots, list) or len(roots) > 32:
        raise ValueError()
    for root in roots:
        if (not isinstance(root, str) or len(root) > 4096 or root == '/' or
                not root.startswith('/') or '..' in PurePosixPath(root).parts or str(PurePosixPath(root)) != root or
                not PurePosixPath(root).is_relative_to(PurePosixPath(project))):
            raise ValueError()
        try:
            scan(root)
        except OSError as failure:
            diagnostics.add('UNSAFE_RESOURCE' if failure.errno in (errno.ELOOP, errno.ENOTDIR) else 'WORKSPACE_UNAVAILABLE')
except LimitReached:
    diagnostics.add('LIMIT_EXCEEDED')
except Exception:
    diagnostics.add('DISCOVERY_FAILED')
print(json.dumps({'items': items, 'diagnostics': sorted(diagnostics)}, ensure_ascii=False))
