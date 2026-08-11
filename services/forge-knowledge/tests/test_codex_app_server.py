from __future__ import annotations

import asyncio
import json
import threading
import time
from pathlib import Path
from typing import Any, Mapping, Sequence

import pytest
from codex_app_server_support import FakeCodexProcess, FakeStream, async_value, defer, notification, raw_line, result, rpc_error, server_request

from knowledge_service.active_profile import ActiveLlmRuntime, ActiveProfileService, ActiveProfileStore, LlmUsageRegistry
from knowledge_service.ai_runtime_discovery import AiRuntimeDiscoveryRegistry, AiRuntimeDiscoveryService, CodexAiRuntimeOptionsSource
from knowledge_service.codex_app_server import (
    CodexAppServerClient,
    CodexAppServerEmptyResponse,
    CodexAppServerError,
    CodexAppServerLifecycleError,
    CodexAppServerProtocolError,
    CodexAppServerRemoteError,
    CodexAppServerTimeout,
    CodexAppServerTransportError,
    CodexNotificationBufferPolicy,
    CodexProcessTransport,
    CodexProtocol,
    CodexRuntimeSettings,
    CodexTurnResult,
)
from knowledge_service.codex_usage import CodexLlmUsageSource
from knowledge_service.config import CodexAppServerSettings
from knowledge_service.generative_runtime import (
    CodexGenerativeProvider,
    GenerativeProviderEmptyResponse,
    GenerativeProviderProtocolError,
    GenerativeProviderRegistry,
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


def test_json_rpc_remote_error_is_local_but_malformed_restarts_and_idempotent_close(tmp_path: Path):
    malformed_process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            {"error": {"code": -32000, "message": "secret upstream failed", "data": {"status": 429}}},
            raw_line(b"{not-json\n"),
        ]
    )
    restart_process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": True})])
    processes = [malformed_process, restart_process]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path))

    async def exercise():
        with pytest.raises(CodexAppServerRemoteError) as remote_exc:
            await client.request("model/list")
        assert remote_exc.value.error_code == -32000
        assert remote_exc.value.status_code == 429
        assert "secret" not in str(remote_exc.value)
        assert malformed_process.terminated is False
        with pytest.raises(CodexAppServerProtocolError):
            await client.request("model/list")
        restarted = await client.request("model/list")
        await client.aclose()
        await client.aclose()
        return restarted

    assert asyncio.run(exercise()) == {"ok": True}
    assert malformed_process.terminated is True
    assert len([sent for sent in restart_process.sent if sent.get("method") == "initialize"]) == 1


def test_server_request_is_not_mistaken_for_pending_response_and_exit_fails_pending(tmp_path: Path):
    process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), server_request("workspace/doThing", {"turnId": "missing"}), defer()])
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


def test_matched_response_missing_result_error_fails_immediately_and_reaps(tmp_path: Path):
    process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), raw_line(b'{"id":2}\n')])
    client = CodexAppServerClient(
        process_factory=lambda command: async_value(process),
        settings=_settings(tmp_path, request_timeout_seconds=30),
    )

    async def exercise():
        await client.initialize()
        started = time.monotonic()
        with pytest.raises(CodexAppServerProtocolError):
            await client.request("model/list")
        await client.aclose()
        return time.monotonic() - started

    elapsed = asyncio.run(exercise())

    assert elapsed < 1.0
    assert process.terminated is True


def test_matched_response_with_result_and_error_fails_immediately_and_reaps(tmp_path: Path):
    process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), raw_line(b'{"id":2,"result":{},"error":{"message":"bad"}}\n')])
    client = _client(process, tmp_path)

    async def exercise():
        await client.initialize()
        with pytest.raises(CodexAppServerProtocolError):
            await client.request("model/list")
        await client.aclose()

    asyncio.run(exercise())

    assert process.terminated is True


@pytest.mark.parametrize("bad_line", [b'{"id":true,"result":{}}\n', b'{"id":"2","result":{}}\n'])
def test_response_id_bool_and_string_are_rejected(tmp_path: Path, bad_line: bytes):
    process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), raw_line(bad_line)])
    client = _client(process, tmp_path)

    async def exercise():
        await client.initialize()
        with pytest.raises(CodexAppServerProtocolError):
            await client.request("model/list")
        await client.aclose()

    asyncio.run(exercise())
    assert process.terminated is True


def test_server_request_id_bool_is_rejected_and_reaps_process(tmp_path: Path):
    process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), raw_line(b'{"id":true,"method":"workspace/doThing","params":{}}\n')])
    client = _client(process, tmp_path)

    async def exercise():
        await client.initialize()
        with pytest.raises(CodexAppServerProtocolError):
            await client.request("model/list")
        await client.aclose()

    asyncio.run(exercise())
    assert process.terminated is True


def test_stdout_eof_marks_connection_failed_and_next_request_restarts(tmp_path: Path):
    first_process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), {"exit": 0}])
    second_process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": True})])
    processes = [first_process, second_process]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path))

    async def exercise():
        with pytest.raises(CodexAppServerTransportError):
            await client.request("model/list")
        payload = await client.request("model/list")
        await client.aclose()
        return payload

    assert asyncio.run(exercise()) == {"ok": True}
    assert [sent.get("method") for sent in first_process.sent] == ["initialize", "initialized", "model/list"]
    assert len([sent for sent in second_process.sent if sent.get("method") == "initialize"]) == 1


def test_stalled_stdin_drain_during_initialize_is_bounded_reaped_and_restarts(tmp_path: Path):
    stalled = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"})])
    stalled.stdin.block_on_drain_call = 1
    restarted = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": True})])
    processes = [stalled, restarted]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path, request_timeout_seconds=0.05))

    async def exercise():
        started = time.monotonic()
        with pytest.raises(CodexAppServerTransportError):
            await client.initialize()
        elapsed = time.monotonic() - started
        payload = await client.request(CodexProtocol.MODEL_LIST)
        await client.aclose()
        await client.aclose()
        await _assert_no_codex_tasks()
        return elapsed, payload

    elapsed, payload = asyncio.run(exercise())

    assert elapsed < 0.5
    assert stalled.terminated is True
    assert payload == {"ok": True}
    assert restarted.by_method(CodexProtocol.INITIALIZE)["method"] == CodexProtocol.INITIALIZE


def test_stalled_stdin_drain_during_normal_request_is_bounded_reaped_and_restarts(tmp_path: Path):
    stalled = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": False})])
    stalled.stdin.block_on_drain_call = 3
    restarted = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": True})])
    processes = [stalled, restarted]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path, request_timeout_seconds=0.05))

    async def exercise():
        with pytest.raises(CodexAppServerTransportError):
            await client.request(CodexProtocol.MODEL_LIST)
        payload = await client.request(CodexProtocol.MODEL_LIST)
        await client.aclose()
        await _assert_no_codex_tasks()
        return payload

    assert asyncio.run(exercise()) == {"ok": True}
    assert stalled.terminated is True
    assert restarted.by_method(CodexProtocol.MODEL_LIST)["method"] == CodexProtocol.MODEL_LIST


def test_broken_pipe_during_request_write_is_controlled_reaped_and_restarts(tmp_path: Path):
    broken = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"})])
    broken.stdin.fail_methods.add(CodexProtocol.MODEL_LIST)
    restarted = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": True})])
    processes = [broken, restarted]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path))

    async def exercise():
        with pytest.raises(CodexAppServerTransportError):
            await client.request(CodexProtocol.MODEL_LIST)
        payload = await client.request(CodexProtocol.MODEL_LIST)
        await client.aclose()
        await _assert_no_codex_tasks()
        return payload

    assert asyncio.run(exercise()) == {"ok": True}
    assert broken.terminated is True


def test_default_process_factory_passes_configured_stdio_stream_limit(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    captured: dict[str, Any] = {}
    process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": True})])

    async def fake_create_subprocess_exec(*command: str, **kwargs: Any) -> FakeCodexProcess:
        captured["command"] = command
        captured["kwargs"] = kwargs
        return process

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_create_subprocess_exec)
    settings = _settings(tmp_path, stdio_stream_limit_bytes=123_456)
    client = CodexAppServerClient(settings=settings)

    async def exercise():
        payload = await client.request(CodexProtocol.MODEL_LIST)
        await client.aclose()
        return payload

    assert asyncio.run(exercise()) == {"ok": True}
    assert captured["command"] == settings.command
    assert captured["kwargs"]["limit"] == 123_456


def test_large_valid_stdout_frame_is_read_parsed_and_keeps_process_healthy(tmp_path: Path):
    large_value = "safe-large-value-" + ("x" * 100_000)
    process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"value": large_value}), result({"ok": True})])
    client = _client(process, tmp_path)

    async def exercise():
        payload = await client.request(CodexProtocol.MODEL_LIST)
        second_payload = await client.request(CodexProtocol.MODEL_LIST)
        await client.aclose()
        await _assert_no_codex_tasks()
        return payload, second_payload

    payload, second_payload = asyncio.run(exercise())

    assert payload == {"value": large_value}
    assert second_payload == {"ok": True}
    assert process.terminated_at_sent_count is None or process.terminated_at_sent_count >= len(process.sent)


def test_large_nested_turn_start_envelope_registers_turn_and_completes(tmp_path: Path):
    large_metadata = "safe-large-value-" + ("x" * 100_000)
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"thread": {"id": "thread-1"}, "metadata": large_metadata}),
            result(
                {"turn": {"id": "turn-1", "status": "running", "metadata": large_metadata}},
                notifications=[
                    notification("item/completed", {"turn": {"id": "turn-1"}, "item": {"type": "agentMessage", "text": "final text"}}),
                    notification("turn/completed", {"turn": {"id": "turn-1", "status": "completed", "metadata": large_metadata}}),
                ],
            ),
        ]
    )
    client = _client(process, tmp_path)

    async def exercise():
        turn = await client.run_turn(
            prompt="prompt",
            model_id="gpt-test",
            effort_id="high",
            response_mode=ResponseMode.TEXT,
            timeout_seconds=1,
        )
        await client.aclose()
        await _assert_no_codex_tasks()
        return turn

    turn = asyncio.run(exercise())

    assert turn.raw_text == "final text"
    assert turn.thread_id == "thread-1"
    assert turn.turn_id == "turn-1"
    assert process.by_method(CodexProtocol.TURN_START)["method"] == CodexProtocol.TURN_START


class LimitOverrunStream(FakeStream):
    def __init__(self, *, normal_reads_before_overrun: int = 0) -> None:
        super().__init__()
        self.read_started = threading.Event()
        self.release_overrun = threading.Event()
        self._normal_reads_before_overrun = normal_reads_before_overrun

    async def readline(self) -> bytes:
        if self._normal_reads_before_overrun > 0:
            self._normal_reads_before_overrun -= 1
            return await super().readline()
        self.read_started.set()
        await asyncio.to_thread(self.release_overrun.wait)
        raise ValueError("Separator is found, but chunk is longer than limit")


