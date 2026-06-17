# Forge AI / Jarvis UX Audit

Forge AI Operator UI is a static Spring Boot UI under:

```text
boot/src/main/resources/static/operator
```

Current pages:

- `index.html` / Tickets
- `new-task.html` / New Task
- `agents.html` / Agents
- `ticket.html` / Ticket Graph
- `lane.html` / Lane Detail
- `jarvis.html` / Infrastructure / Jarvis

Shared UI logic is in:

```text
boot/src/main/resources/static/operator/operator-ui.js
```

Navigation is built by `initSidebar()`.

## Current Sitemap

```text
Operator UI
  Forge AI
    - Tickets
    - New Task
    - Agents
    - Graph   contextual
    - Lane    contextual
    - Health

  Infrastructure
    - Jarvis
```

Jarvis is intentionally not under Agents, Tickets, Graph, or Lane. It is infrastructure.

## Jarvis Page

Page:

```text
boot/src/main/resources/static/operator/jarvis.html
```

Body:

```html
<body data-page="jarvis">
```

The page shows:

- Jarvis status
- Ollama status
- current model
- active Jarvis host/port returned by Forge API
- allowlisted action metadata
- command test input
- last command result
- security notice

The page does not show:

- fake chatbot
- voice controls
- wake word settings
- Home Assistant controls
- raw shell input
- ticket/lane mutation controls
- external model UI links

## UI API Calls

Browser JavaScript calls only Forge AI backend endpoints:

```http
GET  /fgaisox/api/v1/infrastructure/jarvis/status
GET  /fgaisox/api/v1/infrastructure/jarvis/actions
POST /fgaisox/api/v1/infrastructure/jarvis/command
```

The browser must not call the Jarvis direct localhost port.

## UX Boundary

Forge AI UX is for:

- tickets
- tasks
- agents
- lane graph
- lane execution traces
- Codex/operator progress

Jarvis UX is for:

- local assistant runtime
- local model status
- safe action metadata
- command test execution

Jarvis must not become a lane, an `agent.yml` entry, or a ticket mutation surface.

## Future UX

Possible later Infrastructure pages:

- Local Runtime
- Models / Ollama

Voice can be added later only if it routes through the same Jarvis command pipeline.
