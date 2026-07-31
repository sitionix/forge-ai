from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any, Mapping, Sequence

import pytest

from knowledge_service.codex_app_server import (
    CodexAppServerClient,
    CodexAppServerEmptyResponse,
    CodexAppServerProtocolError,
    CodexAppServerTimeout,
    CodexAppServerTransportError,
)
from knowledge_service.generative_runtime import (
    CodexGenerativeProvider,
    GenerativeProviderEmptyResponse,
    GenerativeProviderProtocolError,
    GenerativeProviderTimeout,
    GenerativeProviderTransportError,
    GenerativeRequest,
    ResponseMode,
)


def test_initialize_sends_initialized_notification_and_correlates_requests(tmp_path: Path):
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            notification("remoteControl/status/changed", {"status": "disabled"}),
            result({"data": [], "nextCursor": None}),
        ]
    )
    client = _client(process, tmp_path)

    async def exercise():
        version = await client.initialize()
        payload = await client.request("model/list", {"includeHidden": False})
        await client.aclose()
        return version, payload

    version, payload = asyncio.run(exercise())

    assert version == "0.146.0"
    assert payload == {"data": [], "nextCursor": None}
    assert [sent["method"] for sent in process.sent] == ["initialize", "initialized", "model/list"]


def test_json_rpc_error_server_request_malformed_exit_restart_and_idempotent_close(tmp_path: Path):
    error_process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), rpc_error(-32000, "upstream failed")])
    malformed_process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), raw_line(b"{not-json\n")])
    restart_process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": True})])
    processes = [error_process, malformed_process, restart_process]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), request_timeout_seconds=1, runtime_cwd=tmp_path)

    async def exercise():
        with pytest.raises(CodexAppServerTransportError):
            await client.request("model/list")
        error_process.terminate()
        with pytest.raises(CodexAppServerProtocolError):
            await client.request("model/list")
        malformed_process.terminate()
        restarted = await client.request("model/list")
        await client.aclose()
        await client.aclose()
        return restarted

    assert asyncio.run(exercise()) == {"ok": True}
    assert len([sent for sent in restart_process.sent if sent.get("method") == "initialize"]) == 1


def test_server_request_is_not_mistaken_for_pending_response_and_exit_fails_pending(tmp_path: Path):
    process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), server_request("approval/request", {"turnId": "missing"}), defer()])
    client = _client(process, tmp_path)

    async def exercise():
        await client.initialize()
        task = asyncio.create_task(client.request("model/list"))
        while len(process.sent) < 4:
            await asyncio.sleep(0)
        process.terminate()
        with pytest.raises(CodexAppServerTransportError):
            await task
        await client.aclose()

    asyncio.run(exercise())
    assert any("error" in sent and sent.get("id") == 999 for sent in process.sent)


def test_run_turn_envelope_json_mode_effort_and_agent_message_result(tmp_path: Path):
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result(
                {"turnId": "turn-1"},
                notifications=[
                    notification(
                        "item/completed",
                        {"threadId": "thread-1", "turnId": "turn-1", "item": {"type": "agentMessage", "text": "{\"json\":\"{\\\"ok\\\":true}\"}"}},
                    ),
                    notification("turn/completed", {"threadId": "thread-1", "turnId": "turn-1", "status": "completed", "usage": {"inputTokens": 3}}),
                ],
            ),
        ]
    )
    client = _client(process, tmp_path)

    result_payload = asyncio.run(
        client.run_turn(
            prompt="Return JSON",
            model_id="gpt-5.6-luna",
            effort_id="high",
            response_mode=ResponseMode.JSON_OBJECT,
            timeout_seconds=3,
        )
    )

    assert result_payload.raw_text == "{\"ok\":true}"
    assert result_payload.token_usage == {"inputTokens": 3}
    thread_start = process.by_method("thread/start")
    assert thread_start["params"]["ephemeral"] is True
    assert thread_start["params"]["approvalPolicy"] == "never"
    assert thread_start["params"]["sandbox"] == "read-only"
    assert thread_start["params"]["cwd"] == str(tmp_path)
    turn_start = process.by_method("turn/start")
    assert turn_start["params"]["input"] == [{"type": "text", "text": "Return JSON"}]
    assert turn_start["params"]["model"] == "gpt-5.6-luna"
    assert turn_start["params"]["effort"] == "high"
    assert turn_start["params"]["outputSchema"] == {
        "type": "object",
        "properties": {
            "json": {
                "type": "string",
                "description": "The complete JSON object requested by the user, serialized as a JSON string. The string itself must parse as a JSON object.",
            }
        },
        "required": ["json"],
        "additionalProperties": False,
    }


