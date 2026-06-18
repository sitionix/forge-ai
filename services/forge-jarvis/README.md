# Forge Jarvis

Forge Jarvis is the Python Jarvis service for local assistant chat, command classification, and allowlisted tool runtime.

Physical location:

- `services/forge-jarvis`
- service entrypoint: `services/forge-jarvis/src/jarvis_agent/main.py`
- package name: `jarvis_agent`

Local internal base URL:

- `http://127.0.0.1:7071`

Compatibility rule: existing local `/api/v1/jarvis/*` endpoints and Forge Nexus proxy endpoints are preserved. `JARVIS_CONFIG_DIR` remains supported as the strongest explicit config override.
