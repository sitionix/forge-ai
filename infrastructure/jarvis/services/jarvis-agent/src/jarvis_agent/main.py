from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from logging.handlers import RotatingFileHandler
from typing import Dict

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from jarvis_agent.action_executor import ActionExecutionError, ActionExecutor
from jarvis_agent.action_registry import ActionNotAllowedError, ActionRegistry
from jarvis_agent.config import load_app_config
from jarvis_agent.intent_parser import IntentParseError, parse_intent
from jarvis_agent.intent_schema import CommandRequest, CommandResponse
from jarvis_agent.ollama_client import OllamaClient, OllamaUnavailableError
from jarvis_agent.security import SecurityError


config = load_app_config()
logger = logging.getLogger("jarvis_agent")
registry = ActionRegistry.from_yaml(config.allowed_actions_path)
executor = ActionExecutor(registry, logger)
ollama = OllamaClient(
    base_url=config.model.ollama_base_url,
    model=config.model.default_model,
    timeout_seconds=config.model.request_timeout_seconds,
)


def configure_logging() -> None:
    config.log_file.parent.mkdir(parents=True, exist_ok=True)
    logger.setLevel(logging.INFO)
    if logger.handlers:
        return
    formatter = logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s")
    file_handler = RotatingFileHandler(config.log_file, maxBytes=1_000_000, backupCount=3)
    file_handler.setFormatter(formatter)
    stream_handler = logging.StreamHandler()
    stream_handler.setFormatter(formatter)
    logger.addHandler(file_handler)
    logger.addHandler(stream_handler)


@asynccontextmanager
async def lifespan(_: FastAPI):
    configure_logging()
    logger.info("service start")
    logger.info("selected model: %s", config.model.default_model)
    logger.info("ollama base url: %s", config.model.ollama_base_url)
    yield


app = FastAPI(title="Jarvis Agent", version="0.1.0", lifespan=lifespan)


@app.exception_handler(ValueError)
async def value_error_handler(_: Request, exc: ValueError) -> JSONResponse:
    return JSONResponse(status_code=400, content={"code": "BAD_REQUEST", "message": str(exc)})


@app.get("/health")
async def health() -> Dict[str, str]:
    return {"status": "UP"}


@app.get("/api/v1/jarvis/actions")
async def actions() -> Dict[str, object]:
    return registry.public_actions()


@app.get("/api/v1/jarvis/status")
async def status() -> Dict[str, object]:
    ollama_status = "UP"
    try:
        await ollama.health()
    except OllamaUnavailableError:
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
            "count": len(registry.available_actions_for_prompt()),
        },
    }


@app.post("/api/v1/jarvis/command", response_model=CommandResponse)
async def command(request: CommandRequest):
    text = request.text.strip()
    if not text:
        return error_response(400, "INVALID_COMMAND", "Command text must not be empty")

    logger.info("command received")
    try:
        raw_intent = await ollama.classify_intent(
            system_prompt=config.system_prompt,
            user_text=text,
            actions=registry.available_actions_for_prompt(),
        )
    except OllamaUnavailableError:
        logger.warning("ollama unavailable")
        return error_response(
            503,
            "OLLAMA_UNAVAILABLE",
            f"Ollama is not reachable at {config.model.ollama_base_url}",
        )

    try:
        intent = parse_intent(raw_intent)
        logger.info("parsed intent: %s", intent.dict())
    except IntentParseError:
        logger.warning("invalid model response")
        return error_response(422, "INVALID_MODEL_RESPONSE", "Model did not return valid intent JSON")

    try:
        execution = executor.execute(intent, text)
    except (ActionNotAllowedError, SecurityError):
        logger.warning("unsupported or rejected action: %s", intent.dict())
        return error_response(403, "UNSUPPORTED_ACTION", "The requested action is not allowlisted")
    except ActionExecutionError:
        return error_response(500, "ACTION_EXECUTION_FAILED", "Failed to execute allowlisted action")

    return CommandResponse(input=text, intent=intent, execution=execution)


def error_response(status_code: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status_code, content={"code": code, "message": message})