def test_stdio_frame_limit_overflow_is_classified_reaped_and_restarts_without_payload_leak(
    tmp_path: Path,
    caplog: pytest.LogCaptureFixture,
):
    payload_marker = "secret-prompt-or-payload-" + ("x" * 100_000)
    broken = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"})])
    broken.stdout = LimitOverrunStream(normal_reads_before_overrun=1)
    restarted = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": True})])
    processes = [broken, restarted]
    client = CodexAppServerClient(
        process_factory=lambda command: async_value(processes.pop(0)),
        settings=_settings(tmp_path, stdio_stream_limit_bytes=4096),
    )

    async def exercise():
        await client.initialize()
        request_task = asyncio.create_task(client.request(CodexProtocol.MODEL_LIST, {"prompt": payload_marker}))
        while not any(sent.get("method") == CodexProtocol.MODEL_LIST for sent in broken.sent):
            await asyncio.sleep(0)
        assert await asyncio.to_thread(broken.stdout.read_started.wait, 1.0) is True
        broken.stdout.release_overrun.set()
        with pytest.raises(CodexAppServerTransportError) as exc_info:
            await request_task
        restarted_payload = await client.request(CodexProtocol.MODEL_LIST)
        await client.aclose()
        await _assert_no_codex_tasks()
        return exc_info.value, restarted_payload

    exc, restarted_payload = asyncio.run(exercise())

    assert exc.error_code == "CODEX_STDIO_FRAME_TOO_LARGE"
    assert str(exc) == "Codex app-server stdout JSON-RPC frame exceeded configured limit"
    assert exc.details["stream"] == "stdout"
    assert exc.details["configuredLimitBytes"] == 4096
    assert exc.details["exceptionClass"] == "ValueError"
    assert payload_marker not in str(exc)
    assert payload_marker not in caplog.text
    assert broken.terminated is True
    assert restarted_payload == {"ok": True}


def test_large_stderr_line_below_configured_limit_does_not_crash_reader(tmp_path: Path):
    process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": True})])

    async def process_factory(command: Sequence[str]) -> FakeCodexProcess:
        process.stderr.push(("diagnostic-" + ("x" * 100_000) + "\n").encode("utf-8"))
        return process

    client = CodexAppServerClient(process_factory=process_factory, settings=_settings(tmp_path))

    async def exercise():
        payload = await client.request(CodexProtocol.MODEL_LIST)
        await client.aclose()
        await _assert_no_codex_tasks()
        return payload

    assert asyncio.run(exercise()) == {"ok": True}


def test_permission_error_during_process_creation_is_controlled_and_provider_generic(tmp_path: Path):
    async def denied(command: Sequence[str]) -> Any:
        raise PermissionError("secret-token-123")

    client = CodexAppServerClient(process_factory=denied, settings=_settings(tmp_path))

    async def direct_failure():
        with pytest.raises(CodexAppServerTransportError) as exc_info:
            await client.request(CodexProtocol.MODEL_LIST)
        await client.aclose()
        return str(exc_info.value)

    message = asyncio.run(direct_failure())
    assert "secret-token-123" not in message

    provider_client = CodexAppServerClient(process_factory=denied, settings=_settings(tmp_path / "provider"))
    provider = CodexGenerativeProvider(provider_client, timeout_seconds=0.5)
    with pytest.raises(GenerativeProviderTransportError) as provider_exc:
        provider.generate(GenerativeRequest(prompt="x", model_id="m"))
    provider_client.close()
    assert str(provider_exc.value) == "codex generation transport error"


class RuntimeErrorStream(FakeStream):
    def __init__(self) -> None:
        super().__init__()
        self.read_started = threading.Event()

    async def readline(self) -> bytes:
        self.read_started.set()
        raise RuntimeError("secret-token-stdout")


def test_unexpected_stdout_reader_failure_is_controlled_reaped_and_restarts(tmp_path: Path):
    broken = FakeCodexProcess([])
    broken.stdout = RuntimeErrorStream()
    restarted = FakeCodexProcess([result({"ok": True})])
    processes = [broken, restarted]
    settings = _settings(tmp_path)

    async def ignore_notification(method: str, params: Any) -> None:
        return None

    async def reject_server_request(request_id: int, method: str, params: Any) -> Any:
        raise AssertionError(f"unexpected server request: {request_id} {method}")

    transport = CodexProcessTransport(
        settings,
        process_factory=lambda command: async_value(processes.pop(0)),
        on_notification=ignore_notification,
        on_server_request=reject_server_request,
        on_connection_failed=lambda exc: None,
    )

    async def exercise():
        request_task = asyncio.create_task(transport.request(CodexProtocol.MODEL_LIST))
        assert await asyncio.to_thread(broken.stdout.read_started.wait, 1.0) is True
        with pytest.raises(CodexAppServerTransportError) as exc_info:
            await request_task
        payload = await transport.request(CodexProtocol.MODEL_LIST)
        await transport.stop(CodexAppServerTransportError("test complete"))
        await _assert_no_codex_tasks()
        return str(exc_info.value), payload

    message, payload = asyncio.run(exercise())

    assert "secret-token-stdout" not in message
    assert broken.terminated is True
    assert payload == {"ok": True}


@pytest.mark.parametrize(
    ("first_script", "mode"),
    [
        ([result({"userAgent": "forge-knowledge/0.146.0"}), result({})], ResponseMode.TEXT),
        ([result({"userAgent": "forge-knowledge/0.146.0"}), result({"threadId": "thread-1"}), result({})], ResponseMode.TEXT),
        (
            [
                result({"userAgent": "forge-knowledge/0.146.0"}),
                result(
                    {"threadId": "thread-1"},
                    notifications=[notification("turn/completed", {"turnId": "turn-1"})],
                ),
                result({"turnId": "turn-1"}),
            ],
            ResponseMode.TEXT,
        ),
        (
            [
                result({"userAgent": "forge-knowledge/0.146.0"}),
                result(
                    {"threadId": "thread-1"},
                    notifications=[
                        notification("item/completed", {"turnId": "turn-1", "item": {"type": "agentMessage", "text": "{\"json\":\"[]\"}"}}),
                        notification("turn/completed", {"turnId": "turn-1", "status": "completed"}),
                    ],
                ),
                result({"turnId": "turn-1"}),
            ],
            ResponseMode.JSON_OBJECT,
        ),
    ],
)
def test_method_level_protocol_failure_invalidates_reaps_and_restarts(tmp_path: Path, first_script: list[Mapping[str, Any]], mode: ResponseMode):
    first_process = FakeCodexProcess(first_script)
    second_process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": True})])
    processes = [first_process, second_process]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path))

    async def exercise():
        with pytest.raises(CodexAppServerProtocolError):
            await client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=mode, timeout_seconds=3)
        payload = await client.request("model/list")
        await client.aclose()
        return payload

    assert asyncio.run(exercise()) == {"ok": True}
    assert first_process.terminated is True
    assert first_process.wait_calls >= 1
    assert second_process.by_method("initialize")["method"] == "initialize"


def test_expired_buffered_turn_event_during_method_replay_invalidates_reaps_and_restarts(tmp_path: Path):
    first_process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result(
                {"threadId": "thread-1"},
                notifications=[notification("item/completed", {"turnId": "turn-1", "item": {"type": "agentMessage", "text": "expired"}})],
            ),
            defer(),
        ]
    )
    second_process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": True})])
    processes = [first_process, second_process]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path, notification_buffer=CodexNotificationBufferPolicy(max_per_turn=10, max_turn_ids=10, max_age_seconds=0.01)))

    async def exercise():
        task = asyncio.create_task(client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))
        while not any(sent.get("method") == "turn/start" for sent in first_process.sent):
            await asyncio.sleep(0)
        await asyncio.sleep(0.05)
        first_process.push_json({"id": 3, "result": {"turnId": "turn-1"}})
        with pytest.raises(CodexAppServerProtocolError):
            await task
        payload = await client.request("model/list")
        await client.aclose()
        return payload

    assert asyncio.run(exercise()) == {"ok": True}
    assert first_process.terminated is True
    assert first_process.wait_calls >= 1


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
                    notification("turn/completed", {"threadId": "thread-1", "turnId": "turn-1", "status": "completed", "tokenUsage": {"inputTokens": 3}}),
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


def test_run_turn_accepts_nested_thread_start_result(tmp_path: Path):
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"thread": {"id": "thread-nested"}}),
            result(
                {"turn": {"id": "turn-nested"}},
                notifications=[
                    notification("item/completed", {"threadId": "thread-nested", "turn": {"id": "turn-nested"}, "item": {"type": "agentMessage", "text": "nested"}}),
                    notification("turn/completed", {"threadId": "thread-nested", "turn": {"id": "turn-nested", "status": "completed"}}),
                ],
            ),
        ]
    )
    client = _client(process, tmp_path)

    payload = asyncio.run(client.run_turn(prompt="Plain", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))

    assert payload.raw_text == "nested"
    assert process.by_method(CodexProtocol.TURN_START)["params"]["threadId"] == "thread-nested"


def test_run_turn_accepts_equal_flat_and_nested_envelope_ids_and_status(tmp_path: Path):
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1", "thread": {"id": "thread-1", "sessionId": "thread-1"}}),
            result(
                {"turnId": "turn-1", "turn": {"id": "turn-1"}},
                notifications=[
                    notification(
                        "item/completed",
                        {
                            "turnId": "turn-1",
                            "item": {
                                "turnId": "turn-1",
                                "turn": {"id": "turn-1"},
                                "type": "agentMessage",
                                "text": "equal aliases",
                            },
                        },
                    ),
                    notification("turn/completed", {"turnId": "turn-1", "status": "completed", "turn": {"id": "turn-1", "status": "completed"}}),
                ],
            ),
        ]
    )

    payload = asyncio.run(_client(process, tmp_path).run_turn(prompt="Plain", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))

    assert payload.raw_text == "equal aliases"
    assert payload.thread_id == "thread-1"
    assert payload.turn_id == "turn-1"


