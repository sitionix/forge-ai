from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from typing import Any, Dict, Optional

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from jarvis_agent.action_executor import ActionExecutionError
from jarvis_agent.action_registry import ActionNotAllowedError
from jarvis_agent.bootstrap import JarvisDependencies, build_dependencies, configure_logging
from jarvis_agent.chat_prompt import build_chat_prompt
from jarvis_agent.chat_schema import ChatDiagnostic, ChatRequest, ChatResponse
from jarvis_agent.config import AppConfig, ForgeSettings, load_forge_settings
from jarvis_agent.intent_parser import IntentParseError, parse_intent
from jarvis_agent.intent_schema import CommandRequest, CommandResponse
from jarvis_agent.knowledge_client import KnowledgeBadResponseError, KnowledgeUnavailableError, diagnostics, used_context_items
from jarvis_agent.ollama_client import OllamaBadResponseError, OllamaUnavailableError
from jarvis_agent.security import SecurityError


def create_app(
    settings: Optional[ForgeSettings] = None,
    dependencies: Optional[JarvisDependencies] = None,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        forge_settings = settings or load_forge_settings()
        config = AppConfig.from_forge_settings(forge_settings)
        configure_logging(forge_settings, config)
        deps = dependencies or build_dependencies(config)
        app.state.forge_settings = forge_settings
        app.state.app_config = config
        app.state.jarvis_dependencies = deps
        logger = logging.getLogger("jarvis_agent")
        logger.info("service start")
        logger.info("selected model: %s", config.model.default_model)
        logger.info("ollama base url: %s", config.model.ollama_base_url)
        yield

    app = FastAPI(title="Jarvis Agent", version="0.1.0", lifespan=lifespan)
    if settings is not None and dependencies is not None:
        app.state.forge_settings = settings
        app.state.app_config = AppConfig.from_forge_settings(settings)
        app.state.jarvis_dependencies = dependencies

    @app.exception_handler(ValueError)
    async def value_error_handler(_: Request, exc: ValueError) -> JSONResponse:
        return JSONResponse(status_code=400, content={"code": "BAD_REQUEST", "message": str(exc)})

    @app.get("/health")
    async def health() -> Dict[str, str]:
        return {"status": "UP"}

    @app.get("/api/v1/jarvis/actions")
    async def actions(request: Request) -> Dict[str, Any]:
        _, deps = _state(request)
        return deps.action_registry.public_actions()

    @app.get("/api/v1/jarvis/status")
    async def status(request: Request) -> Dict[str, object]:
        config, deps = _state(request)
        ollama_status = "UP"
        try:
            await deps.model_client.health()
        except (OllamaUnavailableError, OllamaBadResponseError):
            ollama_status = "DOWN"

        return {
            "status": "UP",
            "host": config.host,
            "port": config.port,
            "model": {
                "defaultModel": config.model.default_model,
            },
            "ollama": {
                "baseUrl": config.model.ollama_base_url,
                "status": ollama_status,
            },
            "actions": {
                "count": len(deps.action_registry.available_actions_for_prompt()),
            },
        }

    @app.post("/api/v1/jarvis/command", response_model=CommandResponse)
    async def command(request: Request, body: CommandRequest):
        config, deps = _state(request)
        text = body.text.strip()
        if not text:
            return error_response(400, "INVALID_COMMAND", "Command text must not be empty")

        logging.getLogger("jarvis_agent").info("command received")
        try:
            raw_intent = await deps.model_client.classify_intent(
                system_prompt=config.system_prompt,
                user_text=text,
                actions=deps.action_registry.available_actions_for_prompt(),
            )
        except OllamaUnavailableError:
            logging.getLogger("jarvis_agent").warning("ollama unavailable")
            return error_response(503, "OLLAMA_UNAVAILABLE", f"Ollama is not reachable at {config.model.ollama_base_url}")
        except OllamaBadResponseError:
            logging.getLogger("jarvis_agent").warning("invalid model response")
            return error_response(422, "INVALID_MODEL_RESPONSE", "Model did not return valid intent JSON")

        try:
            intent = parse_intent(raw_intent)
            logging.getLogger("jarvis_agent").info("parsed intent: %s", intent.dict())
        except IntentParseError:
            logging.getLogger("jarvis_agent").warning("invalid model response")
            return error_response(422, "INVALID_MODEL_RESPONSE", "Model did not return valid intent JSON")

        try:
            execution = deps.action_executor.execute(intent, text)
        except (ActionNotAllowedError, SecurityError):
            logging.getLogger("jarvis_agent").warning("unsupported or rejected action: %s", intent.dict())
            return error_response(403, "UNSUPPORTED_ACTION", "The requested action is not allowlisted")
        except ActionExecutionError:
            return error_response(500, "ACTION_EXECUTION_FAILED", "Failed to execute allowlisted action")

        return CommandResponse(input=text, intent=intent, execution=execution)

    @app.post("/api/v1/jarvis/chat", response_model=ChatResponse)
    async def chat(request: Request, body: ChatRequest):
        config, deps = _state(request)
        message = body.message.strip()
        if not message:
            return error_response(400, "INVALID_CHAT_MESSAGE", "Chat message must not be empty")

        max_context_chars = body.maxContextChars or config.knowledge.default_max_context_chars
        logging.getLogger("jarvis_agent").info("chat received")
        try:
            context_bundle = await deps.knowledge_client.context(message, max_context_chars)
        except KnowledgeUnavailableError:
            logging.getLogger("jarvis_agent").warning("knowledge unavailable")
            return error_response(503, "KNOWLEDGE_UNAVAILABLE", f"Knowledge is not reachable at {config.knowledge.base_url}")
        except KnowledgeBadResponseError:
            logging.getLogger("jarvis_agent").warning("knowledge returned malformed response")
            return error_response(502, "KNOWLEDGE_BAD_RESPONSE", "Knowledge returned a malformed context response")

        context_items = used_context_items(context_bundle)
        chat_diagnostics = diagnostics(context_bundle)
        if not context_items:
            chat_diagnostics.append(
                ChatDiagnostic(
                    code="CONTEXT_EMPTY",
                    message="No relevant local Knowledge context was found.",
                )
            )
            return ChatResponse(
                answer="No relevant local Knowledge context was found, so I cannot answer from local files with confidence.",
                usedContext=[],
                diagnostics=chat_diagnostics,
            )

        prompt = build_chat_prompt(config.chat_prompt, message, context_items)
        try:
            answer = await deps.model_client.generate_text(prompt)
        except OllamaUnavailableError:
            logging.getLogger("jarvis_agent").warning("ollama unavailable")
            return error_response(503, "OLLAMA_UNAVAILABLE", f"Ollama is not reachable at {config.model.ollama_base_url}")
        except OllamaBadResponseError:
            logging.getLogger("jarvis_agent").warning("ollama returned malformed response")
            return error_response(502, "OLLAMA_BAD_RESPONSE", "Ollama returned a malformed response")
        if not answer:
            answer = "Ollama returned an empty answer."
            chat_diagnostics.append(ChatDiagnostic(code="OLLAMA_EMPTY_RESPONSE", message=answer))

        return ChatResponse(answer=answer, usedContext=context_items, diagnostics=chat_diagnostics)

    return app


def _state(request: Request) -> tuple[AppConfig, JarvisDependencies]:
    return request.app.state.app_config, request.app.state.jarvis_dependencies


def error_response(status_code: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status_code, content={"code": code, "message": message})


app = create_app()
