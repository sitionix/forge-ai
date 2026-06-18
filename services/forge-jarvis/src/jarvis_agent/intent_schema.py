from typing import Any, Dict, Optional

from pydantic import BaseModel, Field


class Intent(BaseModel):
    action: str = Field(min_length=1)
    target: Optional[str] = None
    arguments: Dict[str, Any] = Field(default_factory=dict)

    class Config:
        extra = "forbid"


class CommandRequest(BaseModel):
    text: str

    class Config:
        extra = "forbid"


class ExecutionResult(BaseModel):
    executed: bool
    message: str
    output: Optional[str] = None


class CommandResponse(BaseModel):
    input: str
    intent: Intent
    execution: ExecutionResult
