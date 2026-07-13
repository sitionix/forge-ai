from __future__ import annotations

from typing import Any, Dict, Protocol

from pydantic import ValidationError

from jarvis_agent.knowledge_client import KnowledgeBadResponseError
from jarvis_agent.query_schema import (
    JarvisHumanAnswerResponse,
    JarvisKnowledgeQueryResponse,
    JarvisQueryIntent,
    JarvisQueryRequest,
    JarvisQueryResponse,
)


class KnowledgeQueryGateway(Protocol):
    async def query(self, payload: Dict[str, Any]) -> Dict[str, Any]: ...

    async def query_flow_explanations(self, payload: Dict[str, Any]) -> Dict[str, Any]: ...


class JarvisQueryService:
    def __init__(self, knowledge_gateway: KnowledgeQueryGateway) -> None:
        self.knowledge_gateway = knowledge_gateway

    async def query(self, request: JarvisQueryRequest) -> JarvisQueryResponse:
        payload = {
            "queryText": request.queryText,
            "intent": request.intent.value,
            "answerLanguage": request.answerLanguage,
            "includeTests": request.includeTests,
            "maxFlows": request.maxFlows,
        }
        if request.intent == JarvisQueryIntent.FLOW_EXPLANATION:
            bundle = await self.knowledge_gateway.query_flow_explanations(payload)
            try:
                return JarvisHumanAnswerResponse.parse_obj(bundle)
            except ValidationError as exc:
                raise KnowledgeBadResponseError("Knowledge returned a malformed query response") from exc
        else:
            bundle = await self.knowledge_gateway.query(payload)
        try:
            return JarvisKnowledgeQueryResponse.parse_obj(bundle)
        except ValidationError as exc:
            raise KnowledgeBadResponseError("Knowledge returned a malformed query response") from exc