def test_run_turn_text_omits_effort_and_output_schema(tmp_path: Path):
    process = completed_turn_process("plain text")
    client = _client(process, tmp_path)

    payload = asyncio.run(client.run_turn(prompt="Plain", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))

    assert payload.raw_text == "plain text"
    turn_start = process.by_method("turn/start")
    assert "effort" not in turn_start["params"]
    assert "outputSchema" not in turn_start["params"]


def test_turn_terminal_failures_timeout_cancellation_and_side_effects(tmp_path: Path):
    blank = completed_turn_process("   ")
    with pytest.raises(CodexAppServerEmptyResponse):
        asyncio.run(_client(blank, tmp_path / "blank").run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))

    failed = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result({"turnId": "turn-1"}, notifications=[notification("turn/completed", {"turnId": "turn-1", "status": "failed", "error": {"message": "failed"}})]),
        ]
    )
    with pytest.raises(CodexAppServerTransportError):
        asyncio.run(_client(failed, tmp_path / "failed").run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))

    timeout = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"threadId": "thread-1"}), result({"turnId": "turn-1"}), defer()])
    with pytest.raises(CodexAppServerTimeout):
        asyncio.run(_client(timeout, tmp_path / "timeout").run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=0.05))
    assert [sent["method"] for sent in timeout.sent].count("turn/interrupt") == 1

    side_effect = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result(
                {"turnId": "turn-1"},
                notifications=[notification("item/completed", {"turnId": "turn-1", "item": {"type": "commandExecution", "text": "nope"}})],
            ),
            result({}),
        ]
    )
    with pytest.raises(CodexAppServerProtocolError):
        asyncio.run(_client(side_effect, tmp_path / "side").run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))
    assert [sent["method"] for sent in side_effect.sent].count("turn/interrupt") == 1


def test_cancellation_sends_one_interrupt(tmp_path: Path):
    process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"threadId": "thread-1"}), result({"turnId": "turn-1"}), defer()])
    client = _client(process, tmp_path)

    async def exercise():
        task = asyncio.create_task(client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=5))
        while any(sent.get("method") == "turn/start" for sent in process.sent) is False:
            await asyncio.sleep(0)
        await asyncio.sleep(0.05)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        await client.aclose()

    asyncio.run(exercise())
    assert [sent["method"] for sent in process.sent].count("turn/interrupt") == 1


def test_two_overlapping_turns_route_interleaved_notifications(tmp_path: Path):
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-a"}),
            result({"threadId": "thread-b"}),
            result({"turnId": "turn-a"}),
            result({"turnId": "turn-b"}),
        ]
    )
    client = _client(process, tmp_path)

    async def exercise():
        first = asyncio.create_task(client.run_turn(prompt="A", model_id="m", effort_id="low", response_mode=ResponseMode.TEXT, timeout_seconds=3))
        second = asyncio.create_task(client.run_turn(prompt="B", model_id="m", effort_id="high", response_mode=ResponseMode.TEXT, timeout_seconds=3))
        while len([sent for sent in process.sent if sent.get("method") == "turn/start"]) < 2:
            await asyncio.sleep(0)
        process.push_json({"method": "item/completed", "params": {"turnId": "turn-b", "item": {"type": "agentMessage", "text": "second"}}})
        process.push_json({"method": "item/completed", "params": {"turnId": "turn-a", "item": {"type": "agentMessage", "text": "first"}}})
        process.push_json({"method": "turn/completed", "params": {"turnId": "turn-b", "status": "completed"}})
        process.push_json({"method": "turn/completed", "params": {"turnId": "turn-a", "status": "completed"}})
        return await first, await second

    first, second = asyncio.run(exercise())
    assert first.raw_text == "first"
    assert second.raw_text == "second"