@pytest.mark.parametrize(
    "script",
    [
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-flat", "thread": {"id": "thread-nested"}}),
        ],
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result({"turnId": "turn-flat", "turn": {"id": "turn-nested"}}),
        ],
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result(
                {"turnId": "turn-1"},
                notifications=[
                    notification("item/completed", {"turnId": "turn-1", "item": {"turnId": "turn-2", "type": "agentMessage", "text": "bad"}}),
                ],
            ),
        ],
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result(
                {"turnId": "turn-1"},
                notifications=[
                    notification("item/completed", {"turnId": "turn-1", "item": {"turn": {"id": "turn-2"}, "type": "agentMessage", "text": "bad"}}),
                ],
            ),
        ],
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result(
                {"turnId": "turn-1"},
                notifications=[
                    notification("item/completed", {"turnId": "turn-1", "item": {"type": "agentMessage", "text": "bad"}}),
                    notification("turn/completed", {"turnId": "turn-1", "status": "completed", "turn": {"id": "turn-1", "status": "failed"}}),
                ],
            ),
        ],
    ],
)
def test_conflicting_nested_codex_envelope_ids_or_status_fail_closed_and_restart(tmp_path: Path, script: list[Mapping[str, Any]]):
    corrupted = FakeCodexProcess(script)
    restarted = completed_turn_process("fresh")
    processes = [corrupted, restarted]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path))

    async def exercise():
        with pytest.raises(CodexAppServerProtocolError):
            await client.run_turn(prompt="bad", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3)
        fresh = await client.run_turn(prompt="fresh", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3)
        await client.aclose()
        await _assert_no_codex_tasks()
        return fresh

    fresh = asyncio.run(exercise())

    assert corrupted.terminated is True
    assert corrupted.wait_calls >= 1
    assert fresh.raw_text == "fresh"
    assert restarted.by_method(CodexProtocol.INITIALIZE)["method"] == CodexProtocol.INITIALIZE


def test_conflicting_turn_identity_cannot_complete_or_mutate_concurrent_turn(tmp_path: Path):
    corrupted = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-a"}),
            result({"threadId": "thread-b"}),
            result({"turnId": "turn-a"}),
            result({"turnId": "turn-b"}),
        ]
    )
    restarted = completed_turn_process("fresh")
    processes = [corrupted, restarted]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path))

    async def exercise():
        first = asyncio.create_task(client.run_turn(prompt="A", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))
        second = asyncio.create_task(client.run_turn(prompt="B", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))
        while len([sent for sent in corrupted.sent if sent.get("method") == CodexProtocol.TURN_START]) < 2:
            await asyncio.sleep(0)
        corrupted.push_json(
            {
                "method": CodexProtocol.ITEM_COMPLETED,
                "params": {"turnId": "turn-a", "item": {"turnId": "turn-b", "type": "agentMessage", "text": "poison"}},
            }
        )
        failures = await asyncio.gather(first, second, return_exceptions=True)
        fresh = await client.run_turn(prompt="fresh", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3)
        await client.aclose()
        await _assert_no_codex_tasks()
        return failures, fresh

    failures, fresh = asyncio.run(exercise())

    assert all(isinstance(failure, CodexAppServerProtocolError) for failure in failures)
    assert fresh.raw_text == "fresh"
    assert fresh.raw_text != "poison"
    assert corrupted.terminated is True
    assert restarted.by_method(CodexProtocol.INITIALIZE)["method"] == CodexProtocol.INITIALIZE


@pytest.mark.parametrize(
    "notifications",
    [
        [notification("item/completed", {"turnId": "turn-1", "item": {"type": "agentMessage", "text": "final"}}), notification("turn/completed", {"turnId": "turn-1"})],
        [notification("turn/completed", {"turnId": "turn-1"})],
        [notification("item/completed", {"turnId": "turn-1", "item": {"type": "agentMessage", "text": "final"}}), notification("turn/completed", {"turnId": "turn-1", "status": "done"})],
    ],
)
def test_missing_or_unknown_turn_status_is_rejected(tmp_path: Path, notifications: Sequence[Mapping[str, Any]]):
    process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"threadId": "thread-1"}), result({"turnId": "turn-1"}, notifications=notifications)])

    with pytest.raises(CodexAppServerProtocolError):
        asyncio.run(_client(process, tmp_path).run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))


@pytest.mark.parametrize(
    "raw_text",
    [
        "not-json",
        "[]",
        "{\"json\":\"{}\",\"extra\":true}",
        "{}",
        "{\"json\":{}}",
        "{\"json\":\"not-json\"}",
        "{\"json\":\"[]\"}",
    ],
)
def test_json_object_contract_rejects_malformed_wrappers(tmp_path: Path, raw_text: str):
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result(
                {"turnId": "turn-1"},
                notifications=[
                    notification("item/completed", {"turnId": "turn-1", "item": {"type": "agentMessage", "text": raw_text}}),
                    notification("turn/completed", {"turnId": "turn-1", "status": "completed"}),
                ],
            ),
        ]
    )

    with pytest.raises(CodexAppServerProtocolError):
        asyncio.run(_client(process, tmp_path).run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.JSON_OBJECT, timeout_seconds=3))


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
    with pytest.raises(CodexAppServerRemoteError):
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


@pytest.mark.parametrize("item_type", ["commandExecution", "fileChange", "webSearch", "futureThing"])
def test_item_started_forbidden_or_unknown_fails_closed_and_interrupts_once(tmp_path: Path, item_type: str):
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result({"turnId": "turn-1"}, notifications=[notification("item/started", {"turnId": "turn-1", "item": {"type": item_type}})]),
            result({}),
        ]
    )

    with pytest.raises(CodexAppServerProtocolError):
        asyncio.run(_client(process, tmp_path / item_type).run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))

    assert [sent["method"] for sent in process.sent].count("turn/interrupt") == 1


def test_safe_item_started_and_completed_events_are_accepted(tmp_path: Path):
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result(
                {"turnId": "turn-1"},
                notifications=[
                    notification("item/started", {"turnId": "turn-1", "item": {"type": "reasoning"}}),
                    notification("item/completed", {"turnId": "turn-1", "item": {"type": "reasoning", "text": "hidden"}}),
                    notification("item/started", {"turnId": "turn-1", "item": {"type": "agentMessage"}}),
                    notification("item/completed", {"turnId": "turn-1", "item": {"type": "agentMessage", "text": "final"}}),
                    notification("turn/completed", {"turnId": "turn-1", "status": "completed"}),
                ],
            ),
        ]
    )

    payload = asyncio.run(_client(process, tmp_path / "safe").run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))

    assert payload.raw_text == "final"


def test_notification_before_turn_registration_replays_normally(tmp_path: Path):
    process = completed_turn_process("buffered")
    payload = asyncio.run(_client_with_buffer(process, tmp_path, max_per_turn=10, max_turn_ids=10, ttl=30).run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))

    assert payload.raw_text == "buffered"


def test_buffered_notification_per_turn_limit_invalidates_connection(tmp_path: Path):
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result(
                {"turnId": "turn-1"},
                notifications=[
                    notification("item/started", {"turnId": "turn-1", "item": {"type": "reasoning"}}),
                    notification("item/started", {"turnId": "turn-1", "item": {"type": "reasoning"}}),
                ],
            ),
        ]
    )

    with pytest.raises(CodexAppServerProtocolError):
        asyncio.run(_client_with_buffer(process, tmp_path, max_per_turn=1, max_turn_ids=10, ttl=30).run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))
    assert process.terminated is True


def test_buffered_notification_global_turn_id_limit_invalidates_connection(tmp_path: Path):
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result(
                {"turnId": "turn-1"},
                notifications=[
                    notification("item/started", {"turnId": "unknown-a", "item": {"type": "reasoning"}}),
                    notification("item/started", {"turnId": "unknown-b", "item": {"type": "reasoning"}}),
                ],
            ),
        ]
    )

    with pytest.raises(CodexAppServerProtocolError):
        asyncio.run(_client_with_buffer(process, tmp_path, max_per_turn=10, max_turn_ids=1, ttl=30).run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))
    assert process.terminated is True


def test_buffer_overflow_emits_no_pending_task_diagnostics(tmp_path: Path):
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result(
                {"turnId": "turn-1"},
                notifications=[
                    notification("item/started", {"turnId": "turn-1", "item": {"type": "reasoning"}}),
                    notification("item/started", {"turnId": "turn-1", "item": {"type": "reasoning"}}),
                ],
            ),
        ]
    )
    client = _client_with_buffer(process, tmp_path, max_per_turn=1, max_turn_ids=10, ttl=30)
    diagnostics: list[dict[str, Any]] = []

    async def exercise():
        loop = asyncio.get_running_loop()
        loop.set_exception_handler(lambda _loop, context: diagnostics.append(dict(context)))
        with pytest.raises(CodexAppServerProtocolError):
            await client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3)
        await client.aclose()

    asyncio.run(exercise())

    assert diagnostics == []


def test_expired_buffered_notifications_are_pruned_deterministically(tmp_path: Path):
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result(
                {"turnId": "turn-1"},
                notifications=[
                    notification("item/started", {"turnId": "expired", "item": {"type": "reasoning"}}),
                ],
            ),
        ]
    )
    client = _client_with_buffer(process, tmp_path, max_per_turn=10, max_turn_ids=1, ttl=0.01)

    async def exercise():
        task = asyncio.create_task(client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))
        while not any(sent.get("method") == "turn/start" for sent in process.sent):
            await asyncio.sleep(0)
        await asyncio.sleep(0.05)
        process.push_json({"method": "item/completed", "params": {"turnId": "turn-1", "item": {"type": "agentMessage", "text": "fresh"}}})
        process.push_json({"method": "turn/completed", "params": {"turnId": "turn-1", "status": "completed"}})
        return await task

    with pytest.raises(CodexAppServerProtocolError):
        asyncio.run(exercise())
    assert process.terminated is True


def test_closed_client_operations_do_not_start_loop_thread_or_process(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    created = 0
    loop_creations = 0
    thread_creations = 0
    original_new_event_loop = asyncio.new_event_loop
    original_thread = threading.Thread

    def counting_new_event_loop():
        nonlocal loop_creations
        loop_creations += 1
        return original_new_event_loop()

    class CountingThread(original_thread):
        def __init__(self, *args, **kwargs):
            nonlocal thread_creations
            thread_creations += 1
            super().__init__(*args, **kwargs)

    monkeypatch.setattr(asyncio, "new_event_loop", counting_new_event_loop)
    monkeypatch.setattr(threading, "Thread", CountingThread)

    async def process_factory(command):
        nonlocal created
        created += 1
        return FakeCodexProcess([])

    client = CodexAppServerClient(process_factory=process_factory, settings=_settings(tmp_path))
    client.close()

    async def exercise():
        with pytest.raises(CodexAppServerTransportError):
            await client.initialize()
        with pytest.raises(CodexAppServerTransportError):
            await client.request("model/list")
        with pytest.raises(CodexAppServerTransportError):
            await client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=1)

    asyncio.run(exercise())
    with pytest.raises(CodexAppServerTransportError):
        client.run_turn_sync(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=1)

    assert created == 0
    assert loop_creations == 0
    assert thread_creations == 0


