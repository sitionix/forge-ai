# Forge Console

Forge Console is the Operator UI.

Physical location:

- `services/forge-console/src/operator`

Forge Nexus includes `services/forge-console/src` as a Boot resource source and packages it as `static`, so the served static paths remain unchanged. The UI continues to call Forge Nexus through `contextPath + /api/v1/infrastructure`, not direct Python service ports.

Compatibility rule: existing `/fgaisox/operator/*` static UI behavior is preserved by the Boot resource mapping.
