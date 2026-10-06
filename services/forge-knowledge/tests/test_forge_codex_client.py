import asyncio
import importlib
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
import pytest

from knowledge_service.codex_app_server import CodexAppServerProtocolError, CodexAppServerTimeout, CodexAppServerTransportError


def bridge(tmp_path, handler):
    try:
        module = importlib.import_module("knowledge_service.forge_codex_client")
    except ModuleNotFoundError:
        pytest.fail("Knowledge has no authenticated Forge Codex bridge")
    token = tmp_path / "service.token"
    token.write_text("synthetic-service-token-0123456789abcdef")
    token.chmod(0o600)
    return module.ForgeCodexClient("http://127.0.0.1:8081", token, transport=httpx.MockTransport(handler))


def test_generation_preserves_codex_turn_result(tmp_path):
    seen = []

    def handler(request):
        seen.append(request)
        if request.method == "POST":
            body = json.loads(request.content)
            assert set(body) == {"requestId", "prompt", "modelId", "effortId", "responseMode", "timeoutSeconds"}
            return httpx.Response(202, json={"requestId": body["requestId"]})
        return httpx.Response(
            200,
            json={
                "status": "completed",
                "rawText": '{"json":"{\\"ok\\":true}"}',
                "threadId": "thread",
                "turnId": "turn",
                "serverVersion": "0.160.0",
                "tokenUsage": {"total": 4},
                "warnings": ["warning"],
                "modelMetadata": {"model": "model"},
                "errorCode": None,
            },
        )

    client = bridge(tmp_path, handler)
    result = client.run_turn_sync(prompt="input", model_id="model", effort_id="low", response_mode="json_object", timeout_seconds=2)
    assert (result.raw_text, result.thread_id, result.turn_id, result.turn_status, result.server_version) == (
        '{"ok":true}',
        "thread",
        "turn",
        "completed",
        "0.160.0",
    )
    assert result.token_usage == {"total": 4} and result.warnings == ("warning",) and result.model_metadata == {"model": "model"}
    assert all(r.headers["Authorization"].startswith("Bearer synthetic-service-token") for r in seen)
    client.close()


def test_models_and_usage_use_forge_bridge(tmp_path):
    paths = []

    def handler(request):
        paths.append(str(request.url))
        payload = (
            {"data": [{"id": "m"}], "nextCursor": "next"} if request.url.path.endswith("/models") else {"rateLimits": {}, "rateLimitsByLimitId": {"a": {}}}
        )
        return httpx.Response(200, json=payload, headers={"X-Forge-Codex-Version": "0.160.0"})

    client = bridge(tmp_path, handler)

    async def run():
        assert await client.initialize() == "0.160.0"
        assert await client.request("model/list", {"cursor": "a", "limit": 100}) == {"data": [{"id": "m"}], "nextCursor": "next"}
        assert await client.request("account/rateLimits/read") == {"rateLimits": {}, "rateLimitsByLimitId": {"a": {}}}
        with pytest.raises(CodexAppServerProtocolError):
            await client.request("turn/start", {})
        with pytest.raises(CodexAppServerProtocolError):
            await client.request("model/list", {"config": {}})
        await client.aclose()

    asyncio.run(run())
    assert "/internal/v1/codex/models?cursor=a&limit=100" in paths[1]


@pytest.mark.parametrize("cancel", [False, True])
def test_timeout_and_cancellation_delete_owned_job(tmp_path, cancel):
    deleted = []

    async def handler(request):
        if request.method == "DELETE":
            deleted.append(request.url.path)
            return httpx.Response(204)
        if request.method == "POST":
            return httpx.Response(202, json={"requestId": json.loads(request.content)["requestId"]})
        await asyncio.sleep(10)
        return httpx.Response(200, json={"status": "running"})

    client = bridge(tmp_path, handler)

    async def run():
        task = asyncio.create_task(client.run_turn(prompt="x", model_id="m", effort_id=None, response_mode="text", timeout_seconds=0.05))
        if cancel:
            await asyncio.sleep(0.01)
            task.cancel()
        with pytest.raises(asyncio.CancelledError if cancel else CodexAppServerTimeout):
            await task
        assert len(deleted) == 1
        await client.aclose()

    asyncio.run(run())


