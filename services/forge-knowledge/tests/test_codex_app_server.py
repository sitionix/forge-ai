from __future__ import annotations

import asyncio
import json
import threading
import time
from pathlib import Path
from typing import Any, Mapping, Sequence

import pytest
from codex_app_server_support import FakeCodexProcess, FakeStream, async_value, defer, notification, raw_line, result, rpc_error, server_request

from knowledge_service.codex_app_server import (
    CodexAppServerClient,
    CodexAppServerEmptyResponse,
    CodexAppServerLifecycleError,
    CodexAppServerProtocolError,
    CodexAppServerTimeout,
    CodexAppServerTransportError,
    CodexNotificationBufferPolicy,
    CodexRuntimeSettings,
    CodexTurnResult,
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


def test_json_rpc_error_malformed_restart_and_idempotent_close(tmp_path: Path):
    error_process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), rpc_error(-32000, "upstream failed")])
    malformed_process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), raw_line(b"{not-json\n")])
    restart_process = FakeCodexProcess([result({"userAgent": "forge-knowledge/0.146.0"}), result({"ok": True})])
    processes = [error_process, malformed_process, restart_process]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), settings=_settings(tmp_path))

    async def exercise():
        with pytest.raises(CodexAppServerTransportError):
            await client.request("model/list")
        error_process.terminate()
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
        with pytest.raises(CodexAppServerTransportError):
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


class TerminateTimeoutProcess(FakeCodexProcess):
    def terminate(self) -> None:
        self.terminated = True

    def kill(self) -> None:
        self.killed = True
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
        "cancellation_poll_interval_seconds": 0.001,
        "notification_buffer": _notification_policy(),
    }
    values.update(overrides)
    return CodexRuntimeSettings(**values)


def _notification_policy() -> CodexNotificationBufferPolicy:
    return CodexNotificationBufferPolicy(max_per_turn=100, max_turn_ids=100, max_age_seconds=30.0)


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
