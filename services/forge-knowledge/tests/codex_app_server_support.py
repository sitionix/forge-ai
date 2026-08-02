from __future__ import annotations

import asyncio
import json
from typing import Any, Mapping, Sequence


class FakeCodexProcess:
    def __init__(self, scripted: Sequence[Mapping[str, Any]]) -> None:
        self.stdin = FakeStdin(self)
        self.stdout = FakeStream()
        self.stderr = FakeStream()
        self.returncode: int | None = None
        self.sent: list[dict[str, Any]] = []
        self.terminated = False
        self.terminated_at_sent_count: int | None = None
        self.killed = False
        self.wait_calls = 0
        self._scripted = list(scripted)
        self._wait: asyncio.Future[int | None] | None = None

    def receive(self, data: bytes) -> None:
        for line in data.decode("utf-8").splitlines():
            request = json.loads(line)
            self.sent.append(request)
            if "id" not in request or "method" not in request:
                continue
            while self._scripted:
                action = dict(self._scripted.pop(0))
                if action.get("defer"):
                    break
                if "raw" in action:
                    self.stdout.push(action["raw"])
                    continue
                if "exit" in action:
                    self.returncode = int(action["exit"])
                    self.stdout.push(b"")
                    self._complete_wait()
                    break
                if action.get("server_request"):
                    self.push_json({"id": 999, "method": action["method"], "params": action.get("params", {})})
                    continue
                if "method" in action:
                    self.push_json(action)
                    continue
                if "error" in action:
                    self.push_json({"id": request["id"], "error": action["error"]})
                    break
                self.push_json({"id": request["id"], "result": action.get("result", {})})
                for emitted in action.get("notifications", []):
                    self.push_json(emitted)
                break

    def push_json(self, payload: Mapping[str, Any]) -> None:
        self.stdout.push(json.dumps(payload).encode("utf-8") + b"\n")

    def by_method(self, method: str) -> dict[str, Any]:
        for sent in self.sent:
            if sent.get("method") == method:
                return sent
        raise AssertionError(f"method not sent: {method}")

    def terminate(self) -> None:
        self.terminated = True
        if self.terminated_at_sent_count is None:
            self.terminated_at_sent_count = len(self.sent)
        self.returncode = 0
        self.stdout.push(b"")
        self.stderr.push(b"")
        self._complete_wait()

    def kill(self) -> None:
        self.killed = True
        self.terminate()

    async def wait(self) -> int:
        self.wait_calls += 1
        if self.returncode is not None:
            return self.returncode
        if self._wait is None:
            self._wait = asyncio.get_running_loop().create_future()
        return await self._wait

    def _complete_wait(self) -> None:
        if self._wait is not None and not self._wait.done():
            self._wait.set_result(self.returncode)


class FakeStdin:
    def __init__(self, process: FakeCodexProcess) -> None:
        self._process = process

    def write(self, data: bytes) -> None:
        self._process.receive(data)

    async def drain(self) -> None:
        return None


class FakeStream:
    def __init__(self) -> None:
        self._queue: asyncio.Queue[bytes] = asyncio.Queue()
        self._loop: asyncio.AbstractEventLoop | None = None

    async def readline(self) -> bytes:
        self._loop = asyncio.get_running_loop()
        return await self._queue.get()

    def push(self, data: bytes) -> None:
        if self._loop is not None and self._loop.is_running():
            self._loop.call_soon_threadsafe(self._queue.put_nowait, data)
        else:
            self._queue.put_nowait(data)


def result(payload: Mapping[str, Any], *, notifications: Sequence[Mapping[str, Any]] = ()) -> dict[str, Any]:
    return {"result": dict(payload), "notifications": [dict(item) for item in notifications]}


def notification(method: str, params: Mapping[str, Any]) -> dict[str, Any]:
    return {"method": method, "params": dict(params)}


def server_request(method: str, params: Mapping[str, Any]) -> dict[str, Any]:
    return {"server_request": True, "method": method, "params": dict(params)}


def rpc_error(code: int, message: str) -> dict[str, Any]:
    return {"error": {"code": code, "message": message}}


def raw_line(value: bytes) -> dict[str, Any]:
    return {"raw": value}


def defer() -> dict[str, Any]:
    return {"defer": True}


async def async_value(value: Any) -> Any:
    return value