def test_close_terminate_timeout_kills_process(tmp_path: Path):
    process = TerminateTimeoutProcess([result({"userAgent": "forge-knowledge/0.146.0"})])
    client = _client(process, tmp_path)

    async def initialize():
        await client.initialize()

    asyncio.run(initialize())
    client.close()

    assert process.terminated is True
    assert process.killed is True
    assert process.wait_calls == 2


def test_close_kill_timeout_raises_lifecycle_error_and_stops_loop(tmp_path: Path):
    process = KillTimeoutProcess([result({"userAgent": "forge-knowledge/0.146.0"})])
    client = _client(process, tmp_path)

    async def initialize():
        await client.initialize()

    asyncio.run(initialize())

    with pytest.raises(CodexAppServerLifecycleError):
        client.close()

    assert process.terminated is True
    assert process.killed is True


def test_async_close_terminate_timeout_kills_and_reaps_process(tmp_path: Path):
    process = TerminateTimeoutProcess([result({"userAgent": "forge-knowledge/0.146.0"})])
    client = _client(process, tmp_path)

    async def exercise():
        await client.initialize()
        await client.aclose()

    asyncio.run(exercise())

    assert process.terminated is True
    assert process.killed is True
    assert process.wait_calls == 2


def test_async_close_kill_timeout_retains_ownership_and_retry_cleans_up(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    process = KillTimeoutProcess([result({"userAgent": "forge-knowledge/0.146.0"})])
    created = 0
    loop_creations = 0
    diagnostics: list[dict[str, Any]] = []
    original_new_event_loop = asyncio.new_event_loop

    def recording_new_event_loop():
        nonlocal loop_creations
        loop_creations += 1
        loop = original_new_event_loop()
        loop.set_exception_handler(lambda _loop, context: diagnostics.append(dict(context)))
        return loop

    async def process_factory(command):
        nonlocal created
        created += 1
        return process

    monkeypatch.setattr(asyncio, "new_event_loop", recording_new_event_loop)
    client = CodexAppServerClient(process_factory=process_factory, settings=_settings(tmp_path))

    async def exercise():
        await client.initialize()
        with pytest.raises(CodexAppServerLifecycleError):
            await client.aclose()
        with pytest.raises(CodexAppServerLifecycleError):
            await client.request("model/list")
        process.returncode = 0
        process._complete_wait()
        await client.aclose()

    asyncio.run(exercise())

    assert created == 1
    assert loop_creations == 1
    assert process.terminated is True
    assert process.killed is True
    assert diagnostics == []


def test_sync_force_stop_wait_timeout_retains_process_for_later_cleanup(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    process = KillTimeoutProcess([result({"userAgent": "forge-knowledge/0.146.0"})])
    settings = _settings(
        tmp_path,
        terminate_grace_seconds=0.2,
        kill_grace_seconds=0.2,
        cancellation_cleanup_timeout_seconds=0.2,
        sync_close_timeout_seconds=1.0,
    )
    object.__setattr__(settings, "sync_close_timeout_seconds", 0.01)
    created = 0
    loop_creations = 0
    diagnostics: list[dict[str, Any]] = []
    original_new_event_loop = asyncio.new_event_loop

    def recording_new_event_loop():
        nonlocal loop_creations
        loop_creations += 1
        loop = original_new_event_loop()
        loop.set_exception_handler(lambda _loop, context: diagnostics.append(dict(context)))
        return loop

    async def process_factory(command):
        nonlocal created
        created += 1
        return process

    monkeypatch.setattr(asyncio, "new_event_loop", recording_new_event_loop)
    client = CodexAppServerClient(process_factory=process_factory, settings=settings)

    async def initialize():
        await client.initialize()

    asyncio.run(initialize())

    with pytest.raises(CodexAppServerLifecycleError):
        client.close()
    with pytest.raises(CodexAppServerLifecycleError):
        asyncio.run(client.request("model/list"))
    process.returncode = 0
    process._complete_wait()
    client.close()

    assert created == 1
    assert loop_creations == 1
    assert process.terminated is True
    assert process.killed is True
    assert diagnostics == []


def test_sync_close_timeout_forced_cleanup_drains_pending_tasks(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    process = TerminateTimeoutProcess([result({"userAgent": "forge-knowledge/0.146.0"})])
    settings = _settings(
        tmp_path,
        terminate_grace_seconds=0.2,
        kill_grace_seconds=0.2,
        cancellation_cleanup_timeout_seconds=0.2,
        sync_close_timeout_seconds=1.0,
    )
    object.__setattr__(settings, "sync_close_timeout_seconds", 0.01)
    diagnostics: list[dict[str, Any]] = []
    original_new_event_loop = asyncio.new_event_loop

    def recording_new_event_loop():
        loop = original_new_event_loop()
        loop.set_exception_handler(lambda _loop, context: diagnostics.append(dict(context)))
        return loop

    monkeypatch.setattr(asyncio, "new_event_loop", recording_new_event_loop)
    client = CodexAppServerClient(process_factory=lambda command: async_value(process), settings=settings)

    async def initialize():
        await client.initialize()

    asyncio.run(initialize())

    with pytest.raises(CodexAppServerLifecycleError, match="timed out"):
        client.close()
    client.close()

    assert process.terminated is True
    assert process.killed is True
    assert diagnostics == []


def test_reader_cleanup_exception_during_close_is_controlled(tmp_path: Path):
    process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"})])
    process.stdout = RaiseOnCancelStream()
    client = _client(process, tmp_path)

    async def exercise():
        await client.initialize()
        await client.aclose()

    asyncio.run(exercise())

    assert process.terminated is True


def test_settings_reject_close_timeout_that_cannot_cover_cleanup(tmp_path: Path):
    with pytest.raises(ValueError, match="sync_close_timeout_seconds"):
        _settings(
            tmp_path,
            terminate_grace_seconds=1.0,
            kill_grace_seconds=1.0,
            cancellation_cleanup_timeout_seconds=1.0,
            sync_close_timeout_seconds=2.9,
        )


def test_item_completed_forbidden_discards_partial_output_and_interrupts_once(tmp_path: Path):
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result(
                {"turnId": "turn-1"},
                notifications=[
                    notification("item/completed", {"turnId": "turn-1", "item": {"type": "agentMessage", "text": "partial"}}),
                    notification("item/completed", {"turnId": "turn-1", "item": {"type": "dynamicToolCall"}}),
                    notification("turn/completed", {"turnId": "turn-1", "status": "completed"}),
                ],
            ),
            result({}),
        ]
    )

    with pytest.raises(CodexAppServerProtocolError):
        asyncio.run(_client(process, tmp_path / "partial").run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))

    assert [sent["method"] for sent in process.sent].count("turn/interrupt") == 1


def test_known_approval_requests_decline_and_do_not_accept(tmp_path: Path):
    command = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"threadId": "thread-1"}), result({"turnId": "turn-1"}), result({})])
    with pytest.raises(CodexAppServerProtocolError):
        asyncio.run(
            _run_turn_until_server_request(
                command,
                tmp_path / "command-approval",
                "item/commandExecution/requestApproval",
                {"turnId": "turn-1"},
            )
        )
    command_response = next(sent for sent in command.sent if sent.get("id") == 999 and "result" in sent)
    assert command_response["result"] == {"decision": "decline"}
    assert "accept" not in json.dumps(command.sent).lower()
    assert "approved" not in json.dumps(command.sent).lower()
    assert [sent.get("method") for sent in command.sent].count("turn/interrupt") == 1

    file_change = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"threadId": "thread-1"}), result({"turnId": "turn-1"}), result({})])
    with pytest.raises(CodexAppServerProtocolError):
        asyncio.run(
            _run_turn_until_server_request(
                file_change,
                tmp_path / "file-approval",
                "item/fileChange/requestApproval",
                {"turnId": "turn-1"},
            )
        )
    file_response = next(sent for sent in file_change.sent if sent.get("id") == 999 and "result" in sent)
    assert file_response["result"] == {"decision": "decline"}
    assert "accept" not in json.dumps(file_change.sent).lower()
    assert "approved" not in json.dumps(file_change.sent).lower()


@pytest.mark.parametrize(
    ("method", "expected_response"),
    [
        ("item/commandExecution/requestApproval", {"result": {"decision": "decline"}}),
        (
            "workspace/doThing",
            {
                "error": {
                    "code": -32601,
                    "message": "Codex server requests are not supported by Forge Knowledge generation",
                }
            },
        ),
    ],
)
def test_pre_registration_server_request_fails_turn_and_does_not_leak(tmp_path: Path, method: str, expected_response: Mapping[str, Any]):
    corrupted = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result(
                {"threadId": "thread-1"},
                notifications=[
                    {"id": 999, "method": method, "params": {"turnId": "turn-1"}},
                ],
            ),
            result({"turnId": "turn-1"}),
            result({}),
        ]
    )
    restarted = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-2"}),
            result(
                {"turnId": "turn-1"},
                notifications=[
                    notification("item/completed", {"turnId": "turn-1", "item": {"type": "agentMessage", "text": "second"}}),
                    notification("turn/completed", {"turnId": "turn-1", "status": "completed"}),
                ],
            ),
        ]
    )
    processes = [corrupted, restarted]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path))

    async def exercise():
        with pytest.raises(CodexAppServerProtocolError):
            await client.run_turn(prompt="first", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3)
        second = await client.run_turn(prompt="second", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3)
        await client.aclose()
        return second

    second_result = asyncio.run(exercise())

    response = next(sent for sent in corrupted.sent if sent.get("id") == 999)
    for key, value in expected_response.items():
        assert response[key] == value
    assert [sent["params"]["turnId"] for sent in corrupted.sent if sent.get("method") == "turn/interrupt"] == ["turn-1"]
    assert corrupted.terminated is True
    assert second_result.raw_text == "second"
    assert restarted.terminated is True


@pytest.mark.parametrize(
    ("method", "expected_response"),
    [
        ("item/commandExecution/requestApproval", {"result": {"decision": "decline"}}),
        (
            "workspace/doThing",
            {
                "error": {
                    "code": -32601,
                    "message": "Codex server requests are not supported by Forge Knowledge generation",
                }
            },
        ),
    ],
)
def test_unscoped_pre_registration_server_request_invalidates_after_response(tmp_path: Path, method: str, expected_response: Mapping[str, Any]):
    corrupted = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result(
                {"threadId": "thread-1"},
                notifications=[
                    {"id": 999, "method": method, "params": {}},
                ],
            ),
            result({"turnId": "turn-1"}),
        ]
    )
    restarted = completed_turn_process("second")
    processes = [corrupted, restarted]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path))

    async def exercise():
        with pytest.raises(CodexAppServerProtocolError):
            await client.run_turn(prompt="first", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3)
        second = await client.run_turn(prompt="second", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3)
        await client.aclose()
        return second

    second_result = asyncio.run(exercise())

    response = next(sent for sent in corrupted.sent if sent.get("id") == 999)
    for key, value in expected_response.items():
        assert response[key] == value
    assert corrupted.terminated is True
    assert second_result.raw_text == "second"
    assert restarted.terminated is True


