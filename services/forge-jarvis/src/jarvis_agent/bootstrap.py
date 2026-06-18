from __future__ import annotations

import logging
from dataclasses import dataclass
from logging.handlers import RotatingFileHandler
from typing import Any, Dict, List, Protocol

from jarvis_agent.action_executor import ActionExecutor
from jarvis_agent.action_registry import ActionRegistry
from jarvis_agent.config import AppConfig, ForgeSettings
from jarvis_agent.knowledge_client import KnowledgeClient
from jarvis_agent.ollama_client import OllamaClient


class ModelClient(Protocol):
    async def health(self) -> None: ...

    async def classify_intent(self, system_prompt: str, user_text: str, actions: List[Dict[str, Any]]) -> str: ...

    async def generate_text(self, prompt: str) -> str: ...


class KnowledgeContextClient(Protocol):
    async def context(self, query: str, max_context_chars: int) -> Dict[str, Any]: ...


@dataclass(frozen=True)
class JarvisDependencies:
    knowledge_client: KnowledgeContextClient
    model_client: ModelClient
    action_registry: ActionRegistry
    action_executor: ActionExecutor


def build_dependencies(config: AppConfig) -> JarvisDependencies:
    logger = logging.getLogger("jarvis_agent")
    registry = ActionRegistry.from_yaml(config.allowed_actions_path)
    return JarvisDependencies(
        knowledge_client=KnowledgeClient(
            base_url=config.knowledge.base_url,
            timeout_seconds=config.knowledge.request_timeout_seconds,
        ),
        model_client=OllamaClient(
            base_url=config.model.ollama_base_url,
            model=config.model.default_model,
            timeout_seconds=config.model.request_timeout_seconds,
        ),
        action_registry=registry,
        action_executor=ActionExecutor(registry, logger),
    )


def configure_logging(settings: ForgeSettings, config: AppConfig) -> None:
    logger = logging.getLogger("jarvis_agent")
    logger.setLevel(settings.logging.level)
    logger.propagate = False
    if getattr(logger, "_forge_configured", False):
        return
    formatter = logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s")
    if settings.logging.console_enabled:
        stream_handler = logging.StreamHandler()
        stream_handler.setFormatter(formatter)
        logger.addHandler(stream_handler)
    if settings.logging.file_enabled:
        config.log_file.parent.mkdir(parents=True, exist_ok=True)
        file_handler = RotatingFileHandler(config.log_file, maxBytes=1_000_000, backupCount=3)
        file_handler.setFormatter(formatter)
        logger.addHandler(file_handler)
    setattr(logger, "_forge_configured", True)