def test_bridge_unavailable_never_falls_back(tmp_path, monkeypatch):
    def forbidden(*args, **kwargs):
        pytest.fail("Bridge attempted terminal fallback")

    monkeypatch.setattr(asyncio, "create_subprocess_exec", forbidden)

    def handler(request):
        raise httpx.ConnectError("unavailable")

    client = bridge(tmp_path, handler)
    with pytest.raises(CodexAppServerTransportError, match="Forge Codex bridge unavailable"):
        client.request_sync("model/list")
    client.close()


def test_service_token_never_reaches_provider_or_logs(tmp_path, caplog):
    def handler(request):
        assert b"synthetic-service-token" not in request.content
        assert "synthetic-service-token" not in str(request.url)
        return httpx.Response(503, json={"code": "UNTRUSTED synthetic-service-token-0123456789abcdef"})

    client = bridge(tmp_path, handler)
    with pytest.raises(CodexAppServerTransportError) as error:
        client.request_sync("model/list")
    assert "synthetic-service-token" not in str(error.value) + caplog.text
    client.close()


def test_bootstrap_never_spawns_terminal_codex(tmp_path, monkeypatch):
    from knowledge_service.bootstrap import build_dependencies
    from knowledge_service.codex_app_server import CodexAppServerClient
    from knowledge_service.config import AppConfig

    def forbidden(*args, **kwargs):
        pytest.fail("Production bootstrap constructed terminal Codex")

    monkeypatch.setattr(CodexAppServerClient, "__init__", forbidden)
    config = AppConfig(
        module_dir=tmp_path,
        host="127.0.0.1",
        port=1,
        local_config_path=tmp_path / "sources.yaml",
        store_path=tmp_path / "store.sqlite",
        runtime_dir=tmp_path / "var",
    )
    dependencies = build_dependencies(config)
    assert type(dependencies.codex_app_server_client).__name__ == "ForgeCodexClient"
    asyncio.run(dependencies.aclose())


def test_service_credential_ignores_environment_proxy_and_never_follows_redirect(tmp_path, monkeypatch):
    from knowledge_service.forge_codex_client import ForgeCodexClient

    received = []

    class Proxy(BaseHTTPRequestHandler):
        def do_GET(self):
            received.append(("proxy", self.headers.get("Authorization")))
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"{}")

        def log_message(self, *args):
            pass

    with ThreadingHTTPServer(("127.0.0.1", 0), Proxy) as proxy:
        proxy_thread = threading.Thread(target=proxy.serve_forever, daemon=True)
        proxy_thread.start()
        target = f"http://127.0.0.1:{proxy.server_port}"

        class Agent(Proxy):
            def do_GET(self):
                received.append(("agent", self.headers.get("Authorization")))
                self.send_response(302)
                self.send_header("Location", target + "/steal")
                self.end_headers()

        with ThreadingHTTPServer(("127.0.0.1", 0), Agent) as agent:
            agent_thread = threading.Thread(target=agent.serve_forever, daemon=True)
            agent_thread.start()
            for name in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"):
                monkeypatch.setenv(name, target)
            monkeypatch.setenv("NO_PROXY", "")
            monkeypatch.setenv("no_proxy", "")
            token = tmp_path / "token"
            token.write_text("synthetic-installation-credential-0123456789")
            token.chmod(0o600)
            client = ForgeCodexClient(f"http://127.0.0.1:{agent.server_port}", token)
            try:
                with pytest.raises(CodexAppServerTransportError):
                    client.request_sync("model/list")
                assert received == [("agent", "Bearer synthetic-installation-credential-0123456789")]
                for invalid in ("http://example.com", "http://user:pass@127.0.0.1", "http://127.0.0.1/elsewhere"):
                    with pytest.raises(ValueError):
                        ForgeCodexClient(invalid, token)
            finally:
                client.close()
                agent.shutdown()
                agent_thread.join(1)
                proxy.shutdown()
                proxy_thread.join(1)