@pytest.mark.parametrize(
    ("method", "expected_response"),
    [
        (CodexProtocol.COMMAND_APPROVAL, {"result": {"decision": "decline"}}),
        (
            "workspace/doThing",
            {
                "error": {
                    "code": -32601,
                    "message": "Codex server requests are not supported by Forge Knowledge generation",
                }
            },
        ),
    ],
)
def test_unscoped_mixed_active_and_pre_registration_invalidates_all_work_and_restarts(
    tmp_path: Path,
    method: str,
    expected_response: Mapping[str, Any],
):
    corrupted = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-a"}),
            result({"threadId": "thread-b"}),
            result({"turnId": "turn-a"}),
            defer(),
        ]
    )
    restarted = completed_turn_process("fresh")
    processes = [corrupted, restarted]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path))

    async def exercise():
        first = asyncio.create_task(client.run_turn(prompt="A", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))
        second = asyncio.create_task(client.run_turn(prompt="B", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))
        while len([sent for sent in corrupted.sent if sent.get("method") == CodexProtocol.TURN_START]) < 2:
            await asyncio.sleep(0)
        corrupted.push_json({"id": 999, "method": method, "params": {}})
        failures = await asyncio.gather(first, second, return_exceptions=True)
        fresh = await client.run_turn(prompt="fresh", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3)
        await client.aclose()
        await client.aclose()
        await _assert_no_codex_tasks()
        return failures, fresh

    failures, fresh = asyncio.run(exercise())

    response_index, response = _server_response(corrupted, 999)
    for key, value in expected_response.items():
        assert response[key] == value
    assert all(isinstance(failure, CodexAppServerProtocolError) for failure in failures)
    assert corrupted.terminated is True
    assert corrupted.terminated_at_sent_count is not None
    assert response_index < corrupted.terminated_at_sent_count
    assert corrupted.wait_calls >= 1
    assert fresh.raw_text == "fresh"
    assert restarted.by_method(CodexProtocol.INITIALIZE)["method"] == CodexProtocol.INITIALIZE


def test_command_approval_at_per_turn_buffer_limit_writes_decline_before_invalidation(tmp_path: Path):
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result(
                {"threadId": "thread-1"},
                notifications=[
                    notification(CodexProtocol.ITEM_STARTED, {"turnId": "turn-1", "item": {"type": "reasoning"}}),
                    {"id": 999, "method": CodexProtocol.COMMAND_APPROVAL, "params": {"turnId": "turn-1"}},
                ],
            ),
            defer(),
        ]
    )
    client = _client_with_buffer(process, tmp_path, max_per_turn=1, max_turn_ids=10, ttl=30)

    async def exercise():
        with pytest.raises(CodexAppServerProtocolError):
            await client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3)
        await client.aclose()

    asyncio.run(exercise())

    response_index, response = _server_response(process, 999)
    assert response["result"] == {"decision": "decline"}
    assert process.terminated is True
    assert process.terminated_at_sent_count is not None
    assert response_index < process.terminated_at_sent_count


def test_file_approval_at_global_turn_id_buffer_limit_writes_decline_before_invalidation(tmp_path: Path):
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result(
                {"threadId": "thread-1"},
                notifications=[
                    notification(CodexProtocol.ITEM_STARTED, {"turnId": "unknown-a", "item": {"type": "reasoning"}}),
                    {"id": 999, "method": CodexProtocol.FILE_CHANGE_APPROVAL, "params": {"turnId": "unknown-b"}},
                ],
            ),
            defer(),
        ]
    )
    client = _client_with_buffer(process, tmp_path, max_per_turn=10, max_turn_ids=1, ttl=30)

    async def exercise():
        with pytest.raises(CodexAppServerProtocolError):
            await client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3)
        await client.aclose()

    asyncio.run(exercise())

    response_index, response = _server_response(process, 999)
    assert response["result"] == {"decision": "decline"}
    assert process.terminated is True
    assert process.terminated_at_sent_count is not None
    assert response_index < process.terminated_at_sent_count


def test_unknown_scoped_server_request_after_buffer_ttl_writes_error_before_invalidation(tmp_path: Path):
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result(
                {"threadId": "thread-1"},
                notifications=[
                    notification(CodexProtocol.ITEM_STARTED, {"turnId": "expired", "item": {"type": "reasoning"}}),
                ],
            ),
            defer(),
        ]
    )
    client = _client_with_buffer(process, tmp_path, max_per_turn=10, max_turn_ids=10, ttl=0.01)

    async def exercise():
        task = asyncio.create_task(client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))
        while not any(sent.get("method") == CodexProtocol.TURN_START for sent in process.sent):
            await asyncio.sleep(0)
        await asyncio.sleep(0.05)
        process.push_json({"id": 999, "method": "workspace/doThing", "params": {"turnId": "later"}})
        with pytest.raises(CodexAppServerProtocolError):
            await task
        await client.aclose()

    asyncio.run(exercise())

    response_index, response = _server_response(process, 999)
    assert response["error"] == {
        "code": -32601,
        "message": "Codex server requests are not supported by Forge Knowledge generation",
    }
    assert process.terminated is True
    assert process.terminated_at_sent_count is not None
    assert response_index < process.terminated_at_sent_count


def test_server_request_scope_and_unknown_fail_closed(tmp_path: Path):
    scoped = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-a"}),
            result({"threadId": "thread-b"}),
            result({"turnId": "turn-a"}),
            result({"turnId": "turn-b"}),
            result({}),
        ]
    )
    first, second = asyncio.run(_run_two_turns_with_server_request(scoped, tmp_path / "scoped", {"turnId": "turn-a"}))
    assert isinstance(first, CodexAppServerProtocolError)
    assert second.raw_text == "second"
    assert [sent["params"]["turnId"] for sent in scoped.sent if sent.get("method") == "turn/interrupt"] == ["turn-a"]

    unscoped = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-a"}),
            result({"threadId": "thread-b"}),
            result({"turnId": "turn-a"}),
            result({"turnId": "turn-b"}),
            result({}),
            result({}),
        ]
    )
    first, second = asyncio.run(_run_two_turns_with_server_request(unscoped, tmp_path / "unscoped", {}))
    assert isinstance(first, CodexAppServerProtocolError)
    assert isinstance(second, CodexAppServerProtocolError)
    assert sorted(sent["params"]["turnId"] for sent in unscoped.sent if sent.get("method") == "turn/interrupt") == ["turn-a", "turn-b"]

    unknown = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"threadId": "thread-1"}), result({"turnId": "turn-1"}), result({})])
    with pytest.raises(CodexAppServerProtocolError):
        asyncio.run(_run_turn_until_server_request(unknown, tmp_path / "unknown-request", "workspace/doThing", {"turnId": "turn-1"}))
    assert any(sent.get("id") == 999 and "error" in sent for sent in unknown.sent)


@pytest.mark.parametrize("bad_line", [b"{not-json\n", b"[]\n", b"{\"id\":\"bad\",\"result\":{}}\n", b"{\"id\":999,\"result\":{}}\n"])
def test_protocol_corruption_invalidates_connection_and_next_request_restarts(tmp_path: Path, bad_line: bytes):
    first_process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), raw_line(bad_line)])
    second_process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": True})])
    processes = [first_process, second_process]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path))

    async def exercise():
        with pytest.raises(CodexAppServerProtocolError):
            await client.request("model/list")
        payload = await client.request("model/list")
        await client.aclose()
        return payload

    assert asyncio.run(exercise()) == {"ok": True}
    assert first_process.terminated is True
    assert second_process.by_method("initialize")["method"] == "initialize"


@pytest.mark.parametrize(
    "bad_notification",
    [
        {"method": "turn/completed", "params": "bad"},
        {"method": "turn/completed", "params": {"status": "completed"}},
        {"method": "item/completed", "params": {"turnId": "turn-1", "item": "bad"}},
        {"method": "item/started", "params": {"item": {"type": "reasoning"}}},
    ],
)
def test_malformed_notifications_invalidate_reap_and_restart(tmp_path: Path, bad_notification: Mapping[str, Any]):
    first_process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), dict(bad_notification)])
    second_process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": True})])
    processes = [first_process, second_process]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path))

    async def exercise():
        with pytest.raises(CodexAppServerProtocolError):
            await client.request("model/list")
        for _ in range(100):
            if first_process.wait_calls:
                break
            await asyncio.sleep(0.01)
        payload = await client.request("model/list")
        await client.aclose()
        return payload

    assert asyncio.run(exercise()) == {"ok": True}
    assert first_process.terminated is True
    assert first_process.wait_calls >= 1
    assert first_process.returncode == 0
    assert second_process.by_method("initialize")["method"] == "initialize"


def test_ordinary_turn_failure_and_timeout_reuse_healthy_process(tmp_path: Path):
    failed_process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result({"turnId": "turn-1"}, notifications=[notification("turn/completed", {"turnId": "turn-1", "status": "failed", "error": {"message": "failed"}})]),
            result({"ok": True}),
        ]
    )
    failed_client = _client(failed_process, tmp_path / "failed-reuse")
    async def failed_exercise():
        with pytest.raises(CodexAppServerRemoteError):
            await failed_client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3)
        payload = await failed_client.request("model/list")
        await failed_client.aclose()
        return payload

    assert asyncio.run(failed_exercise()) == {"ok": True}
    assert [sent["method"] for sent in failed_process.sent].count("initialize") == 1

    timeout_process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result({"turnId": "turn-1"}),
            result({}),
            result({"ok": True}),
        ]
    )
    timeout_client = _client(timeout_process, tmp_path / "timeout-reuse")
    async def timeout_exercise():
        with pytest.raises(CodexAppServerTimeout):
            await timeout_client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=0.05)
        payload = await timeout_client.request("model/list")
        await timeout_client.aclose()
        return payload

    assert asyncio.run(timeout_exercise()) == {"ok": True}
    assert [sent["method"] for sent in timeout_process.sent].count("initialize") == 1