def test_codex_generative_provider_sync_async_hashes_metadata_and_error_mapping(tmp_path: Path):
    sync_process = completed_turn_process("answer")
    provider = CodexGenerativeProvider(_client(sync_process, tmp_path / "sync"), timeout_seconds=3)

    response = provider.generate(GenerativeRequest(prompt="prompt", model_id="m", effort_id="low"))

    assert response.raw_text == "answer"
    assert response.provider_id == "codex"
    assert response.model_id == "m"
    assert response.prompt_char_length == 6
    assert response.response_char_length == 6
    assert response.prompt_hash == "cf07194ee232eb531e15f690000d19846dea69cf05504782658afcfacb9228a2"
    assert response.response_hash == "0db52f4076c082518412afd3dd3576e2cb0c63703fd7fed5e23ade60efef31d9"
    assert set(response.provider_metadata) <= {"threadId", "turnId", "turnStatus", "requestedEffort"}
    assert response.provider_metadata["requestedEffort"] == "low"

    async_process = completed_turn_process("async")
    async_provider = CodexGenerativeProvider(_client(async_process, tmp_path / "async"), timeout_seconds=3)
    async_response = asyncio.run(async_provider.generate_async(GenerativeRequest(prompt="p", model_id="m")))
    assert async_response.raw_text == "async"

    with pytest.raises(GenerativeProviderEmptyResponse):
        CodexGenerativeProvider(_client(completed_turn_process(""), tmp_path / "empty"), timeout_seconds=3).generate(GenerativeRequest(prompt="p", model_id="m"))
    with pytest.raises(GenerativeProviderTimeout):
        CodexGenerativeProvider(_client(FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), defer()]), tmp_path / "timeout-provider"), timeout_seconds=0.01).generate(
            GenerativeRequest(prompt="p", model_id="m")
        )
    with pytest.raises(GenerativeProviderProtocolError):
        CodexGenerativeProvider(
            _client(
                FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"threadId": "t"}), result({"turnId": "u"}, notifications=[notification("item/completed", {"turnId": "u", "item": {"type": "fileChange"}})]), result({})]),
                tmp_path / "protocol-provider",
            ),
            timeout_seconds=3,
        ).generate(GenerativeRequest(prompt="p", model_id="m"))
    with pytest.raises(GenerativeProviderTransportError):
        CodexGenerativeProvider(_client(FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), rpc_error(-1, "bad")]), tmp_path / "transport-provider"), timeout_seconds=3).generate(
            GenerativeRequest(prompt="p", model_id="m")
        )


class FakeCodexProcess:
    def __init__(self, scripted: Sequence[Mapping[str, Any]]) -> None:
        self.stdin = FakeStdin(self)
        self.stdout = FakeStream()
        self.stderr = FakeStream()
        self.returncode: int | None = None
        self.sent: list[dict[str, Any]] = []
        self.terminated = False
        self._scripted = list(scripted)
        self._wait: asyncio.Future[int | None] | None = None

    def receive(self, data: bytes) -> None:
        for line in data.decode("utf-8").splitlines():
            request = json.loads(line)
            self.sent.append(request)
            if "id" not in request:
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
        self.returncode = 0
        self.stdout.push(b"")
        self.stderr.push(b"")
        self._complete_wait()

    def kill(self) -> None:
        self.terminate()

    async def wait(self) -> int:
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


def _client(process: FakeCodexProcess, runtime_cwd: Path) -> CodexAppServerClient:
    return CodexAppServerClient(process_factory=lambda command: async_value(process), request_timeout_seconds=1, runtime_cwd=runtime_cwd)


def completed_turn_process(text: str) -> FakeCodexProcess:
    return FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result(
                {"turnId": "turn-1"},
                notifications=[
                    notification("item/completed", {"threadId": "thread-1", "turnId": "turn-1", "item": {"type": "agentMessage", "text": text}}),
                    notification("turn/completed", {"threadId": "thread-1", "turnId": "turn-1", "status": "completed"}),
                ],
            ),
        ]
    )


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
