from __future__ import annotations


class KnowledgeError(Exception):
    def __init__(self, code: str, message: str, **details):
        super().__init__(message)
        self.code = code
        self.message = message
        self.details = details


class ConfigMissingError(KnowledgeError):
    def __init__(self):
        super().__init__("KNOWLEDGE_CONFIG_MISSING", "No local knowledge-sources.yaml configured")