def test_stalled_write_during_turn_interrupt_does_not_block_timeout_and_restarts(tmp_path: Path):
    stalled = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"threadId": "thread-1"}), result({"turnId": "turn-1"}), defer()])
    stalled.stdin.block_on_method = CodexProtocol.TURN_INTERRUPT
    restarted = completed_turn_process("fresh")
    processes = [stalled, restarted]
    client = CodexAppServerClient(
        process_factory=lambda command: async_value(processes.pop(0)),
        settings=_settings(
            tmp_path,
            interrupt_grace_seconds=0.02,
            terminal_after_interrupt_seconds=0.01,
            request_timeout_seconds=0.2,
        ),
    )

    async def exercise():
        started = time.monotonic()
        with pytest.raises(CodexAppServerTimeout):
            await client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=0.05)
        elapsed = time.monotonic() - started
        fresh = await client.run_turn(prompt="fresh", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=1)
        await client.aclose()
        await _assert_no_codex_tasks()
        return elapsed, fresh

    elapsed, fresh = asyncio.run(exercise())

    assert elapsed < 0.5
    assert stalled.terminated is True
    assert [sent["method"] for sent in stalled.sent].count(CodexProtocol.TURN_INTERRUPT) == 1
    assert fresh.raw_text == "fresh"


def test_server_approval_response_write_failure_invalidates_reaps_and_restarts(tmp_path: Path):
    broken = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result({"turnId": "turn-1"}),
            defer(),
        ]
    )
    broken.stdin.fail_response_writes = True
    restarted = completed_turn_process("fresh")
    processes = [broken, restarted]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path))

    async def exercise():
        task = asyncio.create_task(client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))
        while not any(sent.get("method") == CodexProtocol.TURN_START for sent in broken.sent):
            await asyncio.sleep(0)
        broken.push_json({"id": 999, "method": CodexProtocol.COMMAND_APPROVAL, "params": {"turnId": "turn-1"}})
        with pytest.raises(CodexAppServerTransportError):
            await task
        fresh = await client.run_turn(prompt="fresh", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3)
        await client.aclose()
        await _assert_no_codex_tasks()
        return fresh

    fresh = asyncio.run(exercise())

    assert broken.terminated is True
    assert not any(sent.get("id") == 999 and "method" not in sent for sent in broken.sent)
    assert fresh.raw_text == "fresh"


def test_requested_generation_timeout_includes_cold_initialization_and_restarts(tmp_path: Path):
    stalled = FakeCodexProcess([defer()])
    restarted = completed_turn_process("fresh")
    processes = [stalled, restarted]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path, request_timeout_seconds=1))
    provider = CodexGenerativeProvider(client, timeout_seconds=1)

    started = time.monotonic()
    with pytest.raises(GenerativeProviderTimeout):
        provider.generate(GenerativeRequest(prompt="x", model_id="m", timeout_seconds=0.05))
    elapsed = time.monotonic() - started

    response = provider.generate(GenerativeRequest(prompt="fresh", model_id="m", timeout_seconds=1))
    client.close()
    client.close()

    assert elapsed < 0.5
    assert stalled.terminated is True
    assert response.raw_text == "fresh"


def test_active_turn_caller_cancellation_reaps_and_restarts(tmp_path: Path):
    process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"threadId": "thread-1"}), result({"turnId": "turn-1"}), defer()])
    restarted = completed_turn_process("fresh")
    processes = [process, restarted]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path))

    async def exercise():
        task = asyncio.create_task(client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=5))
        while any(sent.get("method") == "turn/start" for sent in process.sent) is False:
            await asyncio.sleep(0)
        assert await asyncio.to_thread(process.stdout.wait_for_reads, 3, 1.0) is True
        await asyncio.sleep(1.0)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        fresh = await client.run_turn(prompt="fresh", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3)
        await client.aclose()
        await _assert_no_codex_tasks()
        return fresh

    fresh = asyncio.run(exercise())

    assert process.terminated is True
    assert fresh.raw_text == "fresh"
    assert restarted.by_method(CodexProtocol.INITIALIZE)["method"] == CodexProtocol.INITIALIZE


def test_remote_rate_limit_error_during_active_generation_keeps_process_healthy(tmp_path: Path):
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result({"turnId": "turn-1"}),
            rpc_error(-32000, "secret rate-limit failure"),
            result({"ok": True}),
        ]
    )
    client = _client(process, tmp_path)
    service = _active_codex_profile_service(client, tmp_path)

    async def exercise():
        generation = asyncio.create_task(client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))
        await _wait_for_method(process, CodexProtocol.TURN_START)
        assert await asyncio.to_thread(process.stdout.wait_for_reads, 3, 1.0) is True
        profile = await service.get_active_profile()
        assert profile.usage is None
        _complete_turn(process, "turn-1", "answer")
        generated = await generation
        payload = await client.request(CodexProtocol.MODEL_LIST)
        await client.aclose()
        await _assert_no_codex_tasks()
        return generated, payload

    generated, payload = asyncio.run(exercise())

    assert generated.raw_text == "answer"
    assert payload == {"ok": True}
    assert process.terminated is True
    assert [sent["method"] for sent in process.sent].count(CodexProtocol.INITIALIZE) == 1


def test_remote_model_list_error_during_active_generation_degrades_without_restart(tmp_path: Path):
    process = FakeCodexProcess(
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            result({"turnId": "turn-1"}),
            rpc_error(-32000, "secret discovery failure"),
            result({"ok": True}),
        ]
    )
    client = _client(process, tmp_path)
    discovery = AiRuntimeDiscoveryService(
        AiRuntimeDiscoveryRegistry([CodexAiRuntimeOptionsSource(client, cache_ttl_seconds=30.0, max_page_count=100)])
    )

    async def exercise():
        generation = asyncio.create_task(client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))
        await _wait_for_method(process, CodexProtocol.TURN_START)
        assert await asyncio.to_thread(process.stdout.wait_for_reads, 3, 1.0) is True
        discovered = await discovery.discover()
        _complete_turn(process, "turn-1", "answer")
        generated = await generation
        payload = await client.request(CodexProtocol.MODEL_LIST)
        await client.aclose()
        await _assert_no_codex_tasks()
        return discovered, generated, payload

    discovered, generated, payload = asyncio.run(exercise())

    assert discovered["providers"][0]["status"] == "DEGRADED"
    assert generated.raw_text == "answer"
    assert payload == {"ok": True}
    assert process.terminated is True
    assert [sent["method"] for sent in process.sent].count(CodexProtocol.INITIALIZE) == 1


@pytest.mark.parametrize(
    "script",
    [
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            rpc_error(-32000, "secret thread failure"),
            result({"threadId": "thread-2"}),
            result(
                {"turnId": "turn-2"},
                notifications=[
                    notification("item/completed", {"turnId": "turn-2", "item": {"type": "agentMessage", "text": "fresh"}}),
                    notification("turn/completed", {"turnId": "turn-2", "status": "completed"}),
                ],
            ),
        ],
        [
            result({"userAgent": "forge-knowledge/0.146.0"}),
            result({"threadId": "thread-1"}),
            rpc_error(-32000, "secret turn failure"),
            result({"threadId": "thread-2"}),
            result(
                {"turnId": "turn-2"},
                notifications=[
                    notification("item/completed", {"turnId": "turn-2", "item": {"type": "agentMessage", "text": "fresh"}}),
                    notification("turn/completed", {"turnId": "turn-2", "status": "completed"}),
                ],
            ),
        ],
    ],
)
def test_remote_thread_or_turn_start_error_fails_one_generation_and_reuses_process(tmp_path: Path, script: list[Mapping[str, Any]]):
    process = FakeCodexProcess(script)
    client = _client(process, tmp_path)

    async def exercise():
        with pytest.raises(CodexAppServerRemoteError) as exc_info:
            await client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3)
        assert "secret" not in str(exc_info.value)
        fresh = await client.run_turn(prompt="fresh", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3)
        await client.aclose()
        await _assert_no_codex_tasks()
        return fresh

    fresh = asyncio.run(exercise())

    assert fresh.raw_text == "fresh"
    assert process.terminated is True
    assert [sent["method"] for sent in process.sent].count(CodexProtocol.INITIALIZE) == 1


@pytest.mark.parametrize(
    ("script", "blocked_method"),
    [
        ([result({"userAgent": "forge-knowledge/0.146.0"}), defer()], CodexProtocol.THREAD_START),
        ([result({"userAgent": "forge-knowledge/0.146.0"}), result({"threadId": "thread-1"}), defer()], CodexProtocol.TURN_START),
    ],
)
def test_pre_registration_deadline_expiry_reaps_and_next_request_restarts(tmp_path: Path, script: list[Mapping[str, Any]], blocked_method: str):
    stalled = FakeCodexProcess(script)
    restarted = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": True})])
    processes = [stalled, restarted]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path, request_timeout_seconds=1))

    async def exercise():
        diagnostics: list[dict[str, Any]] = []
        asyncio.get_running_loop().set_exception_handler(lambda _loop, context: diagnostics.append(dict(context)))
        started = time.monotonic()
        with pytest.raises(CodexAppServerTimeout):
            await client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=0.05)
        elapsed = time.monotonic() - started
        payload = await client.request(CodexProtocol.MODEL_LIST)
        await client.aclose()
        await _assert_no_codex_tasks()
        return elapsed, payload, diagnostics

    elapsed, payload, diagnostics = asyncio.run(exercise())

    assert elapsed < 0.5
    assert stalled.terminated is True
    assert payload == {"ok": True}
    assert restarted.by_method(CodexProtocol.MODEL_LIST)["method"] == CodexProtocol.MODEL_LIST
    assert stalled.by_method(blocked_method)["method"] == blocked_method
    assert diagnostics == []


@pytest.mark.parametrize(
    ("script", "blocked_method"),
    [
        ([result({"userAgent": "forge-knowledge/0.146.0"}), defer()], CodexProtocol.THREAD_START),
        ([result({"userAgent": "forge-knowledge/0.146.0"}), result({"threadId": "thread-1"}), defer()], CodexProtocol.TURN_START),
    ],
)
def test_pre_registration_caller_cancellation_after_submission_reaps_and_restarts(tmp_path: Path, script: list[Mapping[str, Any]], blocked_method: str):
    stalled = FakeCodexProcess(script)
    restarted = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": True})])
    processes = [stalled, restarted]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path, request_timeout_seconds=1))

    async def exercise():
        diagnostics: list[dict[str, Any]] = []
        asyncio.get_running_loop().set_exception_handler(lambda _loop, context: diagnostics.append(dict(context)))
        task = asyncio.create_task(client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))
        await _wait_for_method(stalled, blocked_method)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        payload = await client.request(CodexProtocol.MODEL_LIST)
        await client.aclose()
        await _assert_no_codex_tasks()
        return payload, diagnostics

    payload, diagnostics = asyncio.run(exercise())

    assert stalled.terminated is True
    assert payload == {"ok": True}
    assert diagnostics == []


