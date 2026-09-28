#!/usr/bin/env python3
"""Native CLI against the actual Stage 5 gateway; normal worker/systemd is a separate boundary."""
import os
import uuid
import stage4_codex_fixture as fixture

fixture.CONNECTION = uuid.UUID(os.environ['FORGE_STAGE5_CONNECTION_ID'])
fixture.ALIAS = 'forge_' + fixture.CONNECTION.hex
fixture.GRANT_NAME = 'FORGE_MCP_GRANT_' + fixture.CONNECTION.hex.upper()
fixture.GRANTS = [os.environ['FORGE_STAGE5_GRANT_A'], os.environ['FORGE_STAGE5_GRANT_B']]
fixture.run(expected_version="0.158.0")
print('STAGE5_NATIVE_GATEWAY_PASS')