def test_public_request_cancellation_after_write_reaps_and_restarts(tmp_path: Path):
    stalled = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), defer()])
    restarted = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": True})])
    processes = [stalled, restarted]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path, request_timeout_seconds=1))

    async def exercise():
        diagnostics: list[dict[str, Any]] = []
        asyncio.get_running_loop().set_exception_handler(lambda _loop, context: diagnostics.append(dict(context)))
        task = asyncio.create_task(client.request(CodexProtocol.RATE_LIMITS_READ))
        await _wait_for_method(stalled, CodexProtocol.RATE_LIMITS_READ)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        payload = await client.request(CodexProtocol.MODEL_LIST)
        await client.aclose()
        await _assert_no_codex_tasks()
        return payload, diagnostics

    payload, diagnostics = asyncio.run(exercise())

    assert stalled.terminated is True
    assert payload == {"ok": True}
    assert diagnostics == []


@pytest.mark.parametrize(
    ("script", "cancel_after_method"),
    [
        ([result({"userAgent": "forge-knowledge/0.146.0"}), defer()], CodexProtocol.RATE_LIMITS_READ),
        ([result({"userAgent": "forge-knowledge/0.146.0"}), defer()], CodexProtocol.MODEL_LIST),
    ],
)
def test_submitted_request_cancellation_preserved_when_cleanup_fails_and_process_remains_owned(
    tmp_path: Path,
    script: list[Mapping[str, Any]],
    cancel_after_method: str,
):
    process = KillTimeoutProcess(script)
    created = 0

    async def process_factory(command):
        nonlocal created
        created += 1
        return process

    client = CodexAppServerClient(
        process_factory=process_factory,
        settings=_settings(tmp_path, terminate_grace_seconds=0.02, kill_grace_seconds=0.02, cancellation_cleanup_timeout_seconds=0.05),
    )

    async def exercise():
        diagnostics: list[dict[str, Any]] = []
        asyncio.get_running_loop().set_exception_handler(lambda _loop, context: diagnostics.append(dict(context)))
        task = asyncio.create_task(client.request(cancel_after_method))
        await _wait_for_method(process, cancel_after_method)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        with pytest.raises(CodexAppServerLifecycleError):
            await client.request(CodexProtocol.MODEL_LIST)
        await _wait_for_cleanup_attempt(process)
        process.returncode = 0
        process._complete_wait()
        await client.aclose()
        await _assert_no_codex_tasks()
        return diagnostics

    diagnostics = asyncio.run(exercise())

    assert created == 1
    assert process.terminated is True
    assert process.killed is True
    assert process.wait_calls >= 2
    assert diagnostics == []


def test_cancelled_submitted_request_blocks_followup_while_cleanup_handoff_is_pending(tmp_path: Path):
    process = KillTimeoutProcess([result({"userAgent": "forge-knowledge/0.146.0"}), defer()])
    created = 0

    async def process_factory(command):
        nonlocal created
        created += 1
        return process

    client = CodexAppServerClient(
        process_factory=process_factory,
        settings=_settings(
            tmp_path,
            terminate_grace_seconds=0.01,
            kill_grace_seconds=0.3,
            cancellation_cleanup_timeout_seconds=0.03,
        ),
    )

    async def exercise():
        diagnostics: list[dict[str, Any]] = []
        asyncio.get_running_loop().set_exception_handler(lambda _loop, context: diagnostics.append(dict(context)))
        task = asyncio.create_task(client.request(CodexProtocol.RATE_LIMITS_READ))
        await _wait_for_method(process, CodexProtocol.RATE_LIMITS_READ)

        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

        with pytest.raises(CodexAppServerLifecycleError, match="cancellation cleanup"):
            await client.request(CodexProtocol.MODEL_LIST)

        await _wait_for_cleanup_attempt(process)
        process.returncode = 0
        process._complete_wait()
        await _wait_for_cancellation_cleanup(client)
        await client.aclose()
        await _assert_no_codex_tasks()
        return diagnostics

    diagnostics = asyncio.run(exercise())

    assert created == 1
    assert process.terminated is True
    assert process.killed is True
    assert [sent.get("method") for sent in process.sent].count(CodexProtocol.MODEL_LIST) == 0
    assert diagnostics == []


def test_cancelled_submitted_request_recovers_after_owned_process_exits(tmp_path: Path):
    stalled = KillTimeoutProcess([result({"userAgent": "forge-knowledge/0.146.0"}), defer()])
    restarted = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": True})])
    processes = [stalled, restarted]
    created = 0

    async def process_factory(command):
        nonlocal created
        created += 1
        return processes.pop(0)

    client = CodexAppServerClient(
        process_factory=process_factory,
        settings=_settings(
            tmp_path,
            terminate_grace_seconds=0.01,
            kill_grace_seconds=0.3,
            cancellation_cleanup_timeout_seconds=0.03,
        ),
    )

    async def exercise():
        diagnostics: list[dict[str, Any]] = []
        asyncio.get_running_loop().set_exception_handler(lambda _loop, context: diagnostics.append(dict(context)))
        task = asyncio.create_task(client.request(CodexProtocol.RATE_LIMITS_READ))
        await _wait_for_method(stalled, CodexProtocol.RATE_LIMITS_READ)

        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

        with pytest.raises(CodexAppServerLifecycleError):
            await client.request(CodexProtocol.MODEL_LIST)
        assert created == 1

        await _wait_for_cleanup_attempt(stalled)
        stalled.returncode = 0
        stalled._complete_wait()
        await _wait_for_cancellation_cleanup(client)
        payload = await client.request(CodexProtocol.MODEL_LIST)

        await client.aclose()
        await _assert_no_codex_tasks()
        return payload, diagnostics

    payload, diagnostics = asyncio.run(exercise())

    assert payload == {"ok": True}
    assert created == 2
    assert stalled.terminated is True
    assert stalled.killed is True
    assert [sent.get("method") for sent in stalled.sent].count(CodexProtocol.MODEL_LIST) == 0
    assert restarted.by_method(CodexProtocol.MODEL_LIST)["method"] == CodexProtocol.MODEL_LIST
    assert diagnostics == []


@pytest.mark.parametrize(
    ("operation", "script", "cancel_after_method"),
    [
        ("initialize", [defer()], CodexProtocol.INITIALIZE),
        ("thread-start", [result({"userAgent": "forge-knowledge/0.146.0"}), defer()], CodexProtocol.THREAD_START),
        ("turn-start", [result({"userAgent": "forge-knowledge/0.146.0"}), result({"threadId": "thread-1"}), defer()], CodexProtocol.TURN_START),
    ],
)
def test_initialize_and_pre_registration_cancellation_preserved_when_cleanup_fails(
    tmp_path: Path,
    operation: str,
    script: list[Mapping[str, Any]],
    cancel_after_method: str,
):
    process = KillTimeoutProcess(script)
    created = 0

    async def process_factory(command):
        nonlocal created
        created += 1
        return process

    client = CodexAppServerClient(
        process_factory=process_factory,
        settings=_settings(tmp_path, terminate_grace_seconds=0.02, kill_grace_seconds=0.02, cancellation_cleanup_timeout_seconds=0.05),
    )

    async def exercise():
        diagnostics: list[dict[str, Any]] = []
        asyncio.get_running_loop().set_exception_handler(lambda _loop, context: diagnostics.append(dict(context)))
        if operation == "initialize":
            task = asyncio.create_task(client.initialize())
        else:
            task = asyncio.create_task(client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))
        await _wait_for_method(process, cancel_after_method)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        with pytest.raises(CodexAppServerLifecycleError):
            await client.request(CodexProtocol.MODEL_LIST)
        await _wait_for_cleanup_attempt(process)
        process.returncode = 0
        process._complete_wait()
        await client.aclose()
        await _assert_no_codex_tasks()
        return diagnostics

    diagnostics = asyncio.run(exercise())

    assert created == 1
    assert process.terminated is True
    assert process.killed is True
    assert process.wait_calls >= 2
    assert diagnostics == []


def test_active_turn_cancellation_preserved_when_cleanup_fails_and_process_remains_owned(tmp_path: Path):
    process = KillTimeoutProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"threadId": "thread-1"}), result({"turnId": "turn-1"}), defer()])
    created = 0

    async def process_factory(command):
        nonlocal created
        created += 1
        return process

    client = CodexAppServerClient(
        process_factory=process_factory,
        settings=_settings(tmp_path, terminate_grace_seconds=0.02, kill_grace_seconds=0.02, cancellation_cleanup_timeout_seconds=0.05),
    )

    async def exercise():
        diagnostics: list[dict[str, Any]] = []
        asyncio.get_running_loop().set_exception_handler(lambda _loop, context: diagnostics.append(dict(context)))
        task = asyncio.create_task(client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))
        await _wait_for_method(process, CodexProtocol.TURN_START)
        assert await asyncio.to_thread(process.stdout.wait_for_reads, 3, 1.0) is True
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        with pytest.raises(CodexAppServerLifecycleError):
            await client.request(CodexProtocol.MODEL_LIST)
        await _wait_for_cleanup_attempt(process)
        process.returncode = 0
        process._complete_wait()
        await client.aclose()
        await _assert_no_codex_tasks()
        return diagnostics

    diagnostics = asyncio.run(exercise())

    assert created == 1
    assert process.terminated is True
    assert process.killed is True
    assert process.wait_calls >= 2
    assert [sent["method"] for sent in process.sent].count(CodexProtocol.TURN_INTERRUPT) <= 1
    assert diagnostics == []


def test_fail_active_interrupt_write_failure_does_not_self_cancel_cleanup(tmp_path: Path):
    broken = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"threadId": "thread-1"}), result({"turnId": "turn-1"}), defer()])
    broken.stdin.block_on_method = CodexProtocol.TURN_INTERRUPT
    restarted = completed_turn_process("fresh")
    processes = [broken, restarted]
    client = CodexAppServerClient(
        process_factory=lambda command: async_value(processes.pop(0)),
        settings=_settings(tmp_path, interrupt_grace_seconds=0.02, terminal_after_interrupt_seconds=0.01),
    )

    async def exercise():
        diagnostics: list[dict[str, Any]] = []
        asyncio.get_running_loop().set_exception_handler(lambda _loop, context: diagnostics.append(dict(context)))
        task = asyncio.create_task(client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))
        await _wait_for_method(broken, CodexProtocol.TURN_START)
        assert await asyncio.to_thread(broken.stdout.wait_for_reads, 3, 1.0) is True
        await asyncio.sleep(0.05)
        broken.push_json({"id": 999, "method": CodexProtocol.COMMAND_APPROVAL, "params": {"turnId": "turn-1"}})
        with pytest.raises(CodexAppServerError):
            await task
        fresh = await client.run_turn(prompt="fresh", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3)
        await client.aclose()
        await _assert_no_codex_tasks()
        return fresh, diagnostics

    fresh, diagnostics = asyncio.run(exercise())

    assert broken.terminated is True
    assert [sent.get("method") for sent in broken.sent].count(CodexProtocol.TURN_INTERRUPT) == 1
    assert fresh.raw_text == "fresh"
    assert diagnostics == []


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
    sync_client = _client(sync_process, tmp_path / "sync")
    provider = CodexGenerativeProvider(sync_client, timeout_seconds=3)

    response = provider.generate(GenerativeRequest(prompt="prompt", model_id="m", effort_id="low"))
    sync_client.close()

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
    async_client = _client(async_process, tmp_path / "async")
    async_provider = CodexGenerativeProvider(async_client, timeout_seconds=3)
    async_response = asyncio.run(async_provider.generate_async(GenerativeRequest(prompt="p", model_id="m")))
    async_client.close()
    assert async_response.raw_text == "async"

    with pytest.raises(GenerativeProviderEmptyResponse):
        empty_client = _client(completed_turn_process(""), tmp_path / "empty")
        try:
            CodexGenerativeProvider(empty_client, timeout_seconds=3).generate(GenerativeRequest(prompt="p", model_id="m"))
        finally:
            empty_client.close()
    with pytest.raises(GenerativeProviderTimeout):
        timeout_client = _client(FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), defer()]), tmp_path / "timeout-provider")
        try:
            CodexGenerativeProvider(timeout_client, timeout_seconds=0.01).generate(GenerativeRequest(prompt="p", model_id="m"))
        finally:
            timeout_client.close()
    with pytest.raises(GenerativeProviderProtocolError):
        protocol_client = _client(
            FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"threadId": "t"}), result({"turnId": "u"}, notifications=[notification("item/completed", {"turnId": "u", "item": {"type": "fileChange"}})]), result({})]),
            tmp_path / "protocol-provider",
        )
        try:
            CodexGenerativeProvider(protocol_client, timeout_seconds=3).generate(GenerativeRequest(prompt="p", model_id="m"))
        finally:
            protocol_client.close()
    with pytest.raises(GenerativeProviderTransportError):
        transport_client = _client(FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), rpc_error(-1, "bad")]), tmp_path / "transport-provider")
        try:
            CodexGenerativeProvider(transport_client, timeout_seconds=3).generate(GenerativeRequest(prompt="p", model_id="m"))
        finally:
            transport_client.close()


@pytest.mark.parametrize("timeout_seconds", [0, -1])
def test_direct_codex_turn_timeout_is_rejected_not_clamped(tmp_path: Path, timeout_seconds: float):
    process = completed_turn_process("unused")
    client = _client(process, tmp_path)
    try:
        with pytest.raises(ValueError, match="timeout_seconds must be positive"):
            asyncio.run(client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=timeout_seconds))
    finally:
        client.close()


@pytest.mark.parametrize("timeout_seconds", [0, -1])
def test_codex_provider_direct_timeout_is_rejected_not_clamped(tmp_path: Path, timeout_seconds: float):
    client = _client(completed_turn_process("unused"), tmp_path)
    try:
        with pytest.raises(ValueError, match="timeout_seconds must be positive"):
            CodexGenerativeProvider(client, timeout_seconds=timeout_seconds)
    finally:
        client.close()


class TerminateTimeoutProcess(FakeCodexProcess):
    def __init__(self, scripted: Sequence[Mapping[str, Any]]) -> None:
        super().__init__(scripted)
        self.terminate_attempted = threading.Event()
        self.kill_attempted = threading.Event()

    def terminate(self) -> None:
        self.terminated = True
        self.terminate_attempted.set()

    def kill(self) -> None:
        self.killed = True
        self.kill_attempted.set()
        self.returncode = 0
        self._complete_wait()

    async def wait(self) -> int:
        self.wait_calls += 1
        if self.returncode is not None:
            return self.returncode
        await asyncio.sleep(10)
        return 0


class KillTimeoutProcess(TerminateTimeoutProcess):
    def kill(self) -> None:
        self.killed = True
        self.kill_attempted.set()


class RaiseOnCancelStream(FakeStream):
    async def readline(self) -> bytes:
        try:
            return await super().readline()
        except asyncio.CancelledError as exc:
            raise RuntimeError("reader cleanup failed") from exc


def _client(process: FakeCodexProcess, runtime_cwd: Path) -> CodexAppServerClient:
    return CodexAppServerClient(process_factory=lambda command: async_value(process), settings=_settings(runtime_cwd))


def _client_with_buffer(process: FakeCodexProcess, runtime_cwd: Path, *, max_per_turn: int, max_turn_ids: int, ttl: float) -> CodexAppServerClient:
    return CodexAppServerClient(
        process_factory=lambda command: async_value(process),
        settings=_settings(
            runtime_cwd,
            notification_buffer=CodexNotificationBufferPolicy(max_per_turn=max_per_turn, max_turn_ids=max_turn_ids, max_age_seconds=ttl),
        ),
    )


def _settings(runtime_cwd: Path, **overrides: Any) -> CodexRuntimeSettings:
    values: dict[str, Any] = {
        "command": ("codex", "app-server", "--stdio"),
        "runtime_cwd": runtime_cwd,
        "client_name": "forge-knowledge",
        "client_version": "0.146.0",
        "stdio_stream_limit_bytes": CodexAppServerSettings().stdio_stream_limit_bytes,
        "request_timeout_seconds": 1,
        "discovery_timeout_cap_seconds": 1,
        "discovery_timeout_allowance_seconds": 0.1,
        "interrupt_grace_seconds": 0.1,
        "terminal_after_interrupt_seconds": 0.1,
        "terminate_grace_seconds": 0.1,
        "kill_grace_seconds": 0.1,
        "sync_close_timeout_seconds": 3,
        "loop_thread_join_timeout_seconds": 0.5,
        "cancellation_cleanup_timeout_seconds": 0.1,
        "notification_buffer": _notification_policy(),
    }
    values.update(overrides)
    return CodexRuntimeSettings(**values)


def _notification_policy() -> CodexNotificationBufferPolicy:
    return CodexNotificationBufferPolicy(max_per_turn=100, max_turn_ids=100, max_age_seconds=30.0)


def _server_response(process: FakeCodexProcess, response_id: int) -> tuple[int, dict[str, Any]]:
    for index, sent in enumerate(process.sent):
        if sent.get("id") == response_id and "method" not in sent:
            return index, sent
    raise AssertionError(f"response not sent: {response_id}")


async def _wait_for_method(process: FakeCodexProcess, method: str) -> None:
    deadline = time.monotonic() + 1.0
    while time.monotonic() < deadline:
        if any(sent.get("method") == method for sent in process.sent):
            return
        await asyncio.sleep(0)
    raise AssertionError(f"method not sent: {method}")


async def _wait_for_cancellation_cleanup(client: CodexAppServerClient) -> None:
    completed = getattr(client, "_cancellation_cleanup_completed")
    if completed is not None:
        await asyncio.wrap_future(completed)


async def _wait_for_cleanup_attempt(process: TerminateTimeoutProcess) -> None:
    assert await asyncio.to_thread(process.terminate_attempted.wait, 1.0) is True
    assert await asyncio.to_thread(process.kill_attempted.wait, 1.0) is True


def _complete_turn(process: FakeCodexProcess, turn_id: str, text: str) -> None:
    process.push_json({"method": CodexProtocol.ITEM_COMPLETED, "params": {"turnId": turn_id, "item": {"type": "agentMessage", "text": text}}})
    process.push_json({"method": CodexProtocol.TURN_COMPLETED, "params": {"turnId": turn_id, "status": "completed"}})


def _active_codex_profile_service(client: CodexAppServerClient, runtime_cwd: Path) -> ActiveProfileService:
    store = ActiveProfileStore(runtime_cwd / "active.sqlite")
    persisted = store.init(provider_id="codex", model_id="m")
    registry = GenerativeProviderRegistry()
    registry.register(CodexGenerativeProvider(client, timeout_seconds=3))
    usage_registry = LlmUsageRegistry()
    usage_registry.register(CodexLlmUsageSource(client))
    discovery = AiRuntimeDiscoveryService(AiRuntimeDiscoveryRegistry([]))
    return ActiveProfileService(store, ActiveLlmRuntime(registry, persisted), discovery, usage_registry)


async def _assert_no_codex_tasks() -> None:
    await asyncio.sleep(0)
    current = asyncio.current_task()
    pending = [
        task
        for task in asyncio.all_tasks()
        if task is not current and not task.done() and task.get_name().startswith("codex-app-server")
    ]
    assert pending == []


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


async def _run_turn_until_server_request(process: FakeCodexProcess, runtime_cwd: Path, method: str, params: Mapping[str, Any]) -> CodexTurnResult:
    client = _client(process, runtime_cwd)
    try:
        task = asyncio.create_task(client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode=ResponseMode.TEXT, timeout_seconds=3))
        while not any(sent.get("method") == "turn/start" for sent in process.sent):
            await asyncio.sleep(0)
        await asyncio.sleep(0.05)
        process.push_json({"id": 999, "method": method, "params": dict(params)})
        return await task
    finally:
        await client.aclose()


async def _run_two_turns_with_server_request(process: FakeCodexProcess, runtime_cwd: Path, params: Mapping[str, Any]) -> tuple[Any, Any]:
    client = _client(process, runtime_cwd)
    try:
        first_task = asyncio.create_task(client.run_turn(prompt="A", model_id="m", effort_id="low", response_mode=ResponseMode.TEXT, timeout_seconds=3))
        second_task = asyncio.create_task(client.run_turn(prompt="B", model_id="m", effort_id="high", response_mode=ResponseMode.TEXT, timeout_seconds=3))
        while len([sent for sent in process.sent if sent.get("method") == "turn/start"]) < 2:
            await asyncio.sleep(0)
        await asyncio.sleep(0.05)
        process.push_json({"id": 999, "method": "item/commandExecution/requestApproval", "params": dict(params)})
        if params.get("turnId") == "turn-a":
            process.push_json({"method": "item/completed", "params": {"turnId": "turn-b", "item": {"type": "agentMessage", "text": "second"}}})
            process.push_json({"method": "turn/completed", "params": {"turnId": "turn-b", "status": "completed"}})
        results = await asyncio.gather(first_task, second_task, return_exceptions=True)
        return results[0], results[1]
    finally:
        await client.aclose()
